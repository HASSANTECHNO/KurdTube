package com.techno.kurdtube.backend.screen

import com.techno.kurdtube.R

enum class Screen(val displayName: Int, val icon: Int) {

    SEARCH(R.string.screen_search, R.drawable.icon_search),
    LIBRARY(R.string.screen_library, R.drawable.icon_library),
    PLAYER(R.string.screen_player, R.drawable.icon_player),
    SETTINGS(R.string.screen_settings, R.drawable.icon_settings);

}