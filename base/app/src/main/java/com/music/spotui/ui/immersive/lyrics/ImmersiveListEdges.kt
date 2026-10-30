/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later,
 * sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerQueue.kt at edc1e6f.
 * Modified for Myspoti, 2026-10.
 */
package com.music.spotui.ui.immersive.lyrics

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp

/**
 * Swallows whatever scroll the list itself didn't use, so a sheet or pager around it never
 * reads the leftover as a drag on itself. A downward fling has to be caught before they see
 * it, but only at the top of the list: otherwise the list could never fling.
 */
internal fun keepScrollInList(listState: LazyListState) = object : NestedScrollConnection {
    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset = available

    override suspend fun onPreFling(available: Velocity): Velocity =
        if (available.y > 0f && !listState.canScrollBackward) available else Velocity.Zero

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}

/** Softens the list where it meets the header and the controls. */
internal fun Modifier.fadingEdges(): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        val fade = 28.dp.toPx()
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color.Black),
                startY = 0f,
                endY = fade,
            ),
            blendMode = BlendMode.DstIn,
        )
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Black, Color.Transparent),
                startY = size.height - fade,
                endY = size.height,
            ),
            blendMode = BlendMode.DstIn,
        )
    }
