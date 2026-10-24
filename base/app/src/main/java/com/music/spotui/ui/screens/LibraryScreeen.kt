package com.music.spotui.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.ui.components.pressable
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.CrossFade
import com.bumptech.glide.integration.compose.GlideImage
import com.music.spotui.data.api.Api
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.AccountModel
import app.myspoti.engine.R
import com.music.spotui.data.entity.LibraryEntry
import com.music.spotui.data.local.LibraryOrder
import com.music.spotui.data.preferences.isLibraryGridView
import com.music.spotui.data.preferences.setLibraryGridView
import com.music.spotui.ui.components.Loader
import com.music.spotui.ui.components.Snackbar
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.navigation.albumRoute
import com.music.spotui.ui.navigation.artistRoute
import com.music.spotui.ui.navigation.playlistRoute
import com.music.spotui.ui.navigation.folderRoute
import com.music.spotui.ui.navigation.localPlaylistRoute
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppPalette
import com.music.spotui.ui.viewmodel.LibraryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(navController: NavController) {

    val libraryViewModel: LibraryViewModel = viewModel()
    val entries by libraryViewModel.entries.collectAsState()
    // The first lists of the library load in the background, ready before a tap.
    androidx.compose.runtime.LaunchedEffect((entries as? Response.Success)?.data?.take(8)?.map { it.spotifyId }) {
        val top = (entries as? Response.Success)?.data.orEmpty().take(8).mapNotNull { e ->
            when {
                e.isFolder || e.spotifyId.startsWith(LOCAL_PLAYLIST_PREFIX) -> null
                e.spotifyId in setOf(Api.HomeCache.LOCAL_FILES_ID, Api.HomeCache.DOWNLOADS_ID, Api.HomeCache.EPISODES_ID) -> null
                e.spotifyId == Api.HomeCache.LIKED_SONGS_ID -> "liked" to ""
                e.isPlaylist -> "playlist" to e.spotifyId
                else -> "album" to e.spotifyId
            }
        }
        if (top.isNotEmpty()) ContextViewModel.prefetchSoon(top)
    }
    val account by libraryViewModel.account.collectAsState()
    var showAccount by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // Spotify-style layout toggle: rows or a 3-column grid, persisted across runs.
    var gridView by remember { mutableStateOf(isLibraryGridView(context)) }
    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    if (showAccount) {
        AccountSheet(
            account = (account as? Response.Success)?.data ?: AccountModel(),
            navController = navController,
            onDismiss = { showAccount = false },
        )
    }

    var selectedCategory by remember { mutableStateOf("ALL") }
    val categoryFade = remember { androidx.compose.animation.core.Animatable(1f) }
    val categorySlide = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(selectedCategory) {
        categoryFade.snapTo(0f)
        categorySlide.snapTo(20f)
        kotlinx.coroutines.coroutineScope {
            launch { categoryFade.animateTo(1f, androidx.compose.animation.core.tween(200)) }
            launch { categorySlide.animateTo(0f, androidx.compose.animation.core.tween(200)) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(AppBackground.toArgb()))
            .statusBarsPadding()
    ) {
        // 1:1 port of Official Spotify Android Your Library header (Screenshot 3)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(com.music.spotui.ui.theme.ScPrimary)
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(com.music.spotui.ui.theme.ScSecondary)
                            .clickable { com.music.spotui.ui.components.ProfileDrawerState.open() },
                        contentAlignment = Alignment.Center
                    ) {
                        val avatar = (account as? Response.Success)?.data?.imageUrl.orEmpty()
                            .ifBlank { com.music.spotui.data.api.ProfileCache.imageUrl.orEmpty() }
                        if (avatar.isNotBlank()) {
                            AccountAvatar(avatar, 34.dp)
                        } else {
                            androidx.compose.foundation.Image(
                                painter = painterResource(R.drawable.sc_user),
                                contentDescription = stringResource(R.string.account_title),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(34.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.navigationbar_musicappitems_yourlibrary_title),
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 24.sp,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { com.music.spotui.ui.navigation.TabReselect.emit(Routes.Library.route) },
                    )
                }

                var creating by remember { mutableStateOf(false) }
                if (creating) LibraryCreateSheet(navController, onDismiss = { creating = false }, onLibraryChanged = { libraryViewModel.load() })
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_spot_search_outline),
                        contentDescription = stringResource(R.string.library_search_text_hint),
                        tint = if (searchActive) com.music.spotui.ui.theme.ScTint else Color.White,
                        modifier = Modifier
                            .pressable(0.96f) {
                                searchActive = !searchActive
                                if (!searchActive) searchQuery = ""
                            }
                            .size(25.dp),
                    )
                    Icon(
                        painter = painterResource(R.drawable.ic_add),
                        contentDescription = stringResource(R.string.create_playlist_content_description),
                        tint = Color.White,
                        modifier = Modifier
                            .pressable(0.96f) { creating = true }
                            .size(26.dp),
                    )
                }
            }

            // Filter pills (32.dp height = 89px, #2A2A2A unselected, #1ED760 selected — Screenshot 3)
            val categories = listOf(
                "PLAYLISTS" to "Playlists",
                "PODCASTS" to "Podcasts",
                "ALBUMS" to "Albums",
                "ARTISTS" to "Artists",
                "DOWNLOADED" to "Downloaded",
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
                    .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            ) {
                categories.forEach { (key, label) ->
                    val isSelected = selectedCategory == key
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .height(32.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (isSelected) Color(0xFF1ED760) else Color(0xFF2A2A2A))
                            .clickable {
                                selectedCategory = if (isSelected) "ALL" else key
                            }
                            .padding(horizontal = 16.dp),
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) Color.Black else Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }

        // Sort & Grid Toggle Row (Screenshot 3: SwapVert + "Recents" on left, 4-square grid icon on right)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp)
        ) {
            var sorting by remember { mutableStateOf(false) }
            if (sorting) LibrarySortSheet(onDismiss = { sorting = false })
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.pressable(0.96f) { sorting = true }.padding(vertical = 4.dp),
            ) {
                Icon(Icons.Default.SwapVert, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Text(stringResource(sortLabel(LibraryOrder.sort)), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp))
            }
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                painter = painterResource(if (gridView) R.drawable.ic_view_list else R.drawable.ic_view_grid),
                contentDescription = if (gridView) stringResource(R.string.list_view) else stringResource(R.string.grid_view),
                tint = Color.White,
                modifier = Modifier.pressable(0.96f) {
                        gridView = !gridView
                        setLibraryGridView(context, gridView)
                    }
                    .size(18.dp)
            )
        }

        AnimatedVisibility(visible = searchActive) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp, 0.dp, 16.dp, 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF252525))
                    .padding(horizontal = 12.dp),
            ) {
                Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF888888), modifier = Modifier.size(18.dp))
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 15.sp, fontFamily = com.music.spotui.ui.theme.AppSans),
                    modifier = Modifier
                        .weight(1f)
                        .padding(8.dp, 10.dp),
                    decorationBox = { inner ->
                        Box {
                            if (searchQuery.isEmpty()) Text(stringResource(R.string.navigationbar_musicappitems_search_title), color = Color(0xFF888888), fontSize = 15.sp)
                            inner()
                        }
                    },
                )
                if (searchQuery.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.chat_content_picker_clear_recent_searches),
                        tint = Color(0xFF888888),
                        modifier = Modifier.pressable(0.96f) { searchQuery = "" }
                            .size(18.dp),
                    )
                }
            }
        }

        val followedArtists by libraryViewModel.followedArtists.collectAsState()
        when (entries) {
            is Response.Loading -> LibrarySkeleton(PaddingValues(0.dp))
            else -> Box(
                modifier = Modifier.graphicsLayer {
                    alpha = categoryFade.value
                    translationY = categorySlide.value.dp.toPx()
                }
            ) {
                val localName = stringResource(R.string.local_files_title)
                val downloadsName = stringResource(R.string.collection_downloaded)
                val localSubtitle = stringResource(R.string.assisted_curation_search_playlist) + " • " + stringResource(R.string.local_playlist_subtitle)
                val hasDownloads = com.music.spotui.data.offline.OfflineStore.tracks.isNotEmpty() ||
                    com.music.spotui.data.offline.OfflineDownloader.requests.isNotEmpty()
                val likedName = stringResource(R.string.collection_liked_songs_title)
                val rootPinned = ((entries as? Response.Success)?.data ?: emptyList())
                    .indexOfFirst { it.spotifyId == Api.HomeCache.LIKED_SONGS_ID } + 2 + (if (hasDownloads) 1 else 0)
                val allEntries = ((entries as? Response.Success)?.data ?: emptyList()).map {
                    if (it.spotifyId == Api.HomeCache.LIKED_SONGS_ID) it.copy(name = likedName, subtitle = "") else it
                }.let { list ->
                    val local = com.music.spotui.data.entity.LibraryEntry(
                        spotifyId = Api.HomeCache.LOCAL_FILES_ID, name = localName, subtitle = "",
                        coverUri = "android.resource://${LocalContext.current.packageName}/drawable/local_files_cover", isPlaylist = true,
                    )
                    val downloads = com.music.spotui.data.entity.LibraryEntry(
                        spotifyId = Api.HomeCache.DOWNLOADS_ID, name = downloadsName, subtitle = "", coverUri = "", isPlaylist = true,
                    )
                    val at = list.indexOfFirst { it.spotifyId == Api.HomeCache.LIKED_SONGS_ID } + 1
                    val localLists = com.music.spotui.data.local.LocalPlaylists.list().map { p ->
                        com.music.spotui.data.entity.LibraryEntry(
                            spotifyId = LOCAL_PLAYLIST_PREFIX + p.id, name = p.name,
                            subtitle = localSubtitle, coverUri = "", isPlaylist = true,
                        )
                    }
                    list.take(at) + local + (if (hasDownloads) listOf(downloads) else emptyList()) + localLists + list.drop(at)
                }.let { list ->
                    LibraryOrder.version
                    LibraryOrder.arrange(list, LibraryOrder.sort, LibraryOrder.custom(LibraryOrder.ROOT), rootPinned)
                }
                val categoryEntries = when (selectedCategory) {
                    "PLAYLISTS" -> allEntries.filter { it.isPlaylist || it.isFolder }
                    "ALBUMS" -> allEntries.filter { !it.isPlaylist && !it.isFolder }
                    "ARTISTS" -> emptyList()
                    "DOWNLOADED" -> allEntries.filter {
                        it.spotifyId == Api.HomeCache.DOWNLOADS_ID ||
                        it.spotifyId == Api.HomeCache.LOCAL_FILES_ID ||
                        com.music.spotui.data.offline.OfflineCollections.isDownloaded(it.uri)
                    }
                    "PODCASTS" -> allEntries.filter { it.spotifyId == Api.HomeCache.EPISODES_ID }
                    else -> allEntries
                }
                val categoryArtists = when (selectedCategory) {
                    "ALL", "ARTISTS" -> followedArtists
                    else -> emptyList()
                }
                val filteredEntries = if (searchQuery.isBlank()) categoryEntries
                    else categoryEntries.filter {
                        it.name.contains(searchQuery, ignoreCase = true) ||
                        it.subtitle.contains(searchQuery, ignoreCase = true)
                    }
                val filteredArtists = if (searchQuery.isBlank()) categoryArtists
                    else categoryArtists.filter { it.name.contains(searchQuery, ignoreCase = true) }
                val canReorder = searchQuery.isBlank() && selectedCategory == "ALL"
                if (gridView) LibraryGridScreen(PaddingValues(0.dp), filteredEntries, filteredArtists, navController,
                    reorderLevel = if (canReorder) LibraryOrder.ROOT else null, pinned = rootPinned)
                else SumUpLibraryScreen(PaddingValues(0.dp), filteredEntries, filteredArtists, navController,
                    reorderLevel = if (canReorder) LibraryOrder.ROOT else null, pinned = rootPinned,
                    onChanged = { libraryViewModel.load() })
                if (entries !is Response.Success) Box(modifier = Modifier.padding(20.dp, 100.dp)) { Snackbar(showMessage = stringResource(R.string.library_error)) }
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun AccountAvatar(url: String, size: androidx.compose.ui.unit.Dp) {
    GlideImage(
        transition = CrossFade,
        modifier = Modifier.size(size).clip(CircleShape),
        model = url,
        contentScale = ContentScale.Crop,
        contentDescription = ""
    )
}

@OptIn(ExperimentalGlideComposeApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SumUpLibraryScreen(
    padding: PaddingValues,
    entries: List<LibraryEntry>,
    followedArtists: List<com.music.spotui.data.entity.ArtistsModel>,
    navController: NavController,
    /** The library level whose order a long press then a drag changes; null: no moving. */
    reorderLevel: String? = null,
    /** Entries at the top that never move (Liked Songs, Local Files, Downloaded). */
    pinned: Int = 0,
    /** A pin, move or delete changed the library: load it again. */
    onChanged: () -> Unit = {},
) {
    var itemMenu by remember { mutableStateOf<LibraryEntry?>(null) }
    itemMenu?.let { LibraryItemSheet(it, onDismiss = { itemMenu = null }, onChanged = onChanged) }
    if (entries.isEmpty() && followedArtists.isEmpty()) {
        Box(modifier = Modifier.padding(20.dp, 40.dp)) { Snackbar(showMessage = stringResource(R.string.library_empty)) }
        return
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.music.spotui.ui.navigation.TabReselect.library.collect {
            if (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0) {
                listState.animateScrollToItem(0)
            }
        }
    }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    var rows by remember { mutableStateOf(entries.distinctBy { LibraryOrder.key(it) }) }
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    val level by androidx.compose.runtime.rememberUpdatedState(reorderLevel)
    val fixed by androidx.compose.runtime.rememberUpdatedState(pinned)
    androidx.compose.runtime.LaunchedEffect(entries) {
        if (dragKey == null) rows = entries.distinctBy { LibraryOrder.key(it) }
    }

    fun follow() {
        val key = dragKey ?: return
        val info = listState.layoutInfo.visibleItemsInfo
        val dragged = info.firstOrNull { it.key == key } ?: return
        val centre = dragged.offset + dragOffset + dragged.size / 2f
        val target = info.firstOrNull { it.key != key && it.key is String && centre.toInt() in it.offset until it.offset + it.size } ?: return
        val from = rows.indexOfFirst { LibraryOrder.key(it) == key }
        val to = rows.indexOfFirst { LibraryOrder.key(it) == target.key }
        if (from < fixed || to < fixed || from < 0 || to < 0) return
        if (listState.firstVisibleItemIndex == from || listState.firstVisibleItemIndex == to)
            listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        rows = rows.toMutableList().apply { add(to, removeAt(from)) }
        dragOffset += dragged.offset - target.offset
    }

    fun finish() {
        val at = level
        if (dragKey != null && at != null) LibraryOrder.saveCustom(at, rows.drop(fixed).map { LibraryOrder.key(it) })
        dragKey = null
        dragOffset = 0f
    }

    androidx.compose.runtime.LaunchedEffect(dragKey) {
        if (dragKey == null) return@LaunchedEffect
        val edge = with(density) { 72.dp.toPx() }
        while (dragKey != null) {
            val info = listState.layoutInfo
            val dragged = info.visibleItemsInfo.firstOrNull { it.key == dragKey }
            if (dragged != null) {
                val top = dragged.offset + dragOffset
                val bottom = top + dragged.size
                val speed = when {
                    bottom > info.viewportEndOffset - edge -> 18f
                    top < info.viewportStartOffset + edge -> -18f
                    else -> 0f
                }
                if (speed != 0f) {
                    val moved = listState.scrollBy(speed)
                    dragOffset += moved
                    follow()
                }
            }
            androidx.compose.runtime.withFrameNanos { }
        }
    }

    Box(Modifier.fillMaxSize().padding(padding)) {
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(top = 4.dp, bottom = com.music.spotui.ui.components.bottomBarsSpace()),
        modifier = Modifier
            .fillMaxSize()
            .background(com.music.spotui.ui.theme.AppBackground)
    ) {
        itemsIndexed(rows, key = { _, e -> LibraryOrder.key(e) }) { index, entry ->
            val key = LibraryOrder.key(entry)
            val dragged = dragKey == key
            val movable = reorderLevel != null && index >= pinned
            val isDownloaded = entry.spotifyId == Api.HomeCache.DOWNLOADS_ID ||
                entry.spotifyId == Api.HomeCache.LOCAL_FILES_ID ||
                com.music.spotui.data.offline.OfflineCollections.isDownloaded(entry.uri) ||
                entry.pinned
            val subtitleText = entry.subtitle.ifBlank {
                when (entry.spotifyId) {
                    Api.HomeCache.LIKED_SONGS_ID -> "Playlist • Liked Songs"
                    Api.HomeCache.LOCAL_FILES_ID -> "Playlist • Local Files"
                    else -> "Playlist"
                }
            }
            Row(
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .animateItem(fadeInSpec = null, fadeOutSpec = null,
                        placementSpec = if (dragged) null else androidx.compose.animation.core.spring(
                            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                            visibilityThreshold = androidx.compose.ui.unit.IntOffset.VisibilityThreshold))
                    .zIndex(if (dragged) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (dragged) dragOffset else 0f
                        if (dragged) { scaleX = 1.03f; scaleY = 1.03f }
                    }
                    .background(if (dragged) Color(0xFF2A2A2A) else Color.Transparent, RoundedCornerShape(8.dp))
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onLongClick = { if (entry.uri.isNotBlank()) itemMenu = entry },
                        onClick = { openLibraryEntry(entry, navController) },
                    )
                    .then(if (!movable) Modifier else Modifier.pointerInput(key) {
                        try { detectDragGesturesAfterLongPress(
                            onDragStart = {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                dragKey = key
                                dragOffset = 0f
                            },
                            onDrag = { change, amount -> change.consume(); dragOffset += amount.y; follow() },
                            onDragEnd = { finish() },
                            onDragCancel = { finish() },
                        ) } finally { if (dragKey == key) finish() }
                    })
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 16.dp, top = 7.dp, bottom = 7.dp)
            ) {
                // 64.dp × 64.dp cover (180px in Screenshot 3)
                LibraryEntryCover(entry, Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(4.dp)),
                )
                Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
                    Text(
                        text = entry.name,
                        color = Color.White,
                        fontSize = 16.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 1.dp),
                    ) {
                        if (entry.pinned || index < 2) {
                            Icon(
                                painter = painterResource(R.drawable.ic_spot_pin),
                                contentDescription = null,
                                tint = Color(0xFF1ED760),
                                modifier = Modifier
                                    .padding(end = 5.dp)
                                    .size(15.dp),
                            )
                        }
                        if (isDownloaded || index == 0) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF1ED760)),
                            ) {
                                Text(
                                    text = "↓",
                                    color = Color.Black,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    lineHeight = 9.sp,
                                )
                            }
                        }
                        Text(
                            text = subtitleText,
                            color = Color(0xFFB3B3B3),
                            fontSize = 13.sp,
                            lineHeight = 15.sp,
                            fontWeight = FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        // ── Artists the user follows on Spotify (integrated cleanly as 64.dp circular rows like Screenshot 3) ──
        items(followedArtists) { artist ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .pressable(0.96f) { navController.navigate(artistRoute(artist.name, artist.id)) }
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 16.dp, top = 7.dp, bottom = 7.dp)
            ) {
                GlideImage(
                    transition = CrossFade,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape),
                    model = artist.coverUri,
                    contentScale = ContentScale.Crop,
                    contentDescription = ""
                )
                Column(modifier = Modifier.padding(start = 14.dp)) {
                    Text(
                        text = artist.name,
                        color = Color.White,
                        fontSize = 16.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.artist_subtitle),
                        color = Color(0xFFB3B3B3),
                        fontSize = 13.sp,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.Normal,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
        }
    }
    com.music.spotui.ui.components.FastScrollbar(listState, Modifier.align(Alignment.CenterEnd))
    }
}

