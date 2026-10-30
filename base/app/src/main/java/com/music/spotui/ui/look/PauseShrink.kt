package com.music.spotui.ui.look

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State

/** How far the Immersive artwork steps back while the music is paused. */
const val PAUSED_ARTWORK_SCALE = 0.86f

/**
 * The Immersive artwork's scale: it steps back to [PAUSED_ARTWORK_SCALE] on pause and comes
 * forward again with a slight bounce when the music resumes. Read it in a graphics layer so
 * the spring only redraws. With animations off it snaps, like every other spring.
 */
@Composable
fun rememberPauseScale(playing: Boolean): State<Float> = animateFloatAsState(
    targetValue = if (playing) 1f else PAUSED_ARTWORK_SCALE,
    animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
    label = "pauseScale",
)
