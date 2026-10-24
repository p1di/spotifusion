package com.music.spotui.ui.screens

import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.positionChange
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.music.spotui.ui.components.pressable
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.vectorResource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.myspoti.power.EnergySettings
import androidx.navigation.NavController
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.CrossFade
import com.bumptech.glide.integration.compose.GlideImage
import app.myspoti.engine.R
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.addLikedSongId
import com.music.spotui.data.preferences.getLikedSongIds
import com.music.spotui.data.preferences.getSongsByIds
import com.music.spotui.data.preferences.isSongLiked
import com.music.spotui.data.preferences.removeLikedSongId
import com.music.spotui.di.Palette
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.Snackbar
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.navigation.albumRoute
import com.music.spotui.ui.navigation.artistRoute
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppPalette
import com.music.spotui.ui.viewmodel.PlayerViewModel
import com.music.spotui.ui.viewmodel.confirmedTrackIdForDisplay
import com.music.spotui.ui.viewmodel.confirmedTrackIdFromUri
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@OptIn(ExperimentalGlideComposeApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PlayerScreen(navController: NavController) {
    val playerViewModel : PlayerViewModel = viewModel()
    val songTitle = playerViewModel.currentSongTitle.value
    val songSinger = playerViewModel.currentSongSinger.value
    val songCoverUri = playerViewModel.currentSongCoverUri.value
    val songPlayingState = playerViewModel.currentSongPlayingState.value
    val audioBuffering = SongPlayer.isBuffering
    // T4: the page shows no track during an ad break; an explicit ad state is shown instead.
    val adPlaying = SongPlayer.adPlaying
    val songId = playerViewModel.currentSongId.value
    val context = LocalContext.current
    val energy by EnergySettings.state.collectAsStateWithLifecycle()
    // Liked state is Spotify's (LibraryAPI), pushed by the engine.
    val isLiked = playerViewModel.likeState
    var showMenu by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var showSavedIn by remember { mutableStateOf(false) }
    var showDevices by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    // Immersive view (official app: a tap on the artwork): artwork full-bleed, no top bar,
    // no title / controls block, only one compact strip. A second tap leaves it.
    var immersive by remember { mutableStateOf(false) }
    if (showDevices) com.music.spotui.ui.components.DevicesSheet { showDevices = false }
    if (showQueue) com.music.spotui.ui.components.QueueSheet(
        // B26: the list's own name (the page's label is blank for some lists), not the album's.
        contextName = playerContextName(playerViewModel),
        onDismiss = { showQueue = false },
    )

    BackHandler(enabled = showLyrics) {
        showLyrics = false
    }
    // Back leaves the immersive view first; the lyrics (handled above) keep priority.
    BackHandler(enabled = immersive && !showLyrics) {
        immersive = false
    }

    if (showMenu) {
        PlayerOptionsSheet(
            navController = navController,
            playerViewModel = playerViewModel,
            context = context,
            isLiked = isLiked,
            onDismiss = { showMenu = false },
            onOpenQueue = { showQueue = true },
        )
    }

    if (showSavedIn) {
        playerViewModel.queue.value.firstOrNull { it.id == songId }?.let { track ->
            com.music.spotui.ui.components.SavedInSheet(
                song = track,
                context = context,
                onDismiss = { showSavedIn = false },
                onLikedChanged = { isLiked.value = it },
            )
        } ?: run { showSavedIn = false }
    }


    // Known cover (warmed by the mini player): the colour is there from the first frame.
    var coverColor by remember {
        mutableStateOf(Palette.cachedSecond(songCoverUri) ?: Color(AppBackground.toArgb()))
    }
    LaunchedEffect(songCoverUri) {
        if (songCoverUri.isNotBlank()) Palette().extractSecondColorFromCoverUrl(context = context, songCoverUri) { color ->
            coverColor = color
        }
    }
    // Track changes cross-fade the background instead of snapping.
    val dominentColor by androidx.compose.animation.animateColorAsState(coverColor, androidx.compose.animation.core.tween(450), label = "cover")

    val shuffle = playerViewModel.shuffleState.value
    val repeat = playerViewModel.repeatState.value
    val queueSongs = playerViewModel.queue.value
    val np by SongPlayer.nowPlaying
    val currentTrack = queueSongs.firstOrNull { it.id == songId }
    val actualTrackId = confirmedTrackIdFromUri(np.uri)
    val displayedTrackId = confirmedTrackIdForDisplay(np.uri, songId)

    // Smooth progress: redrawn every frame from the engine clock (see SpotifySeekBar).
    val frame = com.music.spotui.ui.components.rememberFrameClock(songPlayingState && !audioBuffering, fps = 15)

    // ── Swipe the artwork to change track: [previous, current, next] from Spotify ──
    val artworkPagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })
    LaunchedEffect(songId) {
        if (artworkPagerState.currentPage != 1) artworkPagerState.scrollToPage(1)
    }
    LaunchedEffect(artworkPagerState) {
        snapshotFlow { artworkPagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                when (page) {
                    2 -> SongPlayer.swipeNext()
                    0 -> SongPlayer.previous()
                }
            }
    }

    // The confirmed URI can change after an optimistic songId already changed.
    // Hide a stale result in this composition, before the loading effect runs.
    LaunchedEffect(displayedTrackId, energy.policy.allowCanvas, audioBuffering) {
        playerViewModel.loadCanvas(
            if (energy.policy.allowCanvas && !com.music.spotui.data.DataSaver.active) displayedTrackId else "",
            allowFetch = !audioBuffering,
        )
    }
    val canvas = playerViewModel.canvasState.value
    // Read from the cache in this very frame when already looked up (the view model's state
    // only follows one frame later): the screen then opens on the Canvas, not on the cover.
    val knownCanvas = remember(displayedTrackId) { com.music.spotui.di.Graph.api.knownCanvas(displayedTrackId)?.getOrNull() }
    val canvasUrl = (if (canvas.trackId == displayedTrackId) canvas.url else knownCanvas).takeIf {
        energy.policy.allowCanvas && !com.music.spotui.data.DataSaver.active && displayedTrackId.isNotBlank()
    }
    // The cover stays until the Canvas has drawn its first frame: hiding it earlier showed
    // the bare background for a moment (a flash) between the cover and the clip.
    // Prepared beforehand by the mini player: the clip is shown from the first frame, no cover.
    var canvasShown by remember(canvasUrl) { mutableStateOf(canvasUrl != null && com.music.spotui.ui.components.CanvasPlayer.isReady(canvasUrl)) }
    // Cover to Canvas as one cross-fade: the clip (full screen) fades in while the cover fades
    // out and grows a little into it, instead of the background switching at once.
    val canvasOn = canvasUrl != null && canvasShown
    // A clip already loaded shows at once; one still loading fades in over the cover.
    val readyAtOnce = remember(canvasUrl) { canvasUrl != null && com.music.spotui.ui.components.CanvasPlayer.isReady(canvasUrl) }
    val canvasProgress = remember { androidx.compose.animation.core.Animatable(if (readyAtOnce) 1f else 0f) }
    LaunchedEffect(canvasOn, readyAtOnce) {
        val target = if (canvasOn) 1f else 0f
        if (canvasOn && readyAtOnce) canvasProgress.snapTo(target)
        else canvasProgress.animateTo(target, androidx.compose.animation.core.tween(CANVAS_FADE_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing))
    }
    val canvasAlpha = canvasProgress.value
    val coverAlpha = 1f - canvasProgress.value
    // A tap on the Canvas no longer needs its own mode: the immersive view below handles both
    // cases (Canvas playing or plain cover), so the Canvas-only fade ("chrome") is gone.
    // Music video (YouTube, synced to the Spotify playback): looked up for every track
    // shown here, offered once its time map is known, shown in place of the artwork.
    val mv = com.music.spotui.video.MusicVideoController
    androidx.compose.runtime.DisposableEffect(mv) {
        mv.attachPlayerScreen()
        onDispose { mv.detachPlayerScreen() }
    }
    LaunchedEffect(actualTrackId, songTitle, audioBuffering) {
        if (!audioBuffering) mv.prepare(actualTrackId, songTitle, songSinger, SongPlayer.getDuration())
    }
    val remote = SongPlayer.activeDevice.value != null
    val readyVideo = mv.state.value as? com.music.spotui.video.MusicVideoController.State.Ready
    val videoMatchesTrack = displayedTrackId.isNotBlank() && actualTrackId == displayedTrackId && readyVideo?.trackId == actualTrackId
    val videoReady = videoMatchesTrack && !remote && !audioBuffering && !com.music.spotui.data.DataSaver.active
    // Switching to mobile data with the data saver on stops a clip that is playing.
    LaunchedEffect(com.music.spotui.data.DataSaver.active) {
        if (com.music.spotui.data.DataSaver.active && mv.on.value) mv.setOn(false)
    }
    val videoOn = mv.on.value && videoMatchesTrack && !remote
    // Canvas / clip players are built once the sheet has finished rising (then kept):
    // creating them in the first frame stalled it and the slide jumped.
    val settled by com.music.spotui.ui.navigation.LocalPageSettled.current
    var mediaReady by remember { mutableStateOf(false) }
    LaunchedEffect(settled) { if (settled) mediaReady = true }
    val showVideo = videoOn && !showLyrics && !mv.fullscreen.value && mediaReady
    // Buffer the clip as soon as it is known for the track on screen.
    LaunchedEffect(mv.state.value, remote, audioBuffering, displayedTrackId) { if (videoReady && !audioBuffering) mv.warm() }
    // Pulled down from the top, the player closes, like the back button (issue #4).
    val pullToClose = com.music.spotui.ui.components.rememberPullToDismiss { com.music.spotui.ui.navigation.PlayerSheet.close() }
    pullToClose.enabled = !showLyrics && !mv.fullscreen.value
    Box(
        modifier = Modifier
            .graphicsLayer { translationY = pullToClose.offset }
            .fillMaxSize()
            .background(
                // The tint follows the cover in BOTH modes: AMOLED used to be pure black, and the
                // colour was asked for anyway (that replaces the halo idea). The screen keeps its
                // gradient, the toggle still governs the rest of the app.
                Brush.verticalGradient(
                    colors = listOf(
                        androidx.compose.ui.graphics.lerp(dominentColor, Color.Black, 0.35f),
                        Color(0xFF121212),
                    ),
                    startY = 0f
                )
            )
    ) {
        val pillAboveTitle = canvasUrl != null && !videoOn
        if (canvasUrl != null && !videoOn) {
            CanvasVideo(
                url = canvasUrl,
                onFirstFrame = { canvasShown = true },
                playing = songPlayingState && !showLyrics && !audioBuffering,
                modifier = Modifier.fillMaxSize().alpha(canvasAlpha),
            )
            // Darker than the clip itself while the player shows over it, so the text reads;
            // gone when the Canvas is shown alone.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Lighter dim in the immersive view: the clip is the page there, only the
                    // compact strip needs something to sit on.
                    .alpha(canvasAlpha * if (immersive) 0.35f else 1f)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.60f),
                                Color.Black.copy(alpha = 0.25f),
                                Color.Black.copy(alpha = 0.50f),
                                Color.Black.copy(alpha = 0.85f),
                            )
                        )
                    )
            )
        }
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(pullToClose.connection)
                .statusBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
        item {
        // Sized from the real space (foldables, tablets), not the screen: wide windows centre
        // the content in a capped column and the cover fits both the width and the height.
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier
                .fillParentMaxHeight(if (immersive) 1f else 0.91f)
                .fillMaxWidth(),
            contentAlignment = Alignment.TopCenter,
        ) {
        val wide = maxWidth > 600.dp && maxWidth > maxHeight
        val contentWidth = if (wide) maxWidth.coerceAtMost(520.dp) else maxWidth
        // Height budget from the screen, as before (the list item is shorter than the screen and
        // gave a smaller cover); the width cap only matters on wide windows.
        val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
        // Immersive: the artwork takes the whole height instead of leaving room for controls.
        val artSide = if (immersive) screenHeight
            else minOf(contentWidth, screenHeight - PLAYER_CONTROLS_DP.dp).coerceAtLeast(160.dp)
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(contentWidth)
                .then(if (immersive) Modifier.navigationBarsPadding() else Modifier),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Immersive mode has no top bar: only the artwork and the compact strip remain.
            if (!immersive) {
                PlayerTopBar(
                    navController,
                    onMenuClick = { showMenu = true },
                    np = np,
                    fallbackName = playerViewModel.currentSongAlbum.value,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            // Media area: artwork (swipe to change track) or the music video, with
            // Spotify's "Passer à la vidéo" pill over it (black, like the official app).
            // Square, but never taller than what the controls leave (foldables, landscape-ish
            // screens): otherwise play / pause ends up under the lyrics card.
            // A clear upward drag on the artwork opens the lyrics (observed on the initial pass,
            // never consumed, so the pager and the pull-to-close keep working).
            val artBoxHeight = if (immersive) artSide else (artSide - 72.dp).coerceAtLeast(160.dp)
            Box(modifier = Modifier.width(artSide).height(artBoxHeight).pointerInput(immersive, videoOn) {
                detectTapGestures(onTap = {
                    // Plain tap on the artwork: the immersive toggle, with or without a Canvas
                    // (it used to depend on the Canvas being there, and only ever turned on).
                    if (!videoOn) immersive = !immersive
                })
            }.pointerInput(Unit) {
                val threshold = 80.dp.toPx()
                // A deliberate flick, judged on release: an upward drag that crosses the threshold in
                // under [maxMs]. Reported bug: scrolling up through the player opened the lyrics —
                // that gesture crosses a distance threshold too, but never that fast, so distance
                // alone was not a gesture.
                val maxMs = 350L
                while (true) awaitPointerEventScope {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    var dx = 0f
                    var dy = 0f
                    while (true) {
                        val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val d = change.positionChange()
                        dx += d.x; dy += d.y
                        if (!change.pressed) {
                            val quick = change.uptimeMillis - down.uptimeMillis <= maxMs
                            if (quick && -dy > threshold && -dy > kotlin.math.abs(dx) * 2f) showLyrics = true
                            break
                        }
                    }
                }
            }) {
            if (showVideo) Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize(),
            ) {
                com.music.spotui.ui.components.VideoSurface(onTap = { mv.fullscreen.value = true })
            } else HorizontalPager(
                state = artworkPagerState,
                // Immersive: the artwork fills the screen instead of sitting in a square.
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val cover = when (page) {
                    0 -> np.prev?.coverUri
                    2 -> np.next?.coverUri
                    else -> null
                } ?: songCoverUri
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    GlideImage(
                        transition = CrossFade,
                        modifier = (if (immersive) Modifier.fillMaxSize()
                            // Normal layout keeps the inset, rounded cover (36.dp horizontal padding matching official screenshot).
                            else Modifier.padding(horizontal = 36.dp).aspectRatio(1f).clip(RoundedCornerShape(8.dp)))
                            .graphicsLayer {
                                alpha = coverAlpha
                                val grow = 1f + (1f - coverAlpha) * 0.06f
                                scaleX = grow; scaleY = grow
                            },
                        model = cover,
                        contentScale = ContentScale.Crop,
                        contentDescription = "")
                }
            }
            // Over the artwork / video; with a Canvas the artwork is hidden, so the
            // pill sits above the title instead of floating mid-screen.
            if (!immersive && (videoReady || videoOn) && !pillAboveTitle) {
                com.music.spotui.ui.components.VideoSwitchPill(
                    on = videoOn,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp),
                )
            }
            if (showVideo && !immersive) {
                com.music.spotui.ui.components.VideoFullscreenButton(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 36.dp, bottom = 36.dp),
                )
            }
            }
            Spacer(modifier = Modifier.weight(1f))
            if (!immersive && (videoReady || videoOn) && pillAboveTitle) {
                com.music.spotui.ui.components.VideoSwitchPill(
                    on = videoOn,
                    modifier = Modifier.padding(bottom = 20.dp),
                )
            }

            // Immersive mode keeps none of this: the compact strip at the bottom of
            // the screen replaces the title block and every control.
            if (!immersive) {
                PlayerInfo(
                    songTitle = songTitle,
                    songSinger = songSinger,
                    explicit = np.explicit || currentTrack?.explicit == true,
                    liked = isLiked.value,
                    onLikeClick = { playerViewModel.updateLikeState(!isLiked.value) },
                    likeSong = { nowPlayingSong(playerViewModel) },
                    adPlaying = adPlaying,
                    onTitleClick = {
                        val albumId = np.albumUri.substringAfterLast(":")
                        if (albumId.isNotBlank()) navController.navigate(albumRoute(playerViewModel.currentSongAlbum.value, songSinger, albumId))
                    },
                    onArtistItemClick = { clickedArtist, index ->
                        playerViewModel.goToArtist(SongPlayer.currentTrackId, clickedArtist, index) { route ->
                            navController.navigate(route)
                        }
                    },
                )

                // Seek bar: follows the finger while dragging, seeks once on release.
                var isDragging by remember { mutableStateOf(false) }
                var dragValue by remember { mutableStateOf(0f) }
                val duration = mv.duration()
                SpotifySeekBar(
                    fraction = {
                        frame.value // redraw each frame
                        if (isDragging) dragValue else {
                            val d = mv.duration()
                            if (d > 0) (mv.position().toFloat() / d).coerceIn(0f, 1f) else 0f
                        }
                    },
                    onDrag = { isDragging = true; dragValue = it },
                    onRelease = {
                        val target = (dragValue * mv.duration()).toLong()
                        mv.seekTo(target)
                        isDragging = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(top = 14.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    ElapsedLabel(songPlayingState, if (isDragging) (dragValue * duration).toLong() else null) {
                        playerViewModel.formatDuration(it)
                    }
                    Text(
                        text = playerViewModel.formatDuration(duration.coerceAtLeast(0)),
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                    )
                }

                PlayerFull(songPlayingState, playerViewModel, context, isLiked, shuffle, repeat, queueSongs)

                PlayerConnectRow(
                    context = context,
                    title = songTitle,
                    artist = songSinger,
                    coverUri = songCoverUri,
                    onDevices = { showDevices = true },
                    onQueue = { showQueue = true },
                )
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
        }
        }
        // The lyrics card belongs to the scrolled layout; in immersive mode the list holds the
        // artwork only, so a swipe there cannot pull a card up under the compact strip.
        if (!immersive) item {
            // T4: no lyrics card during an ad break — the song it belongs to is not what plays.
            if (!audioBuffering && !adPlaying) InlineLyrics(
                title = songTitle,
                artist = songSinger,
                album = playerViewModel.currentSongAlbum.value,
                accentColor = dominentColor,
                onExpand = { showLyrics = true },
            )
        }
        }
        // Immersive: one compact strip at the bottom of the full-bleed artwork, the official
        // app's collapsed layout. It swallows taps, so only the artwork leaves the mode.
        if (immersive) {
            ImmersiveStrip(
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
                coverUri = songCoverUri,
                title = songTitle,
                artists = songSinger,
                liked = isLiked.value,
                onLike = { playerViewModel.updateLikeState(!isLiked.value) },
                fraction = {
                    frame.value // redraw each frame
                    val d = mv.duration()
                    if (d > 0) (mv.position().toFloat() / d).coerceIn(0f, 1f) else 0f
                },
            )
        }

        AnimatedVisibility(
            visible = showLyrics,
            enter = slideInVertically(
                animationSpec = tween(durationMillis = 250, easing = EaseOut),
                initialOffsetY = { it },
            ),
            exit = slideOutVertically(
                animationSpec = tween(durationMillis = 200, easing = EaseOut),
                targetOffsetY = { it },
            ),
        ) {
            LyricsScreen(
                title = songTitle,
                artist = songSinger,
                album = playerViewModel.currentSongAlbum.value,
                accentColor = dominentColor,
                onClose = { showLyrics = false }
            )
        }
        // Full-screen clip: the video over everything, turned sideways, with its own controls.
        if (videoOn && mv.fullscreen.value) VideoFullscreen()
    }
}

