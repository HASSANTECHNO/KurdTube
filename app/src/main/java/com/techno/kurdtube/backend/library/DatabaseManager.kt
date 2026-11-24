package com.techno.kurdtube.backend.library

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.techno.kurdtube.R
import com.techno.kurdtube.backend.mediastore.MediaStoreDirectory
import com.techno.kurdtube.backend.mediastore.MediaStoreManager
import com.techno.kurdtube.backend.utils.LOG


class DatabaseManager(context: Context, private val mediaStoreManager: MediaStoreManager) {

    private val databaseQueryFile = "database%.txt" //database(1).txt for example
    private val databaseFileName = "database.txt"

    private val databaseDirectory = MediaStoreDirectory(
        Environment.DIRECTORY_DOCUMENTS,
        context.getString(R.string.app_name).plus("/databases")
    )

    private val filesCollection = getFilesCollection()

    private fun getFilesCollection(): Uri {
        val collection =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Files.getContentUri("external") //MediaStore.VOLUME_EXTERNAL
            }
        return collection
    }

    private var database: Uri? = null

    private fun getDatabase(): Uri? {
        if (database == null) {
            val files = mediaStoreManager.getFilesFromDirectory(
                filesCollection,
                databaseDirectory,
                listOf(databaseQueryFile)
            )
            LOG("database files: ${files.size} (${files.joinToString { it.name }})")
            database = if (files.isNotEmpty()) {
                files.last().uri
            } else {
                createDatabase()
            }
        }
        return database
    }

    private fun createDatabase(): Uri? {
        val uri =
            mediaStoreManager.createFile(databaseFileName, filesCollection, databaseDirectory)
                ?: return null
        val outputStream = mediaStoreManager.openFileOutputStream(uri, "wa") ?: return null
        outputStream.use { out ->
            out.write("#${System.currentTimeMillis()}\n".toByteArray())
        }
        return uri
    }

    fun writeLine(line: String): Boolean {
        val databaseFileUri = getDatabase() ?: return false
        val outputStream =
            mediaStoreManager.openFileOutputStream(databaseFileUri, "wa") ?: return false
        val data = line.plus('\n').toByteArray()
        outputStream.use { out ->
            out.write(data)
        }
        return true
    }

    fun readLines(): List<String>? {
        val databaseFileUri = getDatabase() ?: return null
        val inputStream = mediaStoreManager.openFileInputStream(databaseFileUri) ?: return null
        inputStream.bufferedReader().use { reader ->
            return reader.readLines().filter { !it.startsWith('#') }
        }
    }

    /** Returns the newest downloaded file for [videoId], if the database has one. */
    fun findSongUri(videoId: String): Uri? {
        return readLines()
            ?.asReversed()
            ?.firstNotNullOfOrNull { line ->
                val separator = line.lastIndexOf(' ')
                if (separator <= 0) return@firstNotNullOfOrNull null

                val id = line.substring(separator + 1)
                if (id != videoId) return@firstNotNullOfOrNull null

                Uri.parse(line.substring(0, separator))
            }
    }

}