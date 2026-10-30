package com.music.spotui.data.cache

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.music.spotui.data.local.LocalTrack
import com.music.spotui.di.Graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class CachedTrack(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val coverUrl: String = "",
    val durationMs: Long = 0L,
    val fileName: String,
    val bytes: Long,
    val cachedAt: Long,
    val lastPlayedAt: Long = cachedAt,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", trackId)
        .put("title", title)
        .put("artist", artist)
        .put("album", album)
        .put("coverUrl", coverUrl)
        .put("durationMs", durationMs)
        .put("file", fileName)
        .put("bytes", bytes)
        .put("cachedAt", cachedAt)
        .put("lastPlayedAt", lastPlayedAt)

    companion object {
        fun fromJson(o: JSONObject): CachedTrack = CachedTrack(
            trackId = o.getString("id"),
            title = o.optString("title"),
            artist = o.optString("artist"),
            album = o.optString("album"),
            coverUrl = o.optString("coverUrl"),
            durationMs = o.optLong("durationMs", 0L),
            fileName = o.getString("file"),
            bytes = o.optLong("bytes"),
            cachedAt = o.optLong("cachedAt"),
            lastPlayedAt = o.optLong("lastPlayedAt", o.optLong("cachedAt")),
        )
    }
}

/**
 * Manages locally pre-cached playback tracks in `context.cacheDir/playback_cache/`.
 */
object PlaybackCacheStore {
    private const val TAG = "PlaybackCache"
    private const val COOLDOWN_MS = 15_000L // 15 seconds grace period before purging played tracks

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var cacheDir: File
    private lateinit var indexFile: File

    val cachedTracks: SnapshotStateMap<String, CachedTrack> = mutableStateMapOf()
    private val pendingPurges = ConcurrentHashMap<String, Job>()

    fun init(context: Context) {
        if (::cacheDir.isInitialized) return
        cacheDir = File(context.cacheDir, "playback_cache")
        cacheDir.mkdirs()
        indexFile = File(cacheDir, "index.json")
        loadIndex()
    }

    private fun ensureInit() {
        if (!::cacheDir.isInitialized) {
            init(Graph.app)
        }
    }

    private fun loadIndex() {
        cachedTracks.clear()
        if (!indexFile.exists()) return
        runCatching {
            val json = JSONArray(indexFile.readText())
            for (i in 0 until json.length()) {
                val t = CachedTrack.fromJson(json.getJSONObject(i))
                val audioFile = File(cacheDir, t.fileName)
                if (audioFile.isFile) {
                    cachedTracks[t.trackId] = t
                }
            }
        }.onFailure {
            android.util.Log.w(TAG, "failed to read playback cache index: ${it.message}")
        }
    }

    private fun saveIndex() {
        ensureInit()
        runCatching {
            val json = JSONArray()
            cachedTracks.values.forEach { json.put(it.toJson()) }
            indexFile.writeText(json.toString())
        }
    }

    fun isCached(trackId: String): Boolean {
        if (trackId.isBlank()) return false
        ensureInit()
        val t = cachedTracks[trackId] ?: return false
        return File(cacheDir, t.fileName).isFile
    }

    fun getAudioFile(trackId: String): File? {
        ensureInit()
        val t = cachedTracks[trackId] ?: return null
        val f = File(cacheDir, t.fileName)
        return if (f.isFile) f else null
    }

    fun createCacheFile(trackId: String): File {
        ensureInit()
        return File(cacheDir, "${trackId}.m4a")
    }

    fun toLocalTrack(c: CachedTrack): LocalTrack {
        ensureInit()
        return LocalTrack(
            id = ("cache_" + c.trackId).hashCode().toLong(),
            uri = Uri.fromFile(File(cacheDir, c.fileName)),
            title = c.title.ifBlank { "Preloaded Track" },
            artist = c.artist,
            album = c.album,
            durationMs = c.durationMs,
            artUri = if (c.coverUrl.isNotBlank()) Uri.parse(c.coverUrl) else Uri.EMPTY,
        )
    }

    fun addTrack(t: CachedTrack) {
        ensureInit()
        // Cancel any pending purge if track is re-added
        pendingPurges.remove(t.trackId)?.cancel()
        cachedTracks[t.trackId] = t
        saveIndex()
    }

    fun totalBytes(): Long = cachedTracks.values.sumOf { it.bytes }

    /**
     * Schedules track removal after a 15-second cooldown period once playback of [trackId] completes.
     */
    fun scheduleTrackPurge(trackId: String, delayMs: Long = COOLDOWN_MS) {
        if (!cachedTracks.containsKey(trackId)) return
        pendingPurges.remove(trackId)?.cancel()
        val job = scope.launch {
            delay(delayMs)
            removeTrack(trackId)
            pendingPurges.remove(trackId)
        }
        pendingPurges[trackId] = job
    }

    fun cancelPendingPurge(trackId: String) {
        pendingPurges.remove(trackId)?.cancel()
    }

    fun removeTrack(trackId: String) {
        ensureInit()
        val t = cachedTracks.remove(trackId) ?: return
        File(cacheDir, t.fileName).delete()
        saveIndex()
    }

    fun enforceCap(maxCapBytes: Long) {
        if (maxCapBytes <= 0) return // 0 = unlimited
        var currentSize = totalBytes()
        if (currentSize <= maxCapBytes) return

        // Purge oldest cached tracks (LRU based on lastPlayedAt / cachedAt)
        val sorted = cachedTracks.values.sortedBy { it.lastPlayedAt }
        for (t in sorted) {
            if (currentSize <= maxCapBytes) break
            removeTrack(t.trackId)
            currentSize -= t.bytes
        }
    }

    fun clearAll() {
        ensureInit()
        pendingPurges.values.forEach { it.cancel() }
        pendingPurges.clear()
        cachedTracks.clear()
        cacheDir.listFiles()?.forEach { it.delete() }
        saveIndex()
    }
}