/**
 * The immersive player's single control strip (official app): cover, title, artists, like
 * and a thin progress line. No transport row, no timestamps, no Connect/Share/Queue — those
 * disappear with the rest of the player when the artwork is tapped.
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun ImmersiveStrip(
    modifier: Modifier = Modifier,
    coverUri: String,
    title: String,
    artists: String,
    liked: Boolean,
    onLike: () -> Unit,
    fraction: () -> Float,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // Swallows taps: the strip is not the artwork, so hitting it must not close the
            // mode (its own like button still works).
            .pointerInput(Unit) { detectTapGestures(onTap = {}) }
            .background(
                // Scrim only under the strip: the artwork above stays untouched.
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f)))
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlideImage(
                transition = CrossFade,
                model = coverUri,
                contentDescription = "",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(4.dp)),
            )
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                if (artists.isNotBlank()) Text(
                    text = artists,
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            LikeButton(liked = liked, size = 26.dp, onClick = onLike)
        }
        Spacer(modifier = Modifier.height(10.dp))
        // Read in the draw phase, so the line follows playback without recomposing.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .drawBehind {
                    drawRect(Color.White.copy(alpha = 0.3f))
                    val played = fraction().coerceIn(0f, 1f)
                    drawRect(
                        color = Color.White,
                        size = androidx.compose.ui.geometry.Size(size.width * played, size.height),
                    )
                },
        )
    }
}

/** The clip filling the screen, landscape while it shows; back or the exit button leaves. */
@Composable
private fun VideoFullscreen() {
    val activity = LocalContext.current as? android.app.Activity
    androidx.compose.runtime.DisposableEffect(activity) {
        val before = activity?.requestedOrientation
        activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val window = activity?.window
        val bars = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }
        bars?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        onDispose {
            bars?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = before ?: android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        com.music.spotui.ui.components.VideoSurface(fitHeight = true)
        com.music.spotui.ui.components.VideoFullscreenOverlay()
    }
}









