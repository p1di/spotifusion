package com.music.spotui.di

import androidx.compose.runtime.snapshotFlow
import com.music.spotui.data.cache.PlaybackCacheManager
import com.music.spotui.data.cache.PlaybackCacheStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Monitors active playback queue and triggers proactive caching for the current and upcoming tracks.
 */
object QueuePreloader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastTrackId: String? = null
    private var started = false

    fun init() {
        if (started) return
        started = true
        PlaybackCacheStore.init(Graph.app)

        scope.launch {
            snapshotFlow { SongPlayer.nowPlaying.value }.collectLatest { np ->
                val uri = np.uri
                val currentTrackId = if (uri.startsWith("spotify:track:")) uri.substringAfterLast(":") else ""
                if (currentTrackId.isNotBlank()) {
                    val prev = lastTrackId
                    if (prev != null && prev != currentTrackId) {
                        PlaybackCacheManager.onTrackPlayed(prev)
                    } else if (prev == currentTrackId) {
                        PlaybackCacheManager.onTrackReplayed(currentTrackId)
                    }
                    lastTrackId = currentTrackId
                }
            }
        }

        scope.launch {
            snapshotFlow { SongPlayer.engineQueue.value }.collectLatest { eq ->
                val upcoming = listOfNotNull(eq.current) + eq.queued.map { it.song } + eq.nextUp.map { it.song }
                if (upcoming.isNotEmpty()) {
                    PlaybackCacheManager.preloadTracks(upcoming)
                }
            }
        }
    }
}
