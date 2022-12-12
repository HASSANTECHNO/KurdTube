package com.techno.kurdtube.backend.library

import android.net.Uri

class Playlist(
    val uri: Uri,
    val name: String
) {

    var songs: List<Song>? = null

}