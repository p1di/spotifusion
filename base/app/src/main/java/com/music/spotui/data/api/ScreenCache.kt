package com.music.spotui.data.api

import android.util.AtomicFile
import android.util.Log
import com.music.spotui.data.entity.ContextPageModel
import com.music.spotui.data.entity.HomeFeedModel
import com.music.spotui.data.entity.LibraryEntry
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.Graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The last home feed, library and Liked Songs, kept on disk: after a restart the screens
 * show them at once, while the hidden Web Player is still starting, then refresh.
 * Private app storage; cleared on logout ([clear]).
 */
object ScreenCache {
    private const val TAG = "ScreenCache"
    // Bumped when a cached model changes shape.
    private const val DIR = "screen_cache1"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    // One thread: writes and clear() run in call order, so a write already in
    // flight at logout cannot land after the clear and restore the old account.
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val dir: File by lazy { File(Graph.app.filesDir, DIR).also { it.mkdirs() } }

    val home = Entry("home", HomeFeedModel.serializer())
    val libraryRoot = Entry("library_root", ListSerializer(LibraryEntry.serializer()))
    val liked = Entry("liked", ListSerializer(SongsModel.serializer()))

    fun contextPage(uri: String): Entry<ContextPageModel> {
        val safeKey = uri.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return Entry("ctx_$safeKey", ContextPageModel.serializer())
    }

    class Entry<T>(private val name: String, private val serializer: KSerializer<T>) {
        private val file get() = AtomicFile(File(dir, "$name.json"))

        /** Blocking read: call from a background thread. */
        fun read(): T? = runCatching {
            if (!file.baseFile.exists()) return null
            json.decodeFromString(serializer, file.readFully().decodeToString())
        }.onFailure { Log.w(TAG, "drop unreadable $name: ${it.javaClass.simpleName}"); runCatching { file.delete() } }.getOrNull()

        fun write(value: T) {
            io.launch {
                runCatching {
                    val f = file
                    val out = f.startWrite()
                    try {
                        out.write(json.encodeToString(serializer, value).encodeToByteArray())
                        f.finishWrite(out)
                    } catch (t: Throwable) { f.failWrite(out); throw t }
                }.onFailure { Log.w(TAG, "could not save $name: ${it.javaClass.simpleName}") }
            }
        }
    }

    /** Logout / account switch. */
    fun clear() { io.launch { dir.listFiles()?.forEach { it.delete() } } }
}
