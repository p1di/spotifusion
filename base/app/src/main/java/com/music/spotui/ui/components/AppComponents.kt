package com.music.spotui.ui.components

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.basicMarquee
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.CrossFade
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import app.myspoti.engine.R
import com.music.spotui.data.preferences.addLikedSongId
import com.music.spotui.data.preferences.isSongLiked
import com.music.spotui.data.preferences.removeLikedSongId
import com.music.spotui.di.Palette
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.GridBackground
import com.music.spotui.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay

/**
 * Loading placeholder, as in the official app: static grey shapes of the page to
 * come, no spinner. Shown only if loading lasts (cached pages appear without it),
 * and never animated (no frames spent while waiting).
 */
@Composable
fun Loader(kind: SkeletonKind = SkeletonKind.List) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(200); visible = true }
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(AppBackground.toArgb()))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (!visible) return@Column
        val tile = Color(0xFF242424)
        val line = Color(0xFF2A2A2A)
        when (kind) {
            SkeletonKind.Home -> {
                repeat(4) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(2) { Box(Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(4.dp)).background(tile)) }
                    }
                }
                repeat(2) {
                    Box(Modifier.padding(top = 24.dp, bottom = 12.dp).width(180.dp).height(18.dp).clip(RoundedCornerShape(4.dp)).background(line))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        repeat(3) { Box(Modifier.size(140.dp).clip(RoundedCornerShape(4.dp)).background(tile)) }
                    }
                }
            }
            SkeletonKind.Header -> {
                Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 24.dp, bottom = 20.dp).size(220.dp).clip(RoundedCornerShape(4.dp)).background(tile))
                Box(Modifier.width(200.dp).height(22.dp).clip(RoundedCornerShape(4.dp)).background(line))
                Box(Modifier.padding(top = 10.dp, bottom = 20.dp).width(120.dp).height(14.dp).clip(RoundedCornerShape(4.dp)).background(line))
                SkeletonRows(tile, line, 6)
            }
            SkeletonKind.List -> SkeletonRows(tile, line, 9)
        }
    }
}

enum class SkeletonKind { List, Home, Header }

