package com.techno.kurdtube.backend.library

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.techno.kurdtube.R
import com.techno.kurdtube.backend.mediastore.MediaStoreDirectory
import com.techno.kurdtube.backend.mediastore.MediaStoreFile
import com.techno.kurdtube.backend.mediastore.MediaStoreManager
import java.io.OutputStream


class SongManager(context: Context, private val mediaStoreManager: MediaStoreManager) {

    /**
     * The downloaded streams are webm/opus or mp4/aac, so the file keeps the extension of
     * its container. Older versions used .mp3 for every stream, it is still listed.
     */
    private val songFileExtensions = listOf(".m4a", ".webm", ".mp3")

    private val songDirectory = MediaStoreDirectory(
        Environment.DIRECTORY_MUSIC,
        context.getString(R.string.app_name)
    )

    private val audioCollection = getAudioCollection()

    private fun getAudioCollection(): Uri {
        val collection =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Audio.Media.getContentUri("external") //MediaStore.VOLUME_EXTERNAL
            }
        return collection
    }

    fun getSingleSong(uri: Uri): Song {
        val file = mediaStoreManager.getFileByUri(uri)
        return getSongFromFile(file)
    }

    fun getAllSongs(): List<Song> {
        val files = mediaStoreManager.getFilesFromDirectory(
            audioCollection,
            songDirectory,
            songFileExtensions
        )
        return files.map { file -> getSongFromFile(file) }
    }

    private fun getSongFromFile(file: MediaStoreFile): Song {
        return Song(file.uri, removeExtension(file.name))
    }

    private fun removeExtension(fileName: String): String {
        val extension = songFileExtensions.firstOrNull {
            fileName.endsWith(it, ignoreCase = true)
        }
        return if (extension == null) fileName else fileName.dropLast(extension.length)
    }

    fun createSong(
        fileName: String,
        extension: String = DEFAULT_EXTENSION,
        mimeType: String? = null
    ): Uri? {
        val columnValues =
            if (mimeType != null) mapOf(MediaStore.MediaColumns.MIME_TYPE to mimeType)
            else emptyMap()

        return mediaStoreManager.createFile(
            fileName.plus(extension),
            audioCollection,
            songDirectory,
            columnValues
        )
    }

    fun openSongForWriting(uri: Uri): OutputStream? {
        return mediaStoreManager.openFileOutputStream(uri, "w")
    }

    /** Used by the MP3 conversion, which needs a seekable file descriptor. */
    fun openSongForReading(uri: Uri): ParcelFileDescriptor? {
        return mediaStoreManager.openFileDescriptor(uri, "r")
    }

    /** Renames the song but keeps its extension, otherwise it would disappear from the library. */
    fun renameSong(uri: Uri, newFileName: String): Int {
        val currentFileName = mediaStoreManager.getFileByUri(uri).name
        val extension = songFileExtensions.firstOrNull {
            currentFileName.endsWith(it, ignoreCase = true)
        } ?: DEFAULT_EXTENSION

        val columnValues =
            mapOf(MediaStore.MediaColumns.DISPLAY_NAME to newFileName.plus(extension))

        return mediaStoreManager.updateFile(uri, columnValues)
    }

    fun deleteSong(uri: Uri): Int {
        return mediaStoreManager.deleteFile(uri)
    }

    companion object {
        private const val DEFAULT_EXTENSION = ".mp3"
    }

}
