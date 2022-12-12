package com.techno.kurdtube.backend.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.techno.kurdtube.backend.library.Song
import com.techno.kurdtube.backend.utils.LOG


class PlayerState {

    init {
        LOG("init PlayerState")
    }

    var currentSong by mutableStateOf(null as Song?)
    var isPlaying by mutableStateOf(false)
    var position by mutableStateOf(0L)
    var duration by mutableStateOf(0L)
    var isShuffle by mutableStateOf(false)
    var isRepeat by mutableStateOf(false)

}