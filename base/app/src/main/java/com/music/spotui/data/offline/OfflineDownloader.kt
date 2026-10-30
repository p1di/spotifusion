package com.music.spotui.data.offline

import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.isDownloadsEnabled
import com.music.spotui.di.Graph
import com.music.spotui.video.InterruptibleHttp
import com.music.spotui.video.YouTubeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import com.music.spotui.extensions.MediaHttp
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloads songs for offline listening. Spotify only gives the track (title, artist,
 * duration, ISRC); where the audio file is, and that it is the right recording, is the job
 * of a `download-source` extension (docs/extensions/API.md §3), which answers the files it
 * has, best first. Myspoti then:
 *
 * 1. fetches the first file that works (a FLAC, a DASH manifest of FLAC frames joined
 *    into one FLAC, or an M4A / MP3);
 * 2. checks what any file must pass, whoever sent it: real, decodable audio whose length
 *    is the song's (Spotify's duration), so a preview or another cut is refused;
 * 3. keeps it encrypted ([OfflineCrypto]), with its cover, in the Downloaded list.
 *
 * No extension giving download files: downloads say to install one. Nothing that fails the
 * check is kept: the song is marked unavailable instead.
 */
object OfflineDownloader {
    private const val TAG = "Offline"
    private const val WORKERS = 3
    /** A file may be this much shorter than the song (seconds): more is a preview or a cut. */
    private const val SHORTER_MAX = 5
    /** And this much longer (trailing silence, a longer intro): more is another version. */
    private const val LONGER_MAX = 15

    data class Request(
        val trackId: String,
        val title: String,
        val artist: String,
        val album: String,
        val albumId: String,
        val durationMs: Long,
        val coverUrl: String,
        /** The quality chosen for this download ([DownloadQuality.name]); empty = the setting. */
        val quality: String = "",
    ) {
        fun toJson(): JSONObject = JSONObject().put("id", trackId).put("title", title).put("artist", artist)
            .put("album", album).put("albumId", albumId).put("duration", durationMs).put("cover", coverUrl)
            .put("quality", quality)

        companion object {
            fun of(s: SongsModel) = Request(
                trackId = s.spotifyTrackId, title = s.title, artist = s.singer, album = s.album,
                albumId = s.albumId, durationMs = s.durationMs.toLong(), coverUrl = s.coverUri,
            )
            fun fromJson(o: JSONObject) = Request(
                o.getString("id"), o.optString("title"), o.optString("artist"), o.optString("album"),
                o.optString("albumId"), o.optLong("duration"), o.optString("cover"), o.optString("quality"),
            )
        }
    }

    private class Unavailable(message: String) : Exception(message)

    /** Two AAC encodes at once at most: the CPU stays free for the rest of the app. */
    private val encodeSlots = kotlinx.coroutines.sync.Semaphore(2)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Songs queued, downloading, or whose download did not succeed (for the Downloaded list). */
    val requests: SnapshotStateMap<String, Request> = mutableStateMapOf()
    private val queue = Channel<Request>(Channel.UNLIMITED)
    /** Requests not finished yet, in order (persisted: a restart resumes them). */
    private val pending = LinkedHashMap<String, Request>()
    private val running = ConcurrentHashMap<String, Job>()
    @Volatile private var started = false

