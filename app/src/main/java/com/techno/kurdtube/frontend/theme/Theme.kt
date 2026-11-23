package com.techno.kurdtube.frontend.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import com.techno.kurdtube.R

// Neutral accent: black in light mode, white in dark mode (no purple).
private val AccentBlack = Color(0xFF000000)
private val AccentWhite = Color(0xFFFFFFFF)

//Kurdish Sorani needs a font with the extra letters (ڕ ڵ ۆ ێ ە); the system
//font falls back to Arabic shapes for some of them on many devices.
private val KurdishSoraniFontFamily = FontFamily(
    Font(R.font.rudaw_regular)
)

private val KurdishSoraniTypography = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge.copy(fontFamily = KurdishSoraniFontFamily),
        displayMedium = base.displayMedium.copy(fontFamily = KurdishSoraniFontFamily),
        displaySmall = base.displaySmall.copy(fontFamily = KurdishSoraniFontFamily),
        headlineLarge = base.headlineLarge.copy(fontFamily = KurdishSoraniFontFamily),
        headlineMedium = base.headlineMedium.copy(fontFamily = KurdishSoraniFontFamily),
        headlineSmall = base.headlineSmall.copy(fontFamily = KurdishSoraniFontFamily),
        titleLarge = base.titleLarge.copy(fontFamily = KurdishSoraniFontFamily),
        titleMedium = base.titleMedium.copy(fontFamily = KurdishSoraniFontFamily),
        titleSmall = base.titleSmall.copy(fontFamily = KurdishSoraniFontFamily),
        bodyLarge = base.bodyLarge.copy(fontFamily = KurdishSoraniFontFamily),
        bodyMedium = base.bodyMedium.copy(fontFamily = KurdishSoraniFontFamily),
        bodySmall = base.bodySmall.copy(fontFamily = KurdishSoraniFontFamily),
        labelLarge = base.labelLarge.copy(fontFamily = KurdishSoraniFontFamily),
        labelMedium = base.labelMedium.copy(fontFamily = KurdishSoraniFontFamily),
        labelSmall = base.labelSmall.copy(fontFamily = KurdishSoraniFontFamily),
    )
}

@Composable
fun MainTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    useKurdishSoraniFont: Boolean = false,
    content: @Composable () -> Unit
) {

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
        typography = if (useKurdishSoraniFont) KurdishSoraniTypography else Typography()
    ) {

        val systemUiController = rememberSystemUiController()
        systemUiController.setSystemBarsColor(
            color = MaterialTheme.colorScheme.surface,
            darkIcons = !darkTheme
        )

        content()

    }

}
