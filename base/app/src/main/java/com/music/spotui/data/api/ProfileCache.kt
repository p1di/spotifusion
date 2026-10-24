package com.music.spotui.data.api

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.myspoti.api.best
import app.myspoti.host.android.MyspotiRuntime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** The signed-in profile (name, avatar, user id), fetched once per session, with optional local overrides. */
object ProfileCache {
    private const val PREFS_NAME = "spotifusion_profile_prefs"
    private const val KEY_CUSTOM_NAME = "custom_name"
    private const val KEY_CUSTOM_IMAGE = "custom_image_uri"

    var spotifyName by mutableStateOf<String?>(null)
        private set
    var spotifyImageUrl by mutableStateOf<String?>(null)
        private set

    var customName by mutableStateOf<String?>(null)
        private set
    var customImageUrl by mutableStateOf<String?>(null)
        private set

    val name: String?
        get() = customName?.takeIf { it.isNotBlank() } ?: spotifyName

    val imageUrl: String?
        get() = customImageUrl?.takeIf { it.isNotBlank() } ?: spotifyImageUrl

    var plan by mutableStateOf("Premium")
        private set
    /** Spotify user id (owner of the playlists the user can edit). */
    @Volatile var id: String? = null
        private set

    private val fetching = AtomicBoolean(false)
    private val generation = java.util.concurrent.atomic.AtomicLong()

    fun clearSession() {
        generation.incrementAndGet()
        spotifyName = null
        spotifyImageUrl = null
        plan = "Premium"
        id = null
    }

    fun loadCustomOverrides(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        customName = prefs.getString(KEY_CUSTOM_NAME, null)?.takeIf { it.isNotBlank() }
        customImageUrl = prefs.getString(KEY_CUSTOM_IMAGE, null)?.takeIf { it.isNotBlank() }
    }

    fun saveCustomProfile(context: Context, newName: String?, newImageUri: String?) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val trimmedName = newName?.trim()?.takeIf { it.isNotBlank() }
        val trimmedImg = newImageUri?.trim()?.takeIf { it.isNotBlank() }
        prefs.edit()
            .putString(KEY_CUSTOM_NAME, trimmedName)
            .putString(KEY_CUSTOM_IMAGE, trimmedImg)
            .apply()
        customName = trimmedName
        customImageUrl = trimmedImg
    }

    fun ensure(context: Context) {
        loadCustomOverrides(context)
        if (spotifyName != null) return
        if (!fetching.compareAndSet(false, true)) return
        val requestGeneration = generation.get()
        MyspotiRuntime.scope.launch {
            try {
                MyspotiRuntime.api.ready.first { it }
                val me = runCatching { MyspotiRuntime.api.users.me() }.getOrNull() ?: return@launch
                if (generation.get() != requestGeneration) return@launch
                spotifyName = me.name
                spotifyImageUrl = me.images.best(160)?.url
                id = me.username.takeIf { it.isNotBlank() }
                plan = if (me.premium) "Premium" else "Free"
            } finally {
                fetching.set(false)
            }
        }
    }
}