/** B26: the list the music plays from — the page's own label, else the name the app knows
 *  (the list the play started from), else the album's name. */
private fun contextDisplayName(np: SongPlayer.NowPlaying, fallbackName: String): String =
    np.contextName.ifBlank { SongPlayer.knownContextName(np.contextUri) }.ifBlank { fallbackName }

/** The list name both the header and the queue sheet show (B26). */
private fun playerContextName(playerViewModel: PlayerViewModel): String =
    contextDisplayName(SongPlayer.nowPlaying.value, playerViewModel.currentSongAlbum.value)

/** "Lecture à partir de <type>" + context name, or the queue (like the official app). */
@Composable
private fun playingFromLabels(np: SongPlayer.NowPlaying, fallbackName: String): Pair<String, String> {
    if (np.provider == "queue") return stringResource(R.string.context_type_description_play_queue) to ""
    val type = np.contextUri.split(":").getOrNull(1).orEmpty()
    val name = contextDisplayName(np, fallbackName)
    return when {
        np.contextUri.contains(":collection") -> stringResource(R.string.context_type_description_collection_your_library) to stringResource(R.string.collection_liked_songs_title)
        type == "playlist" -> stringResource(R.string.context_type_description_playlist) to name
        type == "album" -> stringResource(R.string.context_type_description_album) to name
        type == "artist" -> stringResource(R.string.context_type_description_artist) to name
        type == "show" -> stringResource(R.string.context_type_description_show) to name
        type == "search" -> stringResource(R.string.context_type_description_search) to name
        else -> stringResource(R.string.default_notification_channel_name) to name
    }
}

