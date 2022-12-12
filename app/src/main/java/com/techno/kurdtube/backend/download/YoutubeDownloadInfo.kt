package com.techno.kurdtube.backend.download

import android.net.Uri


class YoutubeDownloadInfo(
    val videoTitle: String,
    val videoUrl: Uri,
    val downloadUrl: Uri
)