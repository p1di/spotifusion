package com.music.spotui.ui.components

import androidx.compose.ui.res.stringResource

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.CrossFade
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import app.myspoti.engine.R
import androidx.compose.ui.res.pluralStringResource
import com.music.spotui.data.api.SpotifySync
import com.music.spotui.data.entity.SongsModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val SpotifyGreen: Color get() = com.music.spotui.ui.theme.AppAccent

/**
 * Resolves a Spotify track ID even when the caller passed a local/cached/restored song
 * whose [SongsModel.spotifyTrackId] was not populated yet.
 */
internal suspend fun resolveTrackIdForSong(song: SongsModel): String {
    song.spotifyTrackId.takeIf { it.isNotBlank() && !it.startsWith("local:") && !it.startsWith("episode:") }?.let { return it }
    song.url.takeIf { it.isNotBlank() && !it.startsWith("local:") && !it.startsWith("episode:") }
        ?.removePrefix("spotify:track:")
        ?.substringBefore('|')
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }
    com.music.spotui.di.SongPlayer.currentTrackId
        .takeIf { it.isNotBlank() && !it.startsWith("local:") && !it.startsWith("episode:") }
        ?.let { return it }
    val npUri = com.music.spotui.di.SongPlayer.nowPlaying.value.uri
    if (npUri.startsWith("spotify:track:")) {
        return npUri.removePrefix("spotify:track:")
    }
    val title = song.title.trim()
    val artist = song.singer.trim()
    if (title.isNotBlank()) {
        com.music.spotui.data.offline.OfflineStore.tracks.values.firstOrNull {
            it.title.equals(title, ignoreCase = true) && (artist.isBlank() || it.artist.equals(artist, ignoreCase = true))
        }?.trackId?.let { return it }
        com.music.spotui.data.cache.PlaybackCacheStore.cachedTracks.values.firstOrNull {
            it.title.equals(title, ignoreCase = true) && (artist.isBlank() || it.artist.equals(artist, ignoreCase = true))
        }?.trackId?.let { return it }
        val searched = withContext(Dispatchers.IO) {
            runCatching {
                app.myspoti.host.android.MyspotiRuntime.api.search.all("$title $artist".trim(), limit = 1)
                    .tracks.items.firstOrNull()?.uri?.removePrefix("spotify:track:")
            }.getOrNull()
        }
        if (!searched.isNullOrBlank()) return searched
    }
    return ""
}

