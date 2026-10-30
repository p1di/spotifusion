package com.music.spotui.data.api

import android.content.Context
import app.myspoti.engine.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * New version check against the GitHub releases of [BuildConfig.UPDATE_REPO]
 * (set by the CI build; empty in local builds, which never check).
 * At most one request every 3 hours (releases can come several a day). The release's
 * APK is downloaded and installed in the app ([AppUpdater]).
 */
object UpdateChecker {
    /** [apkUrl]: the release's signed APK; blank when the release has none (then its page opens). */
    data class Release(val version: String, val pageUrl: String, val notes: String = "", val apkUrl: String = "")

    private const val INTERVAL_MS = 3 * 3_600_000L
    private val http by lazy { OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).addInterceptor(com.music.spotui.data.DataSaver.interceptor).build() }

    suspend fun check(context: Context, force: Boolean = false): Release? = withContext(Dispatchers.IO) {
        val repo = BuildConfig.UPDATE_REPO.ifBlank { "p1di/spotifusion" }
        if (repo.isBlank()) return@withContext null
        val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("checked_at", 0L) < INTERVAL_MS) return@withContext pending(prefs)
        val release = runCatching {
            // Stable builds follow "latest", which never includes pre-releases. A beta build
            // also takes the newer betas, then the release they lead to.
            val url = if (isBeta) "https://api.github.com/repos/$repo/releases?per_page=10" else "https://api.github.com/repos/$repo/releases/latest"
            val request = Request.Builder().url(url).header("Accept", "application/vnd.github+json").build()
            http.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return@runCatching null
                val text = r.body.string()
                val list = if (isBeta) org.json.JSONArray(text).let { a -> (0 until a.length()).map { a.getJSONObject(it) } } else listOf(JSONObject(text))
                // Betas and releases are two apps: each one is offered its own channel only.
                list.filter { !it.optBoolean("draft") && (!isBeta || it.optBoolean("prerelease")) }
                    .map { Release(it.getString("tag_name").removePrefix("v"), it.getString("html_url"), cleanNotes(it.optString("body")), apkAsset(it)) }
                    .reduceOrNull { best, next -> if (isNewer(next.version, best.version)) next else best }
            }
        }.getOrNull()
        prefs.edit().putLong("checked_at", now)
            .putString("version", release?.version).putString("url", release?.pageUrl)
            .putString("notes", release?.notes).putString("apk", release?.apkUrl).apply()
        pending(prefs)
    }

    /** The latest release known, if newer than this build and not dismissed. */
    private fun pending(prefs: android.content.SharedPreferences): Release? {
        val version = prefs.getString("version", null) ?: return null
        val url = prefs.getString("url", null) ?: return null
        if (prefs.getString("dismissed", null) == version) return null
        return Release(version, url, prefs.getString("notes", null).orEmpty(), prefs.getString("apk", null).orEmpty()).takeIf { isNewer(version, BuildConfig.VERSION_NAME) }
    }

    /** The release's APK download link (e.g. `spotifusion-<version>.apk` or any .apk asset). */
    internal fun apkAsset(release: JSONObject): String {
        val assets = release.optJSONArray("assets") ?: return ""
        val all = (0 until assets.length()).map { assets.getJSONObject(it) }
        val tag = release.optString("tag_name").removePrefix("v")
        val wantSpotifusion = "spotifusion-$tag.apk"
        val wantMyspoti = "myspoti-$tag.apk"
        return (all.firstOrNull { it.optString("name") == wantSpotifusion }
            ?: all.firstOrNull { it.optString("name") == wantMyspoti }
            ?: all.firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) })
            ?.optString("browser_download_url").orEmpty()
    }

    fun dismiss(context: Context, release: Release) {
        context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit()
            .putString("dismissed", release.version).apply()
    }

    /**
     * The release description as plain lines for the update window: the CHANGELOG section,
     * without GitHub's generated part, Markdown emphasis or link syntax.
     */
    fun cleanNotes(body: String): String = body
        .substringBefore("**Full Changelog**").substringBefore("## What's Changed")
        .lines().map { it.trimEnd() }
        .filter { it.isNotBlank() }
        .joinToString("\n") { line ->
            line.replace("**", "").replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
                .replace(Regex("^(\\s*)[-*] "), "$1• ")
        }
        .trim()

    /** This build is a beta ("2.0.0-beta.1"). */
    val isBeta get() = BuildConfig.VERSION_NAME.contains("-beta")

    /**
     * "1.10.0" > "1.9.2". A release is newer than its betas ("2.0.0" > "2.0.0-beta.3"), and
     * betas compare by number ("2.0.0-beta.2" > "2.0.0-beta.1").
     */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        // Same version number: release > beta, then the higher beta.
        fun beta(v: String) = if (!v.contains('-')) Int.MAX_VALUE else Regex("(\\d+)$").find(v)?.value?.toIntOrNull() ?: 0
        return beta(candidate) > beta(current)
    }
}
