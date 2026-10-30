package app.myspoti.extensions

/**
 * The host's rules for `player.setLocalMute` ([Action.PLAYBACK_MIRROR]), kept apart from the
 * script: it may keep Spotify playing unheard only while Myspoti's own local / extension player
 * plays a track, for one extension at a time, and the mute ends by itself (track change, local
 * stop or pause, extension off, local track's duration). Pure logic: the app feeds it the local
 * player's state and clears the page's mute when it says so.
 */
class MirrorGuard(private val clock: () -> Long = System::currentTimeMillis) {
    /** Who mutes now, for which local track, until when. */
    data class Active(val extensionId: String, val trackUri: String, val untilMs: Long)

    @Volatile var active: Active? = null
        private set

    private val lastEnable = HashMap<String, Long>()

    /**
     * The extension [id] asks to mute. Null: allowed (now active). Otherwise the reason it is
     * refused. [localPlaying]: the local player plays now; [trackUri]: its track; [durationMs]: its length.
     */
    @Synchronized
    fun enable(id: String, localPlaying: Boolean, trackUri: String?, durationMs: Long): String? {
        val now = clock()
        if (!localPlaying || trackUri.isNullOrBlank()) return "setLocalMute: Myspoti's own player is not playing"
        active?.let { if (it.extensionId != id && now < it.untilMs) return "setLocalMute: another extension (${it.extensionId}) is mirroring" }
        val last = lastEnable[id]
        if (last != null && now - last < MIN_INTERVAL_MS) return "setLocalMute: too often (once per ${MIN_INTERVAL_MS / 1000} s)"
        lastEnable[id] = now
        val length = if (durationMs > 0) durationMs + SLACK_MS else MAX_UNKNOWN_MS
        active = Active(id, trackUri, now + length)
        return null
    }

    /** [id] turns its mute off: true when it was the one muting (the page is to be unmuted). */
    @Synchronized
    fun disable(id: String): Boolean {
        if (active?.extensionId != id) return false
        active = null
        return true
    }

    /**
     * The local player's state changed (or a timer fired). The reason to clear the mute, or null
     * when it may stay. Clears [active] when it answers a reason.
     */
    @Synchronized
    fun check(localPlaying: Boolean, trackUri: String?): String? {
        val a = active ?: return null
        val why = when {
            !localPlaying -> "the local player stopped or paused"
            trackUri != a.trackUri -> "the track changed"
            clock() >= a.untilMs -> "the track's duration ended"
            else -> return null
        }
        active = null
        return why
    }

    /** The extension is off, uninstalled, blocked or crashed. Returns whether it was muting. */
    @Synchronized
    fun extensionGone(id: String): Boolean {
        lastEnable.remove(id)
        return disable(id)
    }

    /** Milliseconds until the mute expires, or null when none is active. */
    fun remainingMs(): Long? = active?.let { (it.untilMs - clock()).coerceAtLeast(0) }

    companion object {
        const val MIN_INTERVAL_MS = 20_000L
        const val SLACK_MS = 5_000L
        const val MAX_UNKNOWN_MS = 10 * 60_000L
    }
}