/** Route of the page the music is playing from (playlist, album, artist, liked). */
private fun contextRoute(np: SongPlayer.NowPlaying): String? {
    val parts = np.contextUri.split(":")
    val id = parts.getOrNull(2).orEmpty()
    // B26: opening that page again must carry the name the header shows.
    val name = contextDisplayName(np, "")
    return when {
        np.contextUri.contains(":collection") -> Routes.Liked.route
        parts.getOrNull(1) == "playlist" && id.isNotBlank() -> com.music.spotui.ui.navigation.playlistRoute(id, name)
        parts.getOrNull(1) == "album" && id.isNotBlank() -> albumRoute(name, "", id)
        parts.getOrNull(1) == "artist" && id.isNotBlank() -> com.music.spotui.ui.navigation.artistRoute(name, id)
        else -> null
    }
}

@Composable
fun PlayerTopBar(
    navController: NavController,
    onMenuClick: () -> Unit,
    np: SongPlayer.NowPlaying,
    fallbackName: String = "",
) {
    val (line1, line2) = playingFromLabels(np, fallbackName)
    val route = contextRoute(np)
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Icon(modifier = Modifier
            .size(26.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                       com.music.spotui.ui.navigation.PlayerSheet.close()
            },
            painter = painterResource(id = R.drawable.ic_down),
            tint = Color.White,
            contentDescription = stringResource(R.string.a11y_close_player))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
                .clickable(
                    enabled = route != null,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { route?.let { navController.navigate(it) } },
        ) {
            Text(
                text = line1,
                color = Color.White,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (line2.isNotBlank()) Text(
                text = line2,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.Default.MoreVert,
            tint = Color.White,
            modifier = Modifier
                .size(24.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onMenuClick() },
            contentDescription = stringResource(R.string.content_description_context_menu))
    }
}

/** Small grey "E" badge for explicit tracks (14.dp square, 9.sp bold 'E' matching official app). */
@Composable
fun ExplicitBadge(modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(14.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color(0xFFB3B3B3)),
    ) {
        // No font padding + trimmed line height so the "E" sits dead centre.
        Text(
            "E",
            color = Color(0xFF121212),
            fontWeight = FontWeight.Bold,
            style = androidx.compose.ui.text.TextStyle(
                fontFamily = com.music.spotui.ui.theme.AppSans,
                fontSize = 9.sp,
                lineHeight = 9.sp,
                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                    alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                    trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.Both,
                ),
            ),
        )
    }
}

