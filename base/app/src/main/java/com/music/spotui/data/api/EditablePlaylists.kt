package com.music.spotui.data.api

import app.myspoti.api.LibraryFilter
import app.myspoti.api.MediaKind
import app.myspoti.api.best
import app.myspoti.host.android.MyspotiRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
    @Volatile
    var cachedList: List<EditablePlaylist>? = null

    private val PinnedIds = setOf(
        Api.LIKED_SONGS_ID,
        Api.LOCAL_FILES_ID,
        Api.DOWNLOADS_ID,
        Api.EPISODES_ID,
        Api.RECENT_ID,
    )

    /** Fallback from cached library entries when the live bridge is offline or unavailable. */
    suspend fun fallbackFromCache(): List<EditablePlaylist> = withContext(Dispatchers.IO) {
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

    private suspend fun awaitBridgeOrCache(): Boolean {
        if (MyspotiRuntime.api.ready.value) return true
        // Wait up to 8s for the Web Player bridge to finish initializing before giving up.
        return withTimeoutOrNull(8_000L) {
            MyspotiRuntime.api.ready.first { it }
        } != null
    }

    /** The user's own and collaborative playlists, in library order, folders flattened. */
    suspend fun load(): List<EditablePlaylist> = withContext(Dispatchers.IO) {
        awaitBridgeOrCache()
        val live = runCatching { MyspotiRuntime.api.playlists.editable() }.getOrDefault(emptyList()).map {
            EditablePlaylist(it.uri.substringAfterLast(':'), it.name, it.trackCount, it.images.best(160)?.url)
        }
        if (live.isNotEmpty()) {
            cachedList = live
            return@withContext live
        }
        fallbackFromCache()
    }

    /**
     * One level of the library as Spotify shows it: its folders, then the playlists the user
     * can change, with their covers (Spotify's playlist list has none for mosaic covers;
     * the library has them). Falls back to [load] when the library cannot be read.
     */
    suspend fun level(folderUri: String?): List<EditablePlaylist> = withContext(Dispatchers.IO) {
        awaitBridgeOrCache()
        val api = MyspotiRuntime.api
        val editable = runCatching { api.playlists.editable() }.getOrNull()?.associateBy { it.uri } ?: return@withContext load()
        val items = ArrayList<app.myspoti.api.LibraryItem>()
        runCatching {
            var offset = 0
            repeat(40) {
                val page = api.library.items(filter = LibraryFilter.ALL, folderUri = folderUri, offset = offset, limit = 50)
                items += page.items
                offset = page.nextOffset ?: return@runCatching
            }
        }.onFailure { if (items.isEmpty()) return@withContext if (folderUri == null) load() else emptyList() }
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
        if (result.isEmpty() && folderUri == null) load() else result
    }
}
