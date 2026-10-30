/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later,
 * app/src/main/java/com/music/bitchord/ui/components/BottomFadeScrim.kt at edc1e6f. Modified for Myspoti, 2026-10.
 */
package com.music.spotui.ui.immersive.shell

import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.music.spotui.ui.immersive.Immersive

/** The strip the tab bar alone needs, above the gesture inset. Generous on purpose: the ramp spends most of its run at an alpha too low to see, which hides where the layer begins. */
private val FADE_HEIGHT = 180.dp

/** Taller once the mini player is stacked on top of the tab bar. */
private val FADE_HEIGHT_WITH_MINI_PLAYER = 254.dp

/** How many colour stops the ramp is cut into, so a near-flat colour does not band. */
private const val STOPS = 16

/**
 * The floor the floating bars sit on: the page colour faded in from nothing at the top to solid
 * at the very bottom, so content scrolling under the tab bar and the mini player never competes
 * with them. Flat colour, not a blur: a gradient costs one shader over a fixed rect.
 *
 * Draw it at the bottom of the app's Box, below the bars and above the page.
 *
 * @param withMiniPlayer taller when the mini player is showing above the tab bar.
 * @param pageColor the colour to fade in — whatever the page paints at the foot of the screen.
 */
@Composable
fun ImmersiveBottomFade(
    modifier: Modifier = Modifier,
    withMiniPlayer: Boolean = false,
    pageColor: Color = Immersive.Page,
) {
    // The gesture bar sits below the tab pill and wants covering too.
    val inset = ImmersiveBarInsets.asPaddingValues().calculateBottomPadding()
    val height by animateDpAsState(
        targetValue = inset + if (withMiniPlayer) FADE_HEIGHT_WITH_MINI_PLAYER else FADE_HEIGHT,
        // Matches the beat the mini player takes to appear.
        animationSpec = tween(com.music.spotui.ui.immersive.motion.ImmersiveMotion.SHORT_MS),
        label = "immersiveBottomFadeHeight",
    )
    val brush = remember(pageColor) {
        Brush.verticalGradient(
            // A cubic ease-in: the alpha holds under a few percent for the first half of the
            // strip, which stops the eye finding the line where the layer starts.
            colorStops = Array(STOPS) { i ->
                val t = i / (STOPS - 1f)
                t to pageColor.copy(alpha = EaseInCubic.transform(t))
            },
        )
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(brush),
    )
}

/** How far above the floating bars the page stops taking touches. */
private val TAP_GUARD_MARGIN = 8.dp

/**
 * A dead strip across the foot of the screen, as tall as the floating bars plus a margin, so a
 * thumb that misses a tab or the mini player lands on nothing rather than on a song passing under
 * the bars. Draw it after the page and before the bars.
 *
 * @param barsHeight the bars' height in px, read in layout only (the mini player changes height
 * on every frame of its fold and this must not recompose the app root).
 */
@Composable
fun ImmersiveBarsTapGuard(
    barsHeight: () -> Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .layout { measurable, constraints ->
                val bars = barsHeight()
                val height = if (bars <= 0) 0 else (bars + TAP_GUARD_MARGIN.roundToPx())
                val placeable = measurable.measure(
                    Constraints.fixed(
                        width = constraints.maxWidth,
                        height = height.coerceAtMost(constraints.maxHeight),
                    ),
                )
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
            .pointerInput(Unit) {
                // Being hit at all is what keeps the page underneath from being hit.
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            },
    )
}
