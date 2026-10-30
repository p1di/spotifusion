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
package com.music.spotui.ui.immersive.shell.backdrop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

sealed interface BackdropEffectScope : Density, RuntimeShaderCache {

    val size: Size

    /** Vendored addition: the fraction of the surface resolution the effects run at ([size] is already scaled). */
    val contentScale: Float

    val layoutDirection: LayoutDirection

    val shape: Shape

    var padding: Float

    var renderEffect: RenderEffect?
}

internal abstract class BackdropEffectScopeImpl : BackdropEffectScope, RuntimeShaderCache {

    override var density: Float = 1f
    override var fontScale: Float = 1f
    override var size: Size = Size.Unspecified
    override var contentScale: Float = 1f
    override var layoutDirection: LayoutDirection = LayoutDirection.Ltr
    override var padding: Float = 0f
    override var renderEffect: RenderEffect? = null

    private val runtimeShaderCache = RuntimeShaderCacheImpl()

    override fun obtainRuntimeShader(key: String, string: String): RuntimeShader {
        return runtimeShaderCache.obtainRuntimeShader(key, string)
    }

    // Vendored change: [contentScale] reports the size the effects actually operate
    // on when the backdrop layer is recorded at a reduced resolution, so shader
    // geometry (e.g. the lens size uniform) matches the layer's pixel grid.
    fun update(scope: DrawScope, contentScale: Float = 1f): Boolean {
        val newDensity = scope.density
        val newFontScale = scope.fontScale
        val newSize = if (contentScale != 1f) scope.size * contentScale else scope.size
        val newLayoutDirection = scope.layoutDirection

        val changed = newDensity != density ||
                contentScale != this.contentScale ||
                newFontScale != fontScale ||
                newSize != size ||
                newLayoutDirection != layoutDirection

        if (changed) {
            this.contentScale = contentScale
            density = newDensity
            fontScale = newFontScale
            size = newSize
            layoutDirection = newLayoutDirection
        }

        return changed
    }

    fun apply(effects: BackdropEffectScope.() -> Unit) {
        padding = 0f
        renderEffect = null
        effects()
    }

    fun reset() {
        density = 1f
        contentScale = 1f
        fontScale = 1f
        size = Size.Unspecified
        layoutDirection = LayoutDirection.Ltr
        padding = 0f
        renderEffect = null
        runtimeShaderCache.clear()
    }
}