/**
 * Spotify's "Saved in" sheet: Liked Songs plus every user playlist, each row
 * toggling the track in/out of it (mirrored to the real Spotify account), and a
 * stringResource(R.string.add_to_playlist_new_playlist_button_text) action that creates one on Spotify with the track in it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class)
@Composable
fun SavedInSheet(
    song: SongsModel,
    context: Context,
    onDismiss: () -> Unit,
    onLikedChanged: (Boolean) -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var resolvedTrackId by remember(song.spotifyTrackId, song.title, song.singer) {
        mutableStateOf(song.spotifyTrackId)
    }
    LaunchedEffect(song.spotifyTrackId, song.title, song.singer) {
        if (resolvedTrackId.isBlank()) {
            resolvedTrackId = resolveTrackIdForSong(song)
        }
    }
    val liked = com.music.spotui.di.rememberTrackSaved(resolvedTrackId)
    var playlists by remember { mutableStateOf<List<com.music.spotui.data.api.EditablePlaylist>?>(null) }
    var reloadNonce by remember { mutableIntStateOf(0) }
    var loadStuck by remember { mutableStateOf(false) }
    // playlistId → does it contain this track (filled lazily per row).
    val membership = remember { mutableStateMapOf<String, Boolean>() }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    // Folders opened so far (uri to name); the last one is shown.
    val folders = remember { androidx.compose.runtime.mutableStateListOf<Pair<String, String>>() }
    val folder = folders.lastOrNull()

    LaunchedEffect(folder?.first, reloadNonce) {
        // Only playlists the user can change (theirs and collaborative ones), level by level.
        playlists = null
        loadStuck = false
        playlists = com.music.spotui.data.api.EditablePlaylists.level(folder?.first)
    }

    // Only prompt the recovery modal if still stuck on "Loading playlists..." after 7 seconds.
    LaunchedEffect(playlists == null, reloadNonce) {
        if (playlists == null) {
            delay(7_000L)
            if (playlists == null) {
                loadStuck = true
                val cached = com.music.spotui.data.api.EditablePlaylists.fallbackFromCache()
                if (cached.isNotEmpty() && folder == null) {
                    playlists = cached
                } else if (com.music.spotui.data.preferences.AutoRestartPlayerPref.on && reloadNonce < com.music.spotui.data.preferences.AutoRestartPlayerPref.maxAttempts) {
                    android.widget.Toast.makeText(context, "Auto-restarting player (${reloadNonce + 1}/${com.music.spotui.data.preferences.AutoRestartPlayerPref.maxAttempts})…", android.widget.Toast.LENGTH_SHORT).show()
                    com.music.spotui.di.SongPlayer.restartPlayerEngine()
                    reloadNonce++
                } else {
                    PlayerRecoveryModal.promptPlaylistsStuck {
                        reloadNonce++
                    }
                }
            }
        } else {
            loadStuck = false
        }
    }
    androidx.activity.compose.BackHandler(enabled = folders.isNotEmpty()) { folders.removeAt(folders.lastIndex) }

    fun createNow() {
        val name = newName.trim().ifBlank { context.getString(R.string.my_playlist) }
        creating = false
        newName = ""
        SpotifySync.createPlaylistWithTrack(context, name, resolvedTrackId, folderUri = folder?.first)
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1A1A1A),
    ) {
        Column(modifier = Modifier.navigationBarsPadding().imePadding()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp, 4.dp, 20.dp, 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f, fill = false)) {
                    if (folder != null) Icon(
                        Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.White,
                        modifier = Modifier.padding(end = 12.dp).size(24.dp).clickable { folders.removeAt(folders.lastIndex) },
                    )
                    Text(folder?.second ?: stringResource(R.string.add_to_playlist_saved_in_section_header), color = Color.White,
                        fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    stringResource(R.string.add_to_playlist_new_playlist_button_text),
                    color = SpotifyGreen,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { creating = true },
                )
            }

            if (creating) {
                TextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.playlist_name), color = Color.Gray) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { createNow() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF2A2A2A),
                        unfocusedContainerColor = Color(0xFF2A2A2A),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = SpotifyGreen,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp, 0.dp, 20.dp, 8.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Row(modifier = Modifier.padding(20.dp, 0.dp, 20.dp, 8.dp)) {
                    Text(
                        stringResource(R.string.create_playlist_create_button),
                        color = SpotifyGreen,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { createNow() },
                    )
                    Spacer(Modifier.width(24.dp))
                    Text(
                        stringResource(R.string.add_to_playlist_button_cancel),
                        color = Color.Gray,
                        fontSize = 14.sp,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { creating = false; newName = "" },
                    )
                }
            }

            // ── Liked Songs ──
            if (folder == null) SavedInRow(
                name = stringResource(R.string.collection_liked_songs_title),
                subtitle = "",
                saved = liked,
                cover = {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                Brush.linearGradient(listOf(Color(0xFF4A39EA), Color(0xFF868AE1)))
                            ),
                    ) {
                        Icon(Icons.Default.Favorite, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                },
            ) {
                if (resolvedTrackId.isNotBlank()) {
                    com.music.spotui.di.SongPlayer.setTrackSaved(resolvedTrackId, !liked)
                    onLikedChanged(!liked)
                }
            }

            when (val list = playlists) {
                null -> {
                    Column(modifier = Modifier.padding(20.dp, 12.dp)) {
                        Text(stringResource(R.string.loading_playlists), color = Color.Gray, fontSize = 14.sp)
                        if (loadStuck) {
                            Text(
                                text = "Taking too long? Tap to restart player",
                                color = SpotifyGreen,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .padding(top = 8.dp)
                                    .clickable {
                                        PlayerRecoveryModal.promptPlaylistsStuck { reloadNonce++ }
                                    },
                            )
                        }
                    }
                }
                else -> LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(list.size) { i ->
                        val pl = list[i]
                        if (pl.isFolder) {
                            FolderRow(pl.name, pluralStringResource(R.plurals.add_to_playlist_folder_subtitle_playlists_count, pl.trackCount, pl.trackCount.toString())) {
                                folders.add(pl.folderUri!! to pl.name)
                            }
                            return@items
                        }
                        // Resolve membership lazily (cached per session in SpotifySync).
                        LaunchedEffect(pl.id, resolvedTrackId) {
                            if (membership[pl.id] == null && resolvedTrackId.isNotBlank()) {
                                membership[pl.id] = withContext(Dispatchers.IO) {
                                    SpotifySync.playlistTrackIds(context, pl.id).contains(resolvedTrackId)
                                }
                            }
                        }
                        val saved = membership[pl.id] == true
                        SavedInRow(
                            name = pl.name,
                            subtitle = pluralStringResource(R.plurals.liked_songs_header_metadata_text, pl.trackCount, pl.trackCount),
                            saved = saved,
                            cover = {
                                GlideImage(
                                    transition = CrossFade,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    model = pl.imageUrl,
                                    contentScale = ContentScale.Crop,
                                    failure = placeholder(R.drawable.placeholder),
                                    loading = placeholder(R.drawable.placeholder),
                                    contentDescription = "",
                                )
                            },
                        ) {
                            if (resolvedTrackId.isBlank()) return@SavedInRow
                            membership[pl.id] = !saved
                            val done: (Boolean) -> Unit = { ok ->
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    if (!ok) membership[pl.id] = saved
                                    val text = when {
                                        !ok -> context.getString(R.string.playlist_change_refused)
                                        saved -> context.getString(R.string.removed_from, pl.name)
                                        else -> context.getString(R.string.local_added_to, pl.name)
                                    }
                                    android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                            if (saved) SpotifySync.removeTrackFromPlaylist(context, pl.id, resolvedTrackId, done)
                            else SpotifySync.addTrackToPlaylist(context, pl.id, resolvedTrackId, done)
                        }
                    }
                }
            }
            // Spotify's own "Done": closes the sheet once the changes are made.
            Box(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.done),
                    color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(SpotifyGreen)
                        .clickable(onClick = onDismiss).padding(horizontal = 36.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun FolderRow(name: String, subtitle: String, onOpen: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF333333)),
        ) {
            Icon(Icons.Outlined.Folder, contentDescription = null, tint = Color(0xFFB3B3B3), modifier = Modifier.size(24.dp))
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(name, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Color.Gray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Color(0xFFB3B3B3))
    }
}

@Composable
private fun SavedInRow(
    name: String,
    subtitle: String,
    saved: Boolean,
    cover: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        cover()
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp),
        ) {
            Text(name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = Color.Gray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        if (saved) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SpotifyGreen, modifier = Modifier.size(24.dp))
        } else {
            Icon(Icons.Default.Add, contentDescription = null, tint = Color(0xFFB3B3B3), modifier = Modifier.size(24.dp))
        }
    }
}
