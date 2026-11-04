package com.music.spotui.data.api

import android.content.Context
import android.util.Log
import app.myspoti.host.android.MyspotiRuntime
import com.music.spotui.data.entity.SongsModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Library and playlist edits on the user's Spotify account. Failures are logged and
 * reported through the callbacks; the UI has already shown the change.
 */
object SpotifySync {
    private const val TAG = "SpotifySync"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val api get() = MyspotiRuntime.api

    fun setTrackSaved(context: Context, trackId: String, saved: Boolean) {
        if (trackId.isNotBlank()) com.music.spotui.di.SongPlayer.setTrackSaved(trackId, saved)
    }

    fun setAlbumSaved(context: Context, albumId: String, saved: Boolean) {
        if (albumId.isNotBlank()) com.music.spotui.di.SongPlayer.setSaved("spotify:album:$albumId", saved)
    }

    fun setArtistFollowed(context: Context, artistId: String, followed: Boolean) {
        if (artistId.isNotBlank()) com.music.spotui.di.SongPlayer.setSaved("spotify:artist:$artistId", followed)
    }

    /** Adds a track to one of the user's playlists. */
    fun addTrackToPlaylist(context: Context, playlistId: String, trackId: String, onDone: (Boolean) -> Unit = {}) {
        if (playlistId.isBlank() || trackId.isBlank()) { onDone(false); return }
        scope.launch {
            val ok = runCatching { api.playlists.addTracks("spotify:playlist:$playlistId", listOf("spotify:track:$trackId")) }
                .onFailure { Log.w(TAG, "add $trackId to $playlistId failed: ${it.message}") }.isSuccess
            if (ok) membershipCache[playlistId]?.add(trackId)
            onDone(ok)
        }
    }

    /** Removes a specific row ([uid] when given) or every occurrence of [trackId] from one of the user's playlists. */
    fun removeTrackFromPlaylist(context: Context, playlistId: String, trackId: String, onDone: (Boolean) -> Unit = {}) =
        removeTrackFromPlaylist(context, playlistId, trackId, "", onDone)

    fun removeTrackFromPlaylist(context: Context, playlistId: String, trackId: String, uid: String, onDone: (Boolean) -> Unit = {}) {
        if (playlistId.isBlank() || (trackId.isBlank() && uid.isBlank())) { onDone(false); return }
        scope.launch {
            val ok = runCatching {
                val uri = "spotify:playlist:$playlistId"
                val uids = if (uid.isNotBlank()) {
                    listOf(uid)
                } else {
                    allTracks(uri).filter { it.uri == "spotify:track:$trackId" }.mapNotNull { it.uid }
                }
                check(uids.isNotEmpty()) { "track not in playlist" }
                api.playlists.removeTracks(uri, uids)
            }.onFailure { Log.w(TAG, "remove $trackId from $playlistId failed: ${it.message}") }.isSuccess
            if (ok && trackId.isNotBlank()) membershipCache[playlistId]?.remove(trackId)
            onDone(ok)
        }
    }

    /**
     * Applies track removals and rearrangements from the playlist editor to Spotify:
     * 1. Removes deleted rows via `removeTracks`.
     * 2. Reorders the remaining rows into [finalTracks] using the minimum number of `moveTracks` calls (via LIS).
     */
    suspend fun syncPlaylistTrackEdits(
        playlistUri: String,
        initialTracks: List<SongsModel>,
        finalTracks: List<SongsModel>,
    ) {
        if (initialTracks == finalTracks) return
        val playlistId = playlistUri.substringAfterLast(':')
        // Ensure every track has its playlist row UID (fallback to fetching if any cached row lacked uid).
        val needLookup = initialTracks.any { it.uid.isBlank() } || finalTracks.any { it.uid.isBlank() }
        val serverByTrack = if (needLookup) {
            val queueByUri = HashMap<String, ArrayDeque<String>>()
            for (t in allTracks(playlistUri)) {
                val u = t.uid ?: continue
                queueByUri.getOrPut(t.uri.substringAfterLast(':')) { ArrayDeque() }.addLast(u)
            }
            queueByUri
        } else null

        val resolvedInitial = initialTracks.map { s ->
            if (s.uid.isNotBlank()) s
            else {
                val resolvedUid = serverByTrack?.get(s.spotifyTrackId)?.removeFirstOrNull().orEmpty()
                s.copy(uid = resolvedUid)
            }
        }
        val uidByIdentity = IdentityHashMap<SongsModel, String>()
        resolvedInitial.forEachIndexed { idx, s -> uidByIdentity[initialTracks[idx]] = s.uid }
        val resolvedFinal = finalTracks.map { s ->
            if (s.uid.isNotBlank()) s else s.copy(uid = uidByIdentity[s].orEmpty())
        }

        val initialUids = resolvedInitial.map { it.uid }.filter { it.isNotBlank() }
        val finalUids = resolvedFinal.map { it.uid }.filter { it.isNotBlank() }
        val finalUidSet = finalUids.toHashSet()

        // 1. Remove deleted rows
        val removedUids = initialUids.filter { it !in finalUidSet }
        if (removedUids.isNotEmpty()) {
            api.playlists.removeTracks(playlistUri, removedUids)
            membershipCache[playlistId]?.apply {
                clear()
                addAll(resolvedFinal.mapNotNull { it.spotifyTrackId.takeIf(String::isNotBlank) })
            }
        }

        // 2. Move rearranged rows in minimal steps
        val remainingUids = initialUids.filter { it in finalUidSet }
        if (remainingUids != finalUids && finalUids.size > 1) {
            val moves = computeMinimalMoves(remainingUids, finalUids)
            for ((uid, beforeUid) in moves) {
                api.playlists.moveTracks(playlistUri, listOf(uid), beforeUid)
            }
        }
    }

