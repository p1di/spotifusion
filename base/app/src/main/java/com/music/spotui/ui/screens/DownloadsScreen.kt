package com.music.spotui.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.myspoti.engine.R
import com.bumptech.glide.integration.compose.CrossFade
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.offline.OfflineCollections
import com.music.spotui.data.offline.OfflineDownloader
import com.music.spotui.data.offline.OfflineStatus
import com.music.spotui.data.offline.OfflineStore
import com.music.spotui.data.offline.OfflineTrack
import com.music.spotui.di.LocalPlayer
import com.music.spotui.di.SongPlayer
import com.music.spotui.di.rememberTrackSaved
import com.music.spotui.ui.components.DownloadBadge
import com.music.spotui.ui.components.FindInListField
import com.music.spotui.ui.components.matchesFind
import com.music.spotui.ui.components.pressable
import com.music.spotui.ui.theme.AppAccent
import com.music.spotui.ui.theme.AppBackground
import java.text.Collator

private val downloadsSortUri = "spotify:collection:downloads"

private val downloadsTextCollator = ThreadLocal.withInitial {
    Collator.getInstance().apply { strength = Collator.PRIMARY }
}

private fun sortOfflineTracks(tracks: List<OfflineTrack>, order: TrackOrder): List<OfflineTrack> {
    val collator = downloadsTextCollator.get()
    val sorted = when (order.sort) {
        TrackSort.CUSTOM, TrackSort.ADDED -> tracks.sortedByDescending { it.addedAt }
        TrackSort.TITLE -> tracks.sortedWith { a, b -> collator.compare(a.title, b.title) }
        TrackSort.ARTIST -> tracks.sortedWith { a, b ->
            collator.compare(a.artist, b.artist).takeIf { it != 0 } ?: collator.compare(a.album, b.album)
        }
        TrackSort.ALBUM -> tracks.sortedWith { a, b ->
            collator.compare(a.album, b.album).takeIf { it != 0 } ?: collator.compare(a.title, b.title)
        }
        TrackSort.DURATION -> tracks.sortedBy { it.durationMs }
    }
    return if (order.reversed) sorted.asReversed() else sorted
}

