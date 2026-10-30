package com.music.spotui.video

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.util.AtomicFile
import app.myspoti.power.EnergySettings
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.music.spotui.di.Graph
import com.music.spotui.di.SongPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import java.io.File
import kotlin.math.abs

/**
 * Music videos from YouTube, synced to the Spotify playback.
 *
 * Spotify keeps playing the audio (so plays count, as always); the official video
 * plays muted on top, at the same relative time. The song→video time map is found
 * once per track by aligning the YouTube Music song audio (same master as Spotify)
 * with the video's soundtrack ([AudioAlign]) and cached on disk.
 */
object MusicVideoController {
    private const val TAG = "MusicVideo"
    private const val MIN_SCORE = 0.4

    sealed interface State {
        data object Idle : State
        data object Searching : State
        data class Ready(
            val trackId: String, val videoUrl: String, val map: AudioAlign.TimeMap,
            val mapComplete: Boolean = false, internal val refinementToken: String? = null,
        ) : State
        data object Unavailable : State
    }

    /** Video state for the track on screen. */
    val state = mutableStateOf<State>(State.Idle)
    /** The user switched to the video for this track. */
    val on = mutableStateOf(false)
    val fullscreen = mutableStateOf(false)
    /** width / height of the playing video. */
    val aspect = mutableFloatStateOf(16f / 9f)

    // Observable: a surface composed before the player exists attaches when it appears.
    private val playerState = mutableStateOf<ExoPlayer?>(null)
    val player: ExoPlayer? get() = playerState.value

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var prepareJob: Job? = null
    private var syncJob: Job? = null
    private var refineJob: Job? = null
    private var surfaceIdleJob: Job? = null
    private val generation = java.util.concurrent.atomic.AtomicLong()
    private val prefetchSequence = java.util.concurrent.atomic.AtomicLong()
    private val refineLock = kotlinx.coroutines.sync.Mutex()
    private var currentTitle = ""
    private var currentArtist = ""
    private var currentDuration = 0L
    private data class Retained(val request: Long, val files: RefinementFiles)
    private val retained = java.util.concurrent.ConcurrentHashMap<String, Retained>()
    private var currentTrackId = ""
    private var errorRetryUsedFor: String? = null
    private var nextPrepareAt = 0L
    private val app: Context get() = Graph.app
    private val cacheLock = Any()

    // ── Track → video + time map (cached) ──

    // Bumped when the matching rules change, so earlier answers are looked up again.
    private const val CACHE_DIR = "music_videos18"
    private val cacheDir: File by lazy {
        // Answers of the earlier rules are dropped once.
        app.filesDir.listFiles { f -> f.isDirectory && f.name.startsWith("music_videos") && f.name != CACHE_DIR }
            ?.forEach { it.deleteRecursively() }
        File(app.filesDir, CACHE_DIR)
    }
    private fun cacheFile(trackId: String) = File(cacheDir, "$trackId.json").also { it.parentFile?.mkdirs() }

    private fun readCache(trackId: String): State? = synchronized(cacheLock) {
        runCatching {
            val f = cacheFile(trackId)
            if (!f.exists()) return@runCatching null
            val o = JSONObject(f.readText())
            if (o.optBoolean("none")) {
                return@runCatching if (System.currentTimeMillis() - o.optLong("at") < 7 * 86_400_000L)
                    State.Unavailable else null
            }
            val score = o.getDouble("score")
            require(score.isFinite() && score >= MIN_SCORE)
            val jsonSegments = o.getJSONArray("segments")
            val segments = (0 until jsonSegments.length()).map { i ->
                val s = jsonSegments.getJSONObject(i)
                AudioAlign.Segment(s.getLong("from"), s.getLong("to"), s.getLong("offset"))
            }
            require(segments.isNotEmpty())
            require(segments.all { it.songStartMs >= 0 && it.songEndMs > it.songStartMs })
            require(segments.zipWithNext().all { (a, b) -> a.songEndMs <= b.songStartMs })
            State.Ready(trackId, o.getString("video"), AudioAlign.TimeMap(segments, score), mapComplete = o.optBoolean("complete"))
        }.getOrElse {
            Log.w(TAG, "Ignoring invalid music video cache: ${it.javaClass.simpleName}")
            null
        }
    }

