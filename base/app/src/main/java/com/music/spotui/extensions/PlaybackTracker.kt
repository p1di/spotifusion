package com.music.spotui.extensions

import app.myspoti.extensions.PlaybackEvent
import app.myspoti.extensions.TrackInfo

/**
 * Turns what the players show, read about once a second, into `playback` events for the
 * extensions (docs/extensions/API.md §3). Without Android: [observe] takes a [Snapshot] and the
 * time, and answers the events since the last one.
 */
class PlaybackTracker(
    /** A progress event each time this much more of the song was played. */
    private val progressEveryMs: Long = 10_000,
) {
    /** What one player shows at one moment; [key] tells one song (and source) from another. */
    data class Snapshot(
        val key: String,
        val track: TrackInfo,
        val source: String,
        val playing: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        val contextUri: String = "",
        val shuffle: Boolean = false,
        val origin: String = PlaybackEvent.ORIGIN_SPOTIFY,
        val providerId: String? = null,
    )

    private var cur: Snapshot? = null
    private var at = 0L
    private var played = 0L
    private var nextProgress = progressEveryMs
    /** The current song already ended (the queue stopped on its last song): it does not leave again. */
    private var ended = false
    /** The last next / previous the user asked for, and when (a skip's direction). */
    @Volatile private var hint: Pair<String, Long>? = null

    /** The user asked for the next ("forward") or previous ("backward") song. */
    fun skipAsked(direction: String, now: Long) { hint = direction to now }

    fun observe(s: Snapshot?, now: Long): List<PlaybackEvent> {
        val out = ArrayList<PlaybackEvent>(2)
        val before = cur
        if (before == null) {
            if (s != null) start(s, now, "play", out)
            return out
        }
        if (ended) {
            // After the last song ended: the next thing that plays is a new start.
            if (s == null) { cur = null; ended = false }
            else if (s.key != before.key || s.playing) start(s, now, if (s.key != before.key) "next" else "play", out)
            else { cur = s; at = now }
            return out
        }
        if (s == null || s.key != before.key) {
            // The song left: played to its end, skipped, or playback stopped.
            val reason = leave(before, s, now, out)
            if (s != null) start(s, now, reason, out) else cur = null
            return out
        }
        val elapsed = (now - at).coerceAtLeast(0)
        val expected = before.positionMs + if (before.playing) elapsed else 0
        val delta = s.positionMs - expected
        if (nearEnd(before) && s.positionMs < 5_000 && before.positionMs - s.positionMs > 10_000) {
            // Repeat one: the same song again from the start.
            played += (before.durationMs - before.positionMs).coerceIn(0, elapsed)
            out += event("end", before.copy(positionMs = before.durationMs), now, "finished")
            start(s, now, "next", out)
            return out
        }
        if (before.playing) played += if (kotlin.math.abs(delta) > SEEK_MS) elapsed.coerceAtMost(1_500) else elapsed
        if (kotlin.math.abs(delta) > SEEK_MS) out += event("seek", s, now, if (delta > 0) "forward" else "backward")
        if (before.playing && !s.playing && nearEnd(s)) {
            // The queue stopped at the end of its last song.
            played += elapsed.coerceAtMost(s.positionMs)
            out += event("end", s, now, "finished")
            cur = s; at = now; ended = true
            return out
        }
        if (before.playing && !s.playing) out += event("pause", s, now)
        if (!before.playing && s.playing) out += event("resume", s, now)
        if (s.playing && played >= nextProgress) {
            out += event("progress", s, now)
            while (nextProgress <= played) nextProgress += progressEveryMs
        }
        cur = s
        at = now
        return out
    }

    private fun start(s: Snapshot, now: Long, reason: String, out: MutableList<PlaybackEvent>) {
        cur = s
        ended = false
        at = now
        played = 0
        nextProgress = progressEveryMs
        out += event("start", s, now, reason)
    }

    /** The leaving event of [before]; answers the next song's start reason. */
    private fun leave(before: Snapshot, next: Snapshot?, now: Long, out: MutableList<PlaybackEvent>): String {
        val elapsed = (now - at).coerceAtLeast(0)
        val position = if (before.playing) (before.positionMs + elapsed).coerceAtMost(before.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE) else before.positionMs
        if (before.playing) played += (position - before.positionMs).coerceAtLeast(0)
        val asked = hint?.takeIf { now - it.second < HINT_MS }?.first
        hint = null
        val left = before.copy(positionMs = position)
        return when {
            asked != null -> { out += event("skip", left, now, asked); "skip" }
            nearEnd(left) -> { out += event("end", left, now, "finished"); "next" }
            next == null || next.source != before.source -> { out += event("end", left, now, "stop"); "play" }
            else -> { out += event("skip", left, now, "other"); "skip" }
        }
    }

    private fun nearEnd(s: Snapshot) = s.durationMs > 0 && s.positionMs >= s.durationMs - END_MS

    private fun event(type: String, s: Snapshot, now: Long, reason: String? = null) = PlaybackEvent(
        type = type, track = s.track, positionMs = s.positionMs.coerceAtLeast(0), durationMs = s.durationMs, timestamp = now,
        source = s.source, contextUri = s.contextUri, shuffle = s.shuffle, reason = reason, playedMs = played,
        origin = s.origin, providerId = s.providerId,
    )

    companion object {
        /** A position off by more than this from where playing would have put it is a seek. */
        const val SEEK_MS = 3_000L
        /** Within this of the end, a song that leaves was played to its end. */
        const val END_MS = 5_000L
        /** A next / previous tap explains a change of song for this long. */
        const val HINT_MS = 4_000L
    }
}