/** "Downloaded": songs kept for offline listening, played from the phone by [LocalPlayer]. */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun DownloadsScreen(navController: NavController) {
    val context = LocalContext.current
    val title = stringResource(R.string.collection_downloaded)
    var find by remember { mutableStateOf("") }
    var selectedCollectionUri by remember { mutableStateOf<String?>(null) }
    var sort by remember { mutableStateOf(TrackOrder.load(context, downloadsSortUri)) }

    // Snapshot reads: the list follows downloads as they finish.
    val allTracks = OfflineStore.tracks.values.sortedByDescending { it.addedAt }
    val collections = OfflineCollections.collections.values
        .filter { c -> c.trackIds.any { OfflineStore.isDownloaded(it) } }
        .sortedBy { it.name.lowercase() }
    val activeCollection = selectedCollectionUri?.let { OfflineCollections.collections[it] }
    if (selectedCollectionUri != null && activeCollection == null) {
        selectedCollectionUri = null
    }

    val tracks = remember(allTracks, activeCollection, find, sort) {
        val inCollection = if (activeCollection != null) {
            val byId = allTracks.associateBy { it.trackId }
            activeCollection.trackIds.mapNotNull { byId[it] }
        } else {
            allTracks
        }
        val matching = if (find.isBlank()) {
            inCollection
        } else {
            inCollection.filter { matchesFind(find, it.title, it.artist, it.album) }
        }
        if (activeCollection != null && sort.isCustom) {
            matching
        } else {
            sortOfflineTracks(matching, sort)
        }
    }

    val waiting = OfflineDownloader.requests.values.filter { !OfflineStore.isDownloaded(it.trackId) }
    // A25: only queued or running songs can be stopped; a failed one has nothing left to cancel.
    val stoppable = waiting.count {
        val s = OfflineStore.status[it.trackId]
        s == OfflineStatus.Queued || s is OfflineStatus.Downloading
    }
    val np by SongPlayer.nowPlaying
    val activeContextLabel = activeCollection?.name ?: title
    val playingHere = LocalPlayer.active.value &&
        (LocalPlayer.contextName == activeContextLabel || LocalPlayer.contextName == title)
    var menuSong by remember { mutableStateOf<SongsModel?>(null) }
    menuSong?.let { sel ->
        com.music.spotui.ui.components.SongOptionsSheet(
            song = sel,
            navController = navController,
            context = context,
            onDismiss = { menuSong = null },
        )
    }
    fun play(index: Int, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        LocalPlayer.play(
            tracks.map(OfflineStore::toLocalTrack),
            index,
            shuffle = shuffle,
            context = activeContextLabel,
        )
    }

    Box(Modifier.fillMaxSize().background(AppBackground)) {
        if (allTracks.isEmpty() && waiting.isEmpty()) Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        ) {
            Image(painterResource(R.drawable.downloads_cover), null, modifier = Modifier.size(120.dp).clip(RoundedCornerShape(6.dp)))
            Text(stringResource(R.string.downloads_empty_title), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 24.dp))
            Text(stringResource(R.string.downloads_empty_subtitle), color = Color(0xFFB3B3B3), fontSize = 14.sp,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        } else LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(
                    Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFF1D6B45), AppBackground)))
                        .statusBarsPadding().padding(horizontal = 16.dp),
                ) {
                    Spacer(Modifier.height(56.dp))
                    if (allTracks.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        ) {
                            FindInListField(
                                query = find,
                                onQuery = { find = it },
                                hint = stringResource(R.string.find_in_context_edit_text_playlist_hint),
                                modifier = Modifier.weight(1f),
                            )
                            TrackSortButton(sort) {
                                sort = it
                                TrackOrder.save(context, downloadsSortUri, it)
                            }
                        }
                    }
                    Image(
                        painterResource(R.drawable.downloads_cover), null,
                        modifier = Modifier.align(Alignment.CenterHorizontally).size(200.dp).clip(RoundedCornerShape(6.dp)),
                    )
                    Text(title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp))
                    val n = tracks.size
                    val size = Formatter.formatShortFileSize(context, OfflineStore.totalBytes())
                    Text(pluralStringResource(R.plurals.local_files_count, n, n) + " · " + stringResource(R.string.downloads_size, size),
                        color = Color(0xFFB3B3B3), fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                    Text(stringResource(R.string.downloads_source), color = Color(0xFFB3B3B3), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))

                    if (collections.isNotEmpty()) {
                        LazyRow(
                            contentPadding = PaddingValues(vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            item(key = "chip_all") {
                                DownloadFilterChip(
                                    label = "All",
                                    selected = selectedCollectionUri == null,
                                    onClick = { selectedCollectionUri = null },
                                )
                            }
                            items(collections, key = { "chip_${it.uri}" }) { col ->
                                DownloadFilterChip(
                                    label = col.name,
                                    selected = selectedCollectionUri == col.uri,
                                    onClick = {
                                        selectedCollectionUri = if (selectedCollectionUri == col.uri) null else col.uri
                                    },
                                )
                            }
                        }
                    }

                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        Icon(painterResource(R.drawable.ic_player_shuffle), stringResource(R.string.play_mode_item_shuffle_title), tint = Color.White,
                            modifier = Modifier.size(28.dp).pressable { if (tracks.isNotEmpty()) play(tracks.indices.random(), shuffle = true) })
                        Spacer(Modifier.width(20.dp))
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(56.dp)
                                .pressable(0.92f) {
                                    when {
                                        playingHere && SongPlayer.isPlaying() -> SongPlayer.pause()
                                        playingHere -> SongPlayer.play()
                                        else -> play(0)
                                    }
                                }
                                .clip(CircleShape).background(AppAccent),
                        ) {
                            Icon(painterResource(if (playingHere && SongPlayer.isPlaying()) R.drawable.ic_playing else R.drawable.play_svgrepo_com),
                                stringResource(R.string.content_description_play_button), tint = Color.Black, modifier = Modifier.size(26.dp))
                        }
                    }
                }
            }
            // A25: one tap empties the queue; the songs already downloaded stay untouched.
            if (stoppable > 0) item(key = "stop_all") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A2A2A))
                        .pressable(0.98f) { OfflineDownloader.stopAll() }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.downloads_stop_all_title), color = Color.White, fontSize = 14.sp)
                        Text(stringResource(R.string.downloads_stop_all_detail), color = Color(0xFFB3B3B3), fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Icon(Icons.Default.Close, stringResource(R.string.downloads_stop_all_title),
                        tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
                }
            }
            // Songs still on their way (or that could not be downloaded).
            items(waiting, key = { "w:" + it.trackId }) { r ->
                val status = OfflineStore.status[r.trackId]
                val problem = status == OfflineStatus.Failed || status == OfflineStatus.Unavailable
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                        .pressable(0.97f) { if (status == OfflineStatus.Failed) OfflineDownloader.retry(r.trackId) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    GlideImage(
                        transition = CrossFade,
                        model = r.coverUrl, contentDescription = null, contentScale = ContentScale.Crop,
                        loading = placeholder(R.drawable.placeholder), failure = placeholder(R.drawable.placeholder),
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)),
                    )
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(r.title, color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val line = when (status) {
                            OfflineStatus.Unavailable -> stringResource(R.string.download_unavailable)
                            OfflineStatus.Failed -> stringResource(R.string.download_badge_error_content_description) + " · " + stringResource(R.string.download_retry)
                            else -> stringResource(R.string.download_notification_downloading)
                        }
                        Text(line, color = if (problem) Color(0xFFE22134) else Color(0xFFB3B3B3), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DownloadBadge(status, size = 20.dp)
                    Spacer(Modifier.width(16.dp))
                    Icon(Icons.Default.Close, stringResource(R.string.download_cancel), tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(24.dp).pressable(0.96f) { OfflineDownloader.cancel(r.trackId) })
                }
            }
            itemsIndexed(tracks, key = { _, t -> t.trackId }) { i, t ->
                val current = LocalPlayer.active.value && np.uri == "local:${OfflineStore.localIdOf(t.trackId)}"
                val saved = rememberTrackSaved(t.trackId)
                val songModel = remember(t) { OfflineStore.toSong(t) }
                com.music.spotui.ui.components.SwipeToRemove(t.trackId, onRemove = {
                    val gone = OfflineCollections.removeSong(t.trackId)
                    android.widget.Toast.makeText(context, context.getString(if (gone) R.string.toast_undownload else R.string.download_kept_by_list), android.widget.Toast.LENGTH_SHORT).show()
                }) { Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().pressable(0.97f) { play(i) }.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    GlideImage(
                        transition = CrossFade,
                        model = OfflineStore.coverFile(t) ?: t.coverUrl, contentDescription = null, contentScale = ContentScale.Crop,
                        loading = placeholder(R.drawable.placeholder), failure = placeholder(R.drawable.downloads_cover),
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)),
                    )
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(t.title, color = if (current) AppAccent else Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DownloadBadge(OfflineStatus.Done, size = 14.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(t.artist, color = Color(0xFFB3B3B3), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (saved) {
                        AddButton(song = { songModel }, liked = true, size = 18.dp) {
                            SongPlayer.setTrackSaved(t.trackId, true)
                        }
                        Spacer(Modifier.width(16.dp))
                    }
                    Icon(Icons.Default.MoreVert, stringResource(R.string.content_description_context_menu), tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(24.dp).pressable(0.96f) { menuSong = songModel })
                } }
            }
            item { Spacer(Modifier.height(com.music.spotui.ui.components.bottomBarsSpace())) }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.statusBarsPadding().padding(12.dp).size(40.dp).clip(CircleShape)
                .background(Color(0x66000000)).pressable { navController.navigateUp() },
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.accessibility_navigate_back), tint = Color.White)
        }
    }
}

@Composable
private fun DownloadFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) AppAccent else Color(0xFF282828))
            .border(
                width = 1.dp,
                color = if (selected) AppAccent else Color(0x33FFFFFF),
                shape = RoundedCornerShape(16.dp),
            )
            .pressable(0.95f) { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = if (selected) Color.Black else Color.White,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