    /**
     * Computes the minimum sequence of `(uid, beforeUid)` moves to transform [fromOrder] into [toOrder]
     * using Longest Increasing Subsequence (LIS).
     */
    internal fun computeMinimalMoves(fromOrder: List<String>, toOrder: List<String>): List<Pair<String, String?>> {
        if (fromOrder == toOrder || toOrder.size <= 1) return emptyList()
        val posInFrom = HashMap<String, Int>(fromOrder.size)
        fromOrder.forEachIndexed { i, uid -> posInFrom[uid] = i }

        val n = toOrder.size
        val seq = IntArray(n) { i -> posInFrom[toOrder[i]] ?: -1 }
        val tails = IntArray(n)
        val tailIndices = IntArray(n)
        val prev = IntArray(n) { -1 }
        var len = 0

        for (i in 0 until n) {
            val x = seq[i]
            if (x < 0) continue
            var lo = 0
            var hi = len
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (tails[mid] < x) lo = mid + 1 else hi = mid
            }
            tails[lo] = x
            tailIndices[lo] = i
            if (lo > 0) prev[i] = tailIndices[lo - 1]
            if (lo == len) len++
        }

        val keepSet = HashSet<String>(len)
        var cur = if (len > 0) tailIndices[len - 1] else -1
        while (cur >= 0) {
            keepSet.add(toOrder[cur])
            cur = prev[cur]
        }

        val moves = ArrayList<Pair<String, String?>>(n - keepSet.size)
        for (i in (n - 1) downTo 0) {
            val uid = toOrder[i]
            if (uid !in keepSet) {
                val beforeUid = toOrder.getOrNull(i + 1)
                moves.add(uid to beforeUid)
            }
        }
        return moves
    }

    /** Creates a playlist (and adds the track when given). */
    fun createPlaylistWithTrack(context: Context, name: String, trackId: String, folderUri: String? = null, onDone: (Boolean) -> Unit = {}) {
        if (name.isBlank()) { onDone(false); return }
        scope.launch {
            val ok = runCatching {
                val uri = api.playlists.create(name.trim(), folderUri)
                if (trackId.isNotBlank()) {
                    api.playlists.addTracks(uri, listOf("spotify:track:$trackId"))
                    membershipCache[uri.substringAfterLast(':')] = ConcurrentHashMap.newKeySet<String>().apply { add(trackId) }
                }
            }.onFailure { Log.w(TAG, "create playlist '$name' failed: ${it.message}") }.isSuccess
            Api.HomeCache.libraryRoot = null
            folderUri?.let { Api.HomeCache.folders.remove(it) }
            onDone(ok)
        }
    }

    private suspend fun allTracks(uri: String): List<app.myspoti.api.Track> {
        val out = ArrayList<app.myspoti.api.Track>()
        var offset = 0
        while (true) {
            val page = api.playlists.tracks(uri, offset, 100)
            out += page.items
            offset = page.nextOffset ?: break
        }
        return out
    }

    // Which playlists contain a track, cached per playlist for the session.
    private val membershipCache = ConcurrentHashMap<String, MutableSet<String>>()

    /** Track ids contained in [playlistId] (first call fetches, then cached). */
    suspend fun playlistTrackIds(context: Context, playlistId: String): Set<String> {
        membershipCache[playlistId]?.let { return it }
        val ids = runCatching { allTracks("spotify:playlist:$playlistId").map { it.uri.substringAfterLast(':') } }.getOrNull() ?: return emptySet()
        val set = ConcurrentHashMap.newKeySet<String>().apply { addAll(ids) }
        membershipCache[playlistId] = set
        return set
    }
}
