package com.music.spotui.di

/**
 * Timing and volume curve of the real (overlapping) crossfade of [LocalPlayer], kept pure so it
 * is tested off-device. Equal-power: the two gains' squares add up to 1, so the loudness stays
 * level through the overlap.
 */
object CrossfadeRamp {
    /** The overlap for a song of [durationMs] and a setting of [seconds]: never over half the song. */
    fun fadeMs(seconds: Int, durationMs: Long): Long =
        if (seconds <= 0 || durationMs <= 0) 0L else minOf(seconds * 1000L, durationMs / 2)

    /** The fade starts once [remainingMs] of the song is left. */
    fun shouldStart(seconds: Int, durationMs: Long, positionMs: Long): Boolean {
        val fade = fadeMs(seconds, durationMs)
        return fade > 0 && positionMs >= 0 && durationMs - positionMs <= fade
    }

    /** Progress 0..1 of a fade of [fadeMs] when the incoming song is at [incomingPositionMs]. */
    fun progress(incomingPositionMs: Long, fadeMs: Long): Float =
        if (fadeMs <= 0) 1f else (incomingPositionMs.toFloat() / fadeMs).coerceIn(0f, 1f)

    fun incomingGain(t: Float): Float = Math.sin(t.coerceIn(0f, 1f) * Math.PI / 2).toFloat()
    fun outgoingGain(t: Float): Float = Math.cos(t.coerceIn(0f, 1f) * Math.PI / 2).toFloat()
}
