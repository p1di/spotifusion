package com.music.spotui.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.myspoti.engine.R

/**
 * Official Spotify Mix font family (Spotify's bespoke brand typeface).
 * Fully mapped with Regular, Medium, Bold, ExtraBold, and Ultra weights
 * for pixel-identical typography matching the official Spotify Android app.
 */
val AppSans = FontFamily(
    Font(R.font.spotifymix_regular, weight = FontWeight.Thin),
    Font(R.font.spotifymix_regular, weight = FontWeight.Light),
    Font(R.font.spotifymix_regular, weight = FontWeight.Normal),
    Font(R.font.spotifymix_medium, weight = FontWeight.Medium),
    Font(R.font.spotifymix_bold, weight = FontWeight.SemiBold),
    Font(R.font.spotifymix_bold, weight = FontWeight.Bold),
    Font(R.font.spotifymix_extrabold, weight = FontWeight.ExtraBold),
    Font(R.font.spotifymix_ultra, weight = FontWeight.Black),
)

/** Headings: the same family, heavier cuts. */
val AppSansTitle = AppSans

// Kept as an alias so existing references (Theme.kt etc.) don't break.
val Montserrat = AppSans

private val base = TextStyle(
    fontFamily = AppSans,
)
private val title = TextStyle(
    fontFamily = AppSansTitle,
    fontWeight = FontWeight.Bold,
)

val Typography = Typography(
    displayLarge = title.copy(fontSize = 57.sp, lineHeight = 64.sp),
    displayMedium = title.copy(fontSize = 45.sp, lineHeight = 52.sp),
    displaySmall = title.copy(fontSize = 36.sp, lineHeight = 44.sp),
    headlineLarge = title.copy(fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = title.copy(fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall = title.copy(fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = base.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleMedium = base.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold),
    titleSmall = base.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold),
    bodyLarge = base.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal),
    bodyMedium = base.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    bodySmall = base.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
    labelLarge = base.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = base.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = base.copy(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)
