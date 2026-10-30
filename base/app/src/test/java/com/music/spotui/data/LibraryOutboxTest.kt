package com.music.spotui.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryOutboxTest {
    @Test fun theLastTapOnAnItemWinsAndKeepsTheOrderOfTaps() {
        val p = LibraryOutbox.Pending(null)
        p.put("spotify:track:a", true)
        p.put("spotify:track:b", true)
        p.put("spotify:track:a", false) // unliked again: one change, now the newest
        assertEquals(listOf("spotify:track:b" to true, "spotify:track:a" to false), p.all.toList())

        // Kept across restarts as text.
        val again = LibraryOutbox.Pending(p.text())
        assertEquals(p.all.toList(), again.all.toList())

        // A sent change goes; one the user changed meanwhile stays to be sent.
        again.sent("spotify:track:b", true)
        again.sent("spotify:track:a", true)
        assertEquals(listOf("spotify:track:a" to false), again.all.toList())
        assertEquals(emptyMap<String, Boolean>(), LibraryOutbox.Pending("not json").all)
    }
}
