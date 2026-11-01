package com.techno.kurdtube.backend.locale

import com.techno.kurdtube.R

/**
 * Languages the app supports. The code is the BCP 47 language tag which is also
 * used as the folder qualifier of the translated resources (values-ckb, values-fa).
 */
enum class AppLanguage(val code: String, val label: Int) {

    ENGLISH("en", R.string.language_english),
    KURDISH_SORANI("ckb", R.string.language_kurdish_sorani),
    PERSIAN("fa", R.string.language_persian);

    companion object {

        val DEFAULT = ENGLISH

        fun fromCode(code: String?): AppLanguage {
            return values().firstOrNull { it.code == code } ?: DEFAULT
        }

    }

}
