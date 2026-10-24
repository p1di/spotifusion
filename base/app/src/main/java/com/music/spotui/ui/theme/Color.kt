package com.music.spotui.ui.theme

import androidx.compose.ui.graphics.Color

val AppBackground: Color get() = if (app.myspoti.power.EnergySettings.oledBlack) Color.Black else Color(0xFF121212)
val GridBackground = Color(0xFF282828)
/** The accent (Spotify green #1ED760, or the colour a supporter chose). */
val AppPalette: Color get() = com.music.spotui.data.api.SupportReminder.accentColor
val AppAccent: Color get() = com.music.spotui.data.api.SupportReminder.accentColor

/** Exact color tokens from redretep/spotify-clone config/colors.ts */
val ScPrimary = Color(0xFF121212)
val ScSecondary = Color(0xFF282828)
val ScTint = Color(0xFF1ED760)
val ScWhite = Color(0xFFFFFFFF)
val ScGrey = Color(0xFF777777)
val ScLightGrey = Color(0xFFB3B3B3)
val ScLighterGrey = Color(0xFFD5D5D5)
val ScNav = Color(0xFF191414)

/** Exact 21-color Browse Category palette from redretep/spotify-clone config/colors.ts */
val BrowseCategoryColors = listOf(
    Color(0xFF248FCF),
    Color(0xFF7811B3),
    Color(0xFFA1727E),
    Color(0xFF6EAF78),
    Color(0xFF58428B),
    Color(0xFF5DF27A),
    Color(0xFFB2C69C),
    Color(0xFF60BF84),
    Color(0xFFC6125F),
    Color(0xFFB47FF2),
    Color(0xFF761813),
    Color(0xFF21B47A),
    Color(0xFF8158E6),
    Color(0xFF1181C9),
    Color(0xFF7E9DD2),
    Color(0xFFA265A7),
    Color(0xFFDB6953),
    Color(0xFF362660),
    Color(0xFF393FBB),
    Color(0xFF20C187),
    Color(0xFF6D5A97),
)