/** Liked = green disc with a check; not liked = outlined plus (official app). */
@Composable
fun LikeButton(liked: Boolean, size: androidx.compose.ui.unit.Dp = 28.dp, onClick: () -> Unit) {
    // Pop on change (tap or sync), as the official heart/check: shrink, then spring back.
    // Nothing runs on first display, so long lists do not animate while scrolling.
    val scale = remember { androidx.compose.animation.core.Animatable(1f) }
    val firstShown = remember { booleanArrayOf(true) }
    LaunchedEffect(liked) {
        if (firstShown[0]) { firstShown[0] = false; return@LaunchedEffect }
        scale.snapTo(0.6f)
        scale.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.4f, stiffness = 600f))
    }
    val mod = Modifier
        .size(size)
        .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
        .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) { onClick() }
    if (liked) {
        Box(contentAlignment = Alignment.Center, modifier = mod.clip(CircleShape).background(com.music.spotui.ui.theme.AppAccent)) {
            Icon(Icons.Default.Check, contentDescription = stringResource(R.string.remove_from_liked), tint = Color.Black, modifier = Modifier.size(size * 0.65f))
        }
    } else {
        Icon(painterResource(id = R.drawable.ic_add), contentDescription = stringResource(R.string.aligned_curation_context_menu_item_add_to_liked_songs), tint = Color.White, modifier = mod)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PlayerInfo(
    songTitle: String,
    songSinger: String,
    explicit: Boolean,
    liked: Boolean,
    onLikeClick: () -> Unit,
    /** The track behind the +: with it, the + opens "Saved in" once the track is saved. */
    likeSong: (() -> SongsModel)? = null,
    onArtistClick: (() -> Unit)? = null,
    onArtistItemClick: ((String, Int) -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    /** T4: an ad break is on: what the caller passes is the song from before it, not what plays. */
    adPlaying: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                modifier = Modifier
                    // A few passes, after the sheet has settled (not an endless 120 Hz animation).
                    .basicMarquee(iterations = 3, initialDelayMillis = 1_500, repeatDelayMillis = 2_500)
                    // Nothing to open during an ad: the title would lead to the previous song.
                    .then(if (onTitleClick != null && !adPlaying) Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onTitleClick() } else Modifier),
                text = if (adPlaying) stringResource(R.string.ad_playing) else songTitle,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                softWrap = false,
            )
            // The artist row belongs to the song: during an ad the previous one's artist is
            // what the state still holds, so it is not shown as if it were playing.
            if (!adPlaying) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                if (explicit) {
                    ExplicitBadge()
                    Spacer(modifier = Modifier.width(6.dp))
                }
                val artistParts = remember(songSinger) {
                    songSinger.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                }
                if (onArtistItemClick != null && artistParts.size > 1) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.basicMarquee(iterations = 2, initialDelayMillis = 2_000, repeatDelayMillis = 3_000),
                    ) {
                        artistParts.forEachIndexed { idx, artistName ->
                            Text(
                                text = artistName,
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 16.sp,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { onArtistItemClick(artistName, idx) },
                            )
                            if (idx < artistParts.lastIndex) {
                                Text(
                                    text = ", ",
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 16.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        text = songSinger,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = if (onArtistItemClick != null || onArtistClick != null) Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            if (onArtistItemClick != null) onArtistItemClick(artistParts.firstOrNull() ?: songSinger, 0)
                            else onArtistClick?.invoke()
                        } else Modifier,
                    )
                }
            }
        }
        // No like for a local file (not in the Spotify library), and none during an ad break:
        // the + would save the song from before the break.
        if (!adPlaying && !com.music.spotui.di.LocalPlayer.active.value) {
            if (likeSong != null) AddButton(song = likeSong, liked = liked, onLike = onLikeClick)
            else LikeButton(liked = liked, onClick = onLikeClick)
        }
    }
}

