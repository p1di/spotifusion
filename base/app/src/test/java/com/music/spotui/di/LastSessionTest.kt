package com.music.spotui.di

import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.LastSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LastSessionTest {
    private fun song(i: Int) = SongsModel(i, "t$i", "a", "s", "c", "local:u$i", spotifyTrackId = "id$i", durationMs = 1000)

    @Test fun queueRoundTrips() {
        val q = listOf(song(1), song(2))
        assertEquals(q, LastSession.decodeQueue(LastSession.encodeQueue(q)))
    }

    @Test fun queueIsCappedAndBadInputIsEmpty() {
        assertEquals(100, LastSession.decodeQueue(LastSession.encodeQueue((1..150).map(::song))).size)
        assertTrue(LastSession.decodeQueue("{broken").isEmpty())
        assertTrue(LastSession.decodeQueue(null).isEmpty())
    }

    @Test fun olderPausedEngineStateDoesNotReplaceLocalSession() {
        assertTrue(LastSession.engineStateIsStale(LastSession.SOURCE_LOCAL, 2000, 1000, true))
        assertFalse(LastSession.engineStateIsStale(LastSession.SOURCE_LOCAL, 2000, 3000, true))
        assertFalse(LastSession.engineStateIsStale(LastSession.SOURCE_LOCAL, 2000, 1000, false))
        assertFalse(LastSession.engineStateIsStale(LastSession.SOURCE_SPOTIFY, 2000, 1000, true))
    }
}
