package com.music.spotui.data.cache

import android.content.Context
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.offline.OfflineDownloader
import com.music.spotui.data.offline.OfflineStore
import com.music.spotui.data.preferences.CacheSettingsPref
import com.music.spotui.di.Graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles proactive preloading of the currently playing track and upcoming queue tracks into local cache,
 * and manages cache storage thresholds and post-play purging.
 */
object PlaybackCacheManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun preloadTracks(songs: List<SongsModel>, context: Context = Graph.app) {
        if (!CacheSettingsPref.preloadingEnabled) return
        if (com.music.spotui.data.DataSaver.active || !OfflineStore.isOnline(context)) return

        val capMb = CacheSettingsPref.cacheCapMb
        val maxCapBytes = if (capMb > 0) capMb * 1024L * 1024L else 0L

        if (maxCapBytes > 0) {
            PlaybackCacheStore.enforceCap(maxCapBytes)
        }

        val count = CacheSettingsPref.preloadCount
        val toPreload = songs.take(count).filter { song ->
            val id = song.spotifyTrackId
            id.isNotBlank() && !OfflineStore.isDownloaded(id) && !PlaybackCacheStore.isCached(id)
        }

        if (toPreload.isEmpty()) return

        scope.launch {
            for (song in toPreload) {
                val id = song.spotifyTrackId
                if (id.isBlank() || OfflineStore.isDownloaded(id) || PlaybackCacheStore.isCached(id)) continue
                runCatching {
                    val audioFile = PlaybackCacheStore.createCacheFile(id)
                    val fallback = OfflineDownloader.fetchFallbackAudioFile(id, song.title, song.singer, song.durationMs.toLong())
                    if (fallback != null && fallback.isFile && fallback.length() > 10_000L) {
                        fallback.copyTo(audioFile, overwrite = true)
                    }
                    if (audioFile.isFile && audioFile.length() > 10_000L) {
                        PlaybackCacheStore.addTrack(
                            CachedTrack(
                                trackId = id,
                                title = song.title,
                                artist = song.singer,
                                album = song.album,
                                coverUrl = song.coverUri,
                                durationMs = song.durationMs.toLong(),
                                fileName = audioFile.name,
                                bytes = audioFile.length(),
                                cachedAt = System.currentTimeMillis(),
                            )
                        )
                        if (maxCapBytes > 0) {
                            PlaybackCacheStore.enforceCap(maxCapBytes)
                        }
                    } else {
                        audioFile.delete()
                    }
                }.onFailure {
                    android.util.Log.w("PlaybackCache", "Failed to preload $id (${song.title}): ${it.message}")
                }
            }
        }
    }

    fun onTrackPlayed(trackId: String) {
        if (trackId.isBlank()) return
        PlaybackCacheStore.scheduleTrackPurge(trackId)
    }

    fun onTrackReplayed(trackId: String) {
        if (trackId.isBlank()) return
        PlaybackCacheStore.cancelPendingPurge(trackId)
    }

    fun clearCache() {
        PlaybackCacheStore.clearAll()
    }
}
