package com.music.spotui.data.preferences

import com.music.spotui.data.entity.SongsModel
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

// The last session's queue and the rule that decides who wins at launch, kept pure so they
// are tested without Android. The song itself lives in SongPlayer's "myspoti_last_track".

object LastSession {
    /** Where the saved session played: Spotify's own player, or the local player (an extension's stream, a download). */
    const val SOURCE_SPOTIFY = "spotify"
    const val SOURCE_LOCAL = "local"

    private const val MAX_QUEUE = 100
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The songs of the session's queue as stored; capped so the preferences stay small. */
    fun encodeQueue(songs: List<SongsModel>): String =
        json.encodeToString(ListSerializer(SongsModel.serializer()), songs.take(MAX_QUEUE))

    /** The stored queue, empty when missing or unreadable. */
    fun decodeQueue(raw: String?): List<SongsModel> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(SongsModel.serializer()), raw) }.getOrDefault(emptyList())
    }

    /**
     * Spotify's state at launch is older than the session saved from the local player: it still
     * shows the song played before and must not replace it. A state stamped after the save
     * (another device played since) or one that plays is real and wins.
     */
    fun engineStateIsStale(savedSource: String, savedAtMs: Long, engineTimestampMs: Long, enginePaused: Boolean): Boolean =
        savedSource == SOURCE_LOCAL && enginePaused && engineTimestampMs <= savedAtMs
}
