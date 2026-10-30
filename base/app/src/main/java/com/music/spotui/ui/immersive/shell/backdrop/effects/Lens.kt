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
package com.music.spotui.ui.immersive.shell.backdrop.effects

import androidx.annotation.FloatRange
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.fastCoerceAtLeast
import androidx.compose.ui.util.fastCoerceAtMost
import com.music.spotui.ui.immersive.shell.backdrop.BackdropEffectScope
import com.music.spotui.ui.immersive.shell.backdrop.internal.RoundedRectRefractionShaderString
import com.music.spotui.ui.immersive.shell.backdrop.internal.RoundedRectRefractionWithDispersionShaderString
import com.music.spotui.ui.immersive.shell.backdrop.internal.RuntimeShaderEffect
import com.music.spotui.ui.immersive.shell.backdrop.isRuntimeShaderSupported

fun BackdropEffectScope.lens(
    @FloatRange(from = 0.0) refractionHeight: Float,
    @FloatRange(from = 0.0) refractionAmount: Float,
    depthEffect: Boolean = false,
    chromaticAberration: Boolean = false
) {
    if (!isRuntimeShaderSupported()) return
    if (refractionHeight <= 0f || refractionAmount <= 0f) return

    if (padding > 0f) {
        padding = (padding - refractionHeight).fastCoerceAtLeast(0f)
    }

    val cornerRadii = cornerRadii
    val effect =
        if (cornerRadii != null) {
            val shader =
                if (!chromaticAberration) {
                    obtainRuntimeShader(
                        "Refraction",
                        RoundedRectRefractionShaderString
                    )
                } else {
                    obtainRuntimeShader(
                        "RefractionWithDispersion",
                        RoundedRectRefractionWithDispersionShaderString
                    )
                }
            shader.apply {
                setFloatUniform("size", size.width, size.height)
                setFloatUniform("offset", -padding, -padding)
                setFloatUniform("cornerRadii", cornerRadii)
                setFloatUniform("refractionHeight", refractionHeight)
                setFloatUniform("refractionAmount", -refractionAmount)
                setFloatUniform("depthEffect", if (depthEffect) 1f else 0f)
                if (chromaticAberration) {
                    setFloatUniform("chromaticAberration", 1f)
                }
            }
            RuntimeShaderEffect(shader, "content")
        } else {
            throwUnsupportedSDFException()
        }
    effect(effect)
}

// Vendored change: support for io.github.kyant0:shapes' RoundedRectangularShape was
// dropped so the vendored sources have no external dependency; this app only passes
// CornerBasedShape here.
private val BackdropEffectScope.cornerRadii: FloatArray?
    get() = when (val shape = shape) {
        is AbsoluteRoundedCornerShape -> {
            val size = size
            val maxRadius = size.minDimension / 2f
            // Vendored fix: the shape is measured against the surface, the effect runs on the
            // scaled-down layer; a radius in dp must follow the scale (a percent one already does).
            val full = size / contentScale
            val k = contentScale
            val topLeft = shape.topStart.toPx(full, this) * k
            val topRight = shape.topEnd.toPx(full, this) * k
            val bottomRight = shape.bottomEnd.toPx(full, this) * k
            val bottomLeft = shape.bottomStart.toPx(full, this) * k
            floatArrayOf(
                topLeft.fastCoerceAtMost(maxRadius),
                topRight.fastCoerceAtMost(maxRadius),
                bottomRight.fastCoerceAtMost(maxRadius),
                bottomLeft.fastCoerceAtMost(maxRadius)
            )
        }

        is CornerBasedShape -> {
            val size = size
            val maxRadius = size.minDimension / 2f
            val isLtr = layoutDirection == LayoutDirection.Ltr
            // Vendored fix: see the absolute branch above.
            val full = size / contentScale
            val k = contentScale
            val topLeft =
                (if (isLtr) shape.topStart.toPx(full, this)
                else shape.topEnd.toPx(full, this)) * k
            val topRight =
                (if (isLtr) shape.topEnd.toPx(full, this)
                else shape.topStart.toPx(full, this)) * k
            val bottomRight =
                (if (isLtr) shape.bottomEnd.toPx(full, this)
                else shape.bottomStart.toPx(full, this)) * k
            val bottomLeft =
                (if (isLtr) shape.bottomStart.toPx(full, this)
                else shape.bottomEnd.toPx(full, this)) * k
            floatArrayOf(
                topLeft.fastCoerceAtMost(maxRadius),
                topRight.fastCoerceAtMost(maxRadius),
                bottomRight.fastCoerceAtMost(maxRadius),
                bottomLeft.fastCoerceAtMost(maxRadius)
            )
        }

        else -> null
    }

private fun throwUnsupportedSDFException(): Nothing {
    throw UnsupportedOperationException(
        "Only RoundedRectangularShape or CornerBasedShape is supported in lens effects."
    )
}
