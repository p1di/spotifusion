package app.myspoti.spotify

import app.myspoti.spotify.TrackChanges.Cause
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TrackChangesTest {
    private fun cause(played: Long, duration: Long = 200_000, since: Long = 60_000, ad: Boolean = false, fade: Boolean = false) =
        TrackChanges.cause(played, duration, since, ad, fade)

    @Test fun `a recent command explains the change`() = assertEquals(Cause.COMMAND, cause(7_000, since = 800))
    @Test fun `a command long ago does not`() = assertEquals(Cause.OWN, cause(7_000, since = 20_000))
    @Test fun `the end of the song`() = assertEquals(Cause.ENDED, cause(198_000))
    @Test fun `a crossfade ends the song early`() = assertEquals(Cause.ENDED, cause(190_000, fade = true))
    @Test fun `an ad break`() = assertEquals(Cause.AD, cause(30_000, ad = true))
    @Test fun `unknown duration is not an end`() = assertEquals(Cause.OWN, cause(30_000, duration = 0))
    @Test fun `a command wins over an ad`() = assertEquals(Cause.COMMAND, cause(30_000, since = 100, ad = true))

    @Test fun `three short songs left by the page in a row`() {
        val c = TrackChanges.Chain()
        assertFalse(c.record(Cause.OWN, 400, 1_000))
        assertFalse(c.record(Cause.OWN, 300, 3_000))
        assertTrue(c.record(Cause.OWN, 500, 5_000))
    }

    @Test fun `the chain starts over after it fired`() {
        val c = TrackChanges.Chain()
        repeat(3) { c.record(Cause.OWN, 400, it * 1_000L) }
        assertFalse(c.record(Cause.OWN, 400, 4_000))
    }

    @Test fun `a command breaks the chain`() {
        val c = TrackChanges.Chain()
        c.record(Cause.OWN, 400, 1_000); c.record(Cause.OWN, 400, 2_000)
        assertFalse(c.record(Cause.COMMAND, 400, 3_000))
        assertFalse(c.record(Cause.OWN, 400, 4_000))
    }

    @Test fun `a song that played a while breaks the chain`() {
        val c = TrackChanges.Chain()
        c.record(Cause.OWN, 400, 1_000); c.record(Cause.OWN, 400, 2_000)
        assertFalse(c.record(Cause.OWN, 60_000, 3_000))
        assertFalse(c.record(Cause.OWN, 400, 4_000))
    }

    @Test fun `changes too far apart are not a chain`() {
        val c = TrackChanges.Chain()
        assertFalse(c.record(Cause.OWN, 400, 0)); assertFalse(c.record(Cause.OWN, 400, 20_000))
        assertFalse(c.record(Cause.OWN, 400, 40_000))
    }

    @Test fun `clock`() = assertEquals("3:21", TrackChanges.clock(201_500))
}
