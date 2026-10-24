package com.music.spotui.data.offline

import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.DownloadQuality
import com.music.spotui.data.preferences.isLosslessEnabled
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Downloads songs for offline listening using BitChord's download mechanism:
 *
 * 1. **TrackMatcher**: Matches Spotify metadata (`title`, `artist`, `album`, `durationMs`)
 *    against YouTube Music `SONGS` (and JioSaavn 320kbps AAC/MP4 when available), rejecting
 *    version mismatches (`remix`, `live`, `instrumental`, `karaoke`, `acapella`, `cover`,
 *    `sped up`, `slowed`, `reverb`, `nightcore`, `8d`, etc. unless present in the target title)
 *    and scoring by title core, artist overlap, and tight duration match.
 * 2. **StreamResolver**: Serializes NewPipeExtractor `StreamInfo` extraction via `extractionGate`
 *    so Rhino JS deobfuscation never contends across download workers, selecting progressive
 *    M4A (`.m4a`) or WebM/Opus (`.webm`) audio streams according to the chosen quality.
 * 3. **PlayerClient + Range-Chunked Downloader**: Recovers the minting client (`IOS`,
 *    `ANDROID_VR`, `ANDROID_MUSIC`, `ANDROID`, `WEB_REMIX`, `WEB`, `TVHTML5`, `VISIONOS`) from
 *    the `googlevideo.com` URL's `c=` and `cver=` query params, attaches its exact `User-Agent`,
 *    `Origin`, and `Referer` headers, and downloads in bounded range chunks (`1 MB`, or `512 KB`
 *    for `ANDROID_VR`) to bypass `googlevideo.com` continuous-GET throttling, re-resolving once
 *    on HTTP 403/404/410.
 */
