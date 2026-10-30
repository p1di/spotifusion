package com.music.spotui.extensions

import app.myspoti.extensions.PlaybackEvent
import app.myspoti.extensions.TrackInfo
import com.music.spotui.extensions.PlaybackTracker.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTrackerTest {
    private fun track(id: String) = TrackInfo(id, "spotify:track:$id", "T$id", "A", listOf("A"), "B", 200_000)
    private fun snap(id: String, pos: Long, playing: Boolean = true, source: String = PlaybackEvent.LOCAL) =
        Snapshot("$source:$id", track(id), source, playing, pos, 200_000, "spotify:playlist:p", shuffle = true)

    /** Plays [id] from [from] for [seconds], one snapshot a second; the events of those snapshots. */
    private fun PlaybackTracker.play(id: String, from: Long, seconds: Int, t0: Long): List<PlaybackEvent> =
        (0..seconds).flatMap { s -> observe(snap(id, from + s * 1000L), t0 + s * 1000L) }

    @Test fun startProgressAndASkipAfterFortySeconds() {
        val t = PlaybackTracker()
        val events = t.play("1", 0, 40, 1_000_000).toMutableList()
        t.skipAsked("forward", 1_040_500)
        events += t.observe(snap("2", 0), 1_041_000)
        assertEquals(listOf("start", "progress", "progress", "progress", "progress", "skip", "start"), events.map { it.type })
        assertEquals("play", events.first().reason)
        val skip = events[5]
        assertEquals("forward", skip.reason)
        assertTrue(skip.playedMs in 40_000..41_000)
        assertTrue(skip.positionMs in 40_000..41_000)
        assertEquals("skip", events.last().reason)
        assertEquals("spotify:playlist:p", skip.contextUri)
        assertTrue(skip.shuffle)
        assertEquals(PlaybackEvent.LOCAL, skip.source)
    }

    @Test fun everyEventSaysWhereTheAudioComesFrom() {
        val t = PlaybackTracker()
        val from = snap("1", 0).copy(origin = PlaybackEvent.ORIGIN_DOWNLOAD, providerId = "io.github.x.dl")
        val start = t.observe(from, 0).single()
        assertEquals(PlaybackEvent.ORIGIN_DOWNLOAD, start.origin)
        assertEquals("io.github.x.dl", start.providerId)
        val left = t.observe(snap("2", 0, source = PlaybackEvent.SPOTIFY), 1_000)
        assertEquals("io.github.x.dl", left.first().providerId)
        assertEquals(PlaybackEvent.ORIGIN_SPOTIFY, left.last().origin)
        assertEquals(null, left.last().providerId)
    }

    @Test fun aSongPlayedToItsEndEndsFinishedAndTheNextStartsAsNext() {
        val t = PlaybackTracker()
        t.observe(snap("1", 196_000), 0)
        t.observe(snap("1", 198_000), 2_000)
        val e = t.observe(snap("2", 500), 3_000)
        assertEquals(listOf("end", "start"), e.map { it.type })
        assertEquals("finished", e[0].reason)
        assertEquals("next", e[1].reason)
    }

    @Test fun pauseResumeAndSeekAreReportedAndASeekIsNotPlayedTime() {
        val t = PlaybackTracker()
        t.observe(snap("1", 0), 0)
        t.observe(snap("1", 1_000), 1_000)
        assertEquals(listOf("pause"), t.observe(snap("1", 2_000, playing = false), 2_000).map { it.type })
        assertEquals(emptyList<String>(), t.observe(snap("1", 2_000, playing = false), 10_000).map { it.type })
        assertEquals(listOf("resume"), t.observe(snap("1", 2_000), 11_000).map { it.type })
        val seek = t.observe(snap("1", 120_000), 12_000)
        assertEquals(listOf("seek"), seek.map { it.type })
        assertEquals("forward", seek[0].reason)
        assertTrue(seek[0].playedMs in 2_000..4_000)
    }

    @Test fun stoppingOrSwitchingPlayerEndsWithStop() {
        val t = PlaybackTracker()
        t.observe(snap("1", 10_000), 0)
        val e = t.observe(null, 1_000)
        assertEquals(listOf("end"), e.map { it.type })
        assertEquals("stop", e[0].reason)
        t.observe(snap("1", 10_000), 5_000)
        val sw = t.observe(snap("3", 0, source = PlaybackEvent.SPOTIFY), 6_000)
        assertEquals(listOf("end", "start"), sw.map { it.type })
        assertEquals("stop", sw[0].reason)
        assertEquals(PlaybackEvent.SPOTIFY, sw[1].source)
    }

    @Test fun theLastSongOfTheQueueEndsOnceAndAReplayIsANewStart() {
        val t = PlaybackTracker()
        t.observe(snap("1", 197_000), 0)
        val e = t.observe(snap("1", 200_000, playing = false), 3_000)
        assertEquals(listOf("end"), e.map { it.type })
        assertEquals("finished", e[0].reason)
        assertEquals(emptyList<String>(), t.observe(snap("1", 200_000, playing = false), 4_000).map { it.type })
        assertEquals(emptyList<String>(), t.observe(null, 5_000).map { it.type })
        assertEquals(listOf("start"), t.observe(snap("2", 0), 6_000).map { it.type })
    }

    @Test fun repeatOneIsAnEndAndANewStart() {
        val t = PlaybackTracker()
        t.observe(snap("1", 197_000), 0)
        val e = t.observe(snap("1", 1_000), 4_000)
        assertEquals(listOf("end", "start"), e.map { it.type })
        assertEquals("finished", e[0].reason)
    }
}
