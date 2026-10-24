package com.music.spotui.ui.screens

import androidx.compose.material.icons.automirrored.filled.ArrowBack
import kotlinx.coroutines.launch
import com.music.spotui.ui.components.pressable
import androidx.compose.ui.res.stringResource

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.music.spotui.ui.components.SongOptionsSheet
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.CrossFade
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import app.myspoti.engine.R
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.SearchResults
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.addLikedSongId
import com.music.spotui.data.preferences.isSongLiked
import com.music.spotui.data.preferences.removeLikedSongId
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.Loader
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.navigation.albumRoute
import com.music.spotui.ui.navigation.artistRoute
import com.music.spotui.ui.navigation.categoryRoute
import com.music.spotui.ui.navigation.playlistRoute
import com.music.spotui.ui.navigation.showRoute
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppPalette
import com.music.spotui.ui.viewmodel.SearchViewModel


@Composable
fun SearchScreen(navController: NavController) {
    val searchViewModel : SearchViewModel = viewModel()
    val results by searchViewModel.results.collectAsState()

    // Results are live search hits (or empty); never gate the search UI on them.
    val searchResults = (results as? Response.Success)?.data ?: SearchResults()

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(AppBackground.toArgb()))
    ) {
        SumUpSearchScreen(navController = navController, searchResults, searchViewModel,
            loading = results is Response.Loading, failed = results is Response.Error)
    }
}


