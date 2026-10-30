/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later,
 * sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/SongText.kt at edc1e6f. Modified for Myspoti, 2026-10.
 */
package com.music.spotui.ui.immersive.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.spotui.ui.immersive.Immersive

/**
 * A song title with the catalogue-standard explicit badge in front of it. Give it a width (it
 * fills the row it is in): `ImmersiveSongTitle(song.title, song.explicit, Immersive.Body, color)`.
 */
@Composable
fun ImmersiveSongTitle(
    title: String,
    explicit: Boolean,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (explicit) {
            ImmersiveExplicitBadge(color)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = title,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The explicit mark: a filled tile with the E cut out of it, so whatever sits behind — a list
 * row, the player's artwork — shows through the letter. The cut-out is a real hole rather than
 * the letter painted in a background colour, which is why it draws into its own offscreen layer:
 * DstOut can then only erase the tile, never what is under it.
 */
@Composable
fun ImmersiveExplicitBadge(color: Color, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val style = remember {
        Immersive.Caption.copy(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold)
    }
    val letter = remember(measurer, style) { measurer.measure("E", style) }
    // The letter's line box plus 3dp a side.
    val size = with(LocalDensity.current) {
        DpSize(letter.size.width.toDp() + 6.dp, letter.size.height.toDp())
    }
    Canvas(
        modifier
            .size(size)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        drawRoundRect(color = color, cornerRadius = CornerRadius(2.dp.toPx()))
        drawText(
            textLayoutResult = letter,
            topLeft = Offset(
                (this.size.width - letter.size.width) / 2f,
                (this.size.height - letter.size.height) / 2f,
            ),
            blendMode = BlendMode.DstOut,
        )
    }
}
