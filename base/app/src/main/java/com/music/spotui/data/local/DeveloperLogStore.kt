package com.music.spotui.data.local

import androidx.compose.runtime.mutableStateListOf
import app.myspoti.host.android.Logbook
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Centralised in-app runtime event & error store for Developer Mode.
 * Mirrors every entry into [Logbook] so exported diagnostic reports also include them.
 */
object DeveloperLogStore {
    enum class Level { INFO, WARN, ERROR }

    data class Entry(
        val id: Long,
        val timestamp: String,
        val level: Level,
        val tag: String,
        val message: String,
        val details: String? = null,
    )

    private const val MAX_ENTRIES = 400
    private var nextId = 1L
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    val entries = mutableStateListOf<Entry>()

    @Volatile
    private var exceptionHookInstalled = false

    fun installUncaughtExceptionMonitor() {
        if (exceptionHookInstalled) return
        exceptionHookInstalled = true
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                error("Crash", "Uncaught exception on ${thread.name}: ${throwable.message}", throwable)
            }
            prev?.uncaughtException(thread, throwable)
        }
    }

    fun info(tag: String, message: String, details: String? = null) {
        append(Level.INFO, tag, message, details)
    }

    fun warn(tag: String, message: String, details: String? = null) {
        append(Level.WARN, tag, message, details)
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        val stack = throwable?.stackTraceToString()?.lineSequence()?.take(8)?.joinToString("\n")
        append(Level.ERROR, tag, message, stack)
    }

    private fun append(level: Level, tag: String, message: String, details: String?) {
        val stamp = synchronized(fmt) { fmt.format(Date()) }
        val line = "[Dev/$level/$tag] $message" + (if (!details.isNullOrBlank()) " | ${details.replace('\n', ' ')}" else "")
        runCatching { Logbook.log(line) }
        synchronized(this) {
            val entry = Entry(
                id = nextId++,
                timestamp = stamp,
                level = level,
                tag = tag,
                message = message,
                details = details,
            )
            entries.add(0, entry)
            while (entries.size > MAX_ENTRIES) {
                entries.removeAt(entries.lastIndex)
            }
        }
    }

    fun clear() {
        synchronized(this) {
            entries.clear()
        }
    }

    fun formatAll(includeEngineLog: Boolean = true): String = buildString {
        appendLine("=== Spotifusion Developer Log ===")
        val snapshot = synchronized(this@DeveloperLogStore) { entries.toList() }
        if (snapshot.isEmpty()) {
            appendLine("(No developer events recorded in this session)")
        } else {
            for (e in snapshot) {
                appendLine("${e.timestamp} [${e.level}] ${e.tag}: ${e.message}")
                if (!e.details.isNullOrBlank()) {
                    appendLine("    ${e.details}")
                }
            }
        }
        if (includeEngineLog) {
            appendLine()
            appendLine("=== Engine Logbook (Recent) ===")
            val recent = runCatching { Logbook.recent() }.getOrDefault("")
            val tail = recent.lineSequence().toList().takeLast(120)
            for (l in tail) appendLine(l)
        }
    }
}
