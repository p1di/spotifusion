package app.myspoti.host.android

import android.view.KeyEvent
import app.myspoti.host.android.HeadsetButtons.Action
import app.myspoti.host.android.HeadsetButtons.Command
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeadsetButtonsTest {
    @Test fun episodeSkipsFifteenSeconds() {
        assertEquals(Action.SEEK_FORWARD, HeadsetButtons.map(true, true, Command.NEXT))
        assertEquals(Action.SEEK_BACK, HeadsetButtons.map(true, true, Command.PREVIOUS))
        assertEquals(Action.SEEK_FORWARD, HeadsetButtons.map(true, true, Command.FAST_FORWARD))
        assertEquals(Action.SEEK_BACK, HeadsetButtons.map(true, true, Command.REWIND))
    }

    @Test fun episodeWithSettingOffKeepsEpisodeSkips() {
        assertEquals(Action.NEXT, HeadsetButtons.map(true, false, Command.NEXT))
        assertEquals(Action.PREVIOUS, HeadsetButtons.map(true, false, Command.PREVIOUS))
        // The explicit fast-forward / rewind keys still seek.
        assertEquals(Action.SEEK_FORWARD, HeadsetButtons.map(true, false, Command.FAST_FORWARD))
        assertEquals(Action.SEEK_BACK, HeadsetButtons.map(true, false, Command.REWIND))
    }

    @Test fun musicIsUnchangedWhateverTheSetting() {
        for (setting in listOf(true, false)) {
            assertEquals(Action.NEXT, HeadsetButtons.map(false, setting, Command.NEXT))
            assertEquals(Action.PREVIOUS, HeadsetButtons.map(false, setting, Command.PREVIOUS))
            assertEquals(Action.NONE, HeadsetButtons.map(false, setting, Command.FAST_FORWARD))
            assertEquals(Action.NONE, HeadsetButtons.map(false, setting, Command.REWIND))
        }
    }

    @Test fun keycodes() {
        assertEquals(Command.FAST_FORWARD, HeadsetButtons.commandOf(KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD))
        assertEquals(Command.FAST_FORWARD, HeadsetButtons.commandOf(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
        assertEquals(Command.REWIND, HeadsetButtons.commandOf(KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD))
        assertEquals(Command.REWIND, HeadsetButtons.commandOf(KeyEvent.KEYCODE_MEDIA_REWIND))
        assertNull(HeadsetButtons.commandOf(KeyEvent.KEYCODE_MEDIA_NEXT))
    }

    @Test fun logLines() {
        assertEquals("next on episode -> +15 s", HeadsetButtons.logLine(Command.NEXT, true, Action.SEEK_FORWARD))
        assertEquals("previous on episode -> -15 s", HeadsetButtons.logLine(Command.PREVIOUS, true, Action.SEEK_BACK))
        assertEquals("next", HeadsetButtons.logLine(Command.NEXT, false, Action.NEXT))
    }
}