@OptIn(ExperimentalFoundationApi::class, ExperimentalGlideComposeApi::class)
@Composable
fun SumUpSearchScreen(
    navController: NavController,
    results: SearchResults,
    searchViewModel: SearchViewModel,
    loading: Boolean = false,
    failed: Boolean = false,
) {
    val context = LocalContext.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val dismissKeyboard: () -> Unit = {
        keyboardController?.hide()
        focusManager.clearFocus()
    }

    val text = searchViewModel.query
    // Recents are the *items opened from results* (songs/artists/albums), not the
    // typed queries, and only appear once the user taps into the search bar.
    var searchFocused by remember { mutableStateOf(false) }
    var searchActive by remember { mutableStateOf(text.isNotEmpty()) }
    if (searchFocused || text.isNotEmpty()) {
        searchActive = true
    }
    var recents by remember {
        mutableStateOf(com.music.spotui.data.preferences.getRecentItems(context).filter { com.music.spotui.data.api.Features.PODCASTS || it.type != "show" })
    }
    var menuSong by remember { mutableStateOf<SongsModel?>(null) }
    // Re-tapping the Search tab while already on it focuses the field (official behaviour)
    // and clears any existing query so the user can search something new.
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val searchFieldFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.activity.compose.BackHandler(enabled = searchActive) {
        searchViewModel.search("")
        searchActive = false
        dismissKeyboard()
    }
    LaunchedEffect(Unit) {
        com.music.spotui.ui.navigation.TabReselect.search.collect {
            if (searchViewModel.query.isNotEmpty()) {
                searchViewModel.search("")
            }
            searchActive = true
            runCatching { searchFieldFocus.requestFocus() }
            keyboardController?.show()
            if (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0) {
                listState.animateScrollToItem(0)
            }
        }
    }
    // Spotify's filter chips: Top (mixed), or one kind of result.
    val filter = searchViewModel.filter
    menuSong?.let { sel ->
        SongOptionsSheet(
            song = sel,
            navController = navController,
            context = context,
            onDismiss = { menuSong = null },
        )
    }

    val recordRecent: (com.music.spotui.data.preferences.RecentItem) -> Unit = { item ->
        com.music.spotui.data.preferences.addRecentItem(context, item)
        recents = com.music.spotui.data.preferences.getRecentItems(context).filter { com.music.spotui.data.api.Features.PODCASTS || it.type != "show" }
    }
    // Live Spotify search results for the current query.
    val searchedList = results.songs
    // One relevance-mixed list (songs, artists, albums interleaved) instead of
    // separate type sections — matches how most music apps present search.
    val mixed = remember(results, filter) { filterSearchResults(results, filter) }
    // Profiles are searched only when their chip is on (Spotify keeps them out of Top).
    val profiles by androidx.compose.runtime.produceState(emptyList<app.myspoti.api.MediaCard>(), text, filter) {
        value = emptyList()
        if (filter != SearchFilter.Profiles || text.isBlank()) return@produceState
        kotlinx.coroutines.delay(250)
        value = runCatching { app.myspoti.host.android.MyspotiRuntime.api.search.users(text.trim(), limit = 30).items }.getOrDefault(emptyList())
    }
    val suggestions by androidx.compose.runtime.produceState(emptyList<String>(), text) {
        value = emptyList()
        if (text.isBlank()) return@produceState
        kotlinx.coroutines.delay(300)
        value = runCatching { app.myspoti.host.android.MyspotiRuntime.api.search.suggestions(text.trim(), 6) }.getOrDefault(emptyList())
            .filter { !it.equals(text.trim(), ignoreCase = true) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(AppBackground.toArgb()))
            .statusBarsPadding()
    ) {
        if (!searchActive) {
            SearchTopBar()
        }
        SearchStickyBar(
            text = text,
            isActive = searchActive,
            onFocusChange = { focused ->
                searchFocused = focused
                if (focused) searchActive = true
            },
            onActivate = {
                searchActive = true
                keyboardController?.show()
            },
            onBack = {
                searchViewModel.search("")
                searchActive = false
                dismissKeyboard()
            },
            onClear = {
                searchViewModel.search("")
                runCatching { searchFieldFocus.requestFocus() }
                keyboardController?.show()
            },
            focusRequester = searchFieldFocus,
        ) {
            searchViewModel.search(it)
        }

        LazyColumn(
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = com.music.spotui.ui.components.bottomBarsSpace()),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (text.isBlank()) {
            if (searchActive && recents.isNotEmpty()) {
                // ── Recent searches: the items the user opened (Spotify-style),
                // shown only once the search bar is focused ──
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp, 16.dp, 16.dp, 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            stringResource(R.string.assisted_curation_search_search_history_title),
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            stringResource(R.string.chat_content_picker_clear_recent_searches),
                            color = Color(0xFFB3B3B3),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.pressable(0.96f) {
                                dismissKeyboard()
                                com.music.spotui.data.preferences.clearRecentItems(context)
                                recents = emptyList()
                                // Spotify's own list too, so other devices forget them as well.
                                clearSpotifyRecents()
                            },
                        )
                    }
                }
                items(recents.size) { i ->
                    val item = recents[i]
                    RecentItemRow(
                        item = item,
                        onClick = {
                            dismissKeyboard()
                            when (item.type) {
                                "song" -> {
                                    val songUrl = item.songUrl.ifBlank {
                                        SongPlayer.buildSpotifyPlayQuery(item.spotifyTrackId, item.name, item.singer)
                                    }.let { savedUrl ->
                                        if (
                                            item.spotifyTrackId.isNotBlank() &&
                                            !savedUrl.startsWith("spotify:track:")
                                        ) {
                                            SongPlayer.buildSpotifyPlayQuery(item.spotifyTrackId, item.name, item.singer)
                                        } else {
                                            savedUrl
                                        }
                                    }
                                    val song = SongsModel(
                                        item.songId, item.name, item.songAlbum, item.singer,
                                        item.image, songUrl, item.spotifyTrackId,
                                        explicit = item.explicit,
                                        durationMs = item.durationMs,
                                    )
                                    searchViewModel.startRadioFromSong(song)
                                    SongPlayer.playSong(song.url, context)
                                    searchViewModel.updateSongState(
                                        song.coverUri, song.title, song.singer, true, song.id, 0, song.album)
                                }
                                "artist" -> navController.navigate(artistRoute(item.name, item.key.takeIf { it != item.name }.orEmpty()))
                                "album" -> navController.navigate(albumRoute(item.name, item.singer, item.key.takeIf { it != item.name }.orEmpty()))
                                "show" -> navController.navigate(showRoute(item.key, item.name))
                                "playlist" -> navController.navigate(playlistRoute(item.key, item.name))
                            }
                        },
                        onRemove = {
                            dismissKeyboard()
                            com.music.spotui.data.preferences.removeRecentItem(context, item)
                            recents = com.music.spotui.data.preferences.getRecentItems(context).filter { com.music.spotui.data.api.Features.PODCASTS || it.type != "show" }
                        },
                    )
                }
            } else {
                // ── Spotify-style "Browse all" category grid ──
                item {
                    // Real Spotify opens a genre *catalogue* (a page of curated
                    // playlists) rather than running a keyword song search.
                    BrowseAllSection { genre, title ->
                        dismissKeyboard()
                        if (genre == NEW_RELEASES_GENRE) navController.navigate(com.music.spotui.ui.navigation.Routes.NewReleases.route)
                        else navController.navigate(categoryRoute(genre, title))
                    }
                }
            }
        } else {
            // Spotify's completions for what is typed: one tap searches it.
            if (suggestions.isNotEmpty()) item {
                androidx.compose.foundation.lazy.LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    items(suggestions.size) { i ->
                        val q = suggestions[i]
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF2A2A2A))
                                .pressable(0.96f) {
                                    dismissKeyboard()
                                    searchViewModel.search(q)
                                }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                        ) {
                            Icon(androidx.compose.material.icons.Icons.Default.Search, null, tint = Color(0xFFB3B3B3), modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(q, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            item { SearchFilterChips(filter) { dismissKeyboard(); searchViewModel.filter = it } }
            // Loading, nothing found and a failure each say what they are (never an empty page).
            if (filter != SearchFilter.Profiles && mixed.isEmpty() && results.shows.isEmpty() && results.episodes.isEmpty()) item {
                SearchStatus(text, loading, failed) { dismissKeyboard(); searchViewModel.retry() }
            }
            if (filter == SearchFilter.Profiles) {
                items(profiles.size) { i ->
                    val u = profiles[i]
                    SearchProfileRow(u.title, u.images.firstOrNull()?.url) {
                        dismissKeyboard()
                        navController.navigate(com.music.spotui.ui.navigation.profileRoute(u.uri.substringAfterLast(':'), u.title))
                    }
                }
            }
            items(mixed.size) { i ->
                when (val row = mixed[i]) {
                    is SearchRow.Song -> com.music.spotui.ui.components.SwipeToQueue(row.song) {
                        SearchSongRow(row.song, searchedList, searchViewModel, onPlayed = {
                            dismissKeyboard()
                            recordRecent(row.song.toRecentItem())
                        }, onLongClick = { dismissKeyboard(); menuSong = row.song })
                    }
                    is SearchRow.Artist -> SearchArtistRow(row.artist) {
                        dismissKeyboard()
                        recordRecent(com.music.spotui.data.preferences.RecentItem(
                            type = "artist",
                            key = row.artist.id.ifBlank { row.artist.name },
                            name = row.artist.name,
                            image = row.artist.coverUri,
                        ))
                        navController.navigate(artistRoute(row.artist.name, row.artist.id))
                    }
                    is SearchRow.Album -> SearchAlbumRow(row.album) {
                        dismissKeyboard()
                        recordRecent(com.music.spotui.data.preferences.RecentItem(
                            type = "album",
                            key = row.album.spotifyId.ifBlank { row.album.name },
                            name = row.album.name,
                            singer = row.album.artists,
                            image = row.album.coverUri,
                        ))
                        navController.navigate(albumRoute(row.album.name, row.album.artists, row.album.spotifyId))
                    }
                    is SearchRow.Playlist -> SearchPlaylistRow(row.playlist) {
                        dismissKeyboard()
                        recordRecent(com.music.spotui.data.preferences.RecentItem(
                            type = "playlist",
                            key = row.playlist.spotifyId,
                            name = row.playlist.name,
                            singer = row.playlist.subtitle,
                            image = row.playlist.coverUri,
                        ))
                        navController.navigate(playlistRoute(row.playlist.spotifyId, row.playlist.name))
                    }
                }
            }
            // ── Podcasts: shows (→ detail) then individual episodes (→ play) ──
            if (com.music.spotui.data.api.Features.PODCASTS && (filter == SearchFilter.Top || filter == SearchFilter.Podcasts)) {
            if (results.shows.isNotEmpty()) {
                item { SearchSectionHeader(stringResource(R.string.assisted_curation_search_filter_podcasts)) }
                items(results.shows.size) { i ->
                    val show = results.shows[i]
                    SearchShowRow(show) {
                        dismissKeyboard()
                        recordRecent(com.music.spotui.data.preferences.RecentItem(
                            type = "show",
                            key = show.id,
                            name = show.name,
                            singer = show.publisher,
                            image = show.coverUri,
                        ))
                        navController.navigate(showRoute(show.id, show.name))
                    }
                }
            }
            if (results.episodes.isNotEmpty()) {
                item { SearchSectionHeader(stringResource(R.string.assisted_curation_search_filter_episodes)) }
                items(results.episodes.size) { i ->
                    val ep = results.episodes[i]
                    SearchSongRow(ep, results.episodes, searchViewModel, onPlayed = {
                        dismissKeyboard()
                        recordRecent(ep.toRecentItem())
                    }, onLongClick = { dismissKeyboard(); menuSong = ep })
                }
            }
            }
        }
        }
    }
}

