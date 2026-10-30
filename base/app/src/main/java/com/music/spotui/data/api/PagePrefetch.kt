package com.music.spotui.data.api

import com.music.spotui.data.entity.LibraryEntry
import com.music.spotui.di.Graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The playlists at the top of the library, fetched ahead on Wi-Fi while the app sits idle and
 * kept on storage (not in memory): opening one shows it whole at once, after a restart too, and
 * the network only refreshes it behind. A few pages, refreshed at most every half hour, the
 * oldest deleted; nothing on mobile data or under the data saver.
 */
object PagePrefetch {
    private const val COUNT = 4
    /** Pages kept on storage: the prefetched ones plus a few the user opened since. */
    const val KEEP = 8
    private const val IDLE_MS = 20_000L
    private const val FRESH_MS = 30 * 60_000L
    private const val MIN_GAP_MS = 10 * 60_000L
    /** A bigger playlist is left to the network: its file would be large for one open. */
    private const val MAX_TRACKS = 1_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var lastRun = 0L

    /**
     * At startup, once the player is ready: the library as last saved, so the prefetch does not
     * wait for the Library tab to be opened.
     */
    fun start() {
        scope.launch {
            if (kotlinx.coroutines.withTimeoutOrNull(60_000) { app.myspoti.host.android.MyspotiRuntime.api.ready.first { it } } == null) return@launch
            val root = Api.HomeCache.libraryRoot ?: ScreenCache.libraryRoot.read() ?: return@launch
            schedule(root)
        }
    }

    /** Called with the library's first level each time it is read; runs at most every 10 minutes. */
    fun schedule(entries: List<LibraryEntry>) {
        val now = System.currentTimeMillis()
        if (now - lastRun < MIN_GAP_MS) return
        lastRun = now
        val ids = entries.asSequence()
            .filter { it.isPlaylist && it.uri.startsWith("spotify:playlist:") }
            .map { it.uri.substringAfterLast(':') }
            .take(COUNT).toList()
        if (ids.isEmpty()) return
        scope.launch {
            delay(IDLE_MS)
            for (id in ids) {
                if (com.music.spotui.data.DataSaver.metered) return@launch
                val saved = ScreenCache.pageSavedAt(id)
                if (saved != null && System.currentTimeMillis() - saved < FRESH_MS) continue
                val page = runCatching {
                    Graph.api.getPlaylistPage(id).first { it is Response.Success && it.data.complete || it is Response.Error }
                }.getOrNull()
                val kept = (page as? Response.Success)?.data?.takeIf { it.tracks.isNotEmpty() && it.tracks.size <= MAX_TRACKS }
                kept?.let { ScreenCache.page(id).write(it) }
                android.util.Log.d("Myspoti", "prefetch playlist ${id.take(6)}…: ${kept?.tracks?.size?.let { "$it songs kept on storage" } ?: "not kept"}")
                delay(1_500) // one after the other, gently
            }
            ScreenCache.prunePages(KEEP)
        }
    }

    /** A page just read whole from the network: its copy on storage, when it has one, follows. */
    fun refreshed(id: String, page: com.music.spotui.data.entity.ContextPageModel) {
        if (!page.complete || page.tracks.size > MAX_TRACKS || ScreenCache.pageSavedAt(id) == null) return
        ScreenCache.page(id).write(page)
    }
}
