package com.techno.kurdtube.backend.download

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.techno.kurdtube.backend.utils.LOG


class YoutubeDownloadState {

    init {
        LOG("init YoutubeDownloadState")
    }

    var active by mutableStateOf(false)
    /** The user dismissed the progress dialog; the download keeps running. */
    var hidden by mutableStateOf(false)
    /** The file is downloaded and is being converted to MP3 in the background. */
    var processing by mutableStateOf(false)
    var fileName by mutableStateOf("")
    var progressBytes by mutableStateOf(0L)
    var sizeBytes by mutableStateOf(0L)
    var startTime by mutableStateOf(0L)
    var speedBytesPerSecond by mutableStateOf(0L)

    private var lastUpdateTime = 0L

    val progressPercent: Float
        get() = if (sizeBytes > 0L && progressBytes > 0L) {
            (progressBytes * 100L / sizeBytes).toFloat() / 100f
        } else {
            0f
        }

    val progressMB: Float
        get() = progressBytes.coerceAtLeast(0L) / 1000000f

    val sizeMB: Float
        get() = sizeBytes / 1000000f

    val speedMBPS: Float
        get() = speedBytesPerSecond / 1000000f

    val error: Boolean
        get() = progressBytes < 0L

    /** Resets the state for a new download. Call on the main thread. */
    fun prepare(name: String, size: Long) {
        fileName = name
        sizeBytes = if (size > 0L) size else 0L
        progressBytes = 0L
        speedBytesPerSecond = 0L
        startTime = System.currentTimeMillis()
        lastUpdateTime = startTime
        hidden = false
        processing = false
        active = true
    }

    /** Closes the progress dialog without cancelling the running download. */
    fun hide() {
        if (!active) return
        hidden = true
    }

    /** Call on the main thread. */
    fun updateProgress(downloadedBytes: Long, totalBytes: Long) {
        val now = System.currentTimeMillis()
        val deltaBytes = downloadedBytes - progressBytes.coerceAtLeast(0L)
        val deltaTime = now - lastUpdateTime

        if (deltaBytes > 0L && deltaTime > 0L) {
            val currentSpeed = deltaBytes * 1000f / deltaTime
            speedBytesPerSecond = if (speedBytesPerSecond <= 0L) {
                currentSpeed.toLong()
            } else {
                //smoothed, so a single fast read does not make the shown speed jump
                (speedBytesPerSecond * 0.75f + currentSpeed * 0.25f).toLong()
            }
        }

        lastUpdateTime = now
        progressBytes = downloadedBytes
        if (totalBytes > 0L) sizeBytes = totalBytes
    }

    fun finish() {
        active = false
        speedBytesPerSecond = 0L
    }

    fun fail() {
        active = false
        processing = false
        hidden = false
        speedBytesPerSecond = 0L
        progressBytes = -1L
    }

}
