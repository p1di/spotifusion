package com.music.spotui.data.preferences

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.music.spotui.di.Graph

private const val PREF = "cache_settings_prefs"
private const val KEY_PRELOADING_ENABLED = "preloading_enabled"
private const val KEY_PRELOAD_COUNT = "preload_count"
private const val KEY_CACHE_CAP_MB = "cache_cap_mb"

/** Preferences for proactive playback caching and queue preloading. */
object CacheSettingsPref {
    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    var preloadingEnabled by mutableStateOf(prefs(Graph.app).getBoolean(KEY_PRELOADING_ENABLED, true))
        private set

    var preloadCount by mutableStateOf(prefs(Graph.app).getInt(KEY_PRELOAD_COUNT, 2).coerceIn(1, 10))
        private set

    /** Cache cap in MB: 500, 1000, 2000, 5000, or 0 for Unlimited. Default: 1000 MB. */
    var cacheCapMb by mutableStateOf(prefs(Graph.app).getInt(KEY_CACHE_CAP_MB, 1000))
        private set

    fun setEnabled(c: Context, enabled: Boolean) {
        preloadingEnabled = enabled
        prefs(c).edit().putBoolean(KEY_PRELOADING_ENABLED, enabled).apply()
    }

    fun setCount(c: Context, count: Int) {
        val v = count.coerceIn(1, 10)
        preloadCount = v
        prefs(c).edit().putInt(KEY_PRELOAD_COUNT, v).apply()
    }

    fun setCapMb(c: Context, capMb: Int) {
        cacheCapMb = capMb
        prefs(c).edit().putInt(KEY_CACHE_CAP_MB, capMb).apply()
    }
}

fun isPlaybackCacheEnabled(c: Context): Boolean = CacheSettingsPref.preloadingEnabled
fun setPlaybackCacheEnabled(c: Context, enabled: Boolean) = CacheSettingsPref.setEnabled(c, enabled)
fun getPreloadTrackCount(c: Context): Int = CacheSettingsPref.preloadCount
fun setPreloadTrackCount(c: Context, count: Int) = CacheSettingsPref.setCount(c, count)
fun getMaxCacheSizeMb(c: Context): Int = CacheSettingsPref.cacheCapMb
fun setMaxCacheSizeMb(c: Context, mb: Int) = CacheSettingsPref.setCapMb(c, mb)
