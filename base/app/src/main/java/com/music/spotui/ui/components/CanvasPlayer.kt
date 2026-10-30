package com.music.spotui.ui.components

import com.music.spotui.di.SongPlayer

/**
 * One Canvas player for the whole app: closing and reopening the player screen keeps the
 * clip loaded (and its last frame), instead of building a new player that starts from nothing.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object CanvasPlayer {
    private var exo: androidx.media3.exoplayer.ExoPlayer? = null
    private var url: String? = null
    private var framed: String? = null
    private val firstFrame = mutableListOf<() -> Unit>()

    /**
     * [url] is loaded and ready to draw: its first frame shows as soon as a view is attached,
     * so the player screen opens on the clip instead of the cover.
     */
    fun isReady(url: String) = this.url == url && (framed == url || exo?.playbackState == androidx.media3.common.Player.STATE_READY)

    /**
     * Loads the clip of the song now playing before the player screen opens (called while
     * the mini player is on screen, so only when the app is in use). Main thread.
     */
    fun preload(context: android.content.Context, url: String) { get(context, url) }

    /**
     * Downloads the clip [url] to the media cache on storage, without a player and without
     * keeping anything in memory: the next song's Canvas, so it starts from storage when that
     * song comes (the player still loads it then, but from the phone, not the network).
     */
    suspend fun warm(context: android.content.Context, url: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val source = androidx.media3.datasource.cache.CacheDataSource.Factory()
                .setCache(SongPlayer.mediaCache(context.applicationContext))
                .setUpstreamDataSourceFactory(androidx.media3.datasource.DefaultHttpDataSource.Factory())
                .createDataSource()
            androidx.media3.datasource.cache.CacheWriter(source, androidx.media3.datasource.DataSpec(android.net.Uri.parse(url)), null, null).cache()
        }
    }

    fun get(context: android.content.Context, url: String): androidx.media3.exoplayer.ExoPlayer {
        val player = exo ?: run {
            val cached = androidx.media3.datasource.cache.CacheDataSource.Factory()
                .setCache(SongPlayer.mediaCache(context.applicationContext))
                .setUpstreamDataSourceFactory(androidx.media3.datasource.DefaultHttpDataSource.Factory())
            androidx.media3.exoplayer.ExoPlayer.Builder(context.applicationContext)
                .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(cached))
                .build().apply {
                    repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
                    volume = 0f
                    playWhenReady = false
                    addListener(object : androidx.media3.common.Player.Listener {
                        override fun onRenderedFirstFrame() {
                            framed = this@CanvasPlayer.url
                            firstFrame.toList().forEach { it() }
                        }
                    })
                }.also { exo = it }
        }
        if (url != this.url) {
            this.url = url
            framed = null
            player.setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
            player.prepare()
        }
        return player
    }

    fun onFirstFrame(listener: () -> Unit): () -> Unit {
        firstFrame += listener
        return { firstFrame -= listener }
    }
}
