package com.techno.kurdtube.frontend.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import com.techno.kurdtube.backend.locale.AppLanguage
import com.techno.kurdtube.backend.locale.LocaleManager

// Neutral accent: black in light mode, white in dark mode (no purple).
private val AccentBlack = Color(0xFF000000)
private val AccentWhite = Color(0xFFFFFFFF)

@Composable
fun MainTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {

    //Kurdish Sorani needs a font with the extra letters (ڕ ڵ ۆ ێ ە); the system
    //font falls back to Arabic shapes for some of them on many devices.
    val context = LocalContext.current
    val language = LocaleManager.getLanguage(context)
    val typography = remember(language) {
        if (language == AppLanguage.KURDISH_SORANI) {
            Typography().withFontFamily(RudawFontFamily)
        } else {
            Typography()
        }
    }

    val colors = if (darkTheme) {
        darkColorScheme(
            primary = AccentWhite,
            onPrimary = AccentBlack,
            primaryContainer = AccentWhite,
            onPrimaryContainer = AccentBlack,
            inversePrimary = AccentBlack,
            secondary = AccentWhite,
            onSecondary = AccentBlack,
            secondaryContainer = Color(0xFF2C2C2C),
            onSecondaryContainer = AccentWhite,
            tertiary = AccentWhite,
            onTertiary = AccentBlack,
            tertiaryContainer = Color(0xFF2C2C2C),
            onTertiaryContainer = AccentWhite,
            background = Color(0xFF121212),
            onBackground = AccentWhite,
            surface = Color(0xFF121212),
            onSurface = AccentWhite,
            surfaceVariant = Color(0xFF2A2A2A),
            onSurfaceVariant = Color(0xFFCFCFCF),
            surfaceTint = AccentWhite,
            inverseSurface = Color(0xFFE6E6E6),
            inverseOnSurface = Color(0xFF1A1A1A),
            outline = Color(0xFF8A8A8A),
            outlineVariant = Color(0xFF3A3A3A),
        )
    } else {
        lightColorScheme(
            primary = AccentBlack,
            onPrimary = AccentWhite,
            primaryContainer = AccentBlack,
            onPrimaryContainer = AccentWhite,
            inversePrimary = AccentWhite,
            secondary = AccentBlack,
            onSecondary = AccentWhite,
            secondaryContainer = Color(0xFFE6E6E6),
            onSecondaryContainer = AccentBlack,
            tertiary = AccentBlack,
            onTertiary = AccentWhite,
            tertiaryContainer = Color(0xFFE6E6E6),
            onTertiaryContainer = AccentBlack,
            background = AccentWhite,
            onBackground = AccentBlack,
            surface = AccentWhite,
            onSurface = AccentBlack,
            surfaceVariant = Color(0xFFF2F2F2),
            onSurfaceVariant = Color(0xFF444444),
            surfaceTint = AccentBlack,
            inverseSurface = Color(0xFF2E2E2E),
            inverseOnSurface = AccentWhite,
            outline = Color(0xFF8A8A8A),
            outlineVariant = Color(0xFFE3E3E3),
        )
    }

    MaterialTheme(
        colorScheme = colors,
        typography = typography
    ) {

        val systemUiController = rememberSystemUiController()
        systemUiController.setSystemBarsColor(
            color = MaterialTheme.colorScheme.surface,
            darkIcons = !darkTheme
        )

        content()

    }

}
