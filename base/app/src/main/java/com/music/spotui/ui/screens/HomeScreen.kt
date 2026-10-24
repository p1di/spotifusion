package com.music.spotui.ui.screens

import com.music.spotui.ui.components.SkeletonKind
import com.music.spotui.ui.components.pressable

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.filled.Check
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
import com.music.spotui.data.entity.AlbumsModel
import com.music.spotui.data.entity.ArtistsModel
import com.music.spotui.data.entity.HomeFeedModel
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.entity.HomeSection
import com.music.spotui.ui.components.Loader
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.navigation.albumRoute
import com.music.spotui.ui.navigation.artistRoute
import com.music.spotui.ui.navigation.playlistRoute
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppPalette
import com.music.spotui.ui.theme.GridBackground
import com.music.spotui.ui.viewmodel.HomeViewModel
import java.time.LocalTime


@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun HomeScreen(navController: NavController){

    val homeViewModel : HomeViewModel = viewModel()
    val home by homeViewModel.home.collectAsState()

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(AppBackground.toArgb()))
    ) {
        val feed = (home as? Response.Success)?.data

        when {
            // Preferred: the real personalized Spotify home feed.
            feed != null && feed.sections.isNotEmpty() -> {
                HomeFeedContent(navController, feed, homeViewModel.facet.value) { homeViewModel.selectFacet(it) }
            }

            // No feed to show (loading, empty chip, error): keep the chips on screen
            // so the user can always switch back to "Tout".
            else -> androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                HomeHeaderRow(navController, homeViewModel.facet.value) { homeViewModel.selectFacet(it) }
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when {
                        home is Response.Loading -> Loader(SkeletonKind.Home)
                        else -> androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = if (home is Response.Error) stringResource(R.string.home_error) else stringResource(R.string.home_empty),
                                color = Color.White,
                                textAlign = TextAlign.Center,
                            )
                            if (home is Response.Error) androidx.compose.material3.TextButton(onClick = { homeViewModel.retry() }) {
                                Text(stringResource(R.string.home_retry), color = Color(0xFF1DB954))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Albums and playlists start loading under the finger (see pressHint). */
private fun prefetchHomeItem(item: HomeItem) {
    when (item) {
        is HomeItem.Album -> if (item.id.isNotBlank()) ContextViewModel.prefetch("album", item.id)
        is HomeItem.Playlist -> if (item.id.isNotBlank()) ContextViewModel.prefetch("playlist", item.id)
        else -> {}
    }
}

private fun onHomeItemClick(navController: NavController, item: HomeItem) {
    when (item) {
        is HomeItem.Album -> navController.navigate(albumRoute(item.name, item.artists.ifBlank { item.subtitle }, item.id))
        is HomeItem.Artist -> navController.navigate(artistRoute(item.name, item.id))
        is HomeItem.Show ->
            if (item.isEpisode) com.music.spotui.di.SongPlayer.playSong("episode:${item.id}")
            else navController.navigate(com.music.spotui.ui.navigation.showRoute(item.id, item.name))
        // Load the real playlist content by its Spotify id (daily mixes, etc).
        is HomeItem.Playlist ->
            if (item.id.isNotBlank()) navController.navigate(playlistRoute(item.id, item.name))
            else navController.navigate(albumRoute(item.name))
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun HomeFeedContent(navController: NavController, feed: HomeFeedModel, facet: String = "", onFacet: (String) -> Unit = {}) {
    // Mirror open.spotify.com exactly: sections render in the order the feed
    // returns them. The 2-column "shortcuts" grid is only used for the UNTITLED
    // section the web home starts with — if the feed leads with a titled section
    // ("Jump back in", "Made For …"), it renders as a titled carousel first,
    // not force-squeezed into the grid.
    // Podcasts are off for now: their shows and episodes leave the feed.
    val sections = if (com.music.spotui.data.api.Features.PODCASTS) feed.sections
        else feed.sections.map { s -> s.copy(items = s.items.filterNot { it is HomeItem.Show }) }.filter { it.items.isNotEmpty() }
    val gridSection = sections.firstOrNull()?.takeIf { it.title.isBlank() }
    val carousels = if (gridSection != null) sections.drop(1) else sections
    // The shortcuts at the top load in the background, ready before a tap.
    androidx.compose.runtime.LaunchedEffect(gridSection?.items?.map { it.name }) {
        val top = gridSection?.items.orEmpty().take(8).mapNotNull { item ->
            when (item) {
                is HomeItem.Album -> item.id.takeIf { it.isNotBlank() }?.let { "album" to it }
                is HomeItem.Playlist -> item.id.takeIf { it.isNotBlank() }?.let { "playlist" to it }
                else -> null
            }
        }
        if (top.isNotEmpty()) ContextViewModel.prefetchSoon(top)
    }

    // Re-tapping the Home tab while on Home scrolls this list back to its top (official
    // behaviour): an animation over the existing items, so nothing is fetched again.
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.music.spotui.ui.navigation.TabReselect.home.collect {
            if (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0) {
                listState.animateScrollToItem(0)
            }
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = com.music.spotui.ui.components.bottomBarsSpace()),
        modifier = Modifier
            .fillMaxSize()
            .background(Color(AppBackground.toArgb()))
    ) {
        // 1:1 spotify-clone Header/Header.tsx sticky top header with #121212 background and shadow
        stickyHeader {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(AppBackground.toArgb()))
                    .statusBarsPadding()
            ) {
                HomeHeaderRow(navController, facet, onFacet)
            }
        }
        gridSection?.let { section ->
            item {
                HomeShortcutGrid(navController, section.items.take(8))
            }
        }
        items(carousels.size) { i ->
            HomeFeedSection(navController, carousels[i])
        }
    }
}

/**
 * Exact 1:1 port of Screenshot 1 (com.spotify.music Home top bar):
 * - Left: 34.dp circular user avatar (opens Left Profile Drawer)
 * - 8.dp gap -> Horizontal filter pills ("All", "Music", "Podcasts", "Audiobooks"), 32.dp height
 * - No "Home" text label in the header!
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun HomeHeaderRow(navController: NavController, facet: String, onFacet: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.music.spotui.data.api.ProfileCache.ensure(context)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(AppBackground.toArgb()))
            .padding(start = 16.dp, top = 12.dp, bottom = 10.dp),
    ) {
        val avatarUrl = com.music.spotui.data.api.ProfileCache.imageUrl
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Color(0xFF2A2A2A))
                .clickable { com.music.spotui.ui.components.ProfileDrawerState.open() },
        ) {
            if (avatarUrl != null) {
                GlideImage(
                    transition = CrossFade,
                    model = avatarUrl,
                    contentScale = ContentScale.Crop,
                    loading = placeholder(R.drawable.sc_user),
                    failure = placeholder(R.drawable.sc_user),
                    contentDescription = stringResource(R.string.recently_played_user_profile),
                    modifier = Modifier.size(34.dp),
                )
            } else {
                Image(
                    painter = painterResource(R.drawable.sc_user),
                    contentDescription = stringResource(R.string.recently_played_user_profile),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(34.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .horizontalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(end = 16.dp),
        ) {
            HomePill(stringResource(R.string.filter_chip_all_label), selected = facet.isEmpty()) { onFacet("") }
            HomePill(stringResource(R.string.recents_content_filter_music), selected = facet == "music-chip") { onFacet("music-chip") }
            HomePill(stringResource(R.string.ad_category_title_podcasts), selected = facet == "podcasts-chip") { onFacet("podcasts-chip") }
            HomePill(stringResource(R.string.audiobooks_title), selected = facet == "audiobooks-chip") { onFacet("audiobooks-chip") }
        }
    }
}

@Composable
private fun HomePill(label: String, selected: Boolean = false, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(100.dp))
            .background(if (selected) Color(0xFF1ED760) else Color(0xFF333333))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = label,
            color = if (selected) Color.Black else Color.White,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

/**
 * Exact 1:1 port of Screenshot 1 8-tile shortcut grid:
 * - 2 columns x 4 rows, 16.dp outer horizontal margin, 8.dp horizontal & vertical gap
 * - Exact measured height: 135px = 48.dp, 4.dp corner radius, #2A2A2A background
 * - 48.dp x 48.dp square artwork on left, 8.dp horizontal text padding, 13.sp Bold white 2-line title
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun HomeShortcutGrid(navController: NavController, items: List<HomeItem>) {
    Column(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.chunked(2).forEach { rowItems ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                rowItems.forEach { item ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .pressable(0.96f, onPressHint = { prefetchHomeItem(item) }) { onHomeItemClick(navController, item) }
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF2A2A2A)),
                    ) {
                        GlideImage(
                            transition = CrossFade,
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color(0xFF242424)),
                            contentScale = ContentScale.Crop,
                            model = item.imageUrl,
                            loading = placeholder(R.drawable.sc_disc),
                            failure = placeholder(R.drawable.sc_disc),
                            contentDescription = "",
                        )
                        Text(
                            text = item.name,
                            color = Color.White,
                            fontSize = 13.sp,
                            lineHeight = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
                if (rowItems.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * Exact 1:1 port of Screenshot 1 Horizontal Shelf ("Your shows", "New releases for you"):
 * - Section title: 22.sp Bold White, 16.dp horizontal padding, 26.dp top padding, 12.dp bottom padding (no "Show all")
 * - LazyRow: 16.dp horizontal contentPadding, 16.dp item spacing
 */
@Composable
private fun HomeFeedSection(navController: NavController, section: HomeSection) {
    Text(
        text = section.title,
        color = Color.White,
        fontSize = 22.sp,
        letterSpacing = (-0.4).sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 26.dp, bottom = 12.dp),
    )
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(section.items.size) { i ->
            HomeFeedCard(section.items[i]) { onHomeItemClick(navController, section.items[i]) }
        }
    }
}