/** Elapsed time text with its own ticker (doesn't recompose the whole player). */
@Composable
private fun ElapsedLabel(playing: Boolean, override: Long?, format: (Long) -> String) {
    val mv = com.music.spotui.video.MusicVideoController
    var now by remember { mutableStateOf(mv.position()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(playing, mv.on.value, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = mv.position()
                if (!playing) break
                delay(250L)
            }
        }
    }
    // Seek while paused: pick the new position up as soon as it changes.
    LaunchedEffect(override) { if (override == null) now = mv.position() }
    Text(text = format(override ?: now), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
}

/** Thin Spotify-style seek bar: 3dp track, small white thumb, drag to scrub. */
@Composable
fun SpotifySeekBar(
    fraction: () -> Float,
    onDrag: (Float) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var widthPx by remember { mutableStateOf(1f) }
    val seekDescription = stringResource(R.string.playback_seek_description)
    // Read only in semantics: playback ticks do not recompose the player.
    // Whole percent updates also avoid publishing a new semantic value every frame.
    val semanticProgress = remember(fraction) {
        derivedStateOf {
            val value = fraction()
            if (value.isFinite()) (value.coerceIn(0f, 1f) * 100f).roundToInt() / 100f else 0f
        }
    }
    androidx.compose.foundation.Canvas(
        modifier = modifier
            .height(18.dp)
            .semantics {
                contentDescription = seekDescription
                progressBarRangeInfo = ProgressBarRangeInfo(semanticProgress.value, 0f..1f)
                setProgress { requested ->
                    if (!requested.isFinite()) return@setProgress false
                    onDrag(requested.coerceIn(0f, 1f))
                    onRelease()
                    true
                }
            }
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectTapGestures { o -> onDrag((o.x / widthPx).coerceIn(0f, 1f)); onRelease() }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> onDrag((o.x / widthPx).coerceIn(0f, 1f)) },
                    onDragEnd = { onRelease() },
                    onDragCancel = { onRelease() },
                ) { change, _ ->
                    change.consume()
                    onDrag((change.position.x / widthPx).coerceIn(0f, 1f))
                }
            }
    ) {
        val y = size.height / 2
        val track = 3.dp.toPx()
        val f = fraction().coerceIn(0f, 1f)
        drawLine(Color.White.copy(alpha = 0.3f), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), track, androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(Color.White, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width * f, y), track, androidx.compose.ui.graphics.StrokeCap.Round)
        drawCircle(Color.White, radius = 4.5.dp.toPx(), center = androidx.compose.ui.geometry.Offset(size.width * f, y))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomSlider(
    modifier: Modifier = Modifier,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    colors: SliderColors = SliderDefaults.colors(),
) {
    Box(modifier = modifier.height(10.dp)) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            colors = colors,
        )
    }
}

