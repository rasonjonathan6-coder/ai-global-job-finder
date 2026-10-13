package com.aiglobal.jobfinder.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = RoyalBlue,
    onPrimary = Color.White,
    primaryContainer = InfoContainerLight,
    onPrimaryContainer = Navy700,
    secondary = Teal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDFF5F1),
    onSecondaryContainer = Color(0xFF0B4A41),
    tertiary = Navy700,
    onTertiary = Color.White,
    background = CloudBackground,
    onBackground = InkPrimary,
    surface = CloudSurface,
    onSurface = InkPrimary,
    surfaceVariant = CloudSurfaceVariant,
    onSurfaceVariant = InkSecondary,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFBFE),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF0F3FA),
    surfaceContainerHighest = Color(0xFFE9EEF7),
    outline = CloudOutline,
    outlineVariant = Color(0xFFE2E8F3),
    error = DangerLight,
    onError = Color.White,
    errorContainer = DangerContainerLight,
    onErrorContainer = Color(0xFF7A1B15),
    inverseSurface = Navy800,
    inverseOnSurface = Color(0xFFF2F5FB),
    scrim = Color(0xFF050B18),
)

private val DarkColors = darkColorScheme(
    primary = RoyalBlueLight,
    onPrimary = Color(0xFF04122E),
    primaryContainer = InfoContainerDark,
    onPrimaryContainer = Color(0xFFCBD9FF),
    secondary = TealLight,
    onSecondary = Color(0xFF04231F),
    secondaryContainer = Color(0xFF0E3B35),
    onSecondaryContainer = Color(0xFFA9F0E4),
    tertiary = Color(0xFF9FB6E8),
    onTertiary = Color(0xFF0A1830),
    background = NightBackground,
    onBackground = NightOnSurface,
    surface = NightSurface,
    onSurface = NightOnSurface,
    surfaceVariant = NightSurfaceVariant,
    onSurfaceVariant = NightOnSurfaceSecondary,
    surfaceContainerLowest = Color(0xFF050B16),
    surfaceContainerLow = Color(0xFF0B1526),
    surfaceContainer = NightSurface,
    surfaceContainerHigh = Color(0xFF15223A),
    surfaceContainerHighest = Color(0xFF1D2C48),
    outline = NightOutline,
    outlineVariant = Color(0xFF22314D),
    error = DangerDark,
    onError = Color(0xFF3A0A07),
    errorContainer = DangerContainerDark,
    onErrorContainer = Color(0xFFFFD6D2),
    inverseSurface = Color(0xFFE9EEF8),
    inverseOnSurface = Navy800,
    scrim = Color(0xFF000000),
)

/** Dark-theme preference chosen by the user in Profile (Stage 1: system / light / dark). */
enum class ThemeMode { System, Light, Dark }

val LocalThemeMode = staticCompositionLocalOf { ThemeMode.System }

@Composable
fun AiJobFinderTheme(
    themeMode: ThemeMode = ThemeMode.System,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    val colorScheme = if (darkTheme) DarkColors else LightColors
    val extended = if (darkTheme) DarkExtendedColors else LightExtendedColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        val context = LocalContext.current
        SideEffect {
            val window = (context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(
        LocalExtendedColors provides extended,
        LocalSpacing provides Spacing(),
        LocalThemeMode provides themeMode,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
