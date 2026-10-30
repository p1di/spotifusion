package com.music.spotui.data.api

import android.content.Context
import app.myspoti.engine.R
import app.myspoti.host.android.MyspotiRuntime
import com.music.spotui.data.entity.LibraryEntry

/**
 * Library folders: real Spotify folders, synced to the account (the public Web API has none).
 * Each change shows in the library at once ([LibraryEdits]) and is written in the app's own
 * scope; a refusal takes it back with a short message.
 */
object LibraryFolders {
    private val pendingIds = java.util.concurrent.atomic.AtomicInteger()

    /** Creates a folder at the top of the library. */
    fun create(context: Context, name: String, onDone: (Boolean) -> Unit = {}) {
        val title = name.trim()
        if (title.isBlank()) { onDone(false); return }
        val app = context.applicationContext
        val placeholder = LibraryEdits.PENDING_PREFIX + "folder" + pendingIds.incrementAndGet()
        val edit = LibraryEdits.Add(null, LibraryEntry(
            spotifyId = placeholder, name = title, subtitle = app.getString(R.string.create_menu_item_folder_title),
            coverUri = "", isPlaylist = false, folderUri = placeholder, uri = placeholder,
            addedAt = java.time.Instant.now().toString(),
        ))
        LibraryEdits.run(context, edit, write = {
            val uri = MyspotiRuntime.api.library.createFolder(title)
            LibraryEdits.resolve(edit, edit.entry.copy(spotifyId = uri, folderUri = uri, uri = uri))
            changed()
        }, onDone = onDone)
    }

    /** Renames a folder. */
    fun rename(context: Context, uri: String, name: String, onDone: (Boolean) -> Unit = {}) {
        val title = name.trim()
        if (title.isBlank()) { onDone(false); return }
        LibraryEdits.run(context, LibraryEdits.Rename(uri, title), write = {
            MyspotiRuntime.api.library.renameFolder(uri, title)
            changed()
        }, onDone = onDone)
    }

    /** Deletes a folder (its playlists go to the level above). */
    fun delete(context: Context, uri: String, onDone: (Boolean) -> Unit = {}) {
        LibraryEdits.run(context, LibraryEdits.Remove(uri), write = {
            MyspotiRuntime.api.library.deleteFolder(uri)
            changed()
        }, onDone = onDone)
    }

    /** The library changed: the next load must come from Spotify, not from the caches. */
    fun changed() {
        Api.HomeCache.libraryRoot = null
        Api.HomeCache.folders.clear()
    }
}
