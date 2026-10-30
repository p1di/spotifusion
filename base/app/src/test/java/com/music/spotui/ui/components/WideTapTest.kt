package com.music.spotui.ui.components

import com.music.spotui.ui.components.WideTap.Step
import org.junit.Assert.assertEquals
import org.junit.Test

class WideTapTest {

    private val id = 7L

    /** A down as the node sees it: Main unconsumed, then Final consumed by the node itself. */
    private fun WideTap.press(): Step {
        val step = main(id, pressed = true, previousPressed = false, consumed = false, inside = true)
        assertEquals(Step.NONE, final(id, pressed = true, previousPressed = false, consumed = step == Step.PRESS))
        return step
    }

    @Test
    fun tapClicks() {
        val tap = WideTap()
        assertEquals(Step.PRESS, tap.press())
        assertEquals(Step.CLICK, tap.main(id, pressed = false, previousPressed = true, consumed = false, inside = true))
    }

    @Test
    fun jitterBeforeTheLiftStillClicks() {
        val tap = WideTap()
        tap.press()
        repeat(3) {
            assertEquals(Step.NONE, tap.main(id, pressed = true, previousPressed = true, consumed = false, inside = true))
            assertEquals(Step.NONE, tap.final(id, pressed = true, previousPressed = true, consumed = false))
        }
        assertEquals(Step.CLICK, tap.main(id, pressed = false, previousPressed = true, consumed = false, inside = true))
    }

    @Test
    fun scrollTakingTheFingerCancels() {
        val tap = WideTap()
        tap.press()
        assertEquals(Step.NONE, tap.main(id, pressed = true, previousPressed = true, consumed = false, inside = true))
        assertEquals(Step.CANCEL, tap.final(id, pressed = true, previousPressed = true, consumed = true))
        assertEquals(Step.NONE, tap.main(id, pressed = false, previousPressed = true, consumed = false, inside = true))
    }

    @Test
    fun liftOutsideTheWidenedTargetCancels() {
        val tap = WideTap()
        tap.press()
        assertEquals(Step.CANCEL, tap.main(id, pressed = false, previousPressed = true, consumed = false, inside = false))
    }

    @Test
    fun downTakenByAControlUnderTheFingerIsIgnored() {
        val tap = WideTap()
        assertEquals(Step.NONE, tap.main(id, pressed = true, previousPressed = false, consumed = true, inside = true))
        assertEquals(Step.NONE, tap.main(id, pressed = false, previousPressed = true, consumed = false, inside = true))
    }

    @Test
    fun secondFingerDoesNotClick() {
        val tap = WideTap()
        tap.press()
        assertEquals(Step.NONE, tap.main(8L, pressed = false, previousPressed = true, consumed = false, inside = true))
        assertEquals(Step.CLICK, tap.main(id, pressed = false, previousPressed = true, consumed = false, inside = true))
    }
}
