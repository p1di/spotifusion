package com.music.spotui.extensions

import com.music.spotui.data.entity.SongsModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which plays ask the audio-source extensions, and the queue the local player gets. */
class AudioRouteTest {
    private fun song(id: String, playable: Boolean = true) =
        SongsModel(id.hashCode(), "T$id", "Album", "Artist", "https://i/$id", "spotify:track:$id", spotifyTrackId = id, playable = playable)

    @Test fun onlyATrackWithAnIdAsksAndOnlyWhileAnAudioSourceIsOn() {
        assertTrue(AudioRoute.asks("abc", hasAudioSource = true))
        assertFalse(AudioRoute.asks("abc", hasAudioSource = false))
        assertFalse(AudioRoute.asks(null, hasAudioSource = true))
        assertFalse(AudioRoute.asks("", hasAudioSource = true))
    }

    @Test fun onlyTheSongAfterTheOneSpotifyPlayedForWantOfAnExtensionIsAskedAgain() {
        assertTrue(AudioRoute.backFromFallback("a", "a", "b", hasAudioSource = true, remote = false))
        // Not the song that fell back, nor once the engine moved on from it, nor with none to ask.
        assertFalse(AudioRoute.backFromFallback("a", "a", "a", hasAudioSource = true, remote = false))
        assertFalse(AudioRoute.backFromFallback("a", "c", "b", hasAudioSource = true, remote = false))
        assertFalse(AudioRoute.backFromFallback(null, "a", "b", hasAudioSource = true, remote = false))
        assertFalse(AudioRoute.backFromFallback("a", "a", "", hasAudioSource = true, remote = false))
        assertFalse(AudioRoute.backFromFallback("a", "a", "b", hasAudioSource = false, remote = false))
        assertFalse(AudioRoute.backFromFallback("a", "a", "b", hasAudioSource = true, remote = true))
    }

    @Test fun theCallersListIsUsedWhenItHoldsTheSongElseTheSongIsLookedUp() = runBlocking {
        val list = listOf(song("a"), song("b"))
        var looked = 0
        assertEquals(list, AudioRoute.songsFor("b", list) { looked++; null })
        assertEquals(0, looked)
        // A link or a block: only the uri is known, the song comes from the lookup.
        assertEquals(listOf(song("c")), AudioRoute.songsFor("c", null) { looked++; song("c") })
        assertEquals(listOf(song("c")), AudioRoute.songsFor("c", list) { looked++; song("c") })
        assertEquals(2, looked)
        // Nothing found, or another song: Spotify plays.
        assertNull(AudioRoute.songsFor("d", null) { null })
        assertNull(AudioRoute.songsFor("d", null) { song("e") })
    }

    @Test fun theQueueKeepsPlayableSongsOnceInOrderWithThePickedOne() {
        val songs = listOf(song("a"), song("x", playable = false), song(""), song("b"), song("a"), song("c"))
        val (queue, at) = AudioRoute.queue(songs, song("b"))
        assertEquals(listOf("a", "b", "c"), queue.map { it.spotifyTrackId })
        assertEquals(1, at)
        // The picked song stays even when marked unplayable.
        val (q2, at2) = AudioRoute.queue(songs, song("x", playable = false))
        assertEquals(listOf("a", "x", "b", "c"), q2.map { it.spotifyTrackId })
        assertEquals(1, at2)
        // The song alone (a search result, a link).
        assertEquals(listOf(song("z")) to 0, AudioRoute.queue(listOf(song("z")), song("z")))
    }

    private class Refused(val code: Int) : java.io.IOException("HTTP $code")
    private fun stream(n: Int) = app.myspoti.extensions.AudioStream("https://cdn.example.com/$n.m4a", emptyMap(), null, null)