/** A single row in the search results: a track, an artist, or an album. */
sealed class SearchRow {
    data class Song(val song: SongsModel) : SearchRow()
    data class Artist(val artist: com.music.spotui.data.entity.ArtistsModel) : SearchRow()
    data class Album(val album: com.music.spotui.data.entity.AlbumsModel) : SearchRow()
    data class Playlist(val playlist: com.music.spotui.data.entity.LibraryEntry) : SearchRow()
}

/** The search filter chips (official labels). Podcasts shows shows + episodes. */
@Composable
private fun SearchStatus(query: String, loading: Boolean, failed: Boolean, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp, 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            loading -> androidx.compose.material3.CircularProgressIndicator(color = com.music.spotui.ui.theme.AppAccent)
            failed -> com.music.spotui.ui.components.ErrorRetry(stringResource(R.string.search_failed), Modifier.padding(0.dp), onRetry)
            else -> Text(stringResource(R.string.assisted_curation_search_empty_view_no_result_title, query.trim()),
                color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

enum class SearchFilter(@androidx.annotation.StringRes val label: Int) {
    Top(R.string.assisted_curation_search_filter_top),
    Songs(R.string.assisted_curation_search_filter_songs),
    Artists(R.string.assisted_curation_search_filter_artists),
    Albums(R.string.assisted_curation_search_filter_albums),
    Playlists(R.string.assisted_curation_search_filter_playlists),
    Podcasts(R.string.assisted_curation_search_filter_podcasts),
    Profiles(R.string.search_filter_profiles),
}

private fun filterSearchResults(results: SearchResults, filter: SearchFilter): List<SearchRow> = when (filter) {
    SearchFilter.Top -> mixSearchResults(results)
    SearchFilter.Songs -> results.songs.map { SearchRow.Song(it) }
    SearchFilter.Artists -> results.artists.map { SearchRow.Artist(it) }
    SearchFilter.Albums -> results.albums.map { SearchRow.Album(it) }
    SearchFilter.Playlists -> results.playlists.map { SearchRow.Playlist(it) }
    SearchFilter.Podcasts, SearchFilter.Profiles -> emptyList()
}

@Composable
private fun SearchFilterChips(selected: SearchFilter, onSelect: (SearchFilter) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        val filters = SearchFilter.entries.filter { it != SearchFilter.Podcasts || com.music.spotui.data.api.Features.PODCASTS }
        items(filters.size) { i ->
            val f = filters[i]
            val on = f == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.height(32.dp).clip(RoundedCornerShape(16.dp))
                    .background(if (on) com.music.spotui.ui.theme.AppAccent else Color(0xFF2A2A2A))
                    .pressable(0.96f) { onSelect(if (on && f != SearchFilter.Top) SearchFilter.Top else f) }
                    .padding(horizontal = 14.dp),
            ) {
                Text(stringResource(f.label), color = if (on) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * Interleaves the three result types into one list, weighted toward songs
 * (2 songs per artist+album cycle) so the list reads as mixed rather than
 * grouped, while songs — the most common search intent — stay prominent.
 */
private fun mixSearchResults(results: SearchResults): List<SearchRow> {
    val songs = results.songs.iterator()
    val artists = results.artists.iterator()
    val albums = results.albums.iterator()
    // A few playlists in the mix; the Playlists chip lists them all.
    val playlists = results.playlists.take(5).iterator()
    val out = ArrayList<SearchRow>()
    while (songs.hasNext() || artists.hasNext() || albums.hasNext() || playlists.hasNext()) {
        repeat(2) { if (songs.hasNext()) out += SearchRow.Song(songs.next()) }
        if (artists.hasNext()) out += SearchRow.Artist(artists.next())
        if (albums.hasNext()) out += SearchRow.Album(albums.next())
        if (playlists.hasNext()) out += SearchRow.Playlist(playlists.next())
    }
    return out
}

/** Maps a tapped search-result song to a persisted recent item. */
private fun SongsModel.toRecentItem() = com.music.spotui.data.preferences.RecentItem(
    type = "song",
    key = spotifyTrackId.ifBlank { url },
    name = title,
    singer = singer,
    image = coverUri,
    songId = id,
    songAlbum = album,
    songUrl = url,
    spotifyTrackId = spotifyTrackId,
    explicit = explicit,
    durationMs = durationMs,
)

/** A recent item row (song/artist/album/show the user opened), with remove (x). */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun RecentItemRow(
    item: com.music.spotui.data.preferences.RecentItem,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.pressable(0.96f) { onClick() }
            .fillMaxWidth()
            
            .padding(16.dp, 8.dp),
    ) {
        GlideImage(
            transition = CrossFade,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(if (item.type == "artist") 100.dp else 6.dp)),
            model = item.image,
            contentScale = ContentScale.Crop,
            failure = placeholder(R.drawable.placeholder),
            loading = placeholder(R.drawable.placeholder),
            contentDescription = "",
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp, end = 8.dp),
        ) {
            Text(text = item.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            val subtitle = when (item.type) {
                "song" -> stringResource(R.string.assisted_curation_search_song) + " • ${item.singer}"
                "artist" -> stringResource(R.string.artist_subtitle)
                "album" -> stringResource(R.string.album) + " • ${item.singer}"
                "show" -> stringResource(R.string.album_type_podcast) + (if (item.singer.isNotBlank()) " • ${item.singer}" else "")
                "playlist" -> stringResource(R.string.assisted_curation_search_playlist) + (if (item.singer.isNotBlank()) " • ${item.singer}" else "")
                else -> ""
            }
            Text(text = subtitle, color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        Icon(
            imageVector = Icons.Default.Close,
            tint = Color(0xFFB3B3B3),
            modifier = Modifier.pressable(0.96f) { onRemove() }
                .size(20.dp)
                ,
            contentDescription = stringResource(R.string.aligned_curation_first_save_default_row_remove_action_description),
        )
    }
}

@OptIn(ExperimentalGlideComposeApi::class, ExperimentalFoundationApi::class)
@Composable
fun SearchSongRow(
    song: SongsModel,
    songList: List<SongsModel>,
    searchViewModel: SearchViewModel,
    onPlayed: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val isLiked = com.music.spotui.di.rememberTrackSaved(song.spotifyTrackId)
    val currentPlayingIndicatorColor =
        if (song.id == searchViewModel.currentSongId.value) Color(AppPalette.toArgb()) else Color.White

    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp, 8.dp)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClick = onLongClick,
                onClick = {
                    onPlayed()
                    // Start a radio from the tapped track (queue = this song + Spotify
                    // recommendations) rather than queuing the whole search list.
                    searchViewModel.startRadioFromSong(song)
                    SongPlayer.playSong(song.url, context)
                    searchViewModel.updateSongState(
                        song.coverUri,
                        song.title,
                        song.singer,
                        true,
                        song.id,
                        0,
                        song.album,
                    )
                },
            ),
    ) {
        Row(
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        ) {
            GlideImage(
                transition = CrossFade,
                modifier = Modifier
                    .padding(0.dp, 0.dp, 10.dp, 0.dp)
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp)),
                model = song.coverUri,
                contentScale = ContentScale.Crop,
                failure = placeholder(R.drawable.placeholder),
                loading = placeholder(R.drawable.placeholder),
                contentDescription = "",
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = song.title, color = currentPlayingIndicatorColor, fontSize = 16.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                    if (song.explicit) { com.music.spotui.ui.screens.ExplicitBadge(); androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp)) }
                    Text(text = stringResource(R.string.assisted_curation_search_song) + " • ${song.singer}", color = Color.Gray, fontSize = 13.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
        }

com.music.spotui.ui.screens.AddButton(song = { song }, liked = isLiked, size = 22.dp) {
                    // Spotify library, per track (not the now-playing like state).
                    com.music.spotui.di.SongPlayer.setTrackSaved(song.spotifyTrackId, true)
        }
    }
}