@Composable
fun PlayerFull(
    songPlayingState: Boolean,
    playerViewModel: PlayerViewModel,
    context: Context,
    isLiked: MutableState<Boolean>,
    shuffle: Boolean,
    repeat: Boolean,
    queueSongs: List<SongsModel>
) {
    val smartOn = SmartShuffle.on.value != null && SmartShuffle.on.value == SongPlayer.nowPlaying.value.contextUri
    val smartScope = androidx.compose.runtime.rememberCoroutineScope()

    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 25.dp, end = 25.dp, top = 14.dp, bottom = 22.dp)
            .height(65.dp)
    ) {
        Icon(
            modifier = Modifier
                .size(24.dp)
                .drawWithContent {
                    drawContent()
                    if (shuffle || smartOn) drawCircle(com.music.spotui.ui.theme.AppAccent, radius = 2.dp.toPx(),
                        center = androidx.compose.ui.geometry.Offset(size.width / 2, size.height + 6.dp.toPx()))
                }
                .pressable {
                    // As in Spotify: off → shuffle → smart shuffle (playlists, Liked Songs) → off.
                    val ctx = SongPlayer.nowPlaying.value.contextUri
                    when {
                        smartOn -> { SmartShuffle.on.value = null; playerViewModel.updateShuffleState(false) }
                        shuffle && SmartShuffle.canPlay(ctx) -> {
                            SmartShuffle.on.value = ctx
                            android.widget.Toast.makeText(context, context.getString(R.string.smart_shuffle_on), android.widget.Toast.LENGTH_SHORT).show()
                            smartScope.launch {
                                if (!SmartShuffle.startFor(ctx, SongPlayer.currentTrackId)) {
                                    SmartShuffle.on.value = null
                                    android.widget.Toast.makeText(context, context.getString(R.string.page_load_failed), android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        else -> playerViewModel.updateShuffleState(!shuffle)
                    }
                }
            ,
            tint = if (shuffle || smartOn){
                com.music.spotui.ui.theme.AppAccent
            }
            else{
                Color.White
            },
            // Smart shuffle has its own glyph (shuffle + sparkle), so it reads differently from plain shuffle.
            painter = painterResource(id = if (smartOn) R.drawable.ic_shuffle_smart_active else R.drawable.ic_player_shuffle),
            contentDescription = stringResource(when { smartOn -> R.string.notif_shuffle_disable_smart; shuffle -> R.string.notif_shuffle_disable; else -> R.string.notif_shuffle_enable }))
        
        Icon(
            modifier = Modifier
                .offset(x = 6.dp)
                .size(28.dp)
                .pressable {
                    // The queue itself is already in shuffled order when shuffle
                    // is on (reordered once at toggle) — never re-shuffle per tap.
                    playerViewModel.playPreviousSong(queueSongs, context)
                }
            ,
            tint = Color.White,
            painter = painterResource(id = R.drawable.ic_player_back),
            contentDescription = stringResource(R.string.np_content_desc_prev))
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                // requiredSize forces an exact 65×65 square matching the 180px circle in the official screenshot.
                .requiredSize(65.dp)
                .pressable(pressedScale = 0.92f) {
                    if (songPlayingState) {
                        SongPlayer.pause()
                        playerViewModel.updateSongState(
                            playerViewModel.currentSongCoverUri.value,
                            playerViewModel.currentSongTitle.value,
                            playerViewModel.currentSongSinger.value,
                            false,
                            playerViewModel.currentSongId.value,
                            playerViewModel.currentSongIndex.value,
                            playerViewModel.currentSongAlbum.value
                        )
                    } else {
                        SongPlayer.play()
                        playerViewModel.updateSongState(
                            playerViewModel.currentSongCoverUri.value,
                            playerViewModel.currentSongTitle.value,
                            playerViewModel.currentSongSinger.value,
                            true,
                            playerViewModel.currentSongId.value,
                            playerViewModel.currentSongIndex.value,
                            playerViewModel.currentSongAlbum.value
                        )
                    }
                }
                .clip(CircleShape)
                .background(Color.White)
            ,
            contentAlignment = Alignment.Center
        ) {
            com.music.spotui.ui.components.PlayPauseIcon(
                modifier = Modifier.size(25.dp),
                tint = Color.Black,
                playing = songPlayingState,
                contentDescription = stringResource(if (songPlayingState) R.string.notif_action_pause else R.string.notif_action_play))
        }

        Icon(
            modifier = Modifier
                .offset(x = (-6).dp)
                .size(28.dp)
                .pressable {
                    playerViewModel.playNextSongs(queueSongs, context)
                }
            ,
            tint = Color.White,
            painter = painterResource(id = R.drawable.ic_player_skip),
            contentDescription = stringResource(R.string.np_content_desc_next))
        Icon(
            modifier = Modifier
                .size(24.dp)
                .drawWithContent {
                    drawContent()
                    if (repeat) drawCircle(com.music.spotui.ui.theme.AppAccent, radius = 2.dp.toPx(),
                        center = androidx.compose.ui.geometry.Offset(size.width / 2, size.height + 6.dp.toPx()))
                }
                .pressable {
                    SongPlayer.cycleRepeat()
                }
            ,
            tint = if (repeat){
                com.music.spotui.ui.theme.AppAccent
            }
            else{
                Color.White
            },
            painter = painterResource(id = if (SongPlayer.repeatMode.value == 2) R.drawable.ic_repeat_one else R.drawable.ic_repeat_spotui),
            contentDescription = when (SongPlayer.repeatMode.value) { 1 -> stringResource(R.string.repeat_on); 2 -> stringResource(R.string.repeat_one); else -> stringResource(R.string.repeat_off) })
    }
}

@Composable
fun PlayerConnectRow(
    context: Context,
    title: String,
    artist: String,
    coverUri: String,
    onDevices: () -> Unit,
    onQueue: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 27.dp, top = 6.dp, bottom = 4.dp),
    ) {
        // Spotify Connect: current device, opens the device picker.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onDevices() },
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_devices),
                tint = com.music.spotui.ui.theme.AppAccent,
                modifier = Modifier.size(19.dp),
                contentDescription = stringResource(R.string.mr_user_route_category_name),
            )
            Text(
                text = com.music.spotui.ui.components.activeDeviceLabel(),
                color = com.music.spotui.ui.theme.AppAccent,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = 9.dp)
                    .widthIn(max = 200.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            var shareMenu by remember { mutableStateOf(false) }
            val shareScope = androidx.compose.runtime.rememberCoroutineScope()
            Box {
                Icon(
                    painter = painterResource(id = R.drawable.ic_share),
                    tint = Color.White,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { shareMenu = true },
                    contentDescription = stringResource(R.string.action_share_label),
                )
                androidx.compose.material3.DropdownMenu(expanded = shareMenu, onDismissRequest = { shareMenu = false }) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.share_link)) },
                        onClick = {
                            shareMenu = false
                            // Songs and episodes alike: the link follows what plays (spotify:<kind>:<id>).
                            val parts = SongPlayer.nowPlaying.value.uri.split(':')
                            val link = if (parts.size == 3 && parts[0] == "spotify") "https://open.spotify.com/${parts[1]}/${parts[2]}"
                                else SongPlayer.currentTrackId.takeIf { it.isNotBlank() }?.let { "https://open.spotify.com/track/$it" }
                            if (link != null) {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, link)
                                }
                                context.startActivity(Intent.createChooser(send, context.getString(R.string.action_share_label)))
                            }
                        },
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.share_as_image)) },
                        onClick = {
                            shareMenu = false
                            shareScope.launch {
                                com.music.spotui.ui.components.ShareCard.share(
                                    context, title, artist, coverUri, context.getString(R.string.action_share_label))
                            }
                        },
                    )
                }
            }
            Spacer(modifier = Modifier.width(35.dp))
            Icon(
                painter = painterResource(id = R.drawable.ic_spot_queue),
                tint = Color.White,
                modifier = Modifier
                    .size(18.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onQueue() },
                contentDescription = stringResource(R.string.action_queue_label),
            )
        }
    }
}