object OfflineDownloader {
    private const val TAG = "Offline"
    private const val WORKERS = 3

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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Songs queued, downloading, or whose download did not succeed (for the Downloaded list). */
    val requests: SnapshotStateMap<String, Request> = mutableStateMapOf()
    private val queue = Channel<Request>(Channel.UNLIMITED)
    /** Requests not finished yet, in order (persisted: a restart resumes them). */
    private val pending = LinkedHashMap<String, Request>()
    private val running = ConcurrentHashMap<String, Job>()
    /** BitChord extraction gate: serializes NewPipe Rhino JS signature deobfuscation. */
    private val extractionGate = Mutex()
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
        val saved = runCatching {
            val a = JSONArray(OfflineStore.pendingFile.readText())
            (0 until a.length()).map { Request.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        if (saved.isNotEmpty()) enqueueAll(saved)
    }

    /**
     * Downloads [songs] in [quality] (asked when the setting is "ask each time"), else in
     * the quality chosen in Settings. No SpotiFLAC verification gate is required.
     */
    fun enqueue(
        songs: List<SongsModel>,
        quality: DownloadQuality? = null,
        verifiedOrSkipped: Boolean = false,
        /** Started by the app (a downloaded list keeping in step), not by a tap. */
        auto: Boolean = false,
    ) {
        if (!isLosslessEnabled(Graph.app)) {
            scope.launch(Dispatchers.Main) {
                android.widget.Toast.makeText(Graph.app, app.myspoti.engine.R.string.downloads_off_toast, android.widget.Toast.LENGTH_LONG).show()
            }
            return
        }
        if (auto) {
            if (com.music.spotui.data.DataSaver.metered || !OfflineStore.isOnline(Graph.app)) {
                OfflineCollections.waitForWifi()
                return
            }
        } else {
            if (com.music.spotui.data.DataSaver.metered) scope.launch(Dispatchers.Main) {
                android.widget.Toast.makeText(Graph.app, app.myspoti.engine.R.string.download_on_mobile_data, android.widget.Toast.LENGTH_LONG).show()
            }
        }
        val q = quality?.let { DownloadQuality.saved(it.name) }?.name.orEmpty()
        enqueueAll(songs.filter { it.spotifyTrackId.isNotBlank() && !isEpisodeKey(it.spotifyTrackId) }.map { Request.of(it).copy(quality = q) })
    }

    private fun enqueueAll(list: List<Request>) {
        val added = synchronized(pending) {
            list.filter { r ->
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

    /** Stops a queued or running download (and forgets a failed one). */
    fun cancel(trackId: String) {
        synchronized(pending) { if (pending.remove(trackId) != null) savePending() }
        running.remove(trackId)?.cancel()
        OfflineStore.status.remove(trackId)
        requests.remove(trackId)
    }

    /**
     * Stops every running and queued download and empties the queue. Unlike [removeAll], it
     * deletes nothing: finished songs stay in [OfflineStore.tracks] and in the index.
     */
    fun stopAll() {
        val ids = synchronized(pending) { pending.keys.toList().also { pending.clear(); savePending() } }
        running.values.forEach { it.cancel() }
        running.clear()
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
        scope.launch { OfflineStore.remove(trackId) }
    }

    fun removeAll() {
        val ids = synchronized(pending) { pending.keys.toList().also { pending.clear(); savePending() } }
        ids.forEach { running.remove(it)?.cancel() }
        running.values.forEach { it.cancel() }
        requests.clear()
        scope.launch { OfflineCollections.removeAll(); OfflineStore.removeAll() }
    }

    private fun savePending() {
        runCatching {
            val a = JSONArray()
            pending.values.forEach { a.put(it.toJson()) }
            OfflineStore.pendingFile.writeText(a.toString())
        }
    }

    private suspend fun setStatus(id: String, s: OfflineStatus?) = withContext(Dispatchers.Main) {
        if (s == null) OfflineStore.status.remove(id) else OfflineStore.status[id] = s
    }

    private suspend fun work(r: Request) {
        if (synchronized(pending) { !pending.containsKey(r.trackId) }) return
        while (com.music.spotui.data.DataSaver.active) {
            delay(5_000)
            if (synchronized(pending) { !pending.containsKey(r.trackId) }) return
        }
        val job = scope.launch {
            val outcome: OfflineStatus? = try {
                setStatus(r.trackId, OfflineStatus.Downloading(-1f))
                val t = download(r)
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
        if (!isLosslessEnabled(Graph.app)) throw Unavailable("downloads are off")
        val quality = qualityOf(r)
        val maxKbps = if (quality == DownloadQuality.SMALL) 96 else Int.MAX_VALUE
        val tmp = OfflineStore.tmpDir
        val audioTmp = File(tmp, "${r.trackId}.part")
        try {
            val meta = spotifyMeta(r)
            currentCoroutineContext().ensureActive()

            val resolved = resolveAndFetchBitChord(r.trackId, meta, maxKbps, audioTmp)
                ?: throw Unavailable("no matching audio stream found")
            currentCoroutineContext().ensureActive()

            val out = File(OfflineStore.audioDir, "${r.trackId}.${resolved.ext}")
            if (out.exists()) out.delete()
            if (!audioTmp.renameTo(out)) {
                audioTmp.copyTo(out, overwrite = true)
                audioTmp.delete()
            }

            return withContext(NonCancellable) {
                runInterruptible(Dispatchers.IO) { OfflineCrypto.encryptInPlace(Graph.app, out) }
                val cover = runCatching {
                    val c = File(OfflineStore.coverDir, "${r.trackId}.jpg")
                    httpGet(meta.coverUrl, c); c.name
                }.getOrDefault("")
                OfflineTrack(
                    trackId = r.trackId, title = meta.title, artist = meta.artist, album = meta.album,
                    albumId = meta.albumId, durationMs = meta.durationMs, coverUrl = meta.coverUrl,
                    file = out.name, cover = cover, source = resolved.sourceLabel, verified = true,
                    bytes = out.length(), addedAt = System.currentTimeMillis(),
                )
            }
        } finally {
            audioTmp.delete()
        }
    }

    private data class ResolvedAudio(val url: String, val ext: String, val sourceLabel: String)

    /**
     * BitChord's download pipeline:
     * 1. For high quality (`maxKbps > 160`), first tries JioSaavn 320kbps AAC/MP4 if an exact
     *    title+artist+duration (`<= 3s`) match with `320kbps == "true"` is available.
     * 2. Otherwise resolves the best YouTube Music `SONGS` candidate using BitChord's
     *    `TrackMatcher` scoring (with fallback to official audio videos), extracts its best
     *    progressive audio stream (`M4A` or `WEBMA_OPUS`) under `extractionGate`, and downloads
     *    it in bounded range chunks with `PlayerClient` header dressing.
     * 3. If the CDN returns HTTP 403/404/410 during chunked download, evicts the cached
     *    `StreamInfo` and re-resolves once, just as BitChord's `Downloader.download` does.
     */
    private suspend fun resolveAndFetchBitChord(
        trackId: String,
        meta: Meta,
        maxKbps: Int,
        out: File,
    ): ResolvedAudio? {
        // 1. Try JioSaavn 320kbps AAC/MP4 when high quality is requested
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

        // 2. Match against YouTube Music SONGS + fallback to YouTube official audio via BitChord TrackMatcher
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

    private class StreamExpiredException(val code: Int) : IOException("Stream HTTP $code")

    // ── BitChord TrackMatcher (YouTube Music SONGS + official audio fallback) ──

    private val VERSION_WORDS = listOf(
        "remix", "live", "instrumental", "karaoke", "acapella",
        "cover", "sped up", "slowed", "reverb", "nightcore", "8d",
    )
    private val BRACKET_NOISE = Regex("""[\(\[][^\)\]]*?\b(?:official|video|audio|lyric|lyrics|visualizer|hd|hq|4k|mv|music video|explicit|clean|remaster(?:ed)?|anniversary|deluxe|edition|version|feat\.?|ft\.?)[^\)\]]*?[\)\]]""", RegexOption.IGNORE_CASE)
    private val FEAT_TAIL = Regex("""\s+(?:feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)
    private val PUNCT_CLEAN = Regex("""[^\p{L}\p{N}\s]""")
    private val MULTI_SPACE = Regex("""\s+""")

    private fun cleanNorm(s: String): String =
        PUNCT_CLEAN.replace(s.lowercase(Locale.ROOT), " ").replace(MULTI_SPACE, " ").trim()

    private fun coreTitle(s: String): String =
        cleanNorm(FEAT_TAIL.replace(BRACKET_NOISE.replace(s, ""), ""))

    private fun matchYouTubeTrack(meta: Meta): String? {
        // First try Spotifusion's strict 3s YouTube Music song finder
        YouTubeSource.findSong(meta.title, meta.artist, meta.durationMs)?.let { return it.url }

        // Next run BitChord's full TrackMatcher over YouTube Music SONGS
        val primaryArtist = meta.artist.split(",", "&", "/", ";").firstOrNull()?.trim().orEmpty()
        val query = "${meta.title} $primaryArtist".trim()
        val songHits = runCatching {
            YouTubeSource.searchCandidates(query, YoutubeSearchQueryHandlerFactory.MUSIC_SONGS)
        }.getOrDefault(emptyList())

        pickBestBitChordHit(meta, songHits)?.let { return it.url }

        // Fallback to official audio on YouTube VIDEOS (still filtered by BitChord's version/duration rules)
        YouTubeSource.findAudio(meta.title, meta.artist, meta.durationMs)?.let { return it.url }
        val videoHits = runCatching {
            YouTubeSource.searchCandidates("$query official audio", YoutubeSearchQueryHandlerFactory.VIDEOS)
        }.getOrDefault(emptyList())
        return pickBestBitChordHit(meta, videoHits)?.url
    }

    private fun pickBestBitChordHit(meta: Meta, hits: List<YouTubeSource.Hit>): YouTubeSource.Hit? {
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

            // Hard filter: reject version mismatches (remix, live, sped up, slowed, etc.)
            val badVersion = VERSION_WORDS.any { w ->
                (cNorm.contains(w) || uNorm.contains(w)) && !tNorm.contains(w)
            }
            if (badVersion) continue

            // Hard filter: reject > 30s duration difference when both durations are known
            if (targetDurSec > 0 && c.durationSec > 0 && abs(targetDurSec - c.durationSec) > 30L) continue

            // Score title (0..45)
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
            if (titleScore < 15) continue

            // Score artist (0..35)
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

            // Score duration (-20..25)
            val durScore = if (targetDurSec > 0 && c.durationSec > 0) {
                val diff = abs(targetDurSec - c.durationSec)
                when {
                    diff <= 1L -> 25
                    diff <= 3L -> 22
                    diff <= 5L -> 16
                    diff <= 10L -> 8
                    diff <= 20L -> 0
                    else -> -15
                }
            } else 5

            val total = titleScore + artistScore + durScore
            if (total > bestScore) {
                bestScore = total
                best = c
            }
        }
        return if (bestScore >= 45) best else null
    }

    // ── BitChord StreamResolver (NewPipe progressive audio stream selection) ──

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
            // Smallest quality: prefer M4A around 48-96kbps, or highest under cap
            capped.sortedWith(
                compareByDescending<AudioStream> { it.format == MediaFormat.M4A }
                    .thenByDescending { it.averageBitrate }
            ).first()
        } else {
            // High quality: prefer highest bitrate Opus/M4A
            capped.maxByOrNull { it.averageBitrate } ?: capped.first()
        }

        val ext = if (picked.format == MediaFormat.M4A) "m4a" else "webm"
        val kbps = picked.averageBitrate.takeIf { it > 0 } ?: 128
        val codec = if (ext == "m4a") "AAC" else "Opus"
        return ResolvedAudio(picked.content, ext, "$codec ${kbps}kbps")
    }

    // ── BitChord JioSaavn 320kbps AAC/MP4 Resolver ──

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
            .header("User-Agent", LosslessUa)
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

    // ── BitChord PlayerClient + Range-Chunked Fetcher ──

    private enum class PlayerClient(
        val id: String,
        val userAgent: String,
        val origin: String? = null,
        val referer: String? = null,
        val defaultVersion: String,
        /** ANDROID_VR throttles ranges >= 1 MB; 512 KB keeps it on the fast path. */
        val maxRangeBytes: Long = 1024 * 1024L,
    ) {
        IOS(
            id = "IOS",
            userAgent = "com.google.ios.youtube/%s (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)",
            defaultVersion = "20.03.02",
        ),
        ANDROID_VR(
            id = "ANDROID_VR",
            userAgent = "com.google.android.apps.youtube.vr.oculus/%s (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            defaultVersion = "1.60.19",
            maxRangeBytes = 512 * 1024L,
        ),
        ANDROID_MUSIC(
            id = "ANDROID_MUSIC",
            userAgent = "com.google.android.apps.youtube.music/%s (Linux; U; Android 14; Pixel 8 Build/UQ1A.240205.004) gzip",
            defaultVersion = "7.27.52",
        ),
        ANDROID(
            id = "ANDROID",
            userAgent = "com.google.android.youtube/%s (Linux; U; Android 14; Pixel 8 Build/UQ1A.240205.004) gzip",
            defaultVersion = "19.44.38",
        ),
        WEB_REMIX(
            id = "WEB_REMIX",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36",
            origin = "https://music.youtube.com",
            referer = "https://music.youtube.com/",
            defaultVersion = "1.20250929.01.00",
        ),
        WEB(
            id = "WEB",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36",
            origin = "https://www.youtube.com",
            referer = "https://www.youtube.com/",
            defaultVersion = "2.20250925.01.00",
        ),
        TVHTML5(
            id = "TVHTML5",
            userAgent = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version",
            origin = "https://www.youtube.com",
            referer = "https://www.youtube.com/tv",
            defaultVersion = "7.20250924.13.00",
        ),
        VISIONOS(
            id = "VISIONOS",
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15",
            origin = "https://www.youtube.com",
            referer = "https://www.youtube.com/",
            defaultVersion = "0.1",
        );

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

    /**
     * BitChord's range-chunked downloader:
     * - Reads `clen` from the URL query parameter (or probes `bytes=0-0` for `Content-Range`).
     * - Fetches in bounded range chunks (`client.maxRangeBytes`, `1 MB` or `512 KB` for `ANDROID_VR`)
     *   to stay on `googlevideo.com`'s initial-burst fast path (~5.7 MB/s instead of ~15 KB/s).
     */
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

    /** This download's quality: the one chosen when it started, else Settings (CD when Settings says "ask"). */
    private fun qualityOf(r: Request): DownloadQuality =
        DownloadQuality.saved(r.quality)
            ?: DownloadQuality.saved(com.music.spotui.data.preferences.getDownloadQuality(Graph.app).name)
            ?: DownloadQuality.CD

    private data class Meta(
        val title: String, val artist: String, val album: String, val albumId: String,
        val durationMs: Long, val coverUrl: String, val isrc: String?,
    )

    /** The request, completed by Spotify's track (ISRC, and anything the list lacked). */
    private suspend fun spotifyMeta(r: Request): Meta {
        val api = app.myspoti.host.android.MyspotiRuntime.api
        val uri = "spotify:track:${r.trackId}"
        val t = runCatching {
            if (r.title.isBlank() || r.artist.isBlank() || r.durationMs <= 0L || r.coverUrl.isBlank()) {
                api.catalog.track(uri)
            } else null
        }.onFailure { Log.w(TAG, "${r.title}: Spotify track: $it") }.getOrNull()
        val cover = r.coverUrl.ifBlank { t?.album?.images?.maxByOrNull { it.width ?: 0 }?.url.orEmpty() }
        return Meta(
            title = r.title.ifBlank { t?.name.orEmpty() },
            artist = r.artist.ifBlank { t?.artists?.joinToString(", ") { it.name }.orEmpty() },
            album = r.album.ifBlank { t?.album?.name.orEmpty() },
            albumId = r.albumId.ifBlank { t?.album?.uri?.substringAfterLast(':').orEmpty() },
            durationMs = r.durationMs.takeIf { it > 0 } ?: t?.durationMs ?: 0L,
            coverUrl = cover,
            isrc = null,
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
     * The byte range loop, shared with [PodcastDownloader]: a publisher's MP3 is fetched the
     * same way (follows the 302s to podtrac/radiofrance/simplecast, restarts from zero when a
     * server ignores Range, retries each chunk three times).
     */
    internal fun rangedDownload(url: String, out: File, knownTotal: Long): Boolean {
        val chunk = 4L * 1024 * 1024 // 4 MB: the stream gets its turn between two chunks
        var total = knownTotal
        RandomAccessFile(out, "rw").use { output ->
            output.setLength(0)
            while (true) {
                val end = if (total > 0) minOf(output.length() + chunk - 1, total - 1) else output.length() + chunk - 1
                var done = false
                var attempt = 0
                yieldToPlayer()
                while (true) {
                    attempt++
                    val position = output.length()
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 20_000
                        readTimeout = 60_000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", LosslessUa)
                        setRequestProperty("Range", "bytes=$position-$end")
                    }
                    try {
                        val code = conn.responseCode
                        if (code == 416 && total <= 0 && position > 0) { done = true; break }
                        if (code !in 200..299) throw IOException("stream HTTP $code")
                        val full = code != 206
                        if (full) output.setLength(0)
                        if (total <= 0) total = contentRangeTotal(conn.getHeaderField("Content-Range")) ?: -1L
                        output.seek(output.length())
                        val expected = if (full) -1L else end - position + 1
                        var got = 0L
                        conn.inputStream.use { input ->
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                got += n
                            }
                        }
                        if (full) done = true
                        else if (total <= 0 && got < expected) done = true
                        break
                    } catch (e: IOException) {
                        if (attempt >= 3) throw e
                        Log.w(TAG, "chunk at ${output.length()} failed (attempt $attempt): ${e.message}")
                    } finally {
                        conn.disconnect()
                    }
                }
                if (done) break
                if (total in 1..output.length()) break
            }
        }
        return true
    }

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

    // ── Pure helpers (unit-tested) ──

    internal const val LosslessUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

    /** "bytes 0-99/1234" → 1234; null when absent or "*". */
    fun contentRangeTotal(header: String?): Long? =
        header?.substringAfterLast('/', "")?.trim()?.toLongOrNull()?.takeIf { it > 0 }

    /** "US-UM7-17-03861" / "usum71703861" → "USUM71703861"; null when not an ISRC. */
    fun normalizeIsrc(raw: String): String? =
        raw.uppercase().filter { it.isLetterOrDigit() }.takeIf { Regex("[A-Z]{2}[A-Z0-9]{3}\\d{7}").matches(it) }

    data class PreviewHit(val url: String, val title: String, val artist: String, val durationSec: Long)

    private fun folded(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("""\p{M}+"""), "").lowercase()

    /** Title without "(feat. …)", "[Remastered]", " - Radio Edit"…, letters and digits only. */
    internal fun titleKey(s: String) = folded(s)
        .replace(Regex("""\(.*?\)|\[.*?]"""), " ")
        .replace(Regex("""\s+-\s+.*$"""), " ")
        .replace(Regex("""\b(?:feat|ft)\.?\s.*$"""), " ")
        .filter { it.isLetterOrDigit() }

    /** The search result that is the same song: title, main artist, and duration within 3 s. */
    fun pickPreview(results: List<PreviewHit>, title: String, artist: String, durationMs: Long): String? {
        if (durationMs <= 0) return null
        val t = titleKey(title)
        val a = folded(artist).filter { it.isLetterOrDigit() }
        return results.firstOrNull { h ->
            h.url.startsWith("http") && h.durationSec > 0 &&
                abs(h.durationSec * 1000 - durationMs) <= 3000 &&
                titleKey(h.title) == t && folded(h.artist).filter { it.isLetterOrDigit() } == a
        }?.url
    }
}
