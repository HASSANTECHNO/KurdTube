package com.techno.kurdtube.backend.download

import android.net.Uri


class YoutubeDownloadInfo(
    val videoTitle: String,
    val videoUrl: Uri,
    val downloadUrl: Uri,
    /** Mime type of the audio stream, e.g. "audio/mp4". May be null if not known. */
    val mimeType: String? = null,
    /** File size in bytes. May be -1L if unknown. */
    val contentLength: Long = -1L
)