    @Test fun aRefusedAddressIsAskedForOnceMoreThenFailsCleanly() {
        val status = { t: Throwable -> (t as? Refused)?.code }
        // Expired: dropped, asked again, the new address plays.
        val asked = mutableListOf<Boolean>()
        var n = 0
        val ok = AudioRoute.openStream({ again -> asked += again; stream(++n) }, { s -> if (s.url.endsWith("1.m4a")) throw Refused(403) else 42L }, status)
        assertEquals(42L, ok)
        assertEquals(listOf(false, true), asked)
        // Refused twice: one retry only, then a NoAudioException (the player does not retry it).
        asked.clear(); n = 0
        val twice = runCatching { AudioRoute.openStream({ again -> asked += again; stream(++n) }, { _: app.myspoti.extensions.AudioStream -> throw Refused(401) }, status) }.exceptionOrNull()
        assertTrue(twice is NoAudioException && AudioRoute.isNoAudio(twice))
        assertEquals(listOf(false, true), asked)
        // Wrapped in another error: still seen.
        asked.clear(); n = 0
        AudioRoute.openStream({ again -> asked += again; stream(++n) }, { s -> if (s.url.endsWith("1.m4a")) throw java.io.IOException("open", Refused(403)) else 1L }, status)
        assertEquals(listOf(false, true), asked)
        // No other address: fails.
        val none = runCatching { AudioRoute.openStream({ again -> if (again) null else stream(1) }, { _: app.myspoti.extensions.AudioStream -> throw Refused(403) }, status) }.exceptionOrNull()
        assertTrue(none is NoAudioException)
        // Gone (404, 410): asked again too, once.
        for (code in listOf(404, 410)) {
            asked.clear(); n = 0
            assertEquals(7L, AudioRoute.openStream({ again -> asked += again; stream(++n) }, { s -> if (s.url.endsWith("1.m4a")) throw Refused(code) else 7L }, status))
            assertEquals(listOf(false, true), asked)
        }
        // Another error (500, a network failure) is not retried.
        asked.clear()
        val other = runCatching { AudioRoute.openStream({ again -> asked += again; stream(1) }, { _: app.myspoti.extensions.AudioStream -> throw Refused(500) }, status) }.exceptionOrNull()
        assertTrue(other is Refused && !AudioRoute.isNoAudio(other))
        assertEquals(listOf(false), asked)
        // No answer at all.
        assertTrue(AudioRoute.isNoAudio(java.io.IOException("source", runCatching { AudioRoute.openStream({ null }, { 0L }, status) }.exceptionOrNull())))
    }

    @Test fun aShufflePlayStartsOnAPlayableSongWithAnId() {
        val songs = listOf(song(""), song("x", playable = false), song("a"))
        repeat(20) { assertEquals("a", AudioRoute.shufflePick(songs, kotlin.random.Random(it))?.spotifyTrackId) }
        assertNull(AudioRoute.shufflePick(listOf(song("x", playable = false))))
    }

    @Test fun aSongNoExtensionHadIsNotAskedAgainForAMinute() {
        var now = 0L
        val misses = AudioMisses(60_000L) { now }
        assertFalse(misses.skips("p", ahead = false))
        // Missed ahead: further asks ahead are skipped, the ask when the song starts still goes out.
        misses.record("p", ahead = true)
        assertTrue(misses.skips("p", ahead = true))
        assertFalse(misses.skips("p", ahead = false))
        // Missed when it started: nothing asks again, an ask ahead does not weaken it.
        misses.record("p", ahead = false)
        misses.record("p", ahead = true)
        assertTrue(misses.skips("p", ahead = false))
        assertTrue(misses.skips("p", ahead = true))
        assertFalse(misses.skips("q", ahead = false))
        // A minute later, asked again.
        now = 60_000L
        assertFalse(misses.skips("p", ahead = false))
        assertFalse(misses.skips("p", ahead = true))
        // An extension live again or changed: forgotten at once.
        misses.record("p", ahead = false)
        misses.clear()
        assertFalse(misses.skips("p", ahead = false))
    }

    @Test fun onlyAQueueTheExtensionsPlayGoesOnAfterItsLastSong() {
        assertTrue(AudioRoute.continues(fromExtensions = true, lastId = "a", repeatAll = false, autoplay = true))
        // Repeat all loops, autoplay off ends, a file of the device or a list of downloads never goes on.
        assertFalse(AudioRoute.continues(fromExtensions = true, lastId = "a", repeatAll = true, autoplay = true))
        assertFalse(AudioRoute.continues(fromExtensions = true, lastId = "a", repeatAll = false, autoplay = false))
        assertFalse(AudioRoute.continues(fromExtensions = true, lastId = "", repeatAll = false, autoplay = true))
        assertFalse(AudioRoute.continues(fromExtensions = false, lastId = "a", repeatAll = false, autoplay = true))
    }

    @Test fun theSongRadioAddsOnlyPlayableSongsNotQueuedOnce() {
        val radio = listOf(song("a"), song("b"), song("b"), song("c", playable = false), song(""), song("d"))
        assertEquals(listOf("b", "d"), AudioRoute.continuation(radio, listOf("a")).map { it.spotifyTrackId })
        assertTrue(AudioRoute.continuation(listOf(song("a")), listOf("a")).isEmpty())
    }

    @Test fun backFromAFallbackTheLocalQueueGoesOnFromTheSongMovedTo() {
        val rest = listOf(song("q"), song("b"), song("r"))
        assertEquals(listOf("q", "b", "r"), AudioRoute.resumeList(rest, "q")!!.map { it.spotifyTrackId })
        assertEquals(listOf("b", "r"), AudioRoute.resumeList(rest, "b")!!.map { it.spotifyTrackId })
        assertNull(AudioRoute.resumeList(rest, "elsewhere"))
    }
}
