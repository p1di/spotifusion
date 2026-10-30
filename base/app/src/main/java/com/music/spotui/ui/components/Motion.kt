package com.music.spotui.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import app.myspoti.engine.R
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Official-app press feedback: the control shrinks a little under the finger and
 * springs back on release, no ripple. Animates only while pressed.
 */
fun Modifier.pressable(
    pressedScale: Float = 0.9f,
    enabled: Boolean = true,
    onPressHint: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val hinted = if (onPressHint == null) Modifier else Modifier.pressHint(onPressHint)
    hinted.then(pressScale(source, pressedScale)).clickable(source, indication = null, enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
}

/**
 * Calls [onHint] when a finger rests on the element (80 ms without moving past the
 * touch slop) or taps it: the page behind it starts loading ~100 ms before the
 * click. A touch that turns into a scroll asks for nothing.
 */
fun Modifier.pressHint(onHint: () -> Unit): Modifier = composed {
    // The gesture is not restarted when a caller passes a new lambda: a recomposition under a finger keeps the touch.
    val currentOnHint by rememberUpdatedState(onHint)
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val slop = viewConfiguration.touchSlop
            var moved = false
            withTimeoutOrNull(80) {
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    if ((change.position - down.position).getDistance() > slop) { moved = true; break }
                    if (!change.pressed) break
                }
            }
            if (!moved) currentOnHint()
        }
    }
}

/** The shrink of [pressable], for a control whose clickable sits further down the chain. */
fun Modifier.pressScale(source: MutableInteractionSource, pressedScale: Float = 0.9f): Modifier = composed {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) pressedScale else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * The play/pause glyph of the official app: the new one springs in from a smaller size
 * while the old one fades out, instead of swapping in a single frame. [playing] picks
 * the pause glyph.
 */
@Composable
fun PlayPauseIcon(
    playing: Boolean,
    contentDescription: String?,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val reduce = com.music.spotui.ui.look.rememberReduceMotion()
    AnimatedContent(
        targetState = playing,
        transitionSpec = {
            if (reduce) EnterTransition.None togetherWith ExitTransition.None
            else (scaleIn(spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.6f) + fadeIn(tween(120))) togetherWith
                (scaleOut(tween(120), targetScale = 0.6f) + fadeOut(tween(90)))
        },
        contentAlignment = Alignment.Center,
        label = "playPause",
    ) { isPlaying ->
        Icon(
            painter = painterResource(if (isPlaying) R.drawable.ic_playing else R.drawable.play_svgrepo_com),
            contentDescription = contentDescription,
            tint = tint,
            modifier = modifier,
        )
    }
}

/**
 * A row of a lazy list that reorders or loses items: it slides to its new place, on the spring
 * of the library's rows. No fades (a row that comes or goes simply is, or is not, there), and
 * no slide under "Remove animations". Only for items with stable keys.
 */
@Composable
fun LazyItemScope.animatedRow(): Modifier = Modifier.animateItem(
    fadeInSpec = null, fadeOutSpec = null,
    placementSpec = if (com.music.spotui.ui.look.rememberReduceMotion()) null else spring(
        stiffness = Spring.StiffnessMediumLow,
        visibilityThreshold = androidx.compose.ui.unit.IntOffset.VisibilityThreshold,
    ),
)
