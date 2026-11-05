package com.techno.kurdtube.backend.download

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.ParcelFileDescriptor
import com.naman14.androidlame.AndroidLame
import com.naman14.androidlame.LameBuilder
import com.techno.kurdtube.backend.utils.LOG
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteOrder


/**
 * Turns a downloaded YouTube audio stream (AAC in MP4 or Opus in WebM) into an MP3 file.
 *
 * YouTube does not serve MP3, so the stream is decoded to PCM with [MediaCodec] and encoded
 * again with LAME. The decoder runs on the caller's thread, the download manager calls this
 * from its background download thread.
 */
object Mp3Converter {

    private const val TIMEOUT_US = 10_000L
    private const val OUTPUT_BITRATE_KBPS = 192
    private const val OUTPUT_BUFFER_SIZE = 64 * 1024

    /**
     * @return true when [output] now contains a complete MP3 file.
     * @throws IOException when the stream cannot be decoded or encoded.
     */
    fun convert(
        source: ParcelFileDescriptor,
        output: OutputStream,
        shouldCancel: () -> Boolean = { false },
        onProgress: (percent: Float) -> Unit = {}
    ): Boolean {

        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: AndroidLame? = null
        val sink = BufferedOutputStream(output, OUTPUT_BUFFER_SIZE)

        try {
            extractor.setDataSource(source.fileDescriptor)

            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                mime?.startsWith("audio/") == true
            } ?: throw IOException("the file has no audio track")

            extractor.selectTrack(trackIndex)

            val inputFormat = extractor.getTrackFormat(trackIndex)
            val inputMime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IOException("the audio track has no mime type")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                //the encoder needs signed 16 bit samples, not float
                inputFormat.setInteger(
                    MediaFormat.KEY_PCM_ENCODING,
                    AudioFormat.ENCODING_PCM_16BIT
                )
            }

            val durationUs =
                if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                    inputFormat.getLong(MediaFormat.KEY_DURATION)
                } else {
                    0L
                }

            var channels = intOr(inputFormat, MediaFormat.KEY_CHANNEL_COUNT, 2)
                .coerceIn(1, 2)
            var sampleRate = intOr(inputFormat, MediaFormat.KEY_SAMPLE_RATE, 44100)

            decoder = MediaCodec.createDecoderByType(inputMime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (shouldCancel()) throw IOException("the conversion was cancelled")

                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)

                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                        val size = if (inputBuffer != null) {
                            extractor.readSampleData(inputBuffer, 0)
                        } else {
                            -1
                        }

                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                size,
                                extractor.sampleTime,
                                0
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = decoder.outputFormat
                        sampleRate = intOr(
                            outputFormat,
                            MediaFormat.KEY_SAMPLE_RATE,
                            sampleRate
                        )
                        channels = intOr(
                            outputFormat,
                            MediaFormat.KEY_CHANNEL_COUNT,
                            channels
                        ).coerceIn(1, 2)

                        encoder?.close()
                        encoder = createEncoder(sampleRate, channels)
                    }

                    else -> {
                        if (outputIndex < 0) continue

                        if (bufferInfo.size > 0) {
                            if (encoder == null) {
                                encoder = createEncoder(sampleRate, channels)
                            }

                            val outputBuffer = decoder.getOutputBuffer(outputIndex)
                            if (outputBuffer != null) {
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                                //Android media buffers are little endian 16 bit PCM
                                val pcm = ShortArray(bufferInfo.size / 2)
                                outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                                    .asShortBuffer()
                                    .get(pcm)

                                val frames = pcm.size / channels

                                if (frames > 0) {
                                    val mp3Buffer = ByteArray(
                                        MP3_BUFFER_HEADROOM + frames * 5 / 4
                                    )
                                    val encoded = encoder!!.encodeBufferInterLeaved(
                                        pcm,
                                        frames,
                                        mp3Buffer
                                    )

                                    if (encoded < 0) {
                                        throw IOException("LAME could not encode ($encoded)")
                                    }

                                    if (encoded > 0) sink.write(mp3Buffer, 0, encoded)
                                }
                            }

                            if (durationUs > 0L) {
                                val percent = bufferInfo.presentationTimeUs.toFloat() /
                                        durationUs
                                onProgress(percent.coerceIn(0f, 1f))
                            }
                        }

                        decoder.releaseOutputBuffer(outputIndex, false)

                        val endOfStream = bufferInfo.flags and
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (endOfStream) outputDone = true
                    }
                }
            }

            val lame = encoder ?: throw IOException("no audio was decoded")

            val finalBuffer = ByteArray(MP3_BUFFER_HEADROOM + 2 * 1024)
            val flushed = lame.flush(finalBuffer)
            if (flushed > 0) sink.write(finalBuffer, 0, flushed)

            sink.flush()
            onProgress(1f)

            LOG("MP3 conversion finished (${channels}ch, ${sampleRate}Hz)")
            return true
        } finally {
            runCatching { encoder?.close() }
            runCatching { sink.close() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun intOr(format: MediaFormat, key: String, fallback: Int): Int {
        return try {
            if (format.containsKey(key)) format.getInteger(key) else fallback
        } catch (error: Exception) {
            fallback
        }
    }

    private fun createEncoder(sampleRate: Int, channels: Int): AndroidLame {
        return LameBuilder()
            .setInSampleRate(sampleRate)
            .setOutSampleRate(sampleRate)
            .setOutChannels(channels)
            .setOutBitrate(OUTPUT_BITRATE_KBPS)
            .setMode(
                if (channels > 1) LameBuilder.Mode.JSTEREO else LameBuilder.Mode.MONO
            )
            .setQuality(2)
            .setVbrMode(LameBuilder.VbrMode.VBR_OFF)
            .build()
    }

    /** LAME asks for 1.25 * samples + 7200 bytes of room for one encode call. */
    private const val MP3_BUFFER_HEADROOM = 7200 + 16
}