    private fun writeCache(trackId: String, s: State) {
        synchronized(cacheLock) {
            val o = JSONObject()
            when (s) {
                is State.Ready -> {
                    o.put("video", s.videoUrl).put("score", s.map.score).put("complete", s.mapComplete)
                    o.put("segments", JSONArray().apply {
                        s.map.segments.forEach { put(JSONObject().put("from", it.songStartMs).put("to", it.songEndMs).put("offset", it.offsetMs)) }
                    })
                }
                else -> o.put("none", true).put("at", System.currentTimeMillis())
            }
            runCatching {
                val file = AtomicFile(cacheFile(trackId))
                val stream = file.startWrite()
                try {
                    stream.write(o.toString().toByteArray(Charsets.UTF_8))
                    file.finishWrite(stream)
                } catch (t: Throwable) {
                    file.failWrite(stream)
                    throw t
                }
            }
        }
    }

    // Background look-ups for tracks not on screen yet (the next one), one at a time.
    private val prefetching = java.util.Collections.synchronizedSet(HashSet<String>())
    private val prefetchJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()


    /** Resolve and cache a track's video in the background, without touching the UI state. */
    fun prefetch(trackId: String, title: String, artist: String, durationMs: Long) {
        if (com.music.spotui.data.DataSaver.active || !appVisible || !EnergySettings.state.value.policy.allowBackgroundLookups || trackId.isBlank() || trackId == currentTrackId || !prefetching.add(trackId)) return
        val request = -prefetchSequence.incrementAndGet()
        val published = java.util.concurrent.atomic.AtomicBoolean(false)
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            if (!appVisible || !EnergySettings.state.value.policy.allowBackgroundLookups) return@launch
            try {
                val result = withContext(Dispatchers.IO) {
                    readCache(trackId) ?: resolve(trackId, title, artist, durationMs, request).also { if (it != null) writeCache(trackId, it) }
                }
                if (trackId == currentTrackId && state.value is State.Searching) {
                    if (result == null) nextPrepareAt = SystemClock.elapsedRealtime() + 30_000
                    state.value = result ?: State.Idle
                    published.set(result != null)
                    if (result is State.Ready) { preloadStream(result.videoUrl); resumeWanted() }
                }
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                if (trackId == currentTrackId && state.value is State.Searching) {
                    nextPrepareAt = SystemClock.elapsedRealtime() + 30_000
                    state.value = State.Idle
                }
                Log.w(TAG, "Video prefetch failed: ${t.javaClass.simpleName}")
            }
        }
        prefetchJobs[trackId] = job
        job.invokeOnCompletion {
            if (prefetchJobs.remove(trackId, job)) prefetching.remove(trackId)
            if (!published.get()) {
                discardRetained(request = request)
                scope.launch {
                    if (currentTrackId == trackId && state.value is State.Searching && prepareJob?.isActive != true && trackId !in prefetching) {
                        nextPrepareAt = 0L
                        state.value = State.Idle
                    }
                }
            }
        }
        job.start()
    }

    /** Find (or load) the video of the track now playing. Disk access stays on IO. */
    fun prepare(trackId: String, title: String, artist: String, durationMs: Long) {
        // Data saver on mobile data: no look-up (it downloads audio) and no video stream.
        if (trackId.isBlank() || !appVisible || com.music.spotui.data.DataSaver.active) return
        currentTitle = title; currentArtist = artist; currentDuration = durationMs
        if (trackId == currentTrackId && (state.value !is State.Idle || SystemClock.elapsedRealtime() < nextPrepareAt)) return
        if (currentTrackId != trackId) { errorRetryUsedFor = null; nextPrepareAt = 0L }
        val request = generation.incrementAndGet()
        currentTrackId = trackId
        prepareJob?.cancel()
        refineJob?.cancel()
        discardRetained()
        wantVideo = wantVideo || on.value
        if (on.value) { on.value = false; fullscreen.value = false; syncJob?.cancel() }
        warmedFor = null
        player?.let { it.stop(); it.clearMediaItems() }
        state.value = State.Searching
        if (trackId in prefetching) return
        prepareJob = scope.launch {
            var published = false
            try {
                val result = withContext(Dispatchers.IO) {
                    val cached = readCache(trackId)
                    if (cached != null) cached else {
                        val resolved = resolve(trackId, title, artist, durationMs, request)
                        currentCoroutineContext().ensureActive()
                        if (resolved != null) writeCache(trackId, resolved)
                        resolved
                    }
                }
                if (generation.get() != request || currentTrackId != trackId) return@launch
                if (result == null) {
                    nextPrepareAt = SystemClock.elapsedRealtime() + 30_000
                    state.value = State.Idle
                    return@launch
                }
                state.value = result
                published = true
                if (result is State.Ready) { preloadStream(result.videoUrl); resumeWanted() }
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                if (generation.get() == request) {
                    nextPrepareAt = SystemClock.elapsedRealtime() + 30_000
                    state.value = State.Idle
                }
                Log.w(TAG, "Video preparation failed: ${t.javaClass.simpleName}")
            } finally {
                if (!published) discardRetained(request = request)
            }
        }
    }

    // One look-up at a time (CPU / network).
    private val resolveLock = kotlinx.coroutines.sync.Mutex()

    /**
     * The track's official clip, or none:
     * - only videos YouTube files as official music videos (MUSIC_VIDEO_TYPE_OMV,
     *   published by the artist or the label), never fan uploads;
     * - not a still image (some "official" uploads are the cover over the song);
     * - its soundtrack really is this song: the start of the song (45 s) is found in
     *   the start of the clip (100 s), which also gives the time offset.
     * null = could not decide (network / decoding error): not cached, retried later.
     */
    private suspend fun resolve(trackId: String, title: String, artist: String, durationMs: Long, request: Long): State? = resolveLock.withLock {
        val t0 = SystemClock.elapsedRealtime()
        val token = java.util.UUID.randomUUID().toString()
        val refFile = File(app.cacheDir, "mv_ref_${trackId}_$token")
        val candidateFiles = mutableListOf<File>()
        try {
            coroutineScope {
                val song = async(Dispatchers.IO) {
                    runInterruptible { YouTubeSource.findSong(title, artist, durationMs) ?: YouTubeSource.findAudio(title, artist, durationMs) }
                }
                val candidates = withContext(Dispatchers.IO) {
                    YouTubeSource.videoCandidates(title, artist, durationMs)
                }.take(6)
                // No audio download until an official, moving clip needs verification.
                val referenceFeatures = async(Dispatchers.Default, start = kotlinx.coroutines.CoroutineStart.LAZY) {
                    val reference = song.await() ?: error("No reference audio")
                    downloadAudioFile(reference.url, refFile)
                    runInterruptible { AudioAlign.features(AudioAlign.decode(refFile, 45)) }
                }
                val result = firstVerifiedClip(candidates,
                    classify = { c -> withContext(Dispatchers.IO) { runInterruptible { YouTubeSource.musicVideoType(c.url) } } },
                    verify = { c ->
                        val candFile = File(app.cacheDir, "mv_cand_${trackId}_${candidateFiles.size}_$token")
                        candidateFiles.add(candFile)
                        try {
                            val info = withContext(Dispatchers.IO) { runInterruptible { YouTubeSource.info(c.url) } }
                            val trusted = YouTubeSource.isTrustedClip(info, artist)
                            if (!trusted) Log.i(TAG, "candidate ${info.name} (${info.uploaderName}, verified=${info.isUploaderVerified}): not the artist's clip")
                            val still = if (trusted) withContext(Dispatchers.IO) { runInterruptible { YouTubeSource.isStatic(info) } } else false
                            when {
                                !trusted -> ClipVerification<State>()
                                still == null -> ClipVerification<State>(failed = true)
                                still -> ClipVerification<State>()
                                song.await() == null -> ClipVerification(State.Unavailable)
                                song.await()?.url == c.url -> ClipVerification<State>()
                                else -> {
                                    referenceFeatures.start()
                                    downloadAudioFile(c.url, candFile)
                                    val candidate = withContext(Dispatchers.Default) {
                                        runInterruptible { AudioAlign.features(AudioAlign.decode(candFile, 100)) }
                                    }
                                    val reference = referenceFeatures.await()
                                    val map = withContext(Dispatchers.Default) {
                                        AudioAlign.align(reference, candidate)
                                    }
                                    Log.i(TAG, "candidate ${c.name} (${c.uploader}): score ${"%.2f".format(map.score)}")
                                    if (map.score >= MIN_SCORE && map.segments.isNotEmpty()) {
                                        val files = RefinementFiles.retain(refFile, candFile, app.cacheDir)
                                        val id = files?.reference?.name
                                        if (files != null && id != null) retained[id] = Retained(request, files)
                                        ClipVerification(State.Ready(trackId, c.url, map, refinementToken = id))
                                    } else ClipVerification<State>()
                                }
                            }
                        } catch (t: kotlinx.coroutines.CancellationException) {
                            throw t
                        } catch (t: Throwable) {
                            currentCoroutineContext().ensureActive()
                            Log.w(TAG, "candidate failed: ${t.javaClass.simpleName}")
                            ClipVerification<State>(failed = true)
                        } finally { candFile.delete() }
                    })
                // A LAZY reference job must not keep the scope alive when no clip was tested.
                referenceFeatures.cancel()
                song.cancel()
                Log.i(TAG, "$title: clip lookup ${SystemClock.elapsedRealtime() - t0} ms, found=${result.value is State.Ready}")
                result.value ?: if (result.failed) null else State.Unavailable
            }
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.w(TAG, "Video resolution failed: ${t.javaClass.simpleName}")
            null
        } finally {
            refFile.delete()
            candidateFiles.forEach { it.delete() }
        }
    }

    /** Delete only files not yet transferred to a running refinement. */
    private fun discardRetained(token: String? = null, request: Long? = null) {
        val keys = if (token != null) listOf(token) else retained.entries
            .filter { request == null || it.value.request == request }.map { it.key }
        val files = keys.mapNotNull { retained.remove(it)?.files }
        if (files.isNotEmpty()) scope.launch(Dispatchers.IO) { files.forEach { it.close() } }
    }

    /** Full FFT work starts only after the prefix has been cached and video is requested. */
    private fun ensureRefinement(s: State.Ready) {
        val current = state.value as? State.Ready ?: return
        if (current.trackId != s.trackId || current.videoUrl != s.videoUrl || current.mapComplete) return
        if (s.mapComplete || !on.value || !SongPlayer.isPlaying() || SongPlayer.isBuffering || !appVisible || surfaces <= 0 || SongPlayer.activeDevice.value != null || refineJob?.isActive == true) return
        val request = generation.get()
        val title = currentTitle
        val artist = currentArtist
        val duration = currentDuration
        refineJob = scope.launch {
            var files = s.refinementToken?.let { retained.remove(it)?.files }
            try {
                if (files == null) {
                    val target = RefinementFiles.empty(app.cacheDir)
                    files = target
                    withContext(Dispatchers.IO) {
                        val song = runInterruptible {
                            YouTubeSource.findSong(title, artist, duration) ?: YouTubeSource.findAudio(title, artist, duration)
                        } ?: error("No reference audio for refinement")
                        coroutineScope {
                            val reference = async { downloadAudioFile(song.url, target.reference) }
                            val candidate = async { downloadAudioFile(s.videoUrl, target.candidate) }
                            reference.await(); candidate.await()
                        }
                    }
                }
                val target = files ?: return@launch
                val full = refineLock.withLock { coroutineScope {
                    val reference = async(Dispatchers.Default) {
                        runInterruptible { AudioAlign.features(AudioAlign.decode(target.reference)) }
                    }
                    val candidate = async(Dispatchers.Default) {
                        runInterruptible { AudioAlign.features(AudioAlign.decode(target.candidate)) }
                    }
                    AudioAlign.align(reference.await(), candidate.await())
                } }
                currentCoroutineContext().ensureActive()
                if (full.score < MIN_SCORE || full.segments.isEmpty() || generation.get() != request) return@launch
                val result = s.copy(map = full, mapComplete = true, refinementToken = null)
                withContext(Dispatchers.IO) { writeCache(s.trackId, result) }
                val current = state.value as? State.Ready
                if (generation.get() == request && current?.trackId == s.trackId && current.videoUrl == s.videoUrl) state.value = result
                Log.i(TAG, "Full map ready: ${full.segments.size} segments, score ${full.score}")
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                Log.w(TAG, "Full alignment failed: ${t.javaClass.simpleName}")
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { files?.close() }
            }
        }
    }

    /**
     * Downloads a YouTube item's audio into [out]: AAC first (same decoder on both
     * sides cancels its delay), lowest bitrate first; a stream YouTube no longer
     * serves (empty / truncated) falls back to the next; a refusal (403) gets fresh URLs once.
     */
    internal suspend fun downloadAudioFile(url: String, out: File): File =
        try { downloadAudioFileOnce(url, out, runInterruptible(Dispatchers.IO) { YouTubeSource.info(url) }) } catch (t: Throwable) {
            currentCoroutineContext().ensureActive()
            if (t.message?.contains("403") != true) throw t
            downloadAudioFileOnce(url, out, runInterruptible(Dispatchers.IO) { YouTubeSource.info(url, fresh = true) })
        }

    private suspend fun downloadAudioFileOnce(url: String, out: File, info: org.schabi.newpipe.extractor.stream.StreamInfo): File {
        val streams = info.audioStreams
            .filter { it.format == MediaFormat.WEBMA_OPUS || it.format == MediaFormat.M4A }
            .sortedWith(compareBy({ if (it.format == MediaFormat.M4A) 0 else 1 }, { if (it.averageBitrate > 0) it.averageBitrate else Int.MAX_VALUE }))
        if (streams.isEmpty()) error("no audio stream")
        var last: Throwable? = null
        for (a in streams.take(3)) {
            try {
                fetchRanged(a.content, out)
                val expected = if (a.averageBitrate > 0 && info.duration > 0) a.averageBitrate * 1000L / 8 * info.duration else 0L
                if (out.length() < 50_000 || (expected > 0 && out.length() < expected * 0.6))
                    error("${out.length() / 1024} KB, expected ~${expected / 1024} KB")
                return out
            } catch (t: Throwable) { currentCoroutineContext().ensureActive(); last = t }
        }
        throw last ?: IllegalStateException("no usable audio")
    }

    /** googlevideo: `range=` requests of the exact announced length (`clen`). */
    private suspend fun fetchRanged(url: String, out: File) {
        RangeAudioDownload.fetch(YouTubeSource.http, url, out, streamHeaders(url))
    }

    // ── Playback ──
    //
    // Speed: the stream is resolved as soon as the track's video is known (cached ~5 h),
    // and buffered as soon as the Now Playing screen shows (paused, at the mapped time),
    // so "Passer à la vidéo" only attaches the surface and plays.

    private data class VideoSource(val url: String, val source: androidx.media3.exoplayer.source.MediaSource, val durationMs: Long, val at: Long, val maxHeight: Int)
    private val sources = java.util.concurrent.ConcurrentHashMap<String, VideoSource>()
    private val resolving = java.util.Collections.synchronizedSet(HashSet<String>())
    @Volatile private var warmedFor: String? = null
    @Volatile var videoDurationMs = 0L
        private set

    private fun freshSource(url: String): VideoSource? =
        sources[url]?.takeIf {
            SystemClock.elapsedRealtime() - it.at < 4 * 3_600_000L && it.maxHeight == EnergySettings.state.value.policy.videoMaxHeight
        }

    /** Resolve the playable stream of [url] in the background (no-op when cached). */
    // Callbacks waiting for a stream being resolved: a second request for the same
    // URL (warm-up, then "play it" on a track change) must not be dropped.
    private val waiting = HashMap<String, MutableList<() -> Unit>>()

    private fun preloadStream(url: String, fresh: Boolean = false, then: (() -> Unit)? = null) {
        if (!fresh && freshSource(url) != null) { then?.invoke(); return }
        then?.let { waiting.getOrPut(url) { mutableListOf() }.add(it) }
        if (!resolving.add(url)) return
        scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            val vs = withContext(Dispatchers.IO) {
                runCatching { buildSource(url, fresh) }.onFailure { Log.w(TAG, "video stream failed: ${it.message}") }.getOrNull()
            }
            resolving.remove(url)
            val cbs = waiting.remove(url).orEmpty()
            if (vs != null) {
                sources[url] = vs
                Log.i(TAG, "stream ready in ${SystemClock.elapsedRealtime() - t0} ms")
                cbs.forEach { it() }
            }
        }
    }

    /**
     * The Now Playing screen shows a track with a known video: resolve and buffer it
     * now (paused, muted, at the mapped time) so switching is immediate.
     */
    fun warm() {
        val s = state.value as? State.Ready ?: return
        if (SongPlayer.activeDevice.value != null || !EnergySettings.state.value.policy.allowBackgroundLookups) return
        preloadStream(s.videoUrl) {
            if (!appVisible || !EnergySettings.state.value.policy.allowBackgroundLookups || SongPlayer.activeDevice.value != null || warmedFor == s.videoUrl || on.value || s.trackId != currentTrackId) return@preloadStream
            val vs = freshSource(s.videoUrl) ?: return@preloadStream
            val p = ensurePlayer()
            p.setMediaSource(vs.source)
            p.prepare()
            p.seekTo(s.map.toVideo(SongPlayer.getCurrentPosition() + 400))
            p.playWhenReady = false
            warmedFor = s.videoUrl
            videoDurationMs = vs.durationMs
        }
    }

    /** The user is in video mode (kept across tracks and app switches). */
    @Volatile var wantVideo = false
        private set
    /** Surfaces showing the video right now (player screen, full screen). */
    @Volatile var surfaces = 0
        set(value) {
            field = value.coerceAtLeast(0)
            surfaceIdleJob?.cancel()
            surfaceIdleJob = null
            if (field > 0) {
                (state.value as? State.Ready)?.let(::ensureRefinement)
            } else {
                // A fullscreen handoff briefly detaches the old surface before the new one attaches.
                surfaceIdleJob = scope.launch {
                    delay(500)
                    if (surfaces == 0) refineJob?.cancel()
                }
            }
        }
    // Set by the activity's onStart / onStop; nothing is on screen before the first start.
    @Volatile private var appVisible = false
    /** The app is on screen (look-ups at track start then, not in the background). */
    val inForeground: Boolean get() = appVisible

    private var playerScreens = 0

    /** A player screen is showing: the one place a clip lookup pays off unprompted. */
    val hasPlayerScreen: Boolean get() = playerScreens > 0

    /** A composed player screen still requests clip lookup after an app switch. */
    @androidx.annotation.MainThread
    fun attachPlayerScreen() { playerScreens++ }

    @androidx.annotation.MainThread
    fun detachPlayerScreen() { playerScreens = (playerScreens - 1).coerceAtLeast(0) }

    private fun resumeWanted() {
        if (wantVideo && !on.value && SongPlayer.activeDevice.value == null) {
            val s = state.value as? State.Ready ?: return
            on.value = true
            start(s)
        }
    }

    fun setOn(value: Boolean) {
        val s = state.value
        if (value && s !is State.Ready) return
        com.music.spotui.di.Haptics.click()
        wantVideo = value
        on.value = value
        if (!value) { fullscreen.value = false; stop(); return }
        start(s as State.Ready)
    }

    private fun ensurePlayer(): ExoPlayer = player ?: ExoPlayer.Builder(app)
        .setLoadControl(
            androidx.media3.exoplayer.DefaultLoadControl.Builder()
                // Start fast: half a second of video is enough to show frames.
                .setBufferDurationsMs(4_000, 20_000, 400, 1_000)
                .setBackBuffer(5_000, true)
                .build()
        )
        .build().also { np ->
            playerState.value = np
            np.volume = 0f
            np.addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        aspect.floatValue = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    }
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    // Expired / refused URL (403): drop it and resolve again once.
                    Log.w(TAG, "playback error: ${error.message}")
                    val st = state.value as? State.Ready ?: return
                    sources.remove(st.videoUrl); warmedFor = null
                    if (!on.value || errorRetryUsedFor == st.trackId) { stop(); on.value = false; return }
                    errorRetryUsedFor = st.trackId
                    preloadStream(st.videoUrl, fresh = true) {
                        if (on.value && st.trackId == currentTrackId) start(st)
                    }
                }
            })
        }

    private fun start(s: State.Ready) {
        if (!appVisible || !on.value || s.trackId != currentTrackId || SongPlayer.activeDevice.value != null) return
        val t0 = SystemClock.elapsedRealtime()
        val vs = freshSource(s.videoUrl)
        if (vs == null) { preloadStream(s.videoUrl) { if (on.value && s.trackId == currentTrackId) start(s) }; return }
        val p = ensurePlayer()
        if (warmedFor != s.videoUrl || p.playbackState == Player.STATE_IDLE) {
            p.setMediaSource(vs.source)
            p.prepare()
            warmedFor = s.videoUrl
        }
        videoDurationMs = vs.durationMs
        p.seekTo(s.map.toVideo(SongPlayer.getCurrentPosition()))
        p.playWhenReady = SongPlayer.isPlaying() && !SongPlayer.isBuffering
        p.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                Log.i(TAG, "first frame ${SystemClock.elapsedRealtime() - t0} ms after the switch")
                p.removeListener(this)
            }
        })
        sync(p, s.map)
        ensureRefinement(s)
    }

    /** Headers googlevideo expects for the client the URL was made for (else 403). */
    fun streamHeaders(url: String): Map<String, String> {
        val loc = org.schabi.newpipe.extractor.localization.Localization.DEFAULT
        return when {
            YoutubeParsingHelper.isAndroidStreamingUrl(url) -> mapOf("User-Agent" to YoutubeParsingHelper.getAndroidUserAgent(loc))
            YoutubeParsingHelper.isIosStreamingUrl(url) -> mapOf("User-Agent" to YoutubeParsingHelper.getIosUserAgent(loc))
            YoutubeParsingHelper.isVisionOsStreamingUrl(url) -> mapOf("User-Agent" to YoutubeParsingHelper.getVisionOsUserAgent(loc))
            // Web client URLs: plain requests (a browser User-Agent gets empty answers).
            else -> emptyMap()
        }
    }

    private fun buildSource(url: String, fresh: Boolean = false): VideoSource {
        val t0 = SystemClock.elapsedRealtime()
        val info = YouTubeSource.info(url, fresh = fresh)
        Log.i(TAG, "stream info in ${SystemClock.elapsedRealtime() - t0} ms")
        val maxHeight = EnergySettings.state.value.policy.videoMaxHeight
        val allStreams = info.videoOnlyStreams.filter { it.height in 1..1080 }
        val streams = allStreams.filter { it.height <= maxHeight }.ifEmpty {
            val lowestHeight = allStreams.minOfOrNull { it.height }
            allStreams.filter { it.height == lowestHeight }
        }
        // H.264 remains preferred; the saver cap selects a smaller available stream.
        val preferredHeight = minOf(720, maxHeight)
        val avc = streams.filter { it.format == MediaFormat.MPEG_4 && it.codec?.startsWith("avc") == true }
        val v = avc.filter { it.height <= preferredHeight }.maxByOrNull { it.height }
            ?: avc.minByOrNull { it.height }
            ?: streams.filter { it.height <= preferredHeight }.maxByOrNull { it.height }
            ?: streams.minByOrNull { it.height }
            ?: error("no video stream")
        Log.i(TAG, "video ${v.height}p ${v.codec}")
        val headers = streamHeaders(v.content)
        val http = DefaultHttpDataSource.Factory()
            .apply { headers["User-Agent"]?.let { setUserAgent(it) } }
            .setDefaultRequestProperties(headers - "User-Agent")
            .setAllowCrossProtocolRedirects(true)
        val item = v.itagItem
        val source = if (item != null) {
            // As NewPipe: a DASH manifest over the progressive stream (ranged requests).
            val mpd = YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(v.content, item, info.duration)
            val manifest = DashManifestParser().parse(Uri.parse(v.content), mpd.byteInputStream())
            DashMediaSource.Factory(http).createMediaSource(manifest, MediaItem.fromUri(v.content))
        } else {
            ProgressiveMediaSource.Factory(http).createMediaSource(MediaItem.fromUri(v.content))
        }
        return VideoSource(url, source, info.duration * 1000, SystemClock.elapsedRealtime(), maxHeight)
    }

    /** Keeps the video on the song: small drifts by speed, big ones by seeking. */
    private fun sync(p: ExoPlayer, initial: AudioAlign.TimeMap) {
        syncJob?.cancel()
        syncJob = scope.launch {
            while (isActive && on.value) {
                // The full map replaces the quick one when [refine] finishes.
                val map = (state.value as? State.Ready)?.map ?: initial
                val playing = SongPlayer.isPlaying()
                val shown = playing && !SongPlayer.isBuffering && appVisible && surfaces > 0 && SongPlayer.activeDevice.value == null
                if (p.playWhenReady != shown) p.playWhenReady = shown
                if (!shown) {
                    if (p.playbackParameters.speed != 1f) p.playbackParameters = PlaybackParameters(1f)
                    delay(500)
                    continue
                }
                if (p.playbackState == Player.STATE_READY) {
                    if (p.duration > 0) videoDurationMs = p.duration
                    val target = map.toVideo(SongPlayer.getCurrentPosition())
                    val drift = p.currentPosition - target
                    when {
                        abs(drift) > 600 -> { p.seekTo(target); p.playbackParameters = PlaybackParameters(1f) }
                        playing && abs(drift) > 40 -> p.playbackParameters = PlaybackParameters((1f - drift / 2000f).coerceIn(0.9f, 1.1f))
                        p.playbackParameters.speed != 1f -> p.playbackParameters = PlaybackParameters(1f)
                    }
                }
                delay(150)
            }
        }
    }

    private fun stop() {
        syncJob?.cancel()
        player?.let { it.playWhenReady = false }
        refineJob?.cancel()
        // Keep the buffered source: switching back on is instant.
    }

    // ── Timeline shown while the video is on: the clip's time and length ──

    private fun activeMap(): AudioAlign.TimeMap? =
        if (on.value && SongPlayer.activeDevice.value == null) (state.value as? State.Ready)?.map else null

    fun position(): Long {
        val song = SongPlayer.getCurrentPosition()
        val m = activeMap() ?: return song
        val d = videoDurationMs
        return m.toVideo(song).coerceIn(0, if (d > 0) d else Long.MAX_VALUE)
    }

    fun duration(): Long = if (activeMap() != null && videoDurationMs > 0) videoDurationMs else SongPlayer.getDuration()

    fun seekTo(ms: Long) {
        val m = activeMap()
        SongPlayer.seekTo(if (m == null) ms else m.toSong(ms).coerceIn(0, SongPlayer.getDuration()))
        // Move the clip now (not at the next sync tick), to the nearest keyframe:
        // decoding starts sooner; the sync loop absorbs the few ms of difference.
        if (m != null) player?.let { p ->
            p.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
            p.seekTo(ms)
        }
    }

    /** Audio transport has priority over optional clip work during startup/rebuffering. */
    fun onAudioBuffering(buffering: Boolean, trackId: String) {
        if (!buffering) {
            val ready = (state.value as? State.Ready)?.takeIf { it.trackId == trackId }
            if (SongPlayer.isPlaying() && appVisible && ready != null) {
                // Back from the background while the audio was buffering: onForeground
                // left the clip stopped. Restart it once, not on every status event.
                if (on.value && syncJob?.isActive != true) start(ready)
                else if (!on.value) resumeWanted()
                if (on.value) ensureRefinement(ready)
            } else refineJob?.cancel()
            return
        }
        prepareJob?.cancel()
        prefetchJobs.values.forEach { it.cancel() }
        refineJob?.cancel()
        if (state.value is State.Searching) { state.value = State.Idle; nextPrepareAt = 0L }
        if (trackId.isNotBlank() && currentTrackId != trackId) {
            generation.incrementAndGet()
            discardRetained()
            currentTrackId = trackId
            nextPrepareAt = 0L
            wantVideo = wantVideo || on.value
            on.value = false
            fullscreen.value = false
            syncJob?.cancel()
            warmedFor = null
            player?.let { it.stop(); it.clearMediaItems() }
            state.value = State.Idle
        }
    }

    /** Called on a policy change so an old high-quality source is not reused. */
    fun onEnergyPolicyChanged() {
        scope.launch {
            warmedFor = null
            if (!EnergySettings.state.value.policy.allowBackgroundLookups) prefetchJobs.values.forEach { it.cancel() }
            if (on.value && appVisible) (state.value as? State.Ready)?.let(::start)
            else if (!EnergySettings.state.value.policy.allowBackgroundLookups) player?.stop()
        }
    }

    /** Signed out: no clip, no lookups, nothing kept from the account's songs. */
    fun clearSession() {
        wantVideo = false
        on.value = false
        prepareJob?.cancel()
        prefetchJobs.values.forEach { it.cancel() }
        stop()
        player?.stop()
        currentTrackId = ""
        warmedFor = null
        state.value = State.Idle
    }

    /** App in the background: the video pauses (nothing to show), video mode stays. */
    fun onBackground() {
        appVisible = false
        prepareJob?.cancel()
        prefetchJobs.values.forEach { it.cancel() }
        refineJob?.cancel()
        surfaceIdleJob?.cancel()
        surfaceIdleJob = null
        syncJob?.cancel()
        discardRetained()
        player?.let { it.playWhenReady = false; it.stop() }
        warmedFor = null
        if (state.value is State.Searching) { state.value = State.Idle; nextPrepareAt = 0L }
    }

    /** Back in the foreground: the sync loop seeks the video to the song and plays. */
    fun onForeground() {
        appVisible = true
        if (SongPlayer.isBuffering || !videoLookupOnResumeAllowed(
                EnergySettings.state.value.policy.allowBackgroundLookups, wantVideo, on.value, playerScreens > 0)) return
        val actual = SongPlayer.currentTrackId
        if (actual.isNotBlank() && actual != currentTrackId) {
            prepare(actual, Graph.currentSongState.title.value, Graph.currentSongState.singer.value, SongPlayer.getDuration())
        } else if (state.value is State.Idle && currentTrackId.isNotBlank()) {
            prepare(currentTrackId, currentTitle, currentArtist, currentDuration)
        } else if (on.value) (state.value as? State.Ready)?.let(::start) else resumeWanted()
    }
}
