package com.music.spotui.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.TouchBoundsExpansion
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * A tap target wider than what is drawn: the element answers taps up to [horizontal] beyond its
 * left and right edges and [vertical] beyond its top and bottom, for a small control with empty
 * space around it (a back arrow in a corner, an avatar in a header). Taps that land on another
 * control still go to that control: Compose hits what is actually under the finger first.
 *
 * Put it before any `clip` / glass surface in the chain: a clipping layer stops taps outside its
 * shape before they reach this. Presses go to [interactionSource] for the caller's own feedback.
 */
fun Modifier.wideClickable(
    horizontal: Dp = 12.dp,
    vertical: Dp = 12.dp,
    interactionSource: MutableInteractionSource? = null,
    role: Role? = Role.Button,
    onClick: () -> Unit,
): Modifier = this then WideClickElement(horizontal, vertical, interactionSource, role, onClick)

/** [wideClickable] with the app's press feedback: the control shrinks a little under the finger. */
fun Modifier.widePressable(
    horizontal: Dp = 12.dp,
    vertical: Dp = 12.dp,
    pressedScale: Float = 0.9f,
    onClick: () -> Unit,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    pressScale(source, pressedScale).wideClickable(horizontal, vertical, source, onClick = onClick)
}

private data class WideClickElement(
    val horizontal: Dp,
    val vertical: Dp,
    val interactionSource: MutableInteractionSource?,
    val role: Role?,
    val onClick: () -> Unit,
) : ModifierNodeElement<WideClickNode>() {
    override fun create() = WideClickNode(horizontal, vertical, interactionSource, role, onClick)
    override fun update(node: WideClickNode) {
        if (node.interactionSource != interactionSource) node.release()
        node.horizontal = horizontal
        node.vertical = vertical
        node.interactionSource = interactionSource
        node.role = role
        node.onClick = onClick
    }
    override fun InspectorInfo.inspectableProperties() {
        name = "wideClickable"
        properties["horizontal"] = horizontal
        properties["vertical"] = vertical
    }
}

private class WideClickNode(
    var horizontal: Dp,
    var vertical: Dp,
    var interactionSource: MutableInteractionSource?,
    var role: Role?,
    var onClick: () -> Unit,
) : Modifier.Node(), PointerInputModifierNode, SemanticsModifierNode {

    private val tap = WideTap()
    private var press: PressInteraction.Press? = null

    override val touchBoundsExpansion: TouchBoundsExpansion
        get() = with(requireDensity()) {
            val h = horizontal.roundToPx()
            val v = vertical.roundToPx()
            TouchBoundsExpansion(start = h, top = v, end = h, bottom = v)
        }

    override val shouldMergeDescendantSemantics: Boolean get() = true

    override fun SemanticsPropertyReceiver.applySemantics() {
        // Named through the node: inside this receiver, `role` alone is the semantics property.
        val node = this@WideClickNode
        node.role?.let { role = it }
        onClick { node.onClick(); true }
    }

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        val change = pointerEvent.changes.firstOrNull { tap.follows(it.id.value) } ?: pointerEvent.changes.firstOrNull() ?: return
        val step = when (pass) {
            PointerEventPass.Main -> tap.main(change.id.value, change.pressed, change.previousPressed, change.isConsumed, inside(change.position, bounds))
            PointerEventPass.Final -> tap.final(change.id.value, change.pressed, change.previousPressed, change.isConsumed)
            else -> return
        }
        when (step) {
            WideTap.Step.PRESS -> {
                change.consume()
                press = PressInteraction.Press(change.position).also { interactionSource?.tryEmit(it) }
            }
            WideTap.Step.CLICK -> {
                change.consume()
                press?.let { interactionSource?.tryEmit(PressInteraction.Release(it)) }
                press = null
                onClick()
            }
            WideTap.Step.CANCEL -> cancelPress()
            WideTap.Step.NONE -> Unit
        }
    }

    private fun inside(p: Offset, bounds: IntSize): Boolean = with(requireDensity()) {
        val h = horizontal.toPx()
        val v = vertical.toPx()
        p.x >= -h && p.y >= -v && p.x <= bounds.width + h && p.y <= bounds.height + v
    }

    override fun onCancelPointerInput() {
        tap.cancel()
        cancelPress()
    }

    override fun onDetach() = onCancelPointerInput()

    fun release() = onCancelPointerInput()

    private fun cancelPress() {
        press?.let { interactionSource?.tryEmit(PressInteraction.Cancel(it)) }
        press = null
    }
}

/**
 * The tap rules of [wideClickable], kept apart from Compose so a JVM test can run them. Fed one
 * pointer change per pass: [main] in the Main pass, [final] in the Final pass.
 *
 * Read the raw pressed flags, not `changedToDown()` / `changedToUp()`: those answer false once a
 * change is consumed, and the node consumes the down itself.
 */
internal class WideTap {
    enum class Step { NONE, PRESS, CANCEL, CLICK }

    private var down: Long? = null

    fun follows(id: Long) = id == down

    fun main(id: Long, pressed: Boolean, previousPressed: Boolean, consumed: Boolean, inside: Boolean): Step = when {
        down == null && pressed && !previousPressed && !consumed -> { down = id; Step.PRESS }
        id != down -> Step.NONE
        // Something under the finger took it, or the finger left the (widened) target.
        consumed || !inside -> { down = null; Step.CANCEL }
        !pressed -> { down = null; Step.CLICK }
        else -> Step.NONE
    }

    /** A move consumed after us (Main runs from the deepest node up): a scroll took the finger over. */
    fun final(id: Long, pressed: Boolean, previousPressed: Boolean, consumed: Boolean): Step =
        if (id == down && consumed && pressed && previousPressed) { down = null; Step.CANCEL } else Step.NONE

    fun cancel() { down = null }
}