/**
 * Exact 1:1 port of Screenshot 1 Shelf Card:
 * - Measured size: 434px = 154.dp square artwork
 * - Shape: CircleShape for Artists, RoundedCornerShape(8.dp) for Shows/Albums/Playlists
 * - Title: 13.5.sp Bold White, 8.dp top spacing, maxLines = 2
 * - Subtitle: 13.sp Regular #A7A7A7, 3.dp top spacing, maxLines = 1
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun HomeFeedCard(item: HomeItem, onClick: () -> Unit) {
    val isArtist = item is HomeItem.Artist
    val subtitle = when (item) {
        is HomeItem.Album -> item.subtitle
        is HomeItem.Playlist -> item.subtitle
        is HomeItem.Artist -> stringResource(R.string.artist_subtitle)
        is HomeItem.Show -> item.subtitle
    }
    val fallbackIcon = if (isArtist) R.drawable.sc_user else R.drawable.sc_disc
    Column(
        horizontalAlignment = if (isArtist) Alignment.CenterHorizontally else Alignment.Start,
        modifier = Modifier
            .pressable(0.96f, onPressHint = { prefetchHomeItem(item) }) { onClick() }
            .width(154.dp),
    ) {
        GlideImage(
            transition = CrossFade,
            modifier = Modifier
                .size(154.dp)
                .clip(if (isArtist) CircleShape else RoundedCornerShape(8.dp))
                .background(Color(0xFF282828)),
            contentScale = ContentScale.Crop,
            model = item.imageUrl,
            loading = placeholder(fallbackIcon),
            failure = placeholder(fallbackIcon),
            contentDescription = "",
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = item.name,
            color = Color.White,
            fontSize = 13.5.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
        )
        if (subtitle.isNotBlank()) {
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = subtitle,
                color = Color(0xFFA7A7A7),
                fontSize = 13.sp,
                lineHeight = 15.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
            )
        }
    }
}

/** Profile sheet behind the avatar: account name and sign-out (in the Web Player). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class)
@Composable
private fun ProfileSheet(onOpen: (route: String) -> Unit = {}, onDismiss: () -> Unit) {
    val name = com.music.spotui.data.api.ProfileCache.name.orEmpty()
    val avatar = com.music.spotui.data.api.ProfileCache.imageUrl
    // Opened in full: half-open, the sheet and its list both moved under the finger.
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF282828),
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.fillMaxWidth().then(Modifier.windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.navigationBars))
                .verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(bottom = 16.dp),
        ) {
            // Name and picture open your public profile, as in Spotify.
            val myId = com.music.spotui.data.api.ProfileCache.id
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                .clickable(enabled = myId != null) { myId?.let { onOpen(com.music.spotui.ui.navigation.profileRoute(it, name)) } }
                .padding(horizontal = 20.dp, vertical = 8.dp)) {
                if (avatar != null) GlideImage(
                    transition = CrossFade,
                    model = avatar, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(48.dp).clip(CircleShape),
                )
                Text(name.ifBlank { stringResource(R.string.spotify_account) }, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 14.dp))
                if (com.music.spotui.data.api.SupportReminder.level != com.music.spotui.data.api.SupportReminder.Level.NONE)
                    com.music.spotui.ui.components.SupporterBadge(Modifier.padding(start = 10.dp))
            }
            if (myId != null) Text(stringResource(R.string.view_profile), color = Color(0xFFB3B3B3), fontSize = 13.sp,
                modifier = Modifier.padding(start = 82.dp, bottom = 4.dp))
            androidx.compose.material3.HorizontalDivider(color = Color(0xFF3E3E3E), modifier = Modifier.padding(vertical = 8.dp))
            // Sound quality, downloads, data, battery, display: one page.
            androidx.compose.foundation.layout.Column(
                Modifier.fillMaxWidth().clickable { onOpen(com.music.spotui.ui.navigation.Routes.Settings.route) }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(stringResource(R.string.settings_title), color = Color.White, fontSize = 16.sp)
                Text(stringResource(R.string.settings_detail), color = Color(0xFFB3B3B3), fontSize = 12.sp)
            }
            // In-app recap: every listen on this phone, Spotify, downloads and local files.
            androidx.compose.foundation.layout.Column(
                Modifier.fillMaxWidth().clickable { onOpen(com.music.spotui.ui.navigation.Routes.Recap.route) }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(stringResource(R.string.recap_title), color = Color.White, fontSize = 16.sp)
                Text(stringResource(R.string.recap_detail), color = Color(0xFFB3B3B3), fontSize = 12.sp)
            }
            // The account's own listening history, as Spotify keeps it: it belongs beside the
            // recap (the local counter) because both answer "what did I listen to".
            androidx.compose.foundation.layout.Column(
                Modifier.fillMaxWidth().clickable { onOpen(com.music.spotui.ui.navigation.Routes.Recent.route) }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(stringResource(R.string.recents_title), color = Color.White, fontSize = 16.sp)
                Text(stringResource(R.string.recents_detail), color = Color(0xFFB3B3B3), fontSize = 12.sp)
            }
            // The Spotify account page (plan, privacy, devices), in the app's signed-in browser.
            androidx.compose.foundation.layout.Column(
                Modifier.fillMaxWidth().clickable {
                    onDismiss()
                    com.music.spotui.ui.screens.AccountBrowser.open(com.music.spotui.di.Graph.app, com.music.spotui.ui.screens.AccountBrowser.ACCOUNT_URL)
                }.padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(stringResource(R.string.account_page_title), color = Color.White, fontSize = 16.sp)
                Text(stringResource(R.string.account_page_detail), color = Color(0xFFB3B3B3), fontSize = 12.sp)
            }
            val ctx = androidx.compose.ui.platform.LocalContext.current
            // Donations (Ko-fi or GitHub Sponsors): always here, never in the way.
            var support by remember { androidx.compose.runtime.mutableStateOf(false) }
            if (support) com.music.spotui.ui.components.SupportSheet(onDismiss = { support = false })
            androidx.compose.foundation.layout.Column(
                Modifier.fillMaxWidth().clickable { support = true }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(stringResource(R.string.support_title), color = com.music.spotui.ui.theme.AppAccent, fontSize = 16.sp)
                Text(stringResource(R.string.support_row_detail), color = Color(0xFFB3B3B3), fontSize = 12.sp)
            }
            // About: version, Discord, source code and Ko-fi.
            var about by remember { androidx.compose.runtime.mutableStateOf(false) }
            if (about) com.music.spotui.ui.components.AboutSheet(onDismiss = { about = false })
            androidx.compose.foundation.layout.Column(
                Modifier.fillMaxWidth().clickable { about = true }.padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(stringResource(R.string.creator_biography_section_title), color = Color.White, fontSize = 16.sp)
                Text(stringResource(R.string.about_detail), color = Color(0xFFB3B3B3), fontSize = 12.sp)
            }
            var perks by remember { androidx.compose.runtime.mutableStateOf(false) }
            if (perks) com.music.spotui.ui.components.SupporterSheet(onDismiss = { perks = false })
            if (com.music.spotui.data.api.SupportReminder.PERKS_ENABLED) androidx.compose.foundation.layout.Column(
                Modifier.fillMaxWidth().clickable { perks = true }.padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(stringResource(R.string.supporter_perks_title), color = Color.White, fontSize = 16.sp)
                Text(stringResource(R.string.supporter_perks_detail), color = Color(0xFFB3B3B3), fontSize = 12.sp)
            }
            Text(
                stringResource(R.string.connect_device_menu_logout),
                color = Color.White, fontSize = 16.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onDismiss()
                        com.music.spotui.di.SessionActions.logout(com.music.spotui.di.Graph.app)
                    }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            )
        }
    }
}