@Composable
fun PlayerOptionsSheet(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    context: Context,
    isLiked: MutableState<Boolean>,
    onDismiss: () -> Unit,
    onOpenQueue: (() -> Unit)? = null,
) {
    val song = nowPlayingSong(playerViewModel)
    com.music.spotui.ui.components.SongOptionsSheet(
        song = song,
        navController = navController,
        context = context,
        onDismiss = onDismiss,
        nowPlaying = true,
        onOpenQueue = onOpenQueue,
    )
}


/** The track that plays, as Spotify reports it (the local queue may not have it). */
fun nowPlayingSong(playerViewModel: PlayerViewModel): SongsModel {
    val np = SongPlayer.nowPlaying.value
    val songId = playerViewModel.currentSongId.value
    val known = playerViewModel.queue.value.firstOrNull { it.id == songId }
    return SongsModel(
        id = songId,
        title = playerViewModel.currentSongTitle.value,
        album = known?.album?.ifBlank { null } ?: playerViewModel.currentSongAlbum.value,
        singer = playerViewModel.currentSongSinger.value,
        coverUri = playerViewModel.currentSongCoverUri.value,
        url = known?.url.orEmpty(),
        spotifyTrackId = SongPlayer.currentTrackId,
        explicit = np.explicit,
        albumId = np.albumUri.substringAfterLast(":", "").ifBlank { known?.albumId.orEmpty() },
    )
}

/**
 * The + of a track, as in the official app: a first tap saves it in Liked Songs; once
 * saved (green check), a tap opens "Saved in" to add it to playlists or remove it.
 */
@Composable
fun AddButton(song: () -> SongsModel, liked: Boolean, size: androidx.compose.ui.unit.Dp = 28.dp, onLike: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var savedIn by remember { mutableStateOf<SongsModel?>(null) }
    savedIn?.let { s ->
        com.music.spotui.ui.components.SavedInSheet(song = s, context = context, onDismiss = { savedIn = null })
    }
    LikeButton(liked = liked, size = size) {
        keyboardController?.hide()
        focusManager.clearFocus()
        if (liked) savedIn = song() else {
            onLike()
            android.widget.Toast.makeText(context, context.getString(R.string.snackbar_added_to_liked_songs), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun PlayerMenuRow(
    icon: ImageVector,
    label: String,
    iconTint: Color = Color.White,
    enabled: Boolean = true,
    trailingArrow: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Icon(
            imageVector = icon,
            tint = if (enabled) iconTint else Color.Gray,
            modifier = Modifier.size(22.dp),
            contentDescription = null
        )
        Spacer(modifier = Modifier.width(20.dp))
        Text(
            text = label,
            color = if (enabled) Color.White else Color.Gray,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f)
        )
        if (trailingArrow) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                tint = Color.Gray,
                modifier = Modifier.size(20.dp),
                contentDescription = null
            )
        }
    }
}
/**
 * Plays a Spotify Canvas clip: a short, muted, looping video filling the
 * now-playing background, on the shared [CanvasPlayer] (separate from the audio engine),
 * paused when the composable leaves. [onFirstFrame]: the clip is visible.
 *
 * Loops are served from the media cache: without it every loop re-downloaded the
 * clip, and since ExoPlayer ignores the activity lifecycle it kept doing so in the
 * background (#87, gigabytes per hour). It also pauses while the app is not visible.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun CanvasVideo(url: String, playing: Boolean, onFirstFrame: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val exo = remember(url) { com.music.spotui.ui.components.CanvasPlayer.get(context, url) }
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    val requested by androidx.compose.runtime.rememberUpdatedState(playing)
    val shown by androidx.compose.runtime.rememberUpdatedState(onFirstFrame)
    LaunchedEffect(exo, playing, lifecycle) {
        exo.playWhenReady = playing && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
    }
    androidx.compose.runtime.DisposableEffect(exo, lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            exo.playWhenReady = requested && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        }
        lifecycle.addObserver(observer)
        val removeFrame = com.music.spotui.ui.components.CanvasPlayer.onFirstFrame { shown() }
        onDispose {
            lifecycle.removeObserver(observer)
            removeFrame()
            exo.playWhenReady = false
        }
    }
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier,
        // factory runs once; a new track's clip gets a new player, so rebind it here.
        update = { it.player = exo },
        factory = { ctx ->
            // Inflated: the surface type (texture view) can only be set from XML.
            (android.view.LayoutInflater.from(ctx).inflate(R.layout.canvas_player_view, null) as androidx.media3.ui.PlayerView).apply {
                setArtworkDisplayMode(androidx.media3.ui.PlayerView.ARTWORK_DISPLAY_MODE_OFF)
                setDefaultArtwork(null)
                player = exo
            }
        },
    )
}

/** Cover to Canvas cross-fade. */
private const val CANVAS_FADE_MS = 550

/** Height the player needs under the artwork: top bar, title, seek bar, controls, Connect row. */
private const val PLAYER_CONTROLS_DP = 450
