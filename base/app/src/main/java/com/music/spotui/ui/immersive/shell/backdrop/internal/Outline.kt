/*
 * Vendored from Kyant0/backdrop v2.0.0 (io.github.kyant0:backdrop)
 * https://github.com/Kyant0/backdrop — Copyright 2025 Kyant0, Apache License 2.0
 *
 * Vendored so the library ships as source with this app (binary AARs compiled
 * against older Compose broke at runtime) and to add a backdrop resolution
 * scale for cheaper effect rendering. KMP expect/actual declarations were
 * merged into this single Android source set. Package renamed accordingly.
 * Myspoti copy taken from BitChord's vendored tree (github.com/kushagrasinghx/BitChord,
 * app/src/main/java/com/music/bitchord/ui/components/backdrop at edc1e6f), which adds
 * backdropScale and exportedBackdrop to drawBackdrop. Package renamed again for Myspoti.
 */
package com.music.spotui.ui.immersive.shell.backdrop.internal

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path

internal fun Canvas.clipOutline(outline: Outline, path: Path?) {
    when (outline) {
        is Outline.Rectangle -> clipRect(outline.rect)
        is Outline.Rounded -> {
            path!!.rewind()
            path.addRoundRect(outline.roundRect)
            clipPath(path)
        }

        is Outline.Generic -> clipPath(outline.path)
    }
}
