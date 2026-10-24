package com.music.spotui.ui.screens

import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.music.spotui.ui.components.edgeSwipeBack
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.ui.res.stringResource

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import com.music.spotui.ui.components.SkeletonKind
import com.music.spotui.ui.components.pressable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.CrossFade
import com.bumptech.glide.integration.compose.GlideImage
import com.music.spotui.data.api.Api
import com.music.spotui.data.api.EditablePlaylists
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.ContextPageModel
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.CurrentSongState
import com.music.spotui.di.Graph
import com.music.spotui.di.Palette
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.Loader
import com.music.spotui.ui.navigation.artistRoute
import app.myspoti.engine.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.filled.AutoAwesome
import app.myspoti.host.android.MyspotiRuntime

private val SpotifyGreen: Color get() = com.music.spotui.ui.theme.AppAccent
private val PageBackground: Color get() = com.music.spotui.ui.theme.AppBackground

/** Album / playlist page, loaded by Spotify id and laid out like the official app. */
class ContextViewModel(
    private val api: Api = Graph.api,
    private val state: CurrentSongState = Graph.currentSongState,
) : ViewModel() {
    companion object {
        // Last pages shown, process-wide (back / forward / reopen are instant).
        private val recent = object : java.util.LinkedHashMap<String, Response<ContextPageModel>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Response<ContextPageModel>>?) = size > 12
        }.let { java.util.Collections.synchronizedMap(it) }

        /** Signed out: the pages shown belong to the old account. */
        fun clearCache() { recent.clear(); inflight.values.forEach { it.cancel() }; inflight.clear() }

        // Look-ups started by a touch (see pressHint), joined by the page when it opens.
        private val inflight = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Unit>>()
        private val prefetchScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

        /** Pages likely to be opened next (top of the library, home shortcuts), loaded quietly. */
        fun prefetchSoon(pages: List<Pair<String, String>>) {
            prefetchScope.launch {
                kotlinx.coroutines.delay(1_000)
                // One after the other: they must not slow down what is on screen.
                for ((type, id) in pages) {
                    prefetch(type, id)
                    inflight["$type:$id"]?.await()
                }
            }
        }

        /** Start loading a page the user is about to open (first page of tracks). */
        fun prefetch(type: String, id: String) {
            if (id.isBlank() && type != "liked") return
            val key = "$type:$id"
            if (recent[key] is Response.Success || inflight.containsKey(key)) return
            inflight[key] = prefetchScope.async {
                try {
                    val api = Graph.api
                    val flow = when (type) {
                        "album" -> api.getAlbumPage(id)
                        "liked" -> api.getLikedPage()
                        else -> api.getPlaylistPage(id)
                    }
                    val first = flow.first { it !is Response.Loading }
                    if (first is Response.Success) {
                        recent[key] = first
                        // The hearts are known before the page opens.
                        val p = first.data
                        if (p.type != "liked" && p.type != "liked-artist") SongPlayer.libraryContains(
                            p.tracks.mapNotNull { s -> s.spotifyTrackId.takeIf { it.isNotBlank() }?.let { "spotify:track:$it" } } + p.uri)
                    }
                } catch (t: kotlinx.coroutines.CancellationException) {
                    throw t
                } catch (_: Throwable) {
                } finally {
                    inflight.remove(key)
                }
            }
        }
    }
    private val _page = MutableStateFlow<Response<ContextPageModel>>(Response.Loading())
    val page: StateFlow<Response<ContextPageModel>> = _page
    val savedUris = mutableStateOf<Set<String>>(emptySet())
    val contextSaved = mutableStateOf(false)
    /** The page is a playlist the user may change: only there can a track be removed. */
    val editableContext = mutableStateOf(false)
    private var key: String? = null
    private var savedChecked = 0

    private var lastType = ""
    private var lastId = ""

    /** After an edit (name, cover, description): the page from the server again. */
    fun reload() {
        val k = key ?: return
        recent.remove(k)
        key = null
        load(lastType, lastId)
    }

    fun load(type: String, id: String) {
        lastType = type; lastId = id
        if (key == "$type:$id") return
        key = "$type:$id"
        // A page seen recently is on screen from the first frame (no blank, then pop,
        // in the middle of the slide); the network still refreshes it behind.
        recent[key]?.let { _page.value = it }
        val pending = inflight[key]
        viewModelScope.launch(Dispatchers.IO) {
            // Touched a moment ago: join that look-up instead of sending a second one.
            if (pending != null && _page.value !is Response.Success) {
                runCatching { pending.await() }
                recent[key]?.let { _page.value = it }
            }
            val flow = when (type) {
                "album" -> api.getAlbumPage(id)
                "liked" -> api.getLikedPage()
                // id: "<artist id>|<artist name>"
                "liked-artist" -> api.getLikedByArtistPage(id.substringBefore('|'), id.substringAfter('|', ""))
                else -> api.getPlaylistPage(id)
            }
            // Liked Songs: a like or unlike anywhere puts the list right at once.
            if (type == "liked") viewModelScope.launch(Dispatchers.IO) {
                SongPlayer.likedChanges.drop(1).collectLatest {
                    kotlinx.coroutines.delay(1_500) // Spotify's library takes the change first
                    val songs = api.mergeLikedHead() ?: return@collectLatest
                    val current = (_page.value as? Response.Success)?.data ?: return@collectLatest
                    val updated = Response.Success(current.copy(
                        tracks = songs,
                        metaLine = Graph.app.resources.getQuantityString(R.plurals.liked_songs_header_metadata_text, songs.size, songs.size),
                    ))
                    _page.value = updated
                    recent[key!!] = updated
                }
            }
            flow.collect { r ->
                if (r is Response.Loading && _page.value is Response.Success) return@collect
                _page.value = r
                if (r is Response.Success) {
                    recent[key!!] = r
                    val p = r.data
                    // Check saved state on the first page and again once complete.
                    if ((savedChecked == 0 && p.tracks.isNotEmpty()) || (p.complete && savedChecked < 2)) {
                        savedChecked = if (p.complete) 2 else 1
                        refreshSaved(p)
                    }
                }
            }
        }
    }

    private fun refreshSaved(p: ContextPageModel) = viewModelScope.launch {
        if (p.type == "liked" || p.type == "liked-artist") {
            // Everything here is liked by definition.
            savedUris.value = p.tracks.map { "spotify:track:${it.spotifyTrackId}" }.toSet()
            return@launch
        }
        val uris = p.tracks.mapNotNull { s -> s.spotifyTrackId.takeIf { it.isNotBlank() }?.let { "spotify:track:$it" } } + p.uri
        val saved = SongPlayer.libraryContains(uris)
        savedUris.value = saved
        contextSaved.value = p.uri in saved
        // A11: Spotify refuses an edit to a list the user cannot change, so the entry is only
        // offered where it would work (own and collaborative playlists, as Spotify itself).
        if (p.type == "playlist") editableContext.value = withContext(Dispatchers.IO) {
            runCatching { EditablePlaylists.load().any { it.id == p.uri.substringAfterLast(':') } }.getOrDefault(false)
        }
    }

    fun toggleContextSaved(p: ContextPageModel) {
        val on = !(SongPlayer.library[p.uri] ?: contextSaved.value)
        com.music.spotui.di.Haptics.toggle(on)
        contextSaved.value = on
        SongPlayer.setSaved(p.uri, on)
    }

    /**
     * The track left this page's playlist: its row goes at once, so the list answers the tap
     * without waiting for Spotify (the write reads every page of the playlist first). A
     * failure puts the page back from the server.
     */
    fun dropTrack(song: SongsModel) {
        val k = key ?: return
        val current = (_page.value as? Response.Success)?.data ?: return
        val updated = Response.Success(current.copy(tracks = current.tracks.filterNot { it.id == song.id }))
        _page.value = updated
        recent[k] = updated
    }

    /**
     * Smart shuffle (Spotify keeps its own for Premium): the playlist shuffled, with one of
     * Spotify's recommendations for it after every [SmartShuffle.EVERY] songs.
     */
    fun playSmart(p: ContextPageModel, first: SongsModel?) {
        viewModelScope.launch { SmartShuffle.start(p, first) }
    }

    fun play(p: ContextPageModel, song: SongsModel, sort: TrackOrder = TrackOrder()) {
        // No connection (the Web Player cannot stream), or the data saver on mobile data:
        // the page's downloaded songs play from the phone.
        val app = Graph.app
        if (com.music.spotui.data.offline.OfflineStore.isDownloaded(song.spotifyTrackId) &&
            (com.music.spotui.data.DataSaver.active || !com.music.spotui.data.offline.OfflineStore.isOnline(app))) {
            val local = sort.apply(p.tracks).mapNotNull { com.music.spotui.data.offline.OfflineStore.tracks[it.spotifyTrackId] }.distinctBy { it.trackId }
            val at = local.indexOfFirst { it.trackId == song.spotifyTrackId }.coerceAtLeast(0)
            if (local.size < p.tracks.size)
                android.widget.Toast.makeText(app, app.getString(R.string.offline_only_downloaded, local.size, p.tracks.size), android.widget.Toast.LENGTH_SHORT).show()
            com.music.spotui.di.LocalPlayer.play(local.map(com.music.spotui.data.offline.OfflineStore::toLocalTrack), at, context = p.name)
            return
        }
        state.updateQueue(p.tracks)
        // B26: keep the list's own name — the player header falls back to it when Spotify's
        // context label comes back blank (it then showed the album's name).
        SongPlayer.registerContextName(p.uri, p.name)
        if (p.type == "liked" || p.type == "liked-artist") SongPlayer.registerLikedSongs(p.tracks.map { it.url })
        // Sorted by the user: Spotify plays the list in that order, not the playlist's. An
        // artist's liked songs are a part of Liked Songs: always given, so only they play.
        val order = if (sort.isCustom && p.type != "liked-artist") null else sort.apply(p.tracks).map { "spotify:track:${it.spotifyTrackId}" }
        if (order != null) state.updateQueue(sort.apply(p.tracks))
        SongPlayer.playSong(song.url, contextUri = p.uri, order = order)
        state.updateSongState(song.coverUri, song.title, song.singer, true, song.id, 0, song.album)
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun ContextScreen(navController: NavController, type: String, id: String) {
    val vm: ContextViewModel = viewModel(key = "$type:$id")
    // During composition, so a cached page is there for the very first frame.
    remember(type, id) { vm.load(type, id) }
    val resp by vm.page.collectAsState()
    Box(modifier = Modifier.fillMaxSize().background(PageBackground)) {
        when (val r = resp) {
            is Response.Loading -> Loader(SkeletonKind.Header)
            is Response.Error -> Text(
                r.error,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
            is Response.Success -> ContextContent(navController, vm, r.data)
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun ContextContent(navController: NavController, vm: ContextViewModel, p: ContextPageModel) {
    val context = LocalContext.current
    val playerVm: com.music.spotui.ui.viewmodel.PlayerViewModel = viewModel()
    val currentSongId = playerVm.currentSongId.value
    val playing = playerVm.currentSongPlayingState.value
    val shuffle = playerVm.shuffleState.value
    val np by SongPlayer.nowPlaying
    val isThisContext = np.contextUri == p.uri || ((p.type == "liked" || p.type == "liked-artist") && np.contextUri.contains(":collection"))
    val saved by vm.savedUris
    val contextSaved by vm.contextSaved

    var tintTarget by remember { mutableStateOf(Palette.cachedSecond(p.coverUri) ?: Color(0xFF3A3A3A)) }
    LaunchedEffect(p.coverUri) {
        if (p.coverUri.isNotBlank()) Palette().extractSecondColorFromCoverUrl(context, p.coverUri) { tintTarget = it }
    }
    val tint by androidx.compose.animation.animateColorAsState(tintTarget, androidx.compose.animation.core.tween(350), label = "tint")
    var menuSong by remember { mutableStateOf<SongsModel?>(null) }
    // "Find in Liked Songs / in playlist".
    var find by remember { mutableStateOf("") }
    // "Sort by" for the list, kept per list (also after leaving it or restarting the app).
    val ctx = LocalContext.current
    var sort by remember(p.uri) { mutableStateOf(TrackOrder.load(ctx, p.uri)) }
    var askRemoveDownloads by remember { mutableStateOf(false) }
    var playlistMenu by remember { mutableStateOf(false) }
    // A11: confirmations (and their Undo) float above the list, not inside a menu that closes.
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // A11: the row goes at once, then the removal is written to Spotify. The snackbar keeps
    // the mistake reversible: Undo adds the track back and the page is read again.
    fun removeFromThisPlaylist(song: SongsModel) {
        val playlistId = p.uri.substringAfterLast(':')
        vm.dropTrack(song)
        com.music.spotui.data.api.SpotifySync.removeTrackFromPlaylist(context, playlistId, song.spotifyTrackId) { ok ->
            if (!ok) {
                vm.reload()
                android.widget.Toast.makeText(context, context.getString(R.string.playlist_change_refused), android.widget.Toast.LENGTH_SHORT).show()
            } else scope.launch {
                val chosen = snackbar.showSnackbar(
                    message = context.getString(R.string.removed_from, p.name),
                    actionLabel = context.getString(R.string.undo),
                    withDismissAction = false,
                    // Long: the write has just gone through, the mistake deserves time to be undone.
                    duration = androidx.compose.material3.SnackbarDuration.Long,
                )
                if (chosen == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                    com.music.spotui.data.api.SpotifySync.addTrackToPlaylist(context, playlistId, song.spotifyTrackId) { back ->
                        if (back) vm.reload()
                        else android.widget.Toast.makeText(context, context.getString(R.string.playlist_change_refused), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    if (playlistMenu) com.music.spotui.ui.components.PlaylistOptionsSheet(
        page = p,
        onDismiss = { playlistMenu = false },
        onChanged = { vm.reload() },
        onDeleted = { navController.navigateUp() },
        onProfile = { navController.navigate(com.music.spotui.ui.navigation.profileRoute(it, p.ownerName)) },
    )
    if (askRemoveDownloads) androidx.compose.material3.AlertDialog(
        onDismissRequest = { askRemoveDownloads = false },
        containerColor = Color(0xFF282828),
        title = { Text(stringResource(R.string.playlist_entity_remove_download_dialog_title), color = Color.White) },
        text = { Text(stringResource(R.string.playlist_entity_remove_download_dialog_description), color = Color(0xFFB3B3B3)) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                askRemoveDownloads = false
                com.music.spotui.data.offline.OfflineCollections.remove(p.uri)
                android.widget.Toast.makeText(context, context.getString(R.string.toast_undownload), android.widget.Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.playlist_entity_remove_download_dialog_positive_button), color = SpotifyGreen) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { askRemoveDownloads = false }) {
                Text(stringResource(R.string.add_to_playlist_button_cancel), color = Color.White)
            }
        },
    )
    menuSong?.let { sel ->
        val inPlaylist = p.type == "playlist"
        val canRemove = inPlaylist && vm.editableContext.value
        com.music.spotui.ui.components.SongOptionsSheet(
            song = sel,
            navController = navController,
            context = context,
            onDismiss = { menuSong = null },
            // The removal entry only exists on a playlist page the user may change: nowhere else
            // is there a list to take the track out of.
            onRemoveFromPlaylist = if (canRemove) ({ removeFromThisPlaylist(sel) }) else null,
            currentPlaylistId = if (inPlaylist) p.uri.substringAfterLast(':') else null,
        )
    }

    // Liked Songs: a track unliked anywhere leaves the list right away. Derived state:
    // recomputed when the page, the search, the sort or a like changes, while a recomposition
    // of the screen with nothing new in them re-sorts nothing (a 900-track list is real work).
    val findable = p.type == "liked" || p.type == "playlist"
    val all by remember(p) {
        derivedStateOf {
            if (p.type == "liked" || p.type == "liked-artist") p.tracks.filter { SongPlayer.library["spotify:track:${it.spotifyTrackId}"] != false } else p.tracks
        }
    }
    val rows by remember(p, find, sort) {
        derivedStateOf {
            val found = if (findable) all.filter { com.music.spotui.ui.components.matchesFind(find, it.title, it.singer, it.album) } else all
            if (findable) sort.apply(found) else found
        }
    }
    // Unique and stable for a given list: a playlist can hold the same song twice (the id alone
    // is not unique) and the index changes at every sort (which would lose every item).
    val keys = remember(rows) {
        val seen = HashMap<String, Int>()
        rows.map { s ->
            val id = s.id.toString()
            val n = (seen[id] ?: 0) + 1
            seen[id] = n
            if (n == 1) id else "$id#$n"
        }
    }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var showFindBar by remember(p.uri) { mutableStateOf(false) }
    LaunchedEffect(listState.firstVisibleItemIndex) {
        if (listState.firstVisibleItemIndex > 1 && find.isBlank()) {
            showFindBar = false
        }
    }
    val pullToFindConnection = remember(p.uri, findable) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPostScroll(
                consumed: androidx.compose.ui.geometry.Offset,
                available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
            ): androidx.compose.ui.geometry.Offset {
                if (findable &&
                    source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput &&
                    listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0 &&
                    available.y > 14f
                ) {
                    showFindBar = true
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    val coverProgress by remember {
        derivedStateOf {
            when {
                listState.firstVisibleItemIndex > 0 -> 1f
                else -> (listState.firstVisibleItemScrollOffset / 500f).coerceIn(0f, 1f)
            }
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .edgeSwipeBack { navController.navigateUp() }
    ) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(pullToFindConnection)
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                PageBackground,
                                lerp(tint, PageBackground, 0.35f),
                                PageBackground,
                            )
                        )
                    )
                    .statusBarsPadding()
                    .padding(top = 56.dp)
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = findable && all.isNotEmpty() && (showFindBar || find.isNotBlank()),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 12.dp),
                    ) {
                        com.music.spotui.ui.components.FindInListField(
                            find, { find = it },
                            stringResource(if (p.type == "liked") R.string.find_in_context_edit_text_liked_songs_hint else R.string.find_in_context_edit_text_playlist_hint),
                            Modifier.weight(1f),
                        )
                        TrackSortButton(sort) { sort = it; TrackOrder.save(ctx, p.uri, it) }
                    }
                }
                // Playlist/album cover (216.dp square matching official Spotify app proportions)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(216.dp)
                        .alpha(1f - coverProgress)
                ) {
                    val scale = 1f - (coverProgress * 0.4f)
                    val coverMod = Modifier
                        .size((216 * scale).dp)
                        .shadow(16.dp, RoundedCornerShape(4.dp))
                        .clip(RoundedCornerShape(4.dp))
                        .background(com.music.spotui.ui.theme.ScSecondary)
                    if (p.type == "liked" || p.type == "liked-artist") {
                        LikedCover(coverMod)
                    } else {
                        GlideImage(
                            transition = CrossFade,
                            model = p.coverUri,
                            contentDescription = "",
                            contentScale = ContentScale.Crop,
                            modifier = coverMod,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                // 1:1 port of spotify-clone components/Preview/Summary/Summary.tsx
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        text = p.name,
                        color = Color.White,
                        fontSize = 25.sp,
                        letterSpacing = (-1).sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (p.ownerName.isNotBlank()) {
                        Spacer(Modifier.height(13.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.pressable(0.96f) {
                                if (p.ownerUri.startsWith("spotify:artist:")) {
                                    navController.navigate(artistRoute(p.ownerName.substringBefore(",").trim(), p.ownerUri.substringAfterLast(":")))
                                } else if (p.ownerUri.startsWith("spotify:user:")) {
                                    navController.navigate(com.music.spotui.ui.navigation.profileRoute(p.ownerUri.substringAfterLast(":"), p.ownerName))
                                }
                            },
                        ) {
                            if (p.ownerAvatar.isNotBlank()) {
                                GlideImage(
                                    transition = CrossFade,
                                    model = p.ownerAvatar,
                                    contentDescription = "",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(24.dp).clip(CircleShape),
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                text = p.ownerName,
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    val infoLine = p.metaLine.ifBlank { p.description }
                    if (infoLine.isNotBlank() || p.explicit) {
                        Spacer(Modifier.height(9.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (p.explicit && p.type == "album") {
                                ExplicitBadge()
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                text = infoLine,
                                color = com.music.spotui.ui.theme.ScLightGrey,
                                fontSize = 13.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                // Action row: Download first on playlists (Like + Download on albums), then Share, then MoreVert, with Shuffle & Play on right
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 15.dp, bottom = 12.dp)
                ) {
                    if (p.type == "album") {
                        LikeButton(liked = SongPlayer.library[p.uri] ?: contextSaved, size = 26.dp) { vm.toggleContextSaved(p) }
                        Spacer(Modifier.width(24.dp))
                    }
                    val ids = p.tracks.map { it.spotifyTrackId }.filter { it.isNotBlank() }.distinct()
                    val downloadedCount = ids.count {
                        com.music.spotui.data.offline.OfflineStore.isDownloaded(it) ||
                            com.music.spotui.data.offline.OfflineStore.status[it] == com.music.spotui.data.offline.OfflineStatus.Unavailable
                    }
                    val busy = ids.any {
                        val st = com.music.spotui.data.offline.OfflineStore.status[it]
                        st == com.music.spotui.data.offline.OfflineStatus.Queued || st is com.music.spotui.data.offline.OfflineStatus.Downloading
                    }
                    val listKept = com.music.spotui.data.offline.OfflineCollections.isDownloaded(p.uri)
                    LaunchedEffect(listKept, p.complete, p.tracks.size) { if (listKept && p.complete) com.music.spotui.data.offline.OfflineCollections.sync(p) }
                    com.music.spotui.ui.components.DownloadListButton(downloadedCount, ids.size, busy, kept = listKept) {
                        if (listKept) askRemoveDownloads = true
                        else {
                            com.music.spotui.ui.components.DownloadAsk.start(context) { q ->
                                com.music.spotui.di.Haptics.confirm()
                                com.music.spotui.data.offline.OfflineCollections.download(p, q)
                                android.widget.Toast.makeText(context, context.getString(R.string.download_notification_downloading), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    if (p.type == "playlist" || p.type == "album") {
                        Spacer(Modifier.width(24.dp))
                        Icon(
                            painter = painterResource(id = R.drawable.ic_share),
                            contentDescription = stringResource(R.string.a11y_share),
                            tint = com.music.spotui.ui.theme.ScLightGrey,
                            modifier = Modifier
                                .size(22.dp)
                                .pressable(0.9f) {
                                    val shareId = p.uri.substringAfterLast(":")
                                    val shareUrl = "https://open.spotify.com/${p.type}/$shareId"
                                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(android.content.Intent.EXTRA_SUBJECT, p.name)
                                        putExtra(android.content.Intent.EXTRA_TEXT, shareUrl)
                                    }
                                    context.startActivity(android.content.Intent.createChooser(send, p.name).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                                },
                        )
                    }
                    if (p.type == "playlist") {
                        Spacer(Modifier.width(24.dp))
                        Icon(
                            Icons.Default.MoreVert,
                            stringResource(R.string.content_description_context_menu),
                            tint = com.music.spotui.ui.theme.ScLightGrey,
                            modifier = Modifier
                                .size(24.dp)
                                .pressable(0.9f) { playlistMenu = true },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    val smart = SmartShuffle.on.value == p.uri
                    Box {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_player_shuffle),
                            contentDescription = stringResource(if (smart) R.string.smart_shuffle_title else R.string.play_mode_item_shuffle_title),
                            tint = if (shuffle || smart) SpotifyGreen else com.music.spotui.ui.theme.ScLightGrey,
                            modifier = Modifier
                                .size(26.dp)
                                .drawWithContent {
                                    drawContent()
                                    if (shuffle || smart) drawCircle(SpotifyGreen, radius = 2.dp.toPx(),
                                        center = androidx.compose.ui.geometry.Offset(size.width / 2, size.height + 7.dp.toPx()))
                                }
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    when {
                                        smart -> { SmartShuffle.on.value = null; playerVm.updateShuffleState(false) }
                                        shuffle && (p.type == "playlist" || p.type == "liked") -> {
                                            SmartShuffle.on.value = p.uri
                                            android.widget.Toast.makeText(context, context.getString(R.string.smart_shuffle_on), android.widget.Toast.LENGTH_SHORT).show()
                                            if (isThisContext) vm.playSmart(p, p.tracks.firstOrNull { it.id == currentSongId })
                                        }
                                        else -> playerVm.updateShuffleState(!shuffle)
                                    }
                                },
                        )
                        if (smart) Icon(
                            androidx.compose.material.icons.Icons.Default.AutoAwesome, contentDescription = null, tint = SpotifyGreen,
                            modifier = Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp).size(11.dp),
                        )
                    }
                    Spacer(Modifier.width(20.dp))
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(56.dp)
                            .pressable(pressedScale = 0.92f) {
                                when {
                                    isThisContext && playing -> SongPlayer.pause()
                                    isThisContext -> SongPlayer.play()
                                    p.tracks.isNotEmpty() && SmartShuffle.on.value == p.uri -> vm.playSmart(p, null)
                                    p.tracks.isNotEmpty() -> vm.play(p, if (shuffle) p.tracks.random() else sort.apply(p.tracks).first(), sort)
                                }
                            }
                            .clip(CircleShape)
                            .background(SpotifyGreen),
                    ) {
                        Icon(
                            painter = painterResource(id = if (isThisContext && playing) R.drawable.ic_playing else R.drawable.play_svgrepo_com),
                            contentDescription = if (isThisContext && playing) stringResource(R.string.content_description_pause_button) else stringResource(R.string.content_description_play_button),
                            tint = Color.Black,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }
        if (rows.isEmpty() && find.isNotBlank()) item(key = "find-empty") {
            com.music.spotui.ui.components.FindInListEmpty(stringResource(R.string.assisted_curation_search_empty_view_no_result_title, find.trim()))
        }
        itemsIndexed(rows, key = { i, _ -> keys[i] }) { _, song ->
            com.music.spotui.ui.components.SwipeToQueue(song) { TrackRow(
                song = song,
                showCover = p.type != "album",
                isCurrent = song.id == currentSongId,
                saved = SongPlayer.library["spotify:track:${song.spotifyTrackId}"] ?: ("spotify:track:${song.spotifyTrackId}" in saved),
                offline = com.music.spotui.data.offline.OfflineStore.statusOf(song.spotifyTrackId),
                onClick = { if (SmartShuffle.on.value == p.uri) vm.playSmart(p, song) else vm.play(p, song, sort) },
                onMore = { menuSong = song },
            ) }
        }
        // Phone files added to this Spotify playlist on this phone (Spotify can't hold them).
        if (p.type == "playlist") {
            val playlistId = p.uri.substringAfterLast(":")
            val additions = com.music.spotui.data.local.LocalPlaylists.additions[playlistId].orEmpty()
            if (additions.isNotEmpty()) {
                item {
                    Text(stringResource(R.string.local_added_section), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(16.dp, 20.dp, 16.dp, 4.dp))
                }
                localItems(additions, p.name) { com.music.spotui.data.local.LocalPlaylists.removeFromSpotify(playlistId, it.key) }
            }
        }
        // 1:1 port of spotify-clone components/Preview/Info/Info.tsx & Artists/Artists.tsx
        if (p.type == "album" || p.type == "playlist") {
            item(key = "preview-info") {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 35.dp)) {
                    if (p.metaLine.isNotBlank()) {
                        Text(
                            text = p.metaLine,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        text = "${p.tracks.size} songs",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (p.ownerName.isNotBlank() && p.type == "album") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(15.dp),
                            modifier = Modifier
                                .padding(top = 26.dp)
                                .pressable(0.96f) {
                                    if (p.ownerUri.startsWith("spotify:artist:")) {
                                        navController.navigate(artistRoute(p.ownerName.substringBefore(",").trim(), p.ownerUri.substringAfterLast(":")))
                                    }
                                },
                        ) {
                            if (p.ownerAvatar.isNotBlank()) {
                                GlideImage(
                                    transition = CrossFade,
                                    model = p.ownerAvatar,
                                    contentDescription = "",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(50.dp)
                                        .clip(CircleShape)
                                        .background(com.music.spotui.ui.theme.ScSecondary),
                                )
                            } else {
                                androidx.compose.foundation.Image(
                                    painter = painterResource(R.drawable.sc_user),
                                    contentDescription = "",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(50.dp)
                                        .clip(CircleShape)
                                        .background(com.music.spotui.ui.theme.ScSecondary),
                                )
                            }
                            Text(
                                text = p.ownerName,
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
        // An album: Spotify's "More like this", albums close to its first song.
        if (p.type == "album") item(key = "similar") { SimilarAlbums(navController, p.tracks.firstOrNull()?.spotifyTrackId.orEmpty(), p.uri) }
        item { Spacer(Modifier.height(com.music.spotui.ui.components.bottomBarsSpace())) }
    }

    // 1:1 port of spotify-clone components/CommonHeader/CommonHeader.tsx (sticky animated header)
    val headerAlpha = ((coverProgress - 0.65f) / 0.35f).coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .align(Alignment.TopCenter)
            .background(lerp(tint, com.music.spotui.ui.theme.ScSecondary, 0.5f).copy(alpha = headerAlpha))
            .statusBarsPadding()
            .height(56.dp)
            .padding(horizontal = 12.dp),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.accessibility_navigate_back),
            tint = Color.White,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(28.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { navController.navigateUp() },
        )
        Text(
            text = p.name,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 44.dp)
                .alpha(headerAlpha),
        )
    }

    com.music.spotui.ui.components.FastScrollbar(listState, Modifier.align(Alignment.CenterEnd))
    androidx.compose.material3.SnackbarHost(
        hostState = snackbar,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = com.music.spotui.ui.components.bottomBarsSpace()),
    ) { data ->
        androidx.compose.material3.Snackbar(
            snackbarData = data,
            containerColor = Color(0xFF282828),
            contentColor = Color.White,
            actionContentColor = SpotifyGreen,
        )
    }
    }
}

/** How long a greyed track is given to start before the app says why it did not. */
private const val UNAVAILABLE_CHECK_MS = 4_000L

/**
 * 1:1 port of spotify-clone components/Preview/Track/Track.tsx & styles.ts:
 * - 58.dp row height, 16.dp horizontal padding
 * - 50.dp sharp square cover on playlists (15.dp right margin, #282828 background)
 * - 17.sp title (#1ED760 when playing, #FFFFFF otherwise)
 * - 5.dp top margin for subtitle row: download badge + explicit badge + 14.sp #B3B3B3 artist name
 * - Right side: 18.dp green check circle if saved + MoreVert menu icon
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun TrackRow(
    song: SongsModel,
    showCover: Boolean,
    isCurrent: Boolean,
    saved: Boolean,
    offline: com.music.spotui.data.offline.OfflineStatus?,
    onClick: () -> Unit,
    onMore: () -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val appContext = androidx.compose.ui.platform.LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .then(if (song.playable) Modifier else Modifier.alpha(0.4f))
            .clickable {
                onClick()
                if (!song.playable) scope.launch {
                    kotlinx.coroutines.delay(UNAVAILABLE_CHECK_MS)
                    val playing = SongPlayer.nowPlaying.value.uri
                    if (song.spotifyTrackId.isNotBlank() && !playing.endsWith(song.spotifyTrackId)) {
                        android.widget.Toast.makeText(
                            appContext,
                            app.myspoti.engine.R.string.track_unavailable_region,
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
            .padding(horizontal = 16.dp)
    ) {
        if (showCover) {
            GlideImage(
                transition = CrossFade,
                model = song.coverUri,
                contentDescription = "",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(com.music.spotui.ui.theme.ScSecondary),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                song.title,
                color = if (isCurrent) SpotifyGreen else Color.White,
                fontSize = 16.sp,
                lineHeight = 19.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 1.dp)) {
                if (offline != null) {
                    com.music.spotui.ui.components.DownloadBadge(offline, size = 13.dp)
                    Spacer(Modifier.width(5.dp))
                }
                if (song.explicit) {
                    ExplicitBadge()
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    song.singer,
                    color = com.music.spotui.ui.theme.ScLightGrey,
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (saved) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(18.dp).clip(CircleShape).background(SpotifyGreen),
            ) {
                Icon(Icons.Default.Check, contentDescription = stringResource(R.string.liked_song), tint = com.music.spotui.ui.theme.ScPrimary, modifier = Modifier.size(12.dp))
            }
            Spacer(Modifier.width(20.dp))
        }
        Icon(
            Icons.Default.MoreVert,
            contentDescription = stringResource(R.string.content_description_context_menu),
            tint = com.music.spotui.ui.theme.ScLightGrey,
            modifier = Modifier
                .pressable(0.96f) { onMore() }
                .size(22.dp),
        )
    }
}

/** The Liked Songs artwork: purple-to-blue gradient with a white heart. */
@Composable
private fun LikedCover(modifier: Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF450AF5), Color(0xFF8E8EE5), Color(0xFFC4EFD9)))),
    ) {
        Icon(Icons.Default.Favorite, contentDescription = null, tint = Color.White, modifier = Modifier.fillMaxWidth(0.4f).aspectRatio(1f))
    }
}


/** "Sort by" for the songs of a playlist or Liked Songs; [CUSTOM] is Spotify's own order. */
/** Smart shuffle, per playlist (uri), for this session. */
object SmartShuffle {
    const val EVERY = 3
    val on = androidx.compose.runtime.mutableStateOf<String?>(null)
    /** The recommendations mixed into the playing list. */
    val recommended = androidx.compose.runtime.mutableStateOf<Set<String>>(emptySet())

    /** Smart shuffle can play [contextUri]: a playlist or Liked Songs. */
    fun canPlay(contextUri: String): Boolean = contextUri.startsWith("spotify:playlist:") || contextUri.contains(":collection")

    /**
     * Smart shuffle (Spotify keeps its own for Premium): the playlist shuffled, with one of
     * Spotify's recommendations for it after every [EVERY] songs.
     */
    suspend fun start(p: ContextPageModel, first: SongsModel?, fromPositionMs: Long? = null) {
        val api = Graph.api
        val state = Graph.currentSongState
        on.value = p.uri
        val inList = p.tracks.map { it.spotifyTrackId }.toSet()
        val recs = withContext(Dispatchers.IO) {
            if (p.type == "liked") {
                // Spotify's playlist extender refuses Liked Songs: the radios of a few liked songs instead.
                p.tracks.shuffled().take(3).flatMap { api.getRecommendations(listOf(it.spotifyTrackId)).take(8) }
            } else runCatching { MyspotiRuntime.api.playlists.recommended(p.uri, limit = 20) }.getOrDefault(emptyList()).map(api::songOf)
                // Spotify's extender gives nothing for some playlists: the radios of a few of its songs.
                .ifEmpty { p.tracks.shuffled().take(3).flatMap { runCatching { api.getRecommendations(listOf(it.spotifyTrackId)).take(8) }.getOrDefault(emptyList()) } }
        }.filter { it.spotifyTrackId !in inList }.distinctBy { it.spotifyTrackId }.shuffled()
        val shuffled = p.tracks.shuffled().let { list -> first?.let { f -> listOf(f) + (list - f) } ?: list }
        if (shuffled.isEmpty()) return
        val mixed = ArrayList<SongsModel>()
        val more = recs.iterator()
        shuffled.forEachIndexed { i, s ->
            mixed += s
            if ((i + 1) % EVERY == 0 && more.hasNext()) mixed += more.next()
        }
        recommended.value = recs.map { it.spotifyTrackId }.toSet()
        // Spotify's shuffle would mix the given order again.
        SongPlayer.setShuffle(false); state.shuffle.value = false
        state.updateQueue(mixed)
        val startSong = mixed.first()
        // Turned on from the player: the song playing goes on where it is, not from its start.
        val position = fromPositionMs?.takeIf { first != null && it > 0 }
        SongPlayer.playSong(startSong.url, contextUri = p.uri, order = mixed.map { "spotify:track:${it.spotifyTrackId}" }, positionMs = position)
        state.updateSongState(startSong.coverUri, startSong.title, startSong.singer, true, startSong.id, 0, startSong.album)
    }

    /** From the player: loads the playing list, then smart-shuffles it, the current song first. */
    suspend fun startFor(contextUri: String, currentTrackId: String): Boolean {
        val api = Graph.api
        val flow = when {
            contextUri.contains(":collection") -> api.getLikedPage()
            contextUri.startsWith("spotify:playlist:") -> api.getPlaylistPage(contextUri.substringAfterLast(':'))
            else -> return false
        }
        val page = (withContext(Dispatchers.IO) { flow.first { it !is Response.Loading } } as? Response.Success)?.data ?: return false
        start(page, page.tracks.firstOrNull { it.spotifyTrackId == currentTrackId }, SongPlayer.getCurrentPosition())
        return true
    }
}

/** Text order for sorting (accents ignored): one collator per thread, none is thread-safe. */
private val textOrder = ThreadLocal.withInitial {
    java.text.Collator.getInstance().apply { strength = java.text.Collator.PRIMARY }
}

enum class TrackSort(val label: Int) {
    CUSTOM(R.string.your_library_sort_order_custom),
    TITLE(R.string.track_sort_title),
    ARTIST(R.string.track_sort_artist),
    ALBUM(R.string.track_sort_album),
    DURATION(R.string.track_sort_duration),
    ADDED(R.string.track_sort_added);

    internal fun natural(songs: List<SongsModel>): List<SongsModel> {
        // One collator per thread: sorting runs at every sort change, not at every recomposition.
        val text = textOrder.get()
        return when (this) {
            CUSTOM -> songs
            TITLE -> songs.sortedWith { a, b -> text.compare(a.title, b.title) }
            ARTIST -> songs.sortedWith { a, b -> text.compare(a.singer, b.singer).takeIf { it != 0 } ?: text.compare(a.album, b.album) }
            ALBUM -> songs.sortedWith { a, b -> text.compare(a.album, b.album).takeIf { it != 0 } ?: text.compare(a.title, b.title) }
            DURATION -> songs.sortedBy { it.durationMs }
            // Newest first; ISO 8601 dates sort as text.
            ADDED -> songs.sortedByDescending { it.addedAt }
        }
    }
}

/**
 * A sort and its direction. [reversed] flips the sort's natural order (A to Z, shortest
 * first, newest first for date added); for [TrackSort.CUSTOM], the playlist from its end.
 */
data class TrackOrder(val sort: TrackSort = TrackSort.CUSTOM, val reversed: Boolean = false) {
    val isCustom get() = sort == TrackSort.CUSTOM && !reversed

    fun apply(songs: List<SongsModel>): List<SongsModel> = sort.natural(songs).let { if (reversed) it.asReversed() else it }

    companion object {
        private const val PREFS = "track_sort"

        fun load(context: android.content.Context, uri: String): TrackOrder {
            val v = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString(uri, null) ?: return TrackOrder()
            val sort = TrackSort.entries.firstOrNull { it.name == v.substringBefore(':') } ?: return TrackOrder()
            return TrackOrder(sort, v.endsWith(":reversed"))
        }

        fun save(context: android.content.Context, uri: String, order: TrackOrder) {
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().apply {
                if (order == TrackOrder()) remove(uri) else putString(uri, order.sort.name + if (order.reversed) ":reversed" else "")
            }.apply()
        }
    }
}

/** Spotify's sort menu: picking the sort already chosen flips its direction (the arrow shows it). */
@Composable
private fun TrackSortButton(sort: TrackOrder, onSort: (TrackOrder) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        androidx.compose.material3.IconButton(onClick = { open = true }) {
            Icon(androidx.compose.material.icons.Icons.Default.SwapVert,
                contentDescription = stringResource(R.string.filter_and_sort_sort_section_title),
                tint = if (sort.isCustom) Color.White else SpotifyGreen)
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Color(0xFF282828)) {
            Text(stringResource(R.string.filter_and_sort_sort_section_title), color = Color(0xFFB3B3B3), fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            TrackSort.entries.forEach { option ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(option.label), color = if (option == sort.sort) SpotifyGreen else Color.White) },
                    trailingIcon = if (option == sort.sort) { {
                        Icon(if (sort.reversed) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                            contentDescription = null, tint = SpotifyGreen, modifier = Modifier.size(18.dp))
                    } } else null,
                    onClick = { open = false; onSort(if (option == sort.sort) sort.copy(reversed = !sort.reversed) else TrackOrder(option)) },
                )
            }
        }
    }
}


/** "More like this" under an album: Spotify's similar albums, from its first song. */
@OptIn(com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi::class)
@Composable
private fun SimilarAlbums(navController: NavController, firstTrackId: String, albumUri: String) {
    val albums by androidx.compose.runtime.produceState(emptyList<app.myspoti.api.Album>(), firstTrackId) {
        if (firstTrackId.isBlank()) return@produceState
        value = runCatching { app.myspoti.host.android.MyspotiRuntime.api.catalog.similarAlbums("spotify:track:$firstTrackId", 12) }
            .getOrDefault(emptyList()).filter { it.uri != albumUri }
    }
    if (albums.isEmpty()) return
    Column(Modifier.padding(top = 24.dp)) {
        Text(stringResource(R.string.more_like_this), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(albums.size) { i ->
                val a = albums[i]
                val artists = a.artists.joinToString(", ") { it.name }
                Column(Modifier.width(140.dp).pressable(0.96f) {
                    navController.navigate(com.music.spotui.ui.navigation.albumRoute(a.name, artists, a.uri.substringAfterLast(':')))
                }) {
                    GlideImage(transition = CrossFade, model = a.images.maxByOrNull { it.width ?: 0 }?.url, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(140.dp).clip(RoundedCornerShape(4.dp)))
                    Text(a.name, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    Text(artists, color = Color(0xFFB3B3B3), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
