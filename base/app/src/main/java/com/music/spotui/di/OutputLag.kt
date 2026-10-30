package com.music.spotui.di

import android.os.SystemClock

/**
 * How late the sound is heard after the player plays it: Bluetooth delays it (~150-250 ms with
 * SBC / AAC / LDAC). What is shown in time with the song (clip, lyrics) follows what is heard.
 */
object OutputLag {
    private const val CHECK_MS = 3_000L
    /** One figure for every Bluetooth codec: close enough for lines and words. */
    private const val BLUETOOTH_MS = 200L

    @Volatile private var lag = 0L
    @Volatile private var checkedAt = 0L

    fun ms(): Long {
        val now = SystemClock.elapsedRealtime()
        if (now - checkedAt > CHECK_MS) {
            checkedAt = now
            val route = com.music.spotui.ui.components.AudioRoute.current(Graph.app)
            val l = if (route.kind == com.music.spotui.ui.components.AudioRoute.Kind.BLUETOOTH) BLUETOOTH_MS else 0L
            if (l != lag) app.myspoti.host.android.Logbook.log("sound heard $l ms late (${route.kind.name.lowercase()}): clip and lyrics follow")
            lag = l
        }
        return lag
    }
}
