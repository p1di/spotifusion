package com.music.spotui.ui.theme

import android.app.Activity
import android.graphics.Color.toArgb
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Spotify's dark palette, so the Material components that read the scheme (switches,
 * sliders, dialogs, spinners, text fields, menus) look like the official app: accent
 * green on black, #121212 background, #1F1F1F/#282828 raised surfaces, #B3B3B3
 * secondary text, and no tonal tint on elevated surfaces.
 */
private fun spotifyColorScheme(accent: Color) = darkColorScheme(
    primary = accent,
    onPrimary = Color.Black,
    primaryContainer = accent,
    onPrimaryContainer = Color.Black,
    secondary = Color.White,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF2A2A2A),
    onSecondaryContainer = Color.White,
    tertiary = accent,
    onTertiary = Color.Black,
    background = AppBackground,
    onBackground = Color.White,
    surface = AppBackground,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFB3B3B3),
    surfaceTint = Color.Transparent,
    surfaceDim = AppBackground,
    surfaceBright = Color(0xFF2A2A2A),
    surfaceContainerLowest = AppBackground,
    surfaceContainerLow = Color(0xFF181818),
    surfaceContainer = Color(0xFF1F1F1F),
    surfaceContainerHigh = Color(0xFF282828),
    surfaceContainerHighest = Color(0xFF3E3E3E),
    inverseSurface = Color.White,
    inverseOnSurface = Color.Black,
    inversePrimary = accent,
    outline = Color(0xFF727272),
    outlineVariant = Color(0xFF3E3E3E),
    error = Color(0xFFF3727F),
    onError = Color.Black,
    scrim = Color.Black,
)

@Composable
fun SpotuiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val energy by app.myspoti.power.EnergySettings.state.collectAsStateWithLifecycle()
    val policy = energy.policy
    androidx.compose.runtime.LaunchedEffect(policy.videoMaxHeight, policy.allowBackgroundLookups) {
        com.music.spotui.video.MusicVideoController.onEnergyPolicyChanged()
    }
    // The app is always dark, like Spotify: a phone in light mode must not turn the
    // surfaces white (an empty or failed page then showed white text on white).
    val visualScheme = spotifyColorScheme(AppAccent)
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.attributes = window.attributes.apply { preferredRefreshRate = policy.displayRefreshCap }
            window.statusBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false // always-dark app: white status bar icons
            // B19: the navigation bar was left to the system — a white band under the always-dark
            // UI (reported in the modal sheets, where the bar was opaque) with dark icons on it.
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = visualScheme,
        typography = Typography,
    ) {
        // Bare Text() uses LocalTextStyle, which MaterialTheme does NOT derive
        // from typography — so provide AppSans (Spotify Mix) as the global default so
        // every screen picks it up without touching each Text call.
        androidx.compose.material3.ProvideTextStyle(
            value = androidx.compose.material3.LocalTextStyle.current.copy(
                fontFamily = AppSans,
            ),
            content = content,
        )
    }
}