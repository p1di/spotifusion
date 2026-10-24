package com.music.spotui.data.preferences

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

// Trimmed from Spotui: stream quality, YouTube and web-playback settings are gone (the Web
// Player engine streams Spotify's own audio). Crossfade is ours: a fade, not an overlap.

private const val PREF = "settings_prefs"
private const val KEY_LIBRARY_GRID = "library_grid_view_v2"
private const val KEY_LYRIC_TRANSLATE_LANG = "lyric_translate_lang"
private const val KEY_LYRICS_EXTRA = "lyrics_extra_sources"
private const val KEY_CROSSFADE = "crossfade_seconds"

/** Extra lyrics sources (NetEase, Genius) when Spotify and LRCLIB have none. Off by default. */
fun isLyricsExtraSources(c: Context): Boolean = prefs(c).getBoolean(KEY_LYRICS_EXTRA, false)
fun setLyricsExtraSources(c: Context, v: Boolean) = prefs(c).edit().putBoolean(KEY_LYRICS_EXTRA, v).apply()

private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

/** The bottom bar: fading into the list (the default), or solid black. Read by the bar as state. */
object SolidNavBar {
    private const val KEY = "solid_nav_bar"
    var on by androidx.compose.runtime.mutableStateOf(prefs(com.music.spotui.di.Graph.app).getBoolean(KEY, false))
        private set

    fun set(c: Context, value: Boolean) {
        on = value
        prefs(c).edit().putBoolean(KEY, value).apply()
    }
}

/** "When your music ends, keep playing similar songs" (on by default). Read by the player. */
object AutoplayPref {
    private const val KEY = "autoplay_similar"
    var on by androidx.compose.runtime.mutableStateOf(prefs(com.music.spotui.di.Graph.app).getBoolean(KEY, true))
        private set

    fun set(c: Context, value: Boolean) {
        on = value
        prefs(c).edit().putBoolean(KEY, value).apply()
    }
}

/**
 * The automatic update check at launch (on by default: the app behaved that way before this
 * option existed). Off means no check when the app starts — a check asked for in Settings still
 * works, it reads the same release list.
 */
object AutoUpdateCheckPref {
    private const val KEY = "auto_update_check"
    var on by androidx.compose.runtime.mutableStateOf(prefs(com.music.spotui.di.Graph.app).getBoolean(KEY, true))
        private set

    fun set(c: Context, value: Boolean) {
        on = value
        prefs(c).edit().putBoolean(KEY, value).apply()
    }
}

/** Screen rotation: portrait (the default), automatic (follows the system), or landscape. */
object OrientationPref {
    private const val KEY = "screen_orientation"
    const val PORTRAIT = "portrait"
    const val AUTO = "auto"
    const val LANDSCAPE = "landscape"
    var mode by androidx.compose.runtime.mutableStateOf(prefs(com.music.spotui.di.Graph.app).getString(KEY, PORTRAIT) ?: PORTRAIT)
        private set

    fun set(c: Context, value: String) {
        mode = value
        prefs(c).edit().putString(KEY, value).apply()
    }

    /** The [android.content.pm.ActivityInfo] value for the chosen mode. */
    fun requested(): Int = when (mode) {
        AUTO -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        LANDSCAPE -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
}

/** Library layout: false = Spotify list rows (default, Screenshot 3), true = 3-column grid. */
fun isLibraryGridView(c: Context): Boolean = prefs(c).getBoolean(KEY_LIBRARY_GRID, false)
fun setLibraryGridView(c: Context, v: Boolean) = prefs(c).edit().putBoolean(KEY_LIBRARY_GRID, v).apply()

/**
 * Crossfade length in seconds, 0 = off (the default: nothing changes unless asked for).
 *
 * It is a fade, not an overlap: the page plays one media element, so the next song cannot
 * start before this one ends. The song fades out over its last N seconds, the next one fades
 * in. The value is Compose state, so the settings row redraws when it changes.
 */
object CrossfadePref {
    const val MAX = 12
    var seconds by androidx.compose.runtime.mutableStateOf(
        prefs(com.music.spotui.di.Graph.app).getInt(KEY_CROSSFADE, 0).coerceIn(0, MAX),
    )
        private set

    /** Saves it and hands it to the player: the change applies to the song playing now. */
    fun set(c: Context, value: Int) {
        val v = value.coerceIn(0, MAX)
        seconds = v
        prefs(c).edit().putInt(KEY_CROSSFADE, v).apply()
        applyCrossfade(v)
    }
}

fun getCrossfadeSeconds(c: Context): Int =
    prefs(c).getInt(KEY_CROSSFADE, 0).coerceIn(0, CrossfadePref.MAX)

/** Gives the saved length to the player (at start, and at every change); 0 stops a fade at once. */
fun applyCrossfade(seconds: Int) {
    val runtime = app.myspoti.host.android.MyspotiRuntime
    if (!runtime.isStarted) return
    runtime.spotify.player.crossfadeSeconds = seconds
}

fun getLyricTranslateLang(c: Context): String =
    prefs(c).getString(KEY_LYRIC_TRANSLATE_LANG, "").orEmpty()

fun setLyricTranslateLang(c: Context, code: String) =
    prefs(c).edit().putString(KEY_LYRIC_TRANSLATE_LANG, code.trim()).apply()
