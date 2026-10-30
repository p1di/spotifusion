package com.music.spotui.ui.immersive

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.spotui.ui.look.ImmersiveDisplay
import com.music.spotui.ui.look.ImmersiveSans

/*
 * The Immersive look's tokens, shared by every Immersive screen so they read as one app:
 * a black page, Inter, large bold titles, rounded covers, translucent "glass" bars and an
 * accent taken from the playing cover where there is one.
 */
object Immersive {
    // Colours.
    val Page = Color.Black
    val Raised = Color(0xFF1C1C1E)
    val Separator = Color.White.copy(alpha = 0.10f)
    val Text = Color.White
    val TextSecondary = Color.White.copy(alpha = 0.60f)
    val TextTertiary = Color.White.copy(alpha = 0.38f)
    /** The look's own pink until an Immersive accent colour is chosen in Settings, then that colour (links, the selected tab, toggles). */
    val Accent: Color get() = com.music.spotui.data.api.SupportReminder.run {
        if (immersiveAccentChosen) immersiveAccent.color else Color(0xFFFF4F7B)
    }
    val GlassFill = Color(0xFF2C2C34).copy(alpha = 0.88f)
    val GlassEdge = Color.White.copy(alpha = 0.12f)
    /** The red of a destructive swipe, menu entry or confirmation (Leave, Remove, an error). */
    val Destructive = Color(0xFFFF453A)

    // Shapes.
    val CoverSmall: Shape = RoundedCornerShape(6.dp)
    val CoverMedium: Shape = RoundedCornerShape(10.dp)
    val CoverLarge: Shape = RoundedCornerShape(14.dp)
    val Card: Shape = RoundedCornerShape(14.dp)
    val Button: Shape = RoundedCornerShape(14.dp)

    // Type (Inter).
    private val base = TextStyle(fontFamily = ImmersiveSans, color = Text)
    private val display = TextStyle(fontFamily = ImmersiveDisplay, color = Text)
    val LargeTitle = display.copy(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold)
    val Title = display.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
    val Headline = display.copy(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold)
    val Body = base.copy(fontSize = 16.sp, lineHeight = 21.sp)
    val BodyStrong = Body.copy(fontWeight = FontWeight.SemiBold)
    val Subhead = base.copy(fontSize = 14.sp, lineHeight = 19.sp, color = TextSecondary)
    val Caption = base.copy(fontSize = 12.sp, lineHeight = 16.sp, color = TextSecondary)
    val Lyric = display.copy(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)

    // Spacing.
    val Gutter = 20.dp
    val RowHeight = 60.dp
    /** Room the floating tab bar and mini player take at the bottom of every page. */
    val BottomChrome = 170.dp
}

/** A translucent bar or capsule (tab bar, mini player, menus). */
fun Modifier.glass(shape: Shape = CircleShape): Modifier =
    this.background(Immersive.GlassFill, shape).border(1.dp, Immersive.GlassEdge, shape)
