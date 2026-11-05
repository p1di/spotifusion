package com.music.spotui.di

import androidx.compose.runtime.mutableStateOf
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.music.spotui.data.local.LocalTrack

/**
 * Plays the device's own audio files (Local Files). While it plays, the Spotify engine is
 * paused and SongPlayer routes the controls here (player, mini player, notification);
 * playing anything from Spotify again hands back to the engine.
 */
object LocalPlayer {
    /** Local playback is the current source. */
    val active = mutableStateOf(false)
    /** The play order, kept in step with the player's items (shuffle reorders it, see [setShuffle]). */
    private val tracks = mutableListOf<LocalTrack>()
    /** The order before shuffle, to go back to. */
    private var unshuffled: List<LocalTrack>? = null
    /** Where the queue comes from (Local Files, Downloaded…), shown in the player. */
    var contextName: String = ""
        private set
    var contextUri: String = ""
        private set
    @Volatile var fallbackMode: Boolean = false
        private set
    private var exo: ExoPlayer? = null

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun player(): ExoPlayer = exo ?: ExoPlayer.Builder(Graph.app)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        .setHandleAudioBecomingNoisy(true)
        // Downloaded songs are encrypted files: the player reads them through the decrypting source.
        .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
            com.music.spotui.data.offline.OfflineCrypto.Factory(Graph.app)))
        .build().also { p ->
            p.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = publish(
                    queue = events.containsAny(
                        Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_POSITION_DISCONTINUITY,
                    ),
                )
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED && fallbackMode && !p.hasNextMediaItem() && p.repeatMode == Player.REPEAT_MODE_OFF) {
                        SongPlayer.advanceFallbackQueue(1)
                    }
                }
            })
            exo = p
        }

    fun play(list: List<LocalTrack>, index: Int, shuffle: Boolean = false, context: String? = null, contextUri: String = "", fallback: Boolean = false) {
        if (list.isEmpty()) return
        SongPlayer.pauseEngineForLocal()
        // Shuffling is done on the list before the player gets it: reordering thousands of
        // items inside the player one move at a time rebuilt its timeline at each move.
        val start = index.coerceIn(0, list.lastIndex)
        val order = if (shuffle) QueueOrder.shuffledFrom(list, start) else list
        tracks.clear(); tracks.addAll(order); unshuffled = if (shuffle) list.toList() else null
        contextName = context ?: Graph.app.getString(app.myspoti.engine.R.string.local_files_title)
        this.contextUri = contextUri
        fallbackMode = fallback
        active.value = true
        val p = player()
        p.shuffleModeEnabled = false
        p.setMediaItems(order.map(::item), if (shuffle) 0 else start, 0L)
        p.prepare()
        p.play()
    }

    /** Back to Spotify: stop and forget the local queue. */
    fun stop() {
        if (!active.value) return
        active.value = false
        fallbackMode = false
        exo?.let { it.stop(); it.clearMediaItems() }
        tracks.clear(); unshuffled = null
        SongPlayer.restoreEngineQueue()
    }

    fun resume() { exo?.play() }
    fun pause() { exo?.pause() }
    fun next() {
        exo?.let {
            if (it.hasNextMediaItem()) it.seekToNextMediaItem()
            else if (fallbackMode) SongPlayer.advanceFallbackQueue(1)
        }
    }
    fun previous() {
        exo?.let {
            if (it.currentPosition > 3000) it.seekTo(0)
            else if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem()
            else if (fallbackMode) SongPlayer.advanceFallbackQueue(-1)
            else it.seekTo(0)
        }
    }
    fun seekTo(ms: Long) { exo?.seekTo(ms) }
    /**
     * Shuffle reorders the songs after the current one (and off puts the list's order back),
     * so the queue shown is the order that really plays and can be edited like any list.
     */
    fun setShuffle(on: Boolean) {
        val p = exo ?: return
        if (on == (unshuffled != null) || tracks.isEmpty()) return
        val cur = p.currentMediaItemIndex.coerceIn(0, tracks.lastIndex)
        if (on) {
            unshuffled = tracks.toList()
            arrange(tracks.subList(0, cur + 1).toList() + tracks.subList(cur + 1, tracks.size).shuffled())
        } else {
            val before = unshuffled.orEmpty()
            unshuffled = null
            arrange(QueueOrder.restored(before, tracks))
        }
    }

    val shuffled get() = unshuffled != null

    /**
     * The songs after the current one, in play order, with their index in the list: the next
     * [UPCOMING_MAX] only. The queue screen cannot show thousands of rows, and building them at each
     * change of the player (a copy of the whole list each time) is what ran a big list out of memory.
     */
    fun upcoming(): List<Pair<Int, LocalTrack>> {
        val i = exo?.currentMediaItemIndex ?: return emptyList()
        return (i + 1 until minOf(tracks.size, i + 1 + UPCOMING_MAX)).map { it to tracks[it] }
    }

    private const val UPCOMING_MAX = 500

    fun skipTo(index: Int) { exo?.let { if (index in tracks.indices) { it.seekTo(index, 0L); it.play() } } }

    /** Moves the song at [from] so it ends at [to] (indices of the list after the move). */
    fun move(from: Int, to: Int) {
        val p = exo ?: return
        if (from !in tracks.indices || to !in tracks.indices || from == to) return
        p.moveMediaItem(from, to)
        tracks.add(to, tracks.removeAt(from))
        publish()
    }

    /** Moves the song at [from] right after the current one. */
    fun moveNext(from: Int) { exo?.let { val cur = it.currentMediaItemIndex; move(from, if (from > cur) cur + 1 else cur) } }

    fun remove(index: Int) {
        val p = exo ?: return
        if (index !in tracks.indices || index == p.currentMediaItemIndex) return
        p.removeMediaItem(index)
        val t = tracks.removeAt(index)
        unshuffled = unshuffled?.filterNot { it === t }
        publish()
    }

    /**
     * Puts the player's items in [target]'s order (the same songs, current one included) while the
     * current song keeps playing: the items around it are replaced, which is three changes of the
     * player's timeline whatever the size of the list, not one per song.
     */
    private fun arrange(target: List<LocalTrack>) {
        val p = exo ?: return
        if (tracks.isEmpty()) return
        val cur = p.currentMediaItemIndex.coerceIn(0, tracks.lastIndex)
        val at = target.indexOfFirst { it === tracks[cur] }
        if (at < 0) return
        reordering = true
        try {
            if (cur + 1 < tracks.size) p.removeMediaItems(cur + 1, tracks.size)
            if (cur > 0) p.removeMediaItems(0, cur)
            if (at > 0) p.addMediaItems(0, target.subList(0, at).map(::item))
            if (at + 1 < target.size) p.addMediaItems(target.subList(at + 1, target.size).map(::item))
            tracks.clear(); tracks.addAll(target)
        } finally {
            reordering = false
        }
        publish()
    }

    /** The player's items and [tracks] differ while [arrange] works: nothing is published then. */
    private var reordering = false

    private fun item(t: LocalTrack) = MediaItem.Builder().setUri(t.uri).setMediaId(t.id.toString())
        .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).build()).build()
    fun setRepeatMode(mode: Int) {
        exo?.repeatMode = when (mode) { 1 -> Player.REPEAT_MODE_ALL; 2 -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
    }
    fun isPlaying() = exo?.isPlaying == true
    fun position() = exo?.currentPosition ?: 0L
    fun duration() = exo?.duration?.takeIf { it > 0 } ?: current()?.durationMs ?: 0L
    fun current(): LocalTrack? = exo?.currentMediaItemIndex?.let { tracks.getOrNull(it) }

    private fun publish(queue: Boolean = true) {
        if (!active.value || reordering) return
        val t = current() ?: return
        SongPlayer.onLocalState(t, exo?.playWhenReady == true, position(), duration())
        if (queue) SongPlayer.onLocalQueue(t, upcoming())
    }
}
