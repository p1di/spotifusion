package com.music.spotui.data.local

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Every listen on this phone, whatever the source (Spotify, downloads, local files), for the
 * in-app recap. Stays on the phone; nothing is reported to Spotify or anyone else.
 * A listen counts after 30 s, like most services.
 *
 * Persisted with redundant backup files in [Context.getNoBackupFilesDir] and external app storage
 * so the listening recap survives app updates and cache/files cleanups.
 */
object ListenHistory {
    enum class Source { SPOTIFY, DOWNLOAD, LOCAL }

    data class Listen(
        val atMs: Long,
        val key: String,
        val title: String,
        val artist: String,
        val album: String,
        val cover: String,
        val playedMs: Long,
        val source: Source,
    )

    const val MIN_PLAYED_MS = 30_000L
    private var file: File? = null
    private var backups: List<File> = emptyList()
    private val lock = Any()

    fun init(context: Context) {
        synchronized(lock) {
            val primary = File(context.filesDir, "listen_history.jsonl")
            val backupList = buildList {
                runCatching { add(File(context.noBackupFilesDir, "listen_history_backup.jsonl")) }
                runCatching {
                    context.getExternalFilesDir(null)?.let { ext ->
                        add(File(ext, "listen_history_backup.jsonl"))
                    }
                }
            }
            file = primary
            backups = backupList
            restoreAndSyncFiles(primary, backupList)
        }
    }

    private fun parseLine(line: String): Listen? = runCatching {
        if (line.isBlank()) return null
        val o = JSONObject(line)
        Listen(
            atMs = o.getLong("at"),
            key = o.getString("key"),
            title = o.getString("title"),
            artist = o.optString("artist"),
            album = o.optString("album"),
            cover = o.optString("cover"),
            playedMs = o.getLong("ms"),
            source = runCatching { Source.valueOf(o.optString("src", "SPOTIFY")) }.getOrDefault(Source.SPOTIFY),
        )
    }.getOrNull()

    private fun toJsonLine(listen: Listen): String = JSONObject()
        .put("at", listen.atMs)
        .put("key", listen.key)
        .put("title", listen.title)
        .put("artist", listen.artist)
        .put("album", listen.album)
        .put("cover", listen.cover)
        .put("ms", listen.playedMs)
        .put("src", listen.source.name)
        .toString()

    /**
     * Merges listens across primary and backup files on startup, deduplicating by (atMs, key)
     * and keeping the longest playedMs for each session.
     */
    private fun restoreAndSyncFiles(primary: File, backupList: List<File>) {
        runCatching {
            val allFiles = listOf(primary) + backupList
            val merged = LinkedHashMap<String, Listen>()
            for (f in allFiles) {
                if (!f.exists()) continue
                f.readLines().forEach { raw ->
                    val parsed = parseLine(raw) ?: return@forEach
                    if (parsed.playedMs < MIN_PLAYED_MS || parsed.title.isBlank()) return@forEach
                    val sessionId = "${parsed.atMs}|${parsed.key}"
                    val existing = merged[sessionId]
                    if (existing == null || parsed.playedMs > existing.playedMs) {
                        merged[sessionId] = parsed
                    }
                }
            }
            if (merged.isNotEmpty()) {
                val serialized = merged.values.sortedBy { it.atMs }.joinToString("\n", postfix = "\n") { toJsonLine(it) }
                for (f in allFiles) {
                    runCatching {
                        f.parentFile?.mkdirs()
                        f.writeText(serialized)
                    }
                }
            }
        }
    }

    fun record(listen: Listen) {
        if (listen.playedMs < MIN_PLAYED_MS || listen.title.isBlank()) return
        val line = toJsonLine(listen) + "\n"
        synchronized(lock) {
            val targets = listOfNotNull(file) + backups
            for (t in targets) {
                runCatching {
                    t.parentFile?.mkdirs()
                    t.appendText(line)
                }
            }
        }
    }

    fun all(): List<Listen> = synchronized(lock) {
        val sources = (listOfNotNull(file) + backups).filter { it.exists() }
        if (sources.isEmpty()) return emptyList()
        val merged = LinkedHashMap<String, Listen>()
        for (src in sources) {
            runCatching {
                src.readLines().forEach { line ->
                    val parsed = parseLine(line) ?: return@forEach
                    val sessionId = "${parsed.atMs}|${parsed.key}"
                    val existing = merged[sessionId]
                    if (existing == null || parsed.playedMs > existing.playedMs) {
                        merged[sessionId] = parsed
                    }
                }
            }
        }
        merged.values.sortedBy { it.atMs }
    }

    fun clear() = synchronized(lock) {
        file?.delete()
        backups.forEach { runCatching { it.delete() } }
    }

    data class Ranked(val name: String, val subtitle: String, val cover: String, val count: Int, val minutes: Long)

    data class Recap(
        val listens: Int,
        val minutes: Long,
        val bySource: Map<Source, Long>,
        val topTracks: List<Ranked>,
        val topArtists: List<Ranked>,
        val topAlbums: List<Ranked>,
    )

    /** Totals and top lists for listens since [sinceMs]. */
    fun recap(sinceMs: Long): Recap {
        val list = all().filter { it.atMs >= sinceMs }
        fun rank(group: (Listen) -> String, subtitle: (Listen) -> String) = list.groupBy(group)
            .filterKeys { it.isNotBlank() }
            .map { (name, l) -> Ranked(name, subtitle(l.first()), l.first().cover, l.size, l.sumOf { it.playedMs } / 60_000) }
            .sortedWith(compareByDescending<Ranked> { it.count }.thenByDescending { it.minutes })
            .take(10)
        return Recap(
            listens = list.size,
            minutes = list.sumOf { it.playedMs } / 60_000,
            bySource = list.groupBy { it.source }.mapValues { (_, l) -> l.sumOf { it.playedMs } / 60_000 },
            topTracks = rank({ it.title + "\u0000" + it.artist }, { it.artist }).map { it.copy(name = it.name.substringBefore('\u0000')) },
            topArtists = rank({ it.artist.substringBefore(",").trim() }, { "" }),
            topAlbums = rank({ it.album }, { it.artist }),
        )
    }
}