@Composable
fun SearchSectionHeader(title: String) {
    Text(
        text = title,
        color = Color.White,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(16.dp, 18.dp, 16.dp, 4.dp),
    )
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun SearchShowRow(show: com.music.spotui.data.entity.PodcastModel, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.pressable(0.96f) { onClick() }
            .fillMaxWidth()
            .padding(16.dp, 8.dp)
            ,
    ) {
        GlideImage(
            transition = CrossFade,
            modifier = Modifier
                .padding(0.dp, 0.dp, 10.dp, 0.dp)
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp)),
            model = show.coverUri,
            contentScale = ContentScale.Crop,
            failure = placeholder(R.drawable.placeholder),
            loading = placeholder(R.drawable.placeholder),
            contentDescription = "",
        )
        Column {
            Text(text = show.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(
                text = stringResource(R.string.album_type_podcast) + (if (show.publisher.isNotBlank()) " • ${show.publisher}" else ""),
                color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun SearchArtistRow(artist: com.music.spotui.data.entity.ArtistsModel, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.pressable(0.96f) { onClick() }
            .fillMaxWidth()
            .padding(16.dp, 8.dp)
            ,
    ) {
        GlideImage(
            transition = CrossFade,
            modifier = Modifier
                .padding(0.dp, 0.dp, 10.dp, 0.dp)
                .size(48.dp)
                .clip(RoundedCornerShape(100.dp)),
            model = artist.coverUri,
            contentScale = ContentScale.Crop,
            failure = placeholder(R.drawable.placeholder),
            loading = placeholder(R.drawable.placeholder),
            contentDescription = "",
        )
        Column {
            Text(text = artist.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(text = stringResource(R.string.artist_subtitle), color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun SearchAlbumRow(album: com.music.spotui.data.entity.AlbumsModel, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.pressable(0.96f) { onClick() }
            .fillMaxWidth()
            .padding(16.dp, 8.dp)
            ,
    ) {
        GlideImage(
            transition = CrossFade,
            modifier = Modifier
                .padding(0.dp, 0.dp, 10.dp, 0.dp)
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp)),
            model = album.coverUri,
            contentScale = ContentScale.Crop,
            failure = placeholder(R.drawable.placeholder),
            loading = placeholder(R.drawable.placeholder),
            contentDescription = "",
        )
        Column {
            Text(text = album.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(text = stringResource(R.string.album) + " • ${album.artists}", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun SearchPlaylistRow(playlist: com.music.spotui.data.entity.LibraryEntry, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.pressable(0.96f) { onClick() }.fillMaxWidth().padding(16.dp, 8.dp),
    ) {
        GlideImage(
            transition = CrossFade,
            modifier = Modifier.padding(0.dp, 0.dp, 10.dp, 0.dp).size(48.dp).clip(RoundedCornerShape(6.dp)),
            model = playlist.coverUri,
            contentScale = ContentScale.Crop,
            failure = placeholder(R.drawable.placeholder),
            loading = placeholder(R.drawable.placeholder),
            contentDescription = "",
        )
        Column {
            Text(text = playlist.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(text = stringResource(R.string.assisted_curation_search_playlist) +
                (if (playlist.subtitle.isNotBlank()) " • ${playlist.subtitle}" else ""),
                color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

/** Genre key of the "New releases" tile, opened in its own screen. */
const val NEW_RELEASES_GENRE = "new-releases"

/** One "Browse all" tile: Spotify's own category (title, colour, artwork). */
private data class BrowseTile(val key: String, val title: String, val color: Color, val image: String)

@Composable
fun BrowseAllSection(onCategoryClick: (genre: String, title: String) -> Unit) {
    // Fetch Spotify's real categories so images & navigation routes work seamlessly
    val apiCategories by androidx.compose.runtime.produceState(initialValue = emptyList<BrowseTile>()) {
        val categories = runCatching { app.myspoti.host.android.MyspotiRuntime.api.home.categories() }.getOrDefault(emptyList())
        val palette = com.music.spotui.ui.theme.BrowseCategoryColors
        value = categories.mapIndexed { idx, c ->
            val fallbackColor = palette[idx % palette.size]
            val color = runCatching { Color(android.graphics.Color.parseColor(c.color ?: "")) }.getOrDefault(fallbackColor)
            BrowseTile(c.uri, c.title, color, c.images.firstOrNull()?.url.orEmpty())
        }
    }

    fun findCover(keyword: String, fallbackIdx: Int): String {
        val match = apiCategories.firstOrNull { it.title.contains(keyword, ignoreCase = true) && it.image.isNotBlank() }
        if (match != null) return match.image
        return apiCategories.getOrNull(fallbackIdx)?.image.orEmpty()
    }

    fun findKey(keyword: String, defaultKey: String): String {
        return apiCategories.firstOrNull { it.title.contains(keyword, ignoreCase = true) }?.key ?: defaultKey
    }

    // 1. Top 4 Quick Cards (69.dp height, 6.dp corner radius, no header above them — exact Screenshot 2)
    val topQuickTiles = listOf(
        BrowseTile(findKey("music", "spotify:genre:0JQ5DAqbMKFHOzuVTgTizF"), "Music", Color(0xFFE1118C), findCover("music", 0)),
        BrowseTile(findKey("podcast", "spotify:genre:0JQ5DArNBzkmxXHCqFLx2J"), "Podcasts", Color(0xFF1E8264), findCover("podcast", 1)),
        BrowseTile(findKey("audiobook", "spotify:genre:0JQ5DAqbMKFA6SOHvT3gck"), "Audiobooks", Color(0xFF1E3264), findCover("audiobook", 2)),
        BrowseTile(findKey("live", "spotify:genre:0JQ5DAqbMKFDXXwE9BDJAr"), "Live Events", Color(0xFF8400E7), findCover("live", 3)),
    )

    // 2. "More for you" 6 Taller Cards (102.dp height, 6.dp corner radius — exact Screenshot 2)
    val moreForYouTiles = listOf(
        BrowseTile(findKey("made for you", "spotify:genre:0JQ5DAqbMKFHOzuVTgTizF"), "Made\nFor You", Color(0xFF8D67AB), findCover("made", 4)),
        BrowseTile(NEW_RELEASES_GENRE, "New\nReleases", Color(0xFF608108), findCover("new", 5)),
        BrowseTile(findKey("upcoming", NEW_RELEASES_GENRE), "Upcom-\ning\nreleases", Color(0xFF146956), findCover("release", 6)),
        BrowseTile(findKey("cult", "spotify:genre:0JQ5DAqbMKFEC4WFtoNRpw"), "OFF-\nCULT", Color(0xFF777777), findCover("pop", 7)),
        BrowseTile(findKey("halloween", "spotify:genre:0JQ5DAqbMKFAXlCG6QvYQ4"), "Hal-\nloween", Color(0xFFE13300), findCover("party", 8)),
        BrowseTile(findKey("chart", "spotify:genre:0JQ5DAudkNjCgYMM0TZXDw"), "Charts", Color(0xFF8D67AB), findCover("chart", 9)),
    )

    // 3. "Genres" section (69.dp height, starting with Hip-Hop & Indie then all remaining API categories)
    val genreTiles = listOf(
        BrowseTile(findKey("hip-hop", "spotify:genre:0JQ5DAqbMKFQ00XGBls6ym"), "Hip-Hop", Color(0xFF283EA3), findCover("hip-hop", 10)),
        BrowseTile(findKey("indie", "spotify:genre:0JQ5DAqbMKFCWjUTdzaG0e"), "Indie", Color(0xFF148A08), findCover("indie", 11)),
    ) + apiCategories.filter { c ->
        val t = c.title.lowercase()
        !t.contains("hip-hop") && !t.contains("indie") && !t.contains("made for you") && !t.contains("new release") && !t.contains("chart")
    }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        // Top 4 Quick Cards (2x2)
        topQuickTiles.chunked(2).forEachIndexed { idx, rowItems ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        top = if (idx == 0) 4.dp else 15.dp,
                    ),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                rowItems.forEach { t ->
                    BrowseCategoryTile(
                        name = t.title,
                        color = t.color,
                        cover = t.image,
                        cardHeight = 69.dp,
                        maxLines = 2,
                        modifier = Modifier.weight(1f),
                    ) { onCategoryClick(t.key, t.title.replace("\n", " ")) }
                }
            }
        }

        // "More for you" Header
        Text(
            text = "More for you",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 14.dp),
        )

        // "More for you" 6 Cards (2x3, 102.dp tall)
        moreForYouTiles.chunked(2).forEachIndexed { idx, rowItems ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        top = if (idx == 0) 0.dp else 15.dp,
                    ),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                rowItems.forEach { t ->
                    BrowseCategoryTile(
                        name = t.title,
                        color = t.color,
                        cover = t.image,
                        cardHeight = 102.dp,
                        maxLines = 3,
                        modifier = Modifier.weight(1f),
                    ) { onCategoryClick(t.key, t.title.replace("-\n", "").replace("\n", " ")) }
                }
            }
        }

        // "Genres" Header with "See all" on right
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Genres",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "See all",
                color = Color(0xFFB3B3B3),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // "Genres" Grid (69.dp tall)
        genreTiles.chunked(2).forEachIndexed { idx, rowItems ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        top = if (idx == 0) 0.dp else 15.dp,
                    ),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                rowItems.forEach { t ->
                    BrowseCategoryTile(
                        name = t.title,
                        color = t.color,
                        cover = t.image,
                        cardHeight = 69.dp,
                        maxLines = 2,
                        modifier = Modifier.weight(1f),
                    ) { onCategoryClick(t.key, t.title) }
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun BrowseCategoryTile(
    name: String,
    color: Color,
    cover: String,
    cardHeight: androidx.compose.ui.unit.Dp = 69.dp,
    maxLines: Int = 2,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(cardHeight)
            .clip(RoundedCornerShape(6.dp))
            .background(color)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() },
    ) {
        if (cover.isNotBlank()) {
            GlideImage(
                transition = CrossFade,
                model = cover,
                contentScale = ContentScale.Crop,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(width = 74.dp, height = 84.dp)
                    .offset(x = 14.dp, y = 10.dp)
                    .rotate(25f)
                    .clip(RoundedCornerShape(4.dp)),
            )
        } else {
            // Subtle rotated album card placeholder so every tile has Spotify's tilted artwork silhouette
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(width = 68.dp, height = 78.dp)
                    .offset(x = 14.dp, y = 12.dp)
                    .rotate(25f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White.copy(alpha = 0.16f)),
            )
        }
        Text(
            text = name,
            color = Color.White,
            fontSize = 15.5.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.Bold,
            maxLines = maxLines,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun SearchTopBar() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        com.music.spotui.data.api.ProfileCache.ensure(context)
    }
    val avatarUrl = com.music.spotui.data.api.ProfileCache.imageUrl
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(com.music.spotui.ui.theme.ScSecondary)
                    .clickable { com.music.spotui.ui.components.ProfileDrawerState.open() },
            ) {
                if (avatarUrl != null) {
                    GlideImage(
                        transition = CrossFade,
                        model = avatarUrl,
                        contentScale = ContentScale.Crop,
                        loading = placeholder(R.drawable.sc_user),
                        failure = placeholder(R.drawable.sc_user),
                        contentDescription = null,
                        modifier = Modifier.size(34.dp),
                    )
                } else {
                    androidx.compose.foundation.Image(
                        painter = painterResource(R.drawable.sc_user),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(34.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.navigationbar_musicappitems_search_title),
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
fun SearchStickyBar(
    text: String,
    isActive: Boolean = false,
    onFocusChange: (Boolean) -> Unit = {},
    onActivate: () -> Unit = {},
    onBack: () -> Unit = {},
    onClear: () -> Unit = {},
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    onTextChange: (String) -> Unit,
) {
    LaunchedEffect(isActive) {
        if (isActive && text.isEmpty()) {
            runCatching { focusRequester?.requestFocus() }
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(AppBackground.toArgb()))
            .padding(
                horizontal = if (isActive) 12.dp else 16.dp,
                vertical = if (isActive) 8.dp else 6.dp,
            ),
    ) {
        if (isActive) {
            Icon(
                imageVector = androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                tint = Color.White,
                contentDescription = stringResource(R.string.accessibility_navigate_back),
                modifier = Modifier
                    .size(26.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onBack() },
            )
            Spacer(Modifier.width(12.dp))
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .height(if (isActive) 42.dp else 48.dp)
                .clip(if (isActive) RoundedCornerShape(50) else RoundedCornerShape(6.dp))
                .background(if (isActive) Color(0xFF282828) else Color.White)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    onActivate()
                    runCatching { focusRequester?.requestFocus() }
                }
                .padding(horizontal = if (isActive) 16.dp else 12.dp),
        ) {
            if (!isActive) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_spot_search_outline),
                    tint = Color(0xFF121212),
                    contentDescription = "",
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
            }
            androidx.compose.foundation.text.BasicTextField(
                value = text,
                onValueChange = onTextChange,
                singleLine = true,
                cursorBrush = androidx.compose.ui.graphics.SolidColor(
                    if (isActive) com.music.spotui.ui.theme.AppAccent else Color.Black
                ),
                textStyle = TextStyle(
                    fontFamily = com.music.spotui.ui.theme.AppSans,
                    fontSize = 15.sp,
                    color = if (isActive) Color.White else Color(0xFF121212),
                    fontWeight = if (isActive) FontWeight.Normal else FontWeight.SemiBold,
                ),
                modifier = Modifier
                    .weight(1f)
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .onFocusChanged { onFocusChange(it.isFocused) },
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (text.isEmpty()) {
                            Text(
                                text = stringResource(R.string.search_header_field_hint),
                                style = TextStyle(
                                    fontFamily = com.music.spotui.ui.theme.AppSans,
                                    fontSize = 15.sp,
                                    color = if (isActive) Color(0xFFB3B3B3) else Color(0xFF535353),
                                    fontWeight = if (isActive) FontWeight.Normal else FontWeight.SemiBold,
                                ),
                                maxLines = 1,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            if (isActive && text.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(20.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(Color(0xFFB3B3B3))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onClear() },
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        tint = Color(0xFF121212),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}


/** A user found by the Profiles chip: round picture, name, "Profile". */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun SearchProfileRow(name: String, image: String?, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().pressable(0.98f, onClick = onClick).padding(16.dp, 8.dp),
    ) {
        com.bumptech.glide.integration.compose.GlideImage(
            transition = CrossFade,
            model = image, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            failure = com.bumptech.glide.integration.compose.placeholder(R.drawable.placeholder),
            modifier = Modifier.size(52.dp).clip(androidx.compose.foundation.shape.CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(name, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(stringResource(R.string.recently_played_user_profile), color = Color(0xFFB3B3B3), fontSize = 13.sp)
        }
    }
}

/** Spotify's own recent searches, cleared with the app's list. */
private fun clearSpotifyRecents() {
    val runtime = app.myspoti.host.android.MyspotiRuntime
    runtime.scope.launch { runCatching { runtime.api.search.clearRecent() } }
}
