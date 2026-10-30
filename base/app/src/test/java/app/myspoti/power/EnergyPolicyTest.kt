package app.myspoti.power

import org.junit.Assert.*
import org.junit.Test

class EnergyPolicyTest {
    @Test fun constructorDefaultsMatchResolvedNormalPolicy() {
        assertEquals(EnergyPolicy(), EnergyPolicy.resolve(false, false))
    }
    @Test fun onlySaverCapsTheDisplayAndReducesProgress() {
        for (manual in listOf(false, true)) for (black in listOf(false, true)) {
            val p = EnergyPolicy.resolve(manual, black)
            assertEquals(if (manual) 60f else 0f, p.displayRefreshCap, 0f)
            assertEquals(if (manual) 20 else 30, p.progressFps)
        }
    }
    @Test fun defaultRequestsAStableDisplayBudgetAndPreservesCanvas() {
        val p = EnergyPolicy.resolve(false, false)
        assertTrue(p.allowCanvas); assertTrue(p.allowBackgroundLookups)
        assertFalse(p.oledBlack); assertEquals(0f, p.displayRefreshCap, 0f)
        assertEquals(30, p.progressFps)
    }
    @Test fun androidSaverIsNotFollowed() {
        // Nothing in the app's state comes from Android's battery saver.
        assertFalse(EnergyState().policy.saverEnabled)
        assertTrue(EnergyState().policy.allowCanvas)
    }
    @Test fun saverRestrictsVisualWork() {
        val p = EnergyPolicy.resolve(true, false)
        assertTrue(p.saverEnabled); assertFalse(p.allowCanvas); assertFalse(p.oledBlack)
        assertEquals(480, p.videoMaxHeight); assertEquals(20, p.progressFps)
        assertEquals(60f, p.displayRefreshCap, 0f); assertFalse(p.allowBackgroundLookups)
    }
    @Test fun blackOptionDoesNotDisableMotionOrCanvas() {
        val p = EnergyPolicy.resolve(false, true)
        assertTrue(p.oledBlack); assertTrue(p.allowCanvas); assertFalse(p.saverEnabled)
    }
    @Test fun amoledPreferenceControlsBlackWithEverySaverCombination() {
        for (manual in listOf(false, true)) for (black in listOf(false, true)) {
            val p = EnergyPolicy.resolve(manual, black)
            assertEquals(black, p.oledBlack)
            assertEquals(manual, p.saverEnabled)
            assertEquals(!manual, p.allowCanvas)
            assertEquals(!manual, p.allowBackgroundLookups)
            assertEquals(if (manual) 480 else 1080, p.videoMaxHeight)
            assertEquals(if (manual) 20 else 30, p.progressFps)
            assertEquals(if (manual) 60f else 0f, p.displayRefreshCap, 0f)
        }
    }
    @Test fun turningAmoledOffKeepsTheSameSaverRestrictions() {
        val enabled = EnergyState(manualSaver = true, blackPreference = true)
        val disabled = enabled.copy(blackPreference = false)
        assertTrue(enabled.policy.oledBlack)
        assertFalse(disabled.policy.oledBlack)
        assertEquals(enabled.policy.copy(oledBlack = false), disabled.policy)
    }
    @Test fun leavingSaverRestoresVisualDefaults() {
        val p = EnergyPolicy.resolve(false, false)
        assertEquals(1080, p.videoMaxHeight); assertEquals(30, p.progressFps)
        assertEquals(0f, p.displayRefreshCap, 0f)
        assertTrue(p.allowCanvas)
    }
    @Test fun canvasSwitchOnlyDisablesCanvas() {
        val p = EnergyPolicy.resolve(false, false, canvasEnabled = false)
        assertFalse(p.allowCanvas); assertFalse(p.saverEnabled); assertFalse(p.oledBlack)
        assertEquals(1080, p.videoMaxHeight); assertTrue(p.allowBackgroundLookups)
        assertFalse(EnergyState(canvasEnabled = false).policy.allowCanvas)
        assertTrue(EnergyState().policy.allowCanvas)
    }
}