/** An entry's artwork: Spotify's image, or the drawn cover of the app's own folders. */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun LibraryEntryCover(entry: LibraryEntry, modifier: Modifier) {
    val drawn = when {
        entry.spotifyId == Api.HomeCache.DOWNLOADS_ID -> R.drawable.downloads_cover
        entry.spotifyId == Api.HomeCache.LOCAL_FILES_ID || entry.spotifyId.startsWith(LOCAL_PLAYLIST_PREFIX) -> R.drawable.local_files_cover
        entry.isFolder -> R.drawable.folder_cover
        else -> 0
    }
    // Vector drawables are drawn directly (Glide left them blank).
    if (drawn != 0) androidx.compose.foundation.Image(painterResource(drawn), contentDescription = null, modifier = modifier)
    else GlideImage(transition = CrossFade, model = entry.coverUri, contentScale = ContentScale.Crop, contentDescription = "", modifier = modifier)
}

/** Library ids of the phone's own playlists (see LocalPlaylists). */
const val LOCAL_PLAYLIST_PREFIX = "local-playlist:"

private fun prefetchLibraryEntry(entry: LibraryEntry) = when {
    entry.isFolder || entry.spotifyId.startsWith(LOCAL_PLAYLIST_PREFIX) -> Unit
    entry.spotifyId == Api.HomeCache.LOCAL_FILES_ID || entry.spotifyId == Api.HomeCache.DOWNLOADS_ID || entry.spotifyId == Api.HomeCache.EPISODES_ID -> Unit
    entry.spotifyId == Api.HomeCache.LIKED_SONGS_ID -> ContextViewModel.prefetch("liked", "")
    entry.isPlaylist -> ContextViewModel.prefetch("playlist", entry.spotifyId)
    else -> ContextViewModel.prefetch("album", entry.spotifyId)
}