    private fun start() {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            repeat(WORKERS) { scope.launch { for (r in queue) work(r) } }
        }
    }

    /** Re-queues what was waiting when the app last stopped. */
    fun resumePending() {
        // Read off the main thread (the app is already drawing): the queue fills a moment later.
        scope.launch {
            val saved = runCatching {
                val a = JSONArray(OfflineStore.pendingFile.readText())
                (0 until a.length()).map { Request.fromJson(a.getJSONObject(it)) }
            }.getOrDefault(emptyList())
            if (saved.isNotEmpty()) enqueueAll(saved)
        }
    }

    /**
     * Downloads [songs] in [quality] (asked when the setting is "ask each time"), else in
     * the quality chosen in Settings.
     */
    /**
     * Whether a download can start now, said in a toast when not: downloads switched off in
     * Settings (instead of a list of failed songs), or no extension giving download files (where to
     * get one, for a download the user asked for). A list is only marked downloaded when this holds.
     */
    fun ready(auto: Boolean = false): Boolean {
        if (!isDownloadsEnabled(Graph.app)) {
            scope.launch(Dispatchers.Main) {
                android.widget.Toast.makeText(Graph.app, app.myspoti.engine.R.string.downloads_off_toast, android.widget.Toast.LENGTH_LONG).show()
            }
            return false
        }
        if (!com.music.spotui.extensions.Extensions.hasDownloadSource()) {
            if (!auto) scope.launch(Dispatchers.Main) {
                android.widget.Toast.makeText(Graph.app, app.myspoti.engine.R.string.downloads_need_source, android.widget.Toast.LENGTH_LONG).show()
            }
            return false
        }
        return true
    }

    fun enqueue(
        songs: List<SongsModel>,
        quality: com.music.spotui.data.preferences.DownloadQuality? = null,
        /** Started by the app (a downloaded list keeping in step), not by a tap. */
        auto: Boolean = false,
    ) {
        if (!ready(auto)) return
        if (!auto) {
            val prefs = Graph.app.getSharedPreferences("myspoti_prefs", android.content.Context.MODE_PRIVATE)
            if (!prefs.getBoolean("hide_offline_stats_warning", false)) {
                scope.launch(Dispatchers.Main) {
                    com.music.spotui.offlineStatsWarningOpen.value = true
                }
            }
        }
        if (auto) {
            // Automatic downloads wait for Wi-Fi (never the user's mobile data): the next sync takes them.
            if (com.music.spotui.data.DataSaver.metered || !OfflineStore.isOnline(Graph.app)) { OfflineCollections.waitForWifi(); return }
        } else if (com.music.spotui.data.DataSaver.metered) {
            // Asked for by the user on mobile data: it goes ahead, but they are told.
            scope.launch(Dispatchers.Main) {
                android.widget.Toast.makeText(Graph.app, app.myspoti.engine.R.string.download_on_mobile_data, android.widget.Toast.LENGTH_LONG).show()
            }
        }
        val q = quality?.let { com.music.spotui.data.preferences.DownloadQuality.saved(it.name) }?.name.orEmpty()
        // Episodes go to PodcastDownloader: their id is no track id, so the download-source lookup
        // would 404 (or worse, match an unrelated recording by title).
        enqueueAll(songs.filter { it.spotifyTrackId.isNotBlank() && !isEpisodeKey(it.spotifyTrackId) }.map { Request.of(it).copy(quality = q) })
    }

    private fun enqueueAll(list: List<Request>) {
        val added = synchronized(pending) {
            list.filter { r ->
                // Last guard before the track download path, whatever the caller (resume, retry, list).
                !isEpisodeKey(r.trackId) &&
                    !OfflineStore.isDownloaded(r.trackId) && pending.putIfAbsent(r.trackId, r) == null
            }.also { if (it.isNotEmpty()) savePending() }
        }
        if (added.isEmpty()) return
        scope.launch(Dispatchers.Main) {
            added.forEach { requests[it.trackId] = it; OfflineStore.status[it.trackId] = OfflineStatus.Queued }
        }
        start()
        added.forEach { queue.trySend(it) }
    }

    /**
     * The account's downloads are put away (sign-out, another account signing in): the queue is
     * dropped from memory only (its file goes with the account's folder) and the running downloads
     * stop, waited for a moment so none finishes into the next account's folder.
     */
    internal suspend fun forgetSession() {
        synchronized(pending) { pending.clear() }
        val jobs = running.values.toList()
        running.clear()
        jobs.forEach { it.cancel() }
        kotlinx.coroutines.withTimeoutOrNull(5_000) { jobs.forEach { it.join() } }
        withContext(Dispatchers.Main) { requests.clear() }
    }

    /** Stops a queued or running download (and forgets a failed one). */
    fun cancel(trackId: String) {
        synchronized(pending) { if (pending.remove(trackId) != null) savePending() }
        running.remove(trackId)?.cancel()
        OfflineStore.status.remove(trackId)
        requests.remove(trackId)
    }

    /**
     * Stops every running and queued download and empties the queue. Unlike [removeAll], it
     * deletes nothing: finished songs stay in [OfflineStore.tracks] and in the index, and a
     * cancelled download only drops its own partial file in the temp folder.
     */
    fun stopAll() {
        // The queue survives a restart through pending.json: clearing it here is what makes the
        // stop stick, instead of the downloads resuming at the next launch.
        val ids = synchronized(pending) { pending.keys.toList().also { pending.clear(); savePending() } }
        // Running jobs may not all be in `pending` any more; cancel them by their own handle.
        running.values.forEach { it.cancel() }
        running.clear()
        // Snapshot state: the badges and the list are read from the UI, so write them there.
        scope.launch(Dispatchers.Main) {
            ids.forEach { OfflineStore.status.remove(it); requests.remove(it) }
        }
    }

    /** Tries a failed song again. */
    fun retry(trackId: String) {
        val r = requests[trackId] ?: return
        enqueueAll(listOf(r))
    }

    /** Removes the song: the file if downloaded, else its download. */
    fun remove(trackId: String) {
        cancel(trackId)
        // File deletion on the IO scope, not the (UI) caller's thread.
        scope.launch { OfflineStore.remove(trackId) }
    }

    fun removeAll() {
        val ids = synchronized(pending) { pending.keys.toList().also { pending.clear(); savePending() } }
        ids.forEach { running.remove(it)?.cancel() }
        running.values.forEach { it.cancel() }
        requests.clear()
        // Deleting a large library can take a while: IO scope, not the caller.
        scope.launch { OfflineCollections.removeAll(); OfflineStore.removeAll() }
    }

    private fun savePending() {
        runCatching {
            val a = JSONArray()
            pending.values.forEach { a.put(it.toJson()) }
            com.music.spotui.data.writeTextSafely(OfflineStore.pendingFile, a.toString())
        }
    }

    private suspend fun setStatus(id: String, s: OfflineStatus?) = withContext(Dispatchers.Main) {
        if (s == null) OfflineStore.status.remove(id) else OfflineStore.status[id] = s
    }

    private suspend fun work(r: Request) {
        // Cancelled while it waited in the queue.
        if (synchronized(pending) { !pending.containsKey(r.trackId) }) return
        // Data saver on mobile data: downloads wait for Wi-Fi.
        while (com.music.spotui.data.DataSaver.active) {
            kotlinx.coroutines.delay(5_000)
            if (synchronized(pending) { !pending.containsKey(r.trackId) }) return
        }
        val job = scope.launch {
            val outcome: OfflineStatus? = try {
                setStatus(r.trackId, OfflineStatus.Downloading(-1f))
                val t = download(r)
                // Already on the IO scope: the index rewrite stays off the main thread.
                OfflineStore.add(t)
                withContext(Dispatchers.Main) { requests.remove(r.trackId) }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Unavailable) {
                Log.i(TAG, "${r.title}: ${e.message}")
                OfflineStatus.Unavailable
            } catch (t: Throwable) {
                Log.w(TAG, "${r.title} failed", t)
                OfflineStatus.Failed
            }
            synchronized(pending) { pending.remove(r.trackId); savePending() }
            if (outcome != null) setStatus(r.trackId, outcome)
        }
        running[r.trackId] = job
        job.join()
        running.remove(r.trackId, job)
    }

    private suspend fun download(r: Request): OfflineTrack {
        if (!isDownloadsEnabled(Graph.app)) throw Unavailable("downloads are off")
        val quality = qualityOf(r)
        val tmp = OfflineStore.tmpDir
        val audioTmp = File(tmp, "${r.trackId}.part")
        try {
            val meta = spotifyMeta(r)
            Log.i(TAG, "${meta.title}: isrc=${meta.isrc ?: "-"}")

            // ── The files the download-source extensions give, tried in their order; each one
            // is checked (real audio, the song's length) before it is taken.
            val file = resolveAndFetch(r, meta, audioTmp, quality)
            currentCoroutineContext().ensureActive()

            val out: File
            if (file.lossy != null) {
                // A small file as the source served it (M4A, MP3): kept as it is.
                out = File(OfflineStore.audioDir, "${r.trackId}.${file.lossy}")
                if (!audioTmp.renameTo(out)) throw IOException("could not move the file")
            } else if (quality == com.music.spotui.data.preferences.DownloadQuality.SMALL) {
                // "Smallest": the checked FLAC becomes AAC, encoded on the phone.
                out = File(OfflineStore.audioDir, "${r.trackId}.m4a")
                val aacTmp = File(OfflineStore.tmpDir, "${r.trackId}.m4apart")
                try {
                    // Encoding is long: a few at a time, the other downloads go on meanwhile.
                    encodeSlots.withPermit { runInterruptible(Dispatchers.Default) { AacTranscoder.transcode(audioTmp, aacTmp) } }
                    if (aacTmp.length() < 10_000 || !aacTmp.renameTo(out)) throw IOException("could not encode the small file")
                } finally {
                    aacTmp.delete()
                }
            } else {
                out = File(OfflineStore.audioDir, "${r.trackId}.flac")
                if (!audioTmp.renameTo(out)) throw IOException("could not move the file")
            }
            // The audio is complete on disk from here on: "stop all" must not interrupt this last
            // part, or the file would stay out of the index and be swept as a stray at startup.
            return withContext(NonCancellable) {
                // Kept encrypted: the file only plays in Myspoti, on this phone.
                runInterruptible(Dispatchers.IO) { OfflineCrypto.encryptInPlace(Graph.app, out) }
                val cover = runCatching {
                    val c = File(OfflineStore.coverDir, "${r.trackId}.jpg")
                    httpGet(app.myspoti.host.android.Covers.large(meta.coverUrl), c); c.name
                }.getOrDefault("")
                OfflineTrack(
                    trackId = r.trackId, title = meta.title, artist = meta.artist, album = meta.album,
                    albumId = meta.albumId, durationMs = meta.durationMs, coverUrl = meta.coverUrl,
                    file = out.name, cover = cover, source = file.provider, verified = file.isrcMatched,
                    bytes = out.length(), addedAt = System.currentTimeMillis(), providerId = file.extensionId,
                )
            }
        } finally {
            audioTmp.delete()
        }
    }

    private class Fetched(
        val provider: String,
        val isrcMatched: Boolean,
        /** The id of the download-source extension that supplied it. */
        val extensionId: String,
        /** The extension of a lossy file kept as served (m4a, mp3); null for a FLAC. */
        val lossy: String? = null,
    )

    /** This download's quality: the one chosen when it started, else Settings (CD when Settings says "ask"). */
    private fun qualityOf(r: Request): com.music.spotui.data.preferences.DownloadQuality =
        com.music.spotui.data.preferences.DownloadQuality.saved(r.quality)
            ?: com.music.spotui.data.preferences.DownloadQuality.saved(com.music.spotui.data.preferences.getDownloadQuality(Graph.app).name)
            ?: com.music.spotui.data.preferences.DownloadQuality.CD

    /** The quality as a download-source extension is asked for it. */
    internal fun queryQuality(q: com.music.spotui.data.preferences.DownloadQuality): String = when (q) {
        com.music.spotui.data.preferences.DownloadQuality.HI_RES -> "hi-res"
        com.music.spotui.data.preferences.DownloadQuality.SMALL -> "small"
        else -> "cd"
    }

    /**
     * Asks the download-source extensions for [meta]'s files and saves the first one that works
     * into [out]. Falls back to built-in BitChord downloader (JioSaavn 320kbps AAC + YouTube Music)
     * when no extension has a file, or none of them could be used.
     */
    private suspend fun resolveAndFetch(r: Request, meta: Meta, out: File, quality: com.music.spotui.data.preferences.DownloadQuality): Fetched {
        val track = com.music.spotui.extensions.Extensions.trackInfo(r.trackId, meta.title, meta.artist, meta.album, meta.durationMs, meta.coverUrl)
            .copy(isrc = meta.isrc)
        val extResult = com.music.spotui.extensions.Extensions.downloadFiles(track, queryQuality(quality))
        if (extResult != null) {
            val (extensionId, extension, files) = extResult
            for (file in files) {
                currentCoroutineContext().ensureActive()
                setStatus(r.trackId, OfflineStatus.Downloading(-1f))
                val label = listOfNotNull(file.provider ?: extension, file.bitDepth?.takeIf { file.lossless }?.let { "$it-bit" }).joinToString(" ")
                val ok = try {
                    when {
                        file.format == "dash-flac" -> runInterruptible(Dispatchers.IO) {
                            DashFlacRemux.download("data:application/dash+xml;base64,${file.manifest}", out, file.headers)
                        }
                        else -> fetchWithProgress(r.trackId, file.url!!, out, file.headers)
                    } && out.length() >= 10_000
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "${meta.title}: $label ${file.format}: ${e.message}")
                    false
                }
                if (!ok) continue
                val refused = runCatching {
                    if (file.lossless) checkFlacHeader(out, meta, file.bitDepth) else checkAudio(out, meta)
                }.exceptionOrNull()
                if (refused != null) { Log.i(TAG, "${meta.title}: $label: ${refused.message}"); continue }
                if (!file.lossless) {
                    Log.i(TAG, "${meta.title}: saved $label ${file.format}")
                    return Fetched(label, file.isrcMatched, extensionId, lossy = file.format)
                }
                Log.i(TAG, "${meta.title}: saved FLAC from $label")
                return Fetched(label, file.isrcMatched, extensionId)
            }
        }
        // Built-in BitChord fallback (JioSaavn 320kbps AAC + YouTube Music / Opus / M4A)
        val maxKbps = if (quality == com.music.spotui.data.preferences.DownloadQuality.SMALL) 96 else Int.MAX_VALUE
        val bc = resolveAndFetchBitChord(r.trackId, meta, maxKbps, out)
            ?: throw Unavailable("no download source has this song")
        return Fetched(bc.sourceLabel, isrcMatched = true, extensionId = "bitchord", lossy = bc.ext)
    }

    private val extractionGate = Mutex()
    private data class ResolvedAudio(val url: String, val ext: String, val sourceLabel: String)
    private class StreamExpiredException(val code: Int) : IOException("Stream HTTP $code")

    private suspend fun resolveAndFetchBitChord(
        trackId: String,
        meta: Meta,
        maxKbps: Int,
        out: File,
    ): ResolvedAudio? {
        if (maxKbps > 160) {
            val saavnUrl = runCatching {
                runInterruptible(Dispatchers.IO) { matchJioSaavn320(meta) }
            }.getOrNull()
            if (saavnUrl != null) {
                val ok = runCatching {
                    fetchBitChordChunks(trackId, saavnUrl, out)
                }.getOrDefault(false)
                if (ok && out.length() >= 10_000L) {
                    Log.i(TAG, "${meta.title}: saved 320kbps AAC from JioSaavn")
                    return ResolvedAudio(saavnUrl, "m4a", "AAC 320kbps")
                }
            }
        }

        val ytUrl = runInterruptible(Dispatchers.IO) { matchYouTubeTrack(meta) } ?: return null
        for (attempt in 0..1) {
            val fresh = attempt > 0
            val stream = extractionGate.withLock {
                runCatching {
                    runInterruptible(Dispatchers.IO) { resolveYouTubeAudioStream(ytUrl, maxKbps, fresh) }
                }.onFailure { Log.w(TAG, "${meta.title}: stream extract failed (fresh=$fresh): ${it.message}") }.getOrNull()
            } ?: return null

            val ok = try {
                fetchBitChordChunks(trackId, stream.url, out)
            } catch (e: StreamExpiredException) {
                Log.w(TAG, "${meta.title}: stream HTTP ${e.code} on attempt $attempt, re-resolving")
                false
            } catch (e: IOException) {
                Log.w(TAG, "${meta.title}: download IO failed on attempt $attempt: ${e.message}")
                false
            }
            if (ok && out.length() >= 10_000L) {
                Log.i(TAG, "${meta.title}: saved ${stream.sourceLabel}")
                return stream
            }
        }
        return null
    }

    /**
     * Resolves and downloads a temporary audio file for immediate local fallback playback
     * or queue preloading.
     */
    suspend fun fetchFallbackAudioFile(trackId: String, title: String, artist: String, durationMs: Long): File? {
        if (title.isBlank()) return null
        val dir = File(Graph.app.cacheDir, "playback_fallback").apply { mkdirs() }
        dir.listFiles()?.firstOrNull { it.name.startsWith("$trackId.") && it.length() >= 10_000L }?.let {
            it.setLastModified(System.currentTimeMillis())
            return it
        }
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(15)?.forEach { runCatching { it.delete() } }

        val meta = Meta(title = title, artist = artist, album = "", albumId = "", durationMs = durationMs, coverUrl = "", isrc = null)
        val tmp = File(dir, "$trackId.tmp")
        val resolved = runCatching {
            resolveAndFetchBitChord(trackId, meta, 320, tmp)
        }.getOrNull() ?: run {
            tmp.delete()
            return null
        }
        val finalFile = File(dir, "$trackId.${resolved.ext}")
        if (finalFile.exists()) finalFile.delete()
        return if (tmp.renameTo(finalFile)) finalFile else tmp.takeIf { it.exists() && it.length() >= 10_000L }
    }

    private val VERSION_WORDS = listOf(
        "remix", "live", "instrumental", "karaoke", "acapella",
        "cover", "sped up", "slowed", "reverb", "nightcore", "8d",
    )
    private val VERSION_REGEXES = VERSION_WORDS.associateWith { w ->
        Regex("""\b${Regex.escape(w)}\b""", RegexOption.IGNORE_CASE)
    }
    private val BRACKET_NOISE = Regex("""[\(\[][^\)\]]*?\b(?:official|video|audio|lyric|lyrics|visualizer|hd|hq|4k|mv|music video|explicit|clean|remaster(?:ed)?|anniversary|deluxe|edition|version|feat\.?|ft\.?|with\b)[^\)\]]*?[\)\]]""", RegexOption.IGNORE_CASE)
    private val HYPHEN_NOISE = Regex("""\s+-\s+(?:.*?\b(?:remaster(?:ed)?|radio edit|single version|album version|explicit|clean|deluxe|edition|anniversary|mono|stereo|from\b).*)$""", RegexOption.IGNORE_CASE)
    private val FEAT_TAIL = Regex("""\s+(?:feat\.?|ft\.?|featuring|with)\s+.*$""", RegexOption.IGNORE_CASE)
    private val PUNCT_CLEAN = Regex("""[^\p{L}\p{N}\s]""")
    private val MULTI_SPACE = Regex("""\s+""")

    private fun cleanNorm(s: String): String =
        PUNCT_CLEAN.replace(s.lowercase(Locale.ROOT), " ").replace(MULTI_SPACE, " ").trim()

    private fun coreTitle(s: String): String =
        cleanNorm(FEAT_TAIL.replace(HYPHEN_NOISE.replace(BRACKET_NOISE.replace(s, ""), ""), ""))

    private fun matchYouTubeTrack(meta: Meta): String? {
        YouTubeSource.findSong(meta.title, meta.artist, meta.durationMs)?.let { return it.url }

        val primaryArtist = meta.artist.split(",", "&", "/", ";").firstOrNull()?.trim().orEmpty()
        val query = "${meta.title} $primaryArtist".trim()
        val songHits = runCatching {
            YouTubeSource.searchCandidates(query, YoutubeSearchQueryHandlerFactory.MUSIC_SONGS)
        }.getOrDefault(emptyList())

        pickBestBitChordHit(meta, songHits, minScore = 40, maxDurDiffSec = 35L)?.let { return it.url }

        YouTubeSource.findAudio(meta.title, meta.artist, meta.durationMs)?.let { return it.url }
        val videoHits = runCatching {
            YouTubeSource.searchCandidates("$query official audio", YoutubeSearchQueryHandlerFactory.VIDEOS)
        }.getOrDefault(emptyList())
        pickBestBitChordHit(meta, videoHits, minScore = 38, maxDurDiffSec = 45L)?.let { return it.url }

        val plainVideoHits = runCatching {
            YouTubeSource.searchCandidates(query, YoutubeSearchQueryHandlerFactory.VIDEOS)
        }.getOrDefault(emptyList())
        return pickBestBitChordHit(meta, plainVideoHits, minScore = 28, maxDurDiffSec = 90L)?.url
            ?: songHits.firstOrNull()?.url
            ?: videoHits.firstOrNull()?.url
    }

    private fun pickBestBitChordHit(meta: Meta, hits: List<YouTubeSource.Hit>, minScore: Int = 40, maxDurDiffSec: Long = 35L): YouTubeSource.Hit? {
        if (hits.isEmpty()) return null
        val tNorm = cleanNorm(meta.title)
        val tCore = coreTitle(meta.title)
        val targetArtists = meta.artist.split(",", "&", "/", ";").map { cleanNorm(it) }.filter { it.isNotEmpty() }
        val targetDurSec = meta.durationMs / 1000L

        var best: YouTubeSource.Hit? = null
        var bestScore = Int.MIN_VALUE

        for (c in hits) {
            val cNorm = cleanNorm(c.name)
            val cCore = coreTitle(c.name)
            val uNorm = cleanNorm(c.uploader)

            val badVersion = VERSION_REGEXES.any { (_, rx) ->
                rx.containsMatchIn(cNorm) && !rx.containsMatchIn(tNorm)
            }
            if (badVersion) continue
            if (targetDurSec > 0 && c.durationSec > 0 && abs(targetDurSec - c.durationSec) > maxDurDiffSec) continue

            val titleScore = when {
                cNorm == tNorm -> 45
                cCore.isNotEmpty() && cCore == tCore -> 42
                tCore.isNotEmpty() && (cCore.startsWith(tCore) || tCore.startsWith(cCore)) -> 34
                tCore.isNotEmpty() && (cCore.contains(tCore) || tCore.contains(cCore)) -> 26
                else -> {
                    val tTokens = tCore.split(" ").filter { it.length > 1 }.toSet()
                    val cTokens = cCore.split(" ").filter { it.length > 1 }.toSet()
                    if (tTokens.isEmpty() || cTokens.isEmpty()) 0
                    else (tTokens.intersect(cTokens).size * 24) / maxOf(tTokens.size, cTokens.size)
                }
            }
            if (titleScore < 12) continue

            val cleanUploader = uNorm.removeSuffix(" - topic").removeSuffix(" topic").trim()
            val primary = targetArtists.firstOrNull().orEmpty()
            var artistScore = when {
                primary.isNotEmpty() && cleanUploader == primary -> 30
                primary.isNotEmpty() && (cleanUploader.contains(primary) || primary.contains(cleanUploader)) -> 24
                primary.isNotEmpty() && cNorm.contains(primary) -> 16
                else -> 0
            }
            if (targetArtists.drop(1).any { cleanUploader.contains(it) || cNorm.contains(it) }) {
                artistScore = minOf(35, artistScore + 5)
            }

            val durScore = if (targetDurSec > 0 && c.durationSec > 0) {
                val diff = abs(targetDurSec - c.durationSec)
                when {
                    diff <= 1L -> 25
                    diff <= 3L -> 22
                    diff <= 5L -> 16
                    diff <= 10L -> 8
                    diff <= 20L -> 0
                    else -> -10
                }
            } else 5

            val total = titleScore + artistScore + durScore
            if (total > bestScore) {
                bestScore = total
                best = c
            }
        }
        return if (bestScore >= minScore) best else null
    }

    private fun resolveYouTubeAudioStream(ytUrl: String, maxKbps: Int, fresh: Boolean): ResolvedAudio? {
        val info = YouTubeSource.info(ytUrl, fresh = fresh)
        val streams = info.audioStreams.orEmpty()
        val progressive = streams.filter {
            it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.content.isNotBlank()
        }.ifEmpty {
            streams.filter { it.content.isNotBlank() }
        }
        if (progressive.isEmpty()) return null

        val capped = progressive.filter { it.averageBitrate in 1..maxKbps }.ifEmpty { progressive }
        val picked: AudioStream = if (maxKbps <= 96) {
            capped.sortedWith(
                compareByDescending<AudioStream> { it.format == MediaFormat.M4A }
                    .thenByDescending { it.averageBitrate }
            ).first()
        } else {
            capped.maxByOrNull { it.averageBitrate } ?: capped.first()
        }

        val ext = if (picked.format == MediaFormat.M4A) "m4a" else "webm"
        val kbps = picked.averageBitrate.takeIf { it > 0 } ?: 128
        val codec = if (ext == "m4a") "AAC" else "Opus"
        return ResolvedAudio(picked.content, ext, "$codec ${kbps}kbps")
    }

    private val SAAVN_DES_KEY = "38346591".toByteArray(Charsets.UTF_8)

    private fun matchJioSaavn320(meta: Meta): String? {
        val primaryArtist = meta.artist.split(",", "&", "/", ";").firstOrNull()?.trim().orEmpty()
        val q = "${meta.title} $primaryArtist".trim()
        val url = "https://www.jiosaavn.com/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("__call", "search.getResults")
            .addQueryParameter("_format", "json")
            .addQueryParameter("_marker", "0")
            .addQueryParameter("cc", "in")
            .addQueryParameter("includeMetaTags", "1")
            .addQueryParameter("q", q)
            .build().toString()
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", DownloadUa)
            .build()
        val body = InterruptibleHttp.read(YouTubeSource.http.newCall(req)) { res ->
            if (!res.isSuccessful) return@read ""
            res.body?.string().orEmpty()
        }
        if (body.isBlank()) return null
        val results = JSONObject(body).optJSONArray("results") ?: return null
        val tCore = coreTitle(meta.title)
        val pNorm = cleanNorm(primaryArtist)
        val targetDurSec = meta.durationMs / 1000L

        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val more = item.optJSONObject("more_info") ?: continue
            if (more.optString("320kbps") != "true") continue
            val encUrl = more.optString("encrypted_media_url")
            if (encUrl.isBlank()) continue
            val durSec = item.optString("duration").toLongOrNull() ?: more.optString("duration").toLongOrNull() ?: 0L
            if (targetDurSec > 0 && durSec > 0 && abs(targetDurSec - durSec) > 3L) continue

            val sTitle = coreTitle(item.optString("song").ifBlank { item.optString("title") })
            if (sTitle.isEmpty() || sTitle != tCore) continue

            val sArtists = cleanNorm(more.optString("singers") + " " + more.optString("primary_artists") + " " + item.optString("subtitle"))
            if (pNorm.isNotEmpty() && !sArtists.contains(pNorm)) continue

            return decryptSaavnUrl(encUrl, use320 = true)
        }
        return null
    }

    private fun decryptSaavnUrl(encryptedBase64: String, use320: Boolean): String? = runCatching {
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(SAAVN_DES_KEY, "DES"))
        val decoded = Base64.decode(encryptedBase64.trim(), Base64.DEFAULT)
        val decrypted = String(cipher.doFinal(decoded), Charsets.UTF_8).trim()
        if (use320) decrypted.replace("_96.mp4", "_320.mp4") else decrypted
    }.getOrNull()

    private enum class PlayerClient(
        val id: String,
        val userAgent: String,
        val origin: String? = null,
        val referer: String? = null,
        val defaultVersion: String,
        val maxRangeBytes: Long = 1024 * 1024L,
    ) {
        IOS("IOS", "com.google.ios.youtube/%s (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)", defaultVersion = "20.03.02"),
        ANDROID_VR("ANDROID_VR", "com.google.android.apps.youtube.vr.oculus/%s (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip", defaultVersion = "1.60.19", maxRangeBytes = 512 * 1024L),
        ANDROID_MUSIC("ANDROID_MUSIC", "com.google.android.apps.youtube.music/%s (Linux; U; Android 14; Pixel 8 Build/UQ1A.240205.004) gzip", defaultVersion = "7.27.52"),
        ANDROID("ANDROID", "com.google.android.youtube/%s (Linux; U; Android 14; Pixel 8 Build/UQ1A.240205.004) gzip", defaultVersion = "19.44.38"),
        WEB_REMIX("WEB_REMIX", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36", "https://music.youtube.com", "https://music.youtube.com/", "1.20250929.01.00"),
        WEB("WEB", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36", "https://www.youtube.com", "https://www.youtube.com/", "2.20250925.01.00"),
        TVHTML5("TVHTML5", "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version", "https://www.youtube.com", "https://www.youtube.com/tv", "7.20250924.13.00"),
        VISIONOS("VISIONOS", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15", "https://www.youtube.com", "https://www.youtube.com/", "0.1");

        fun renderUserAgent(cver: String?): String =
            if (userAgent.contains("%s")) userAgent.format(cver?.takeIf { it.isNotBlank() } ?: defaultVersion)
            else userAgent

        companion object {
            fun forStreamUrl(url: String): Pair<PlayerClient, String?> {
                val uri = runCatching { Uri.parse(url) }.getOrNull()
                val c = uri?.getQueryParameter("c")?.uppercase(Locale.ROOT)
                val cver = uri?.getQueryParameter("cver")
                val client = when (c) {
                    "IOS" -> IOS
                    "ANDROID_VR" -> ANDROID_VR
                    "ANDROID_MUSIC" -> ANDROID_MUSIC
                    "ANDROID", "ANDROID_TESTSUITE", "ANDROID_PRODUCER" -> ANDROID
                    "WEB_REMIX", "MWEB" -> WEB_REMIX
                    "TVHTML5", "TVHTML5_SIMPLY_EMBEDDED_PLAYER", "TVHTML5_SIMPLY" -> TVHTML5
                    "VISIONOS" -> VISIONOS
                    "WEB", "WEB_EMBEDDED_PLAYER", "WEB_CREATOR" -> WEB
                    else -> ANDROID_VR
                }
                return client to cver
            }
        }
    }

    private fun okhttp3.Request.Builder.dressForStream(url: String): okhttp3.Request.Builder {
        val (client, cver) = PlayerClient.forStreamUrl(url)
        header("User-Agent", client.renderUserAgent(cver))
        header("Accept", "*/*")
        header("Accept-Encoding", "identity")
        client.origin?.let { header("Origin", it) }
        client.referer?.let { header("Referer", it) }
        return this
    }

    private suspend fun fetchBitChordChunks(trackId: String, url: String, out: File): Boolean {
        val (client, _) = PlayerClient.forStreamUrl(url)
        val chunkSize = client.maxRangeBytes
        val urlClen = runCatching { Uri.parse(url).getQueryParameter("clen")?.toLongOrNull() }.getOrNull()
        var total = if (urlClen != null && urlClen > 0L) urlClen else runInterruptible(Dispatchers.IO) { probeRangeTotal(url) }

        return runInterruptible(Dispatchers.IO) {
            RandomAccessFile(out, "rw").use { raf ->
                raf.setLength(0)
                var offset = 0L
                val buf = ByteArray(64 * 1024)
                while (total <= 0L || offset < total) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    yieldToPlayer()
                    val endInclusive = if (total > 0L) minOf(offset + chunkSize - 1L, total - 1L) else offset + chunkSize - 1L
                    var attempt = 0
                    var done = false
                    while (true) {
                        attempt++
                        val req = okhttp3.Request.Builder()
                            .url(url)
                            .dressForStream(url)
                            .header("Range", "bytes=$offset-$endInclusive")
                            .build()
                        try {
                            InterruptibleHttp.read(YouTubeSource.http.newCall(req)) { res ->
                                val code = res.code
                                if (code == 416 && total <= 0L && offset > 0L) {
                                    done = true
                                    return@read
                                }
                                if (code == 403 || code == 404 || code == 410) {
                                    throw StreamExpiredException(code)
                                }
                                if (!res.isSuccessful) throw IOException("HTTP $code")
                                val fullBody = code != 206
                                if (fullBody) {
                                    raf.setLength(0)
                                    offset = 0L
                                }
                                if (total <= 0L) {
                                    total = contentRangeTotal(res.header("Content-Range"))
                                        ?: res.body?.contentLength()?.takeIf { it > 0L }
                                        ?: -1L
                                }
                                val body = res.body ?: throw IOException("Empty body")
                                raf.seek(offset)
                                var chunkRead = 0L
                                body.byteStream().use { input ->
                                    while (true) {
                                        val n = input.read(buf)
                                        if (n < 0) break
                                        raf.write(buf, 0, n)
                                        offset += n
                                        chunkRead += n
                                    }
                                }
                                if (total > 0L) {
                                    val p = (offset.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                                    scope.launch(Dispatchers.Main) {
                                        if (OfflineStore.status[trackId] is OfflineStatus.Downloading) {
                                            OfflineStore.status[trackId] = OfflineStatus.Downloading(p)
                                        }
                                    }
                                }
                                val expected = endInclusive - (offset - chunkRead) + 1L
                                if (fullBody || (total <= 0L && chunkRead < expected)) {
                                    done = true
                                }
                            }
                            break
                        } catch (e: StreamExpiredException) {
                            throw e
                        } catch (e: IOException) {
                            if (attempt >= 3) throw e
                            Log.w(TAG, "range chunk at $offset failed (attempt $attempt): ${e.message}")
                        }
                    }
                    if (done) break
                }
            }
            true
        }
    }

    private fun probeRangeTotal(url: String): Long = runCatching {
        val req = okhttp3.Request.Builder()
            .url(url)
            .dressForStream(url)
            .header("Range", "bytes=0-0")
            .build()
        InterruptibleHttp.read(YouTubeSource.http.newCall(req)) { res ->
            contentRangeTotal(res.header("Content-Range")) ?: -1L
        }
    }.getOrDefault(-1L)

    /**
     * A STREAMINFO read on the saved file: anything that is not a FLAC file (an encrypted or
     * AAC stream) is refused, a preview or another cut is caught (the header states the real
     * length), and a misplaced 24-bit label shows up in the log.
     */
    private fun checkFlacHeader(file: File, meta: Meta, bitDepth: Int?) {
        val seconds = runCatching {
            file.inputStream().use { s ->
                val head = ByteArray(42); var off = 0
                while (off < head.size) { val n = s.read(head, off, head.size - off); if (n < 0) break; off += n }
                flacDurationSec(head)
            }
        }.getOrNull() ?: throw Unavailable("the source did not send a FLAC file")
        checkLength(seconds.toLong(), meta)
        Log.d(TAG, "${meta.title}: FLAC ${seconds}s header check, labelled ${bitDepth ?: "-"}-bit")
    }

    /** An M4A / MP3 as served: the phone's own extractor must find an audio track of the song's length. */
    private fun checkAudio(file: File, meta: Meta) {
        val extractor = android.media.MediaExtractor()
        val seconds = try {
            extractor.setDataSource(file.path)
            val format = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw Unavailable("the source did not send an audio file")
            if (format.containsKey(android.media.MediaFormat.KEY_DURATION)) format.getLong(android.media.MediaFormat.KEY_DURATION) / 1_000_000 else -1L
        } catch (e: IOException) {
            throw Unavailable("the file could not be read: ${e.message}")
        } finally {
            extractor.release()
        }
        // No length in the track's header (some MP3 streams): the file's own metadata is asked next.
        val known = seconds.takeIf { it > 0 } ?: runCatching {
            android.media.MediaMetadataRetriever().let { r ->
                try {
                    r.setDataSource(file.path)
                    r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.div(1000)
                } finally { r.release() }
            }
        }.getOrNull()?.takeIf { it > 0 }
        if (known != null) checkLength(known, meta)
        else Log.w(TAG, "${meta.title}: length of the file unknown, not compared with the song's")
    }

    private fun checkLength(seconds: Long, meta: Meta) {
        lengthMismatch(seconds, meta.durationMs)?.let { throw Unavailable("backed out: $it") }
    }

    private data class Meta(
        val title: String, val artist: String, val album: String, val albumId: String,
        val durationMs: Long, val coverUrl: String, val isrc: String?,
    )

    /** The request, completed by Spotify's track (ISRC, and anything the list lacked). */
    private suspend fun spotifyMeta(r: Request): Meta {
        val api = app.myspoti.host.android.MyspotiRuntime.api
        val uri = "spotify:track:${r.trackId}"
        val t = runCatching { api.catalog.track(uri) }.onFailure { Log.w(TAG, "${r.title}: Spotify track: $it") }.getOrNull()
        val isrc = runCatching { api.catalog.isrc(uri) }.onFailure { Log.w(TAG, "${r.title}: ISRC: $it") }.getOrNull()
        val cover = r.coverUrl.ifBlank { t?.album?.images?.maxByOrNull { it.width ?: 0 }?.url.orEmpty() }
        return Meta(
            title = r.title.ifBlank { t?.name.orEmpty() },
            artist = r.artist.ifBlank { t?.artists?.joinToString(", ") { it.name }.orEmpty() },
            album = r.album.ifBlank { t?.album?.name.orEmpty() },
            albumId = r.albumId.ifBlank { t?.album?.uri?.substringAfterLast(':').orEmpty() },
            durationMs = r.durationMs.takeIf { it > 0 } ?: t?.durationMs ?: 0L,
            coverUrl = cover,
            isrc = isrc?.let(::normalizeIsrc),
        ).also { if (it.title.isBlank() || it.artist.isBlank()) throw Unavailable("no track metadata") }
    }

    private suspend fun httpGet(url: String, out: File) = runInterruptible {
        InterruptibleHttp.read(YouTubeSource.http.newCall(okhttp3.Request.Builder().url(url).build())) { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
            val body = res.body ?: throw IOException("empty body")
            out.outputStream().use { body.byteStream().copyTo(it) }
        }
    }

    /**
     * Downloads the file URL into [out] with HTTP **Range** requests in chunks,
     * reporting progress: a plain streaming GET with a browser User-Agent and the
     * extension's headers, retried per chunk on resets.
     */
    private suspend fun fetchWithProgress(trackId: String, url: String, out: File, headers: Map<String, String> = emptyMap()): Boolean {
        // A quick HEAD for the size: the progress bar needs it (-1 when the server says nothing).
        val total = runInterruptible(Dispatchers.IO) { contentLength(url, headers) }
        val watcher = if (total > 0) scope.launch {
            while (isActive) {
                setStatus(trackId, OfflineStatus.Downloading((out.length().toFloat() / total).coerceIn(0f, 1f)))
                delay(400)
            }
        } else null
        try {
            return runInterruptible(Dispatchers.IO) { rangedDownload(url, out, total, headers) }
        } finally {
            watcher?.cancel()
            setStatus(trackId, OfflineStatus.Downloading(-1f))
        }
    }

    /**
     * The byte range loop, shared with [PodcastDownloader]: a publisher's MP3 is fetched the
     * same way (follows the 302s to podtrac/radiofrance/simplecast, restarts from zero when a
     * server ignores Range, retries each chunk three times).
     */
    internal fun rangedDownload(url: String, out: File, knownTotal: Long, headers: Map<String, String> = emptyMap()): Boolean {
        val chunk = 4L * 1024 * 1024 // 4 MB: the stream gets its turn between two chunks
        var total = knownTotal
        java.io.RandomAccessFile(out, "rw").use { output ->
            output.setLength(0)
            while (true) {
                // A chunk is [start, end]; a retry resumes where the bytes stopped, never re-appends.
                val end = if (total > 0) minOf(output.length() + chunk - 1, total - 1) else output.length() + chunk - 1
                var done = false
                var attempt = 0
                yieldToPlayer()
                while (true) {
                    attempt++
                    val position = output.length()
                    // The safe client (see MediaHttp): redirects followed by hand and re-checked, the headers kept on their own host only.
                    try {
                        MediaHttp.open(url, headers = headers, fixed = mapOf("User-Agent" to DownloadUa, "Range" to "bytes=$position-$end"), timeoutMs = 60_000).use { conn ->
                            val code = conn.response.code
                            // Asked past the end of a file whose size was unknown: it is complete.
                            if (code == 416 && total <= 0 && position > 0) { done = true; return@use }
                            if (code !in 200..299) throw IOException("stream HTTP $code")
                            val full = code != 206 // server ignored Range → the whole body, from byte 0
                            if (full) output.setLength(0)
                            // A part must be the one asked for: bytes from elsewhere in the file would be
                            // written at the wrong place and make a file that plays wrong.
                            if (!full) {
                                val span = contentRangeSpan(conn.response.header("Content-Range"))
                                if (span == null || span.first != position || span.last > end)
                                    throw IOException("stream sent ${conn.response.header("Content-Range") ?: "no Content-Range"} for bytes $position-$end")
                            }
                            if (total <= 0) total = contentRangeTotal(conn.response.header("Content-Range")) ?: -1L
                            output.seek(output.length())
                            val expected = if (full) -1L else end - position + 1
                            var got = 0L
                            conn.response.body.byteStream().use { input ->
                                val buf = ByteArray(256 * 1024)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    // Never more than the part asked for, whatever the server sends after it.
                                    val keep = if (expected >= 0) minOf(n.toLong(), expected - got).toInt() else n
                                    output.write(buf, 0, keep)
                                    got += keep
                                    if (expected in 0..got) break
                                }
                            }
                            if (full) done = true
                            // A short chunk with the size still unknown is the end of the stream.
                            else if (total <= 0 && got < expected) done = true
                        }
                        break
                    } catch (e: IOException) {
                        if (attempt >= 3 || e is app.myspoti.extensions.ExtensionMediaHttp.Refused || app.myspoti.extensions.SafeNet.isRefusal(e)) throw e
                        Log.w(TAG, "chunk at ${output.length()} failed (attempt $attempt): ${e.message}")
                    }
                }
                if (done) break
                if (total in 1..output.length()) break
            }
        }
        return true
    }

    /**
     * The song playing here is loading: downloads wait (up to 30 s) so they do not starve
     * it, or the player's stall watchdog would take it for stuck and skip it.
     */
    private fun yieldToPlayer() {
        var waited = 0
        while (waited < 30_000 && playerStarving()) {
            Thread.sleep(500); waited += 500
        }
    }

    private fun playerStarving(): Boolean = runCatching {
        com.music.spotui.di.SongPlayer.isPlaying() && com.music.spotui.di.SongPlayer.isBuffering &&
            com.music.spotui.di.SongPlayer.activeDevice.value == null && !com.music.spotui.di.LocalPlayer.active.value
    }.getOrDefault(false)

    private fun contentLength(url: String, headers: Map<String, String> = emptyMap()): Long =
        runCatching {
            MediaHttp.open(url, "HEAD", headers, mapOf("User-Agent" to DownloadUa)).use { conn ->
                conn.response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 } ?: -1L
            }
        }.getOrDefault(-1L)

    // ── Pure helpers (unit-tested) ──

    /** File hosts expect a browser-ish User-Agent on downloads. */
    internal const val DownloadUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** Length from a FLAC STREAMINFO header, or null if [head] is not one. */
    internal fun flacDurationSec(head: ByteArray): Int? {
        if (head.size < 26 || String(head, 0, 4, Charsets.US_ASCII) != "fLaC") return null
        // Bytes 18..25: sample rate (20 bits), channels (3), bits/sample (5), total samples (36).
        var bits = 0L
        for (i in 18..25) bits = (bits shl 8) or (head[i].toLong() and 0xFF)
        val sampleRate = (bits ushr 44).toInt()
        val totalSamples = bits and 0xFFFFFFFFFL
        if (sampleRate <= 0 || totalSamples <= 0) return null
        return (totalSamples / sampleRate).toInt()
    }

    /** Why a file of [seconds] is not the song of [durationMs], or null when it is (or the song's length is unknown). */
    internal fun lengthMismatch(seconds: Long, durationMs: Long): String? {
        val expected = durationMs / 1000
        return when {
            expected <= 0 -> null
            seconds < expected - SHORTER_MAX -> "the file is ${seconds}s, the song ${expected}s (a preview)"
            seconds > expected + LONGER_MAX -> "the file is ${seconds}s, the song ${expected}s (another version)"
            else -> null
        }
    }

    /** "bytes 0-99/1234" → 1234; null when absent or "*". */
    /** "bytes 100-199/1234" → 100..199; null when absent or not that shape. */
    fun contentRangeSpan(header: String?): LongRange? {
        val m = Regex("""^\s*bytes\s+(\d+)-(\d+)/(\d+|\*)\s*$""", RegexOption.IGNORE_CASE).find(header ?: return null) ?: return null
        val first = m.groupValues[1].toLongOrNull() ?: return null
        val last = m.groupValues[2].toLongOrNull() ?: return null
        return if (last >= first) first..last else null
    }

    fun contentRangeTotal(header: String?): Long? =
        header?.substringAfterLast('/', "")?.trim()?.toLongOrNull()?.takeIf { it > 0 }

    /** "US-UM7-17-03861" / "usum71703861" → "USUM71703861"; null when not an ISRC. */
    fun normalizeIsrc(raw: String): String? =
        raw.uppercase().filter { it.isLetterOrDigit() }.takeIf { Regex("[A-Z]{2}[A-Z0-9]{3}\\d{7}").matches(it) }
}