@Composable
private fun SkeletonRows(tile: Color, line: Color, count: Int) {
    repeat(count) {
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)).background(tile))
            Column(Modifier.padding(start = 12.dp)) {
                Box(Modifier.width(170.dp).height(14.dp).clip(RoundedCornerShape(4.dp)).background(line))
                Box(Modifier.padding(top = 8.dp).width(110.dp).height(12.dp).clip(RoundedCornerShape(4.dp)).background(line))
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun MiniPlayer(navController: NavHostController) {
    val vm : PlayerViewModel = viewModel()
    val songTitle = vm.currentSongTitle.value
    val songSinger = vm.currentSongSinger.value
    val songCoverUri = vm.currentSongCoverUri.value
    val songPlayingState = vm.currentSongPlayingState.value
    val songId = vm.currentSongId.value
    val liked = vm.likeState.value
    val np by SongPlayer.nowPlaying
    val context = LocalContext.current
    // A29: where the sound goes when it is not this phone's speaker. rememberAudioRoute
    // follows devices coming and going, so plugging a headset or getting in the car
    // updates the label without the screen reopening.
    val route = rememberAudioRoute()
    val routeLabel = when (route.kind) {
        AudioRoute.Kind.SPEAKER -> null
        AudioRoute.Kind.BLUETOOTH -> route.name.ifBlank { stringResource(R.string.label_bluetooth) }
        AudioRoute.Kind.WIRED -> stringResource(R.string.headphones)
    }

    // Card tint from the cover — extracted once per cover, not on every recomposition.
    var tint by remember { mutableStateOf(Palette.cachedFirst(songCoverUri) ?: Color(0xFF2A2A2A)) }
    LaunchedEffect(songCoverUri) {
        if (songCoverUri.isNotBlank()) {
            Palette().extractFirstColorFromImageUrl(context, songCoverUri) { tint = it }
            // Warm the full player's background colour: it opens already tinted.
            Palette().extractSecondColorFromCoverUrl(context, songCoverUri) { }
        }
    }

    // A new song cross-fades the card's colour instead of snapping to it.
    val animatedTint by androidx.compose.animation.animateColorAsState(tint, androidx.compose.animation.core.tween(400), label = "miniTint")

    // The Canvas of the song playing is looked up and loaded now, so the full player
    // opens on it directly instead of showing the cover first.
    val energy by app.myspoti.power.EnergySettings.state.collectAsStateWithLifecycle()
    LaunchedEffect(np.uri, energy.policy.allowCanvas, com.music.spotui.data.DataSaver.active) {
        val id = np.uri.removePrefix("spotify:track:").takeIf { np.uri.startsWith("spotify:track:") } ?: return@LaunchedEffect
        if (!energy.policy.allowCanvas || com.music.spotui.data.DataSaver.active) return@LaunchedEffect
        val url = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.music.spotui.di.Graph.api.getCanvasUrl(id) }.getOrNull()
        } ?: return@LaunchedEffect
        CanvasPlayer.preload(context, url)
    }

    // The thin bar moves ~2 px/s: 4 ticks a second look identical to 20.
    val frame = rememberFrameClock(songPlayingState, fps = 4)

    var showDevices by remember { mutableStateOf(false) }
    if (showDevices) DevicesSheet { showDevices = false }

    // Swipe the track info left/right to change track: [previous, current, next].
    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = 1, pageCount = { 3 })
    LaunchedEffect(songId) { if (pager.currentPage != 1) pager.scrollToPage(1) }
    LaunchedEffect(pager) {
        androidx.compose.runtime.snapshotFlow { pager.settledPage }.collect { page ->
            when (page) {
                2 -> SongPlayer.swipeNext()
                0 -> SongPlayer.previous()
            }
        }
    }

    Column(
        modifier = Modifier.pressable(0.96f) { com.music.spotui.ui.navigation.PlayerSheet.show() }
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(animatedTint)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .padding(start = 8.dp, end = 12.dp)
        ) {
            androidx.compose.foundation.pager.HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f),
            ) { page ->
                val s = when (page) { 0 -> np.prev; 2 -> np.next; else -> null }
                // T4: during an ad break the page publishes no track, so the song before it
                // would sit in the mini player; an explicit ad state is shown instead.
                val adPlaying = com.music.spotui.di.SongPlayer.adPlaying
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    GlideImage(
                        transition = CrossFade,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        model = s?.coverUri ?: songCoverUri,
                        contentScale = ContentScale.Crop,
                        failure = placeholder(R.drawable.placeholder),
                        loading = placeholder(R.drawable.placeholder),
                        contentDescription = ""
                    )
                    val remote = SongPlayer.activeDevice.value
                    Column(modifier = Modifier.padding(start = 10.dp, end = 8.dp)) {
                        if (remote == null) {
                            Text(
                                text = if (adPlaying) stringResource(R.string.ad_playing) else (s?.title ?: songTitle),
                                color = Color.White,
                                fontSize = 13.5.sp,
                                lineHeight = 16.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            if (!adPlaying) {
                                Text(
                                    text = s?.singer ?: songSinger,
                                    color = Color.White.copy(alpha = 0.72f),
                                    fontSize = 13.sp,
                                    lineHeight = 15.sp,
                                    fontWeight = FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                        } else {
                            // Playing on another device: "Title • Artist", then the device in green.
                            Text(
                                text = androidx.compose.ui.text.buildAnnotatedString {
                                    pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, color = Color.White))
                                    append(s?.title ?: songTitle)
                                    pop()
                                    pushStyle(androidx.compose.ui.text.SpanStyle(color = Color.White.copy(alpha = 0.7f)))
                                    append(" • " + (s?.singer ?: songSinger))
                                    pop()
                                },
                                fontSize = 13.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, softWrap = false,
                                modifier = Modifier.basicMarquee(iterations = 1, initialDelayMillis = 2_000),
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = com.music.spotui.ui.theme.AppAccent, modifier = Modifier.size(14.dp))
                                Text(remote.name, color = com.music.spotui.ui.theme.AppAccent, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp))
                            }
                        }
                    }
                }
            }
            Icon(
                painter = painterResource(id = R.drawable.ic_spot_connect),
                tint = if (SongPlayer.activeDevice.value != null) com.music.spotui.ui.theme.AppAccent else Color.White,
                modifier = Modifier.pressable(0.96f) { showDevices = true }
                    .size(24.dp),
                contentDescription = stringResource(R.string.mr_user_route_category_name),
            )
            Spacer(modifier = Modifier.width(18.dp))
            if (!com.music.spotui.di.LocalPlayer.active.value) com.music.spotui.ui.screens.AddButton(song = { com.music.spotui.ui.screens.nowPlayingSong(vm) }, liked = liked, size = 22.dp) { vm.updateLikeState(true) }
            Spacer(modifier = Modifier.width(18.dp))
            com.music.spotui.ui.components.PlayPauseIcon(
                playing = songPlayingState,
                contentDescription = if (songPlayingState) stringResource(R.string.content_description_pause_button) else stringResource(R.string.content_description_play_button),
                tint = Color.White,
                modifier = Modifier.pressable(0.96f) {
                        if (songPlayingState) {
                            SongPlayer.pause()
                            vm.updateSongState(songCoverUri, songTitle, songSinger, false, songId, vm.currentSongIndex.value, vm.currentSongAlbum.value)
                        } else {
                            SongPlayer.play()
                            vm.updateSongState(songCoverUri, songTitle, songSinger, true, songId, vm.currentSongIndex.value, vm.currentSongAlbum.value)
                        }
                    }
                    .size(24.dp)
            )
        }
        // Thin progress line at the bottom of the card.
        // Drawn every frame from the engine clock: smooth at the display refresh rate.
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
        ) {
            frame.value
            val dur = SongPlayer.getDuration().toFloat()
            val p = if (dur > 0f) (SongPlayer.getCurrentPosition() / dur).coerceIn(0f, 1f) else 0f
            drawRect(Color.White.copy(alpha = 0.25f))
            drawRect(Color.White, size = size.copy(width = size.width * p))
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomSlider(
    modifier: Modifier = Modifier,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    colors: SliderColors = SliderDefaults.colors(),
) {
    Box(
        //contentAlignment = Alignment.BottomEnd,
        modifier = Modifier
            .fillMaxWidth()
            .height(0.dp)

    ) {

        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            colors = colors,
        )
    }
}


@Composable
fun Snackbar(showMessage : String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.White),
        contentAlignment = Alignment.Center
    ){
        Text(
            fontWeight = FontWeight.W500,
            fontSize = 14.sp,
            color = Color.Black,
            text = showMessage
        )
    }
}