private fun openLibraryEntry(entry: LibraryEntry, navController: NavController) {
    if (entry.isFolder) navController.navigate(folderRoute(entry.folderUri, entry.name))
    else if (entry.spotifyId.startsWith(LOCAL_PLAYLIST_PREFIX)) navController.navigate(localPlaylistRoute(entry.spotifyId.removePrefix(LOCAL_PLAYLIST_PREFIX)))
    else if (entry.spotifyId == Api.HomeCache.DOWNLOADS_ID) navController.navigate(Routes.Downloads.route)
    else if (entry.spotifyId == Api.HomeCache.LOCAL_FILES_ID) navController.navigate(Routes.LocalFiles.route)
    else if (entry.spotifyId == Api.HomeCache.LIKED_SONGS_ID) navController.navigate(Routes.Liked.route)
    else if (entry.spotifyId == Api.HomeCache.EPISODES_ID) navController.navigate(Routes.Episodes.route)
    else if (entry.isPlaylist) navController.navigate(playlistRoute(entry.spotifyId, entry.name))
    else navController.navigate(albumRoute(entry.name, entry.artists, entry.spotifyId))
}

/**
 * Grid layout of the same library content: 3 columns of square covers with the
 * title/subtitle underneath, like Spotify's "Grid" library view. Followed
 * artists render as circles under their own full-width header.
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun LibraryGridScreen(
    padding: PaddingValues,
    entries: List<LibraryEntry>,
    followedArtists: List<com.music.spotui.data.entity.ArtistsModel>,
    navController: NavController,
    /** The library level whose order a long press then a drag changes; null: no moving. */
    reorderLevel: String? = null,
    /** Entries at the top that never move (Liked Songs, Local Files, Downloaded). */
    pinned: Int = 0,
) {
    if (entries.isEmpty() && followedArtists.isEmpty()) {
        Box(modifier = Modifier.padding(20.dp, 40.dp)) { Snackbar(showMessage = stringResource(R.string.library_empty)) }
        return
    }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    // Same re-tap shortcut as the list view: the grid animates back to its first tile.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.music.spotui.ui.navigation.TabReselect.library.collect {
            if (gridState.firstVisibleItemIndex != 0 || gridState.firstVisibleItemScrollOffset != 0) {
                gridState.animateScrollToItem(0)
            }
        }
    }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Same as the list view, in two dimensions: the tile follows the finger and takes
    // the place of the tile its centre is over.
    var rows by remember { mutableStateOf(entries.distinctBy { LibraryOrder.key(it) }) }
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val level by androidx.compose.runtime.rememberUpdatedState(reorderLevel)
    val fixed by androidx.compose.runtime.rememberUpdatedState(pinned)
    androidx.compose.runtime.LaunchedEffect(entries) {
        if (dragKey == null) rows = entries.distinctBy { LibraryOrder.key(it) }
    }

    fun follow() {
        val key = dragKey ?: return
        val info = gridState.layoutInfo.visibleItemsInfo
        val dragged = info.firstOrNull { it.key == key } ?: return
        val cx = dragged.offset.x + dragOffset.x + dragged.size.width / 2f
        val cy = dragged.offset.y + dragOffset.y + dragged.size.height / 2f
        val target = info.firstOrNull {
            it.key != key && it.key is String &&
                cx >= it.offset.x && cx < it.offset.x + it.size.width && cy >= it.offset.y && cy < it.offset.y + it.size.height
        } ?: return
        val from = rows.indexOfFirst { LibraryOrder.key(it) == key }
        val to = rows.indexOfFirst { LibraryOrder.key(it) == target.key }
        if (from < fixed || to < fixed || from < 0 || to < 0) return
        if (gridState.firstVisibleItemIndex == from || gridState.firstVisibleItemIndex == to)
            gridState.requestScrollToItem(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
        rows = rows.toMutableList().apply { add(to, removeAt(from)) }
        dragOffset += androidx.compose.ui.geometry.Offset(
            (dragged.offset.x - target.offset.x).toFloat(), (dragged.offset.y - target.offset.y).toFloat())
    }

    fun finish() {
        val at = level
        if (dragKey != null && at != null) LibraryOrder.saveCustom(at, rows.drop(fixed).map { LibraryOrder.key(it) })
        dragKey = null
        dragOffset = androidx.compose.ui.geometry.Offset.Zero
    }

    androidx.compose.runtime.LaunchedEffect(dragKey) {
        if (dragKey == null) return@LaunchedEffect
        val edge = with(density) { 72.dp.toPx() }
        while (dragKey != null) {
            val info = gridState.layoutInfo
            val dragged = info.visibleItemsInfo.firstOrNull { it.key == dragKey }
            if (dragged != null) {
                val top = dragged.offset.y + dragOffset.y
                val bottom = top + dragged.size.height
                val speed = when {
                    bottom > info.viewportEndOffset - edge -> 18f
                    top < info.viewportStartOffset + edge -> -18f
                    else -> 0f
                }
                if (speed != 0f) {
                    val moved = gridState.scrollBy(speed)
                    dragOffset += androidx.compose.ui.geometry.Offset(0f, moved)
                    follow()
                }
            }
            androidx.compose.runtime.withFrameNanos { }
        }
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(3),
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(com.music.spotui.ui.theme.AppBackground),
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, com.music.spotui.ui.components.bottomBarsSpace()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        itemsIndexed(rows, key = { _, e -> LibraryOrder.key(e) }) { index, entry ->
            val key = LibraryOrder.key(entry)
            val dragged = dragKey == key
            val movable = reorderLevel != null && index >= pinned
            Column(
                modifier = Modifier
                    .animateItem(fadeInSpec = null, fadeOutSpec = null,
                        placementSpec = if (dragged) null else androidx.compose.animation.core.spring(
                            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                            visibilityThreshold = androidx.compose.ui.unit.IntOffset.VisibilityThreshold))
                    .zIndex(if (dragged) 1f else 0f)
                    .graphicsLayer {
                        translationX = if (dragged) dragOffset.x else 0f
                        translationY = if (dragged) dragOffset.y else 0f
                        if (dragged) { scaleX = 1.06f; scaleY = 1.06f }
                    }
                    .pressable(0.96f, onPressHint = { prefetchLibraryEntry(entry) }) { openLibraryEntry(entry, navController) }
                    .then(if (!movable) Modifier else Modifier.pointerInput(key) {
                        try { detectDragGesturesAfterLongPress(
                            onDragStart = {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                dragKey = key
                                dragOffset = androidx.compose.ui.geometry.Offset.Zero
                            },
                            onDrag = { change, amount -> change.consume(); dragOffset += amount; follow() },
                            onDragEnd = { finish() },
                            onDragCancel = { finish() },
                        ) } finally { if (dragKey == key) finish() }
                    })
            ) {
                LibraryEntryCover(
                    entry,
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(0.dp))
                        .background(com.music.spotui.ui.theme.ScSecondary),
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = entry.name,
                    color = Color.White,
                    fontSize = 13.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.subtitle.isNotBlank()) {
                    Spacer(modifier = Modifier.height(5.dp))
                    Text(
                        text = entry.subtitle,
                        color = com.music.spotui.ui.theme.ScLightGrey,
                        fontSize = 13.sp,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (followedArtists.isNotEmpty()) {
            items(followedArtists, key = { "artist:${it.id}" }) { artist ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.pressable(0.96f) { navController.navigate(artistRoute(artist.name, artist.id)) }
                ) {
                    GlideImage(
                        transition = CrossFade,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(CircleShape)
                            .background(com.music.spotui.ui.theme.ScSecondary),
                        model = artist.coverUri,
                        loading = placeholder(R.drawable.sc_user),
                        failure = placeholder(R.drawable.sc_user),
                        contentScale = ContentScale.Crop,
                        contentDescription = ""
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = artist.name,
                        color = Color.White,
                        fontSize = 13.sp,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(5.dp))
                    Text(
                        text = stringResource(R.string.artist_subtitle),
                        color = com.music.spotui.ui.theme.ScLightGrey,
                        fontSize = 13.sp,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun LibrarySkeleton(padding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(com.music.spotui.ui.theme.AppBackground)
    ) {
        Spacer(modifier = Modifier.height(10.dp))
        repeat(8) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp, 6.dp)
            ) {
                Box(modifier = Modifier.size(55.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF1E1E1E)))
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Box(modifier = Modifier.height(14.dp).width(160.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF1E1E1E)))
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(modifier = Modifier.height(11.dp).width(90.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF1E1E1E)))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountSheet(
    account: AccountModel,
    navController: NavController,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1A1A1A),
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(16.dp, 4.dp, 16.dp, 16.dp)
            ) {
                Box(
                    modifier = Modifier.size(56.dp).clip(CircleShape).background(Color(0xFF3A3A3A)),
                    contentAlignment = Alignment.Center
                ) {
                    if (account.imageUrl.isNotBlank()) AccountAvatar(account.imageUrl, 56.dp)
                    else Icon(Icons.Default.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(account.name.ifBlank { stringResource(R.string.spotify_user) }, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (account.email.isNotBlank()) Text(account.email, color = Color.Gray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (account.plan.isNotBlank()) Text("Spotify ${account.plan}", color = Color(0xFF1DB954), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
            HorizontalDivider(color = Color(0xFF2A2A2A))

            // Spotify's own account pages (plan, privacy, devices), signed in, in the app.
            val context = androidx.compose.ui.platform.LocalContext.current
            com.music.spotui.data.api.ProfileCache.id?.let { myId ->
                AccountRow(stringResource(R.string.view_profile)) {
                    onDismiss()
                    navController.navigate(com.music.spotui.ui.navigation.profileRoute(myId, account.name))
                }
            }
            AccountRow(stringResource(R.string.settings_title)) {
                onDismiss()
                navController.navigate(com.music.spotui.ui.navigation.Routes.Settings.route)
            }
            AccountRow(stringResource(R.string.account_page_title) + "  \u2197") {
                onDismiss()
                AccountBrowser.open(context, AccountBrowser.ACCOUNT_URL)
            }
            HorizontalDivider(color = Color(0xFF2A2A2A))
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AccountRow(label: String, tint: Color = Color.White, onClick: () -> Unit) {
    Text(
        text = label,
        color = tint,
        fontSize = 15.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(20.dp, 16.dp)
    )
}

private fun sortLabel(sort: LibraryOrder.Sort) = when (sort) {
    LibraryOrder.Sort.CUSTOM -> R.string.your_library_sort_order_custom
    LibraryOrder.Sort.RECENTS -> R.string.library_sort_recents
    LibraryOrder.Sort.RECENTLY_ADDED -> R.string.library_sort_recently_added
    LibraryOrder.Sort.ALPHABETICAL -> R.string.your_library_sort_order_alphabetical
    LibraryOrder.Sort.CREATOR -> R.string.your_library_sort_order_creator
}

/** "Sort by": custom order (moved by hand), alphabetical or by creator. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibrarySortSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF282828)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(stringResource(R.string.filter_and_sort_sort_section_title), color = Color.White, fontSize = 18.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            for (sort in LibraryOrder.Sort.entries) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { LibraryOrder.choose(sort); onDismiss() }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    val selected = LibraryOrder.sort == sort
                    Text(stringResource(sortLabel(sort)), color = if (selected) com.music.spotui.ui.theme.AppAccent else Color.White,
                        fontSize = 16.sp, modifier = Modifier.weight(1f))
                    if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = com.music.spotui.ui.theme.AppAccent, modifier = Modifier.size(20.dp))
                }
            }
            Text(stringResource(R.string.library_reorder_hint), color = Color(0xFFB3B3B3), fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            Text(stringResource(R.string.library_reorder_reset), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth().clickable { LibraryOrder.reset(); onDismiss() }.padding(horizontal = 20.dp, vertical = 14.dp))
        }
    }
}
