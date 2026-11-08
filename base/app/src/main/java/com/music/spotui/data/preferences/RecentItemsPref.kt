package com.music.spotui.data.preferences

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * One "Recent searches" entry — Spotify-style: not the typed query but the
 * actual item the user opened from the results (song / artist / album / show).
 * Carries enough payload to re-launch the item on tap.
 */
data class RecentItem(
    val type: String,            // "song" | "artist" | "album" | "show"
    val key: String,             // stable id for dedupe (spotify id, or name)
    val name: String,
    val singer: String = "",     // artist line (songs/albums)
    val image: String = "",
    // Song-only payload so a tap can replay it.
    val songId: Int = -1,
    val songAlbum: String = "",
    val songUrl: String = "",
    val spotifyTrackId: String = "",
    val explicit: Boolean = false,
    val durationMs: Int = 0,
)

private const val PREF = "RecentItems"
private const val KEY = "items"
private const val MAX_ITEMS = 20

private fun RecentItem.toJson(): JSONObject = JSONObject().apply {
    put("type", type); put("key", key); put("name", name); put("singer", singer)
    put("image", image); put("songId", songId); put("songAlbum", songAlbum)
    put("songUrl", songUrl); put("spotifyTrackId", spotifyTrackId)
    put("explicit", explicit); put("durationMs", durationMs)
}

private fun JSONObject.toRecentItem(): RecentItem = RecentItem(
    type = optString("type"), key = optString("key"), name = optString("name"),
    singer = optString("singer"), image = optString("image"),
    songId = optInt("songId", -1), songAlbum = optString("songAlbum"),
    songUrl = optString("songUrl"), spotifyTrackId = optString("spotifyTrackId"),
    explicit = optBoolean("explicit", false), durationMs = optInt("durationMs", 0),
)

fun getRecentItems(context: Context): List<RecentItem> {
    val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null)
        ?: return emptyList()
    return runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { arr.getJSONObject(it).toRecentItem() }
    }.getOrDefault(emptyList())
}

private fun save(context: Context, items: List<RecentItem>) {
    val arr = JSONArray().also { a -> items.forEach { a.put(it.toJson()) } }
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .edit().putString(KEY, arr.toString()).apply()
}

fun addRecentItem(context: Context, item: RecentItem) {
    if (item.name.isBlank()) return
    val rest = getRecentItems(context).filterNot { it.type == item.type && it.key == item.key }
    save(context, (listOf(item) + rest).take(MAX_ITEMS))
}

fun removeRecentItem(context: Context, item: RecentItem) {
    save(context, getRecentItems(context).filterNot { it.type == item.type && it.key == item.key })
}

fun clearRecentItems(context: Context) {
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY).apply()
}

/**
 * Merges Spotify's cloud recent searches ([remoteItems]) behind the user's locally recorded
 * recent items so searches from Spotify across devices also appear in Spotifusion (Issue #20).
 */
fun mergeRemoteRecentItems(context: Context, remoteItems: List<RecentItem>): List<RecentItem> {
    if (remoteItems.isEmpty()) return getRecentItems(context)
    val local = getRecentItems(context)
    val seen = local.map { "${it.type}:${it.key}" }.toHashSet()
    val merged = ArrayList<RecentItem>(local)
    for (r in remoteItems) {
        if (r.name.isBlank() || r.type.isBlank() || r.key.isBlank()) continue
        if (seen.add("${r.type}:${r.key}")) {
            merged.add(r)
        }
    }
    val capped = merged.take(35)
    save(context, capped)
    return capped
}
