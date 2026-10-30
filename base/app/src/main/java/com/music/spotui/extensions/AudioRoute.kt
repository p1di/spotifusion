package com.music.spotui.extensions

import app.myspoti.extensions.AudioStream
import com.music.spotui.data.entity.SongsModel
import java.io.IOException

/**
 * How a play goes through the `audio-source` extensions before Spotify (docs/extensions/API.md
 * §3), wherever it starts: a list, a search result, an artist, Recents, a link, an extension's
 * block, shuffle. Without Android: [ExtensionAudio] plays what this decides.
 */
object AudioRoute {
    /** Whether a play of [trackId] asks the extensions first. Episodes and plays without an id never do. */
    fun asks(trackId: String?, hasAudioSource: Boolean): Boolean = hasAudioSource && !trackId.isNullOrBlank()

    /**
     * Whether the song [targetId] played after [currentId] (a next, a previous, the engine moving
     * on) is asked to the extensions again: [currentId] is the song Spotify played because none had
     * it ([fallbackId]), only that one falls back. Never on another device ([remote]).
     */
    fun backFromFallback(fallbackId: String?, currentId: String, targetId: String?, hasAudioSource: Boolean, remote: Boolean): Boolean =
        hasAudioSource && !remote && !fallbackId.isNullOrBlank() && fallbackId == currentId && !targetId.isNullOrBlank() && targetId != currentId

    /**
     * The songs to play when [trackId] is picked: [given] when it holds it (a list, a search
     * result), else the song alone as [lookup] finds it (a link, a block: only the uri is known).
     * Null: no song to give the extensions, Spotify plays.
     */
    suspend fun songsFor(trackId: String, given: List<SongsModel>?, lookup: suspend () -> SongsModel?): List<SongsModel>? {
        if (given != null && given.any { it.spotifyTrackId == trackId }) return given
        val one = lookup()?.takeIf { it.spotifyTrackId == trackId } ?: return null
        return listOf(one)
    }

    /**
     * The local player's queue: the playable songs with a Spotify id, once each, in order, and
     * where the picked one is in it (it is kept even when marked unplayable: it was picked).
     */
    fun queue(songs: List<SongsModel>, picked: SongsModel): Pair<List<SongsModel>, Int> {
        val list = songs.filter { it.spotifyTrackId.isNotBlank() && (it.playable || it.spotifyTrackId == picked.spotifyTrackId) }
            .distinctBy { it.spotifyTrackId }
            .ifEmpty { listOf(picked) }
        return list to list.indexOfFirst { it.spotifyTrackId == picked.spotifyTrackId }.coerceAtLeast(0)
    }

    /**
     * Back to the extensions after one song Spotify played for want of them: the local queue as it
     * was after that song ([rest]: queued songs, list, song radio, in play order) from the song the
     * engine moved on to ([targetId]); null when the engine went elsewhere (its own list is used then).
     */
    fun resumeList(rest: List<SongsModel>, targetId: String): List<SongsModel>? =
        rest.indexOfFirst { it.spotifyTrackId == targetId }.takeIf { it >= 0 }?.let { rest.drop(it) }

    /**
     * Whether the local queue goes on after its last song as Spotify does after a list or a song
     * played alone (its song radio): a queue the extensions play ([fromExtensions]) whose last
     * song has a Spotify id ([lastId]), without repeat all (it loops) and with autoplay on. Files
     * of the device never do.
     */
    fun continues(fromExtensions: Boolean, lastId: String?, repeatAll: Boolean, autoplay: Boolean): Boolean =
        fromExtensions && !repeatAll && autoplay && !lastId.isNullOrBlank()

    /** The songs the queue goes on with: [radio]'s playable ones with an id, once each, none of [queued] again. */
    fun continuation(radio: List<SongsModel>, queued: Collection<String>): List<SongsModel> =
        radio.filter { it.spotifyTrackId.isNotBlank() && it.playable && it.spotifyTrackId !in queued }.distinctBy { it.spotifyTrackId }

    /**
     * Opens the stream an extension gave: [ask] answers it (`again`: the cached answer is dropped
     * and the extension asked once more), [open] reads it. A server that refuses the address
     * (HTTP 401, 403, 404 or 410, read by [status] from the error: expired or gone) gets one more answer, then the song fails
     * cleanly with a [NoAudioException] (never retried by the player: each try would ask the
     * extensions again); any other error is thrown as it is.
     */
    fun <T> openStream(ask: (again: Boolean) -> AudioStream?, open: (AudioStream) -> T, status: (Throwable) -> Int?): T {
        val first = ask(false) ?: throw NoAudioException("no extension has the audio of this song")
        return try {
            open(first)
        } catch (e: IOException) {
            val code = refusal(e, status) ?: throw e
            val again = ask(true) ?: throw NoAudioException("the extension's address was refused (HTTP $code), and it gave no other", e)
            try {
                open(again)
            } catch (e2: IOException) {
                val code2 = refusal(e2, status) ?: throw e2
                throw NoAudioException("the extension's address was refused again (HTTP $code2)", e2)
            }
        }
    }

    /** The statuses of an address that expired or is gone: the extension is asked once more. */
    private val REFUSED = setOf(401, 403, 404, 410)

    /** One of [REFUSED] in [e] or one of its causes, else null. */
    private fun refusal(e: Throwable, status: (Throwable) -> Int?): Int? =
        generateSequence(e) { it.cause }.take(8).mapNotNull(status).firstOrNull { it in REFUSED }

    /** The first song of a shuffle play: any playable song with an id, or null (Spotify shuffles). */
    fun shufflePick(songs: List<SongsModel>, random: kotlin.random.Random = kotlin.random.Random.Default): SongsModel? =
        songs.filter { it.spotifyTrackId.isNotBlank() && it.playable }.randomOrNull(random)

    /** True when [e] or one of its causes says no extension gave usable audio ([NoAudioException]). */
    fun isNoAudio(e: Throwable?): Boolean = generateSequence(e) { it.cause }.take(8).any { it is NoAudioException }
}

/** No extension gave audio for the song, or the address it gave was refused twice: Spotify plays it. */
class NoAudioException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The songs no extension had audio for, so they are not asked again for [keepMs] (a queue that
 * reaches them, a replay, a resume): a miss asked ahead ([ahead]) only keeps further asks ahead
 * away, the ask when the song starts still goes out. [clear] when an extension is live again or
 * changes (loaded, stopped). [clock]: ms.
 */
class AudioMisses(private val keepMs: Long = 60_000L, private val clock: () -> Long = System::currentTimeMillis) {
    private class Miss(val at: Long, val ahead: Boolean)
    private val misses = java.util.concurrent.ConcurrentHashMap<String, Miss>()

    fun record(trackId: String, ahead: Boolean) {
        if (trackId.isBlank()) return
        // A miss when the song starts is never weakened by a later one ahead.
        misses.compute(trackId) { _, m -> if (ahead && m != null && !m.ahead && fresh(m)) m else Miss(clock(), ahead) }
    }

    /** Whether an ask for [trackId] ([ahead] or when it starts) is skipped: a recent miss covers it. */
    fun skips(trackId: String, ahead: Boolean): Boolean {
        val m = misses[trackId] ?: return false
        if (!fresh(m)) { misses.remove(trackId, m); return false }
        return ahead || !m.ahead
    }

    /** A song the extensions answered after all. */
    fun forget(trackId: String) { misses.remove(trackId) }

    fun clear() = misses.clear()

    private fun fresh(m: Miss) = clock() - m.at < keepMs
}
