package com.music.spotui.data.api

import app.myspoti.api.LibraryFilter
import app.myspoti.api.MediaKind
import app.myspoti.api.best
import app.myspoti.host.android.MyspotiRuntime
import com.music.spotui.data.local.DeveloperLogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A playlist the user can add tracks to, for the "Add to playlist" pickers. With a
 * [folderUri] the row is a library folder instead, opened in place.
 */
data class EditablePlaylist(
    val id: String,
    val name: String,
    val trackCount: Int,
    val imageUrl: String?,
    val folderUri: String? = null,
) {
    val isFolder: Boolean get() = folderUri != null
}

object EditablePlaylists {
    private const val BRIDGE_TIMEOUT_MS = 4_500L

    @Volatile
    var cachedList: List<EditablePlaylist>? = null

    /** True when the most recent [load] or [level] timed out or failed on the live bridge. */
    @Volatile
    var lastLoadTimedOut: Boolean = false

    private val PinnedIds = setOf(
        Api.LIKED_SONGS_ID,
        Api.LOCAL_FILES_ID,
        Api.DOWNLOADS_ID,
        Api.EPISODES_ID,
        Api.RECENT_ID,
    )

    /** Fallback from cached library entries when the WebView bridge is slow or stalled. */
    private suspend fun fallbackFromCache(): List<EditablePlaylist> = withContext(Dispatchers.IO) {
        cachedList?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
        val entries = Api.HomeCache.library
            ?: Api.HomeCache.libraryRoot
            ?: runCatching { ScreenCache.libraryRoot.read() }.getOrNull()
            ?: emptyList()
        entries
            .filter { it.isPlaylist && it.spotifyId.isNotBlank() && it.spotifyId !in PinnedIds }
            .distinctBy { it.spotifyId }
            .map { entry ->
                val count = Api.HomeCache.contextPages["spotify:playlist:${entry.spotifyId}"]?.tracks?.size ?: 0
                EditablePlaylist(
                    id = entry.spotifyId,
                    name = entry.name,
                    trackCount = count,
                    imageUrl = entry.coverUri.ifBlank { null },
                )
            }
    }

    /** The user's own and collaborative playlists, in library order, folders flattened. */
    suspend fun load(): List<EditablePlaylist> {
        lastLoadTimedOut = false
        val live = withTimeoutOrNull(BRIDGE_TIMEOUT_MS) {
            runCatching { MyspotiRuntime.api.playlists.editable() }.getOrNull()
        }
        if (live == null) {
            lastLoadTimedOut = true
            DeveloperLogStore.warn("EditablePlaylists", "Timed out loading editable playlists after ${BRIDGE_TIMEOUT_MS}ms; using cache fallback")
            return fallbackFromCache()
        }
        val mapped = live.map {
            EditablePlaylist(it.uri.substringAfterLast(':'), it.name, it.trackCount, it.images.best(160)?.url)
        }
        if (mapped.isNotEmpty()) {
            cachedList = mapped
            return mapped
        }
        val fallback = fallbackFromCache()
        if (fallback.isEmpty() && !MyspotiRuntime.api.ready.value) {
            lastLoadTimedOut = true
        }
        return fallback
    }

    /**
     * One level of the library as Spotify shows it: its folders, then the playlists the user
     * can change, with their covers (Spotify's playlist list has none for mosaic covers;
     * the library has them). Falls back to [load] when the library cannot be read.
     */
    suspend fun level(folderUri: String?): List<EditablePlaylist> {
        lastLoadTimedOut = false
        val api = MyspotiRuntime.api
        val editable = withTimeoutOrNull(BRIDGE_TIMEOUT_MS) {
            runCatching { api.playlists.editable() }.getOrNull()
        }?.associateBy { it.uri }
        if (editable == null) {
            lastLoadTimedOut = true
            DeveloperLogStore.warn("EditablePlaylists", "Timed out loading playlist level ($folderUri) after ${BRIDGE_TIMEOUT_MS}ms")
            return if (folderUri == null) fallbackFromCache() else emptyList()
        }
        val items = ArrayList<app.myspoti.api.LibraryItem>()
        val ok = withTimeoutOrNull(BRIDGE_TIMEOUT_MS) {
            runCatching {
                var offset = 0
                repeat(40) {
                    val page = api.library.items(filter = LibraryFilter.ALL, folderUri = folderUri, offset = offset, limit = 50)
                    items += page.items
                    offset = page.nextOffset ?: return@runCatching true
                }
                true
            }.getOrDefault(false)
        } ?: false
        if (!ok && items.isEmpty()) {
            return if (folderUri == null) {
                val mapped = editable.values.map {
                    EditablePlaylist(it.uri.substringAfterLast(':'), it.name, it.trackCount, it.images.best(160)?.url)
                }
                if (mapped.isNotEmpty()) {
                    cachedList = mapped
                    mapped
                } else fallbackFromCache()
            } else emptyList()
        }
        val result = items.mapNotNull { item ->
            when (item.kind) {
                MediaKind.FOLDER -> EditablePlaylist(item.uri, item.name, item.count ?: 0, null, folderUri = item.uri)
                MediaKind.PLAYLIST -> editable[item.uri]?.let {
                    EditablePlaylist(
                        item.uri.substringAfterLast(':'),
                        item.name,
                        it.trackCount,
                        item.images.best(160)?.url ?: it.images.best(160)?.url,
                    )
                }
                else -> null
            }
        }
        if (folderUri == null && result.isNotEmpty()) {
            cachedList = result.filter { !it.isFolder }
        }
        return if (result.isEmpty() && folderUri == null) fallbackFromCache() else result
    }
}
