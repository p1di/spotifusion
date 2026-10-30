package com.music.spotui.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import org.json.JSONObject

/**
 * Likes, unlikes, saves and follows made without a connection: shown at once, kept here (the last
 * tap on an item wins) and sent to Spotify when the network is back, in the order they were made.
 */
object LibraryOutbox {
    private const val PREFS = "library_outbox"
    private const val KEY = "pending"

    /** What waits, in order: item uri -> saved. Pure, for tests: see [Pending]. */
    class Pending(text: String?) {
        private val items = LinkedHashMap<String, Boolean>().apply {
            runCatching { JSONObject(text ?: "{}") }.getOrNull()?.let { o -> o.keys().forEach { put(it, o.optBoolean(it)) } }
        }
        val all: Map<String, Boolean> get() = items

        /** The newest tap on [uri]: it moves to the end, the earlier one is forgotten. */
        fun put(uri: String, saved: Boolean) { items.remove(uri); items[uri] = saved }
        fun sent(uri: String, saved: Boolean) { if (items[uri] == saved) items.remove(uri) }
        fun text(): String = JSONObject(items as Map<*, *>).toString()
    }

    private var context: Context? = null
    private var send: (suspend (uri: String, saved: Boolean) -> Unit)? = null

    private fun prefs() = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    @Synchronized private fun read() = Pending(prefs()?.getString(KEY, null))
    @Synchronized private fun write(p: Pending) { prefs()?.edit()?.putString(KEY, p.text())?.apply() }

    @Synchronized fun put(uri: String, saved: Boolean) = write(read().apply { put(uri, saved) })
    val waiting: Int get() = read().all.size

    /** [sender] writes one change to Spotify (throws when it cannot); called when the network is back. */
    fun init(c: Context, sender: suspend (uri: String, saved: Boolean) -> Unit, onAvailable: () -> Unit) {
        if (context != null) return
        context = c.applicationContext
        send = sender
        val cm = c.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = onAvailable()
            })
        }
    }

    /** Sends what waits, oldest first; stops at the first failure (the rest waits for the next network). */
    suspend fun flush() {
        val sender = send ?: return
        for ((uri, saved) in read().all.entries.toList()) {
            runCatching { sender(uri, saved) }.onFailure {
                android.util.Log.w("LibraryOutbox", "kept for later: $uri (${it.message})")
                return
            }
            synchronized(this) { write(read().apply { sent(uri, saved) }) }
        }
    }
}
