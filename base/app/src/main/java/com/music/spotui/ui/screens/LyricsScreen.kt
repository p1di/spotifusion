package com.music.spotui.ui.screens

import com.music.spotui.ui.components.pressable
import androidx.compose.ui.res.stringResource

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.music.spotui.data.api.LyricsApi
import com.music.spotui.data.entity.LyricLine
import com.music.spotui.data.entity.Lyrics
import com.music.spotui.data.preferences.getLyricTranslateLang
import com.music.spotui.data.preferences.isLyricsExtraSources
import com.music.spotui.data.preferences.setLyricsExtraSources
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.rememberFrameClock
import com.music.spotui.ui.viewmodel.LyricsViewModel
import app.myspoti.engine.R
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Spotify's lyrics, as the official Android app shows them: the lyrics card in the
 * Now Playing scroll and the full-screen view, both on the track's own colour
 * (color-lyrics `colors`). Sung and current lines are in the highlight colour,
 * upcoming lines in the text colour; tapping a line seeks to it.
 */

/** The three colours of a lyrics view: background, upcoming text, sung / current text. */
private data class LyricsPalette(val background: Color, val text: Color, val highlight: Color)

private fun paletteFor(lyrics: Lyrics?, accent: Color): LyricsPalette {
    lyrics?.colors?.let { return LyricsPalette(Color(it.background), Color(it.text), Color(it.highlight)) }
    // No Spotify colours (fallback source): the artwork colour, like Spotify's own fallback.
    val bg = lerp(accent, Color.Black, 0.15f)
    // B23: upcoming lines were black at 55 % — invisible on a dark (AMOLED) card. On a dark
    // background they are drawn in a slightly dimmed white instead, the sung lines staying
    // the full highlight colour.
    val text = if (bg.luminance() > 0.35f) Color.Black.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.72f)
    return LyricsPalette(bg, text, Color.White)
}

/** Playback position, re-read on every frame while playing (smooth word highlight). */
@Composable
private fun rememberPosition(running: Boolean): () -> Long {
    val frame = rememberFrameClock(running, fps = 30)
    return remember(frame) {
        { frame.value; SongPlayer.getCurrentPosition().coerceAtLeast(0L) }
    }
}

private fun activeIndexFor(lyrics: Lyrics, positionMs: Long): Int {
    if (!lyrics.synced) return -1
    // Binary search: last line starting at or before the position.
    var lo = 0
    var hi = lyrics.lines.size - 1
    var found = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (lyrics.lines[mid].timeMs <= positionMs + 150) { found = mid; lo = mid + 1 } else hi = mid - 1
    }
    return found
}

/** Seek to a tapped synced line and make sure we're playing. */
private fun jumpTo(timeMs: Long) {
    com.music.spotui.di.Haptics.click()
    SongPlayer.seekTo(timeMs)
    if (!SongPlayer.isPlaying()) SongPlayer.play()
}

private fun shareLyrics(context: Context, title: String, artist: String, line: String?) {
    val id = SongPlayer.currentTrackId
    val text = buildString {
        if (!line.isNullOrBlank() && line != "♪") append("« ").append(line).append(" »\n")
        append(title).append(" · ").append(artist)
        if (id.isNotBlank()) append("\nhttps://open.spotify.com/track/").append(id)
    }
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, context.getString(R.string.lyrics_share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun rememberLyricsState(vm: LyricsViewModel, title: String, artist: String): LyricsViewModel.State {
    LaunchedEffect(title, artist, SongPlayer.currentTrackId) {
        SongPlayer.currentLyricsTrack()?.let { vm.load(it) }
    }
    return vm.state.collectAsState().value
}

/**
 * Keeps the active line about a third down the view, like Spotify. A manual scroll
 * pauses the follow for 3 s.
 */
@Composable
private fun AutoFollow(listState: LazyListState, activeIndex: Int, offsetPx: Int, enabled: Boolean) {
    var userScrollAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect {
            if (it is DragInteraction.Start || it is DragInteraction.Stop) userScrollAt = System.currentTimeMillis()
        }
    }
    LaunchedEffect(activeIndex, enabled) {
        if (!enabled || activeIndex < 0) return@LaunchedEffect
        val wait = 3000 - (System.currentTimeMillis() - userScrollAt)
        if (wait > 0) delay(wait)
        listState.animateScrollToItem(activeIndex, -offsetPx)
    }
}

/**
 * One lyric line. Word-synced lines fill word by word; others switch colour as a
 * whole when they become current.
 */
@Composable
private fun LyricLineText(
    line: LyricLine,
    state: Int, // -1 sung, 0 current, 1 upcoming, 2 unsynced
    palette: LyricsPalette,
    fontSize: TextUnit,
    position: () -> Long,
    translation: String?,
    onTap: (() -> Unit)?,
) {
    val target = when (state) {
        1 -> palette.text
        else -> palette.highlight
    }
    val color by animateColorAsState(target, tween(220), label = "lyricColor")
    val modifier = Modifier
        .fillMaxWidth()
        .then(
            if (onTap != null) Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onTap() } else Modifier
        )
    Column(modifier) {
        val words = line.words
        if (state == 0 && words != null) {
            // Only this line follows the clock: count of words already sung.
            val sung by remember(line) {
                derivedStateOf { val p = position(); words.count { it.startMs <= p } }
            }
            Text(
                text = buildAnnotatedString {
                    words.forEachIndexed { i, w ->
                        withStyle(SpanStyle(color = if (i < sung) palette.highlight else palette.text)) { append(w.text) }
                    }
                },
                fontSize = fontSize,
                lineHeight = fontSize * 1.25f,
                fontWeight = FontWeight.Bold,
            )
        } else {
            Text(
                text = line.text,
                color = color,
                fontSize = fontSize,
                lineHeight = fontSize * 1.25f,
                fontWeight = FontWeight.Bold,
            )
        }
        AnimatedVisibility(
            visible = !translation.isNullOrBlank(),
            enter = expandVertically(tween(260)) + fadeIn(tween(260)),
            exit = shrinkVertically(tween(260)) + fadeOut(tween(260)),
        ) {
            Text(
                text = translation.orEmpty(),
                color = color.copy(alpha = color.alpha * 0.7f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private fun lineState(lyrics: Lyrics, index: Int, active: Int): Int = when {
    !lyrics.synced -> 2
    index < active -> -1
    index == active -> 0
    else -> 1
}

/** Full-screen lyrics (Spotify's "Lyrics" view). */
@Composable
fun LyricsScreen(
    title: String,
    artist: String,
    album: String,
    accentColor: Color,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val vm: LyricsViewModel = viewModel()
    val state = rememberLyricsState(vm, title, artist)
    val lyrics = (state as? LyricsViewModel.State.Loaded)?.lyrics
    val palette = paletteFor(lyrics, accentColor)
    val bg by animateColorAsState(palette.background, tween(300), label = "lyricsBg")
    val playing = com.music.spotui.di.Graph.currentSongState.playingState.value
    val position = rememberPosition(playing)
    val targetLanguage = getLyricTranslateLang(context).ifBlank { Locale.getDefault().language.ifBlank { "en" } }
    var menu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // ── Header: close, title / artist, more ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(48.dp).clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose,
                ),
            ) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.lyrics_fullscreen_close_button_readout_value), tint = palette.highlight, modifier = Modifier.size(32.dp))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Text(title, color = palette.highlight, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(artist, color = palette.highlight.copy(alpha = 0.75f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(48.dp).clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                    ) { menu = true },
                ) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.content_description_context_menu), tint = palette.highlight)
                }
                LyricsMenu(
                    expanded = menu,
                    onDismiss = { menu = false },
                    canTranslate = state is LyricsViewModel.State.Loaded,
                    translating = (state as? LyricsViewModel.State.Loaded)?.showTranslation == true,
                    onTranslate = { vm.toggleTranslation(targetLanguage) },
                    onReload = { SongPlayer.currentLyricsTrack()?.let { vm.load(it, force = true) } },
                )
            }
        }

        when (state) {
            is LyricsViewModel.State.Loading ->
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = palette.highlight)
                }
            is LyricsViewModel.State.NotFound ->
                Box(Modifier.weight(1f).fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.lyrics_fullscreen_no_lyrics_error_version_two), color = palette.highlight, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            is LyricsViewModel.State.Loaded -> {
                val l = state.lyrics
                // Translation is requested by onTranslate; do not upload lyrics on screen entry.
                val active by remember(l) { derivedStateOf { activeIndexFor(l, position()) } }
                val listState = rememberLazyListState()
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val offsetPx = with(LocalDensity.current) { (maxHeight * 0.28f).roundToPx() }
                    AutoFollow(listState, active, offsetPx, enabled = l.synced)
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(24.dp, 8.dp, 24.dp, 48.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        if (!l.synced) item {
                            Text(
                                stringResource(R.string.lyrics_fullscreen_unsynced),
                                color = palette.highlight.copy(alpha = 0.8f),
                                fontSize = 13.sp,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                        itemsIndexed(l.lines, key = { i, _ -> i }) { i, line ->
                            LyricLineText(
                                line = line,
                                state = lineState(l, i, active),
                                palette = palette,
                                fontSize = 24.sp,
                                position = position,
                                translation = if (state.showTranslation) state.translations?.getOrNull(i) else null,
                                onTap = if (l.synced) ({ jumpTo(line.timeMs) }) else null,
                            )
                        }
                        l.provider?.let { p ->
                            item {
                                Text(
                                    stringResource(R.string.lyrics_credential_note, p),
                                    color = palette.highlight.copy(alpha = 0.8f),
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(top = 24.dp),
                                )
                            }
                        }
                    }
                }
                val sameLanguage = state.sourceLanguage?.substringBefore('-')
                    ?.equals(targetLanguage.substringBefore('-'), ignoreCase = true) == true
                LyricsControls(
                    palette = palette,
                    position = position,
                    showTranslate = state.translationChecked && !sameLanguage,
                    translationActive = state.showTranslation,
                    translating = state.translating && state.translationRequested,
                    onTranslate = { vm.toggleTranslation(targetLanguage) },
                    onShare = { shareLyrics(context, title, artist, l.lines.getOrNull(active)?.text) },
                )
            }
        }
    }
}

@Composable
private fun LyricsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    canTranslate: Boolean,
    translating: Boolean,
    onTranslate: () -> Unit,
    onReload: () -> Unit,
) {
    val context = LocalContext.current
    var extra by remember { mutableStateOf(isLyricsExtraSources(context)) }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (canTranslate) DropdownMenuItem(
            text = { Text(stringResource(if (translating) R.string.lyrics_fullscreen_translate_button_enabled_readout_value else R.string.lyrics_fullscreen_context_menu_main_page_translate_lyrics_action)) },
            onClick = { onDismiss(); onTranslate() },
        )
        DropdownMenuItem(
            text = {
                Column {
                    Text(stringResource(R.string.lyrics_extra_title))
                    Text(stringResource(R.string.lyrics_extra_summary), fontSize = 12.sp, color = Color.Gray)
                }
            },
            trailingIcon = { if (extra) Icon(Icons.Default.Check, contentDescription = null) },
            onClick = {
                extra = !extra
                setLyricsExtraSources(context, extra)
                LyricsApi.extraSources = extra
                onDismiss()
                onReload()
            },
        )
        DropdownMenuItem(text = { Text(stringResource(R.string.lyrics_reload)) }, onClick = { onDismiss(); onReload() })
    }
}

@Composable
private fun LyricsChip(label: String, icon: Int, active: Boolean, loading: Boolean, palette: LyricsPalette, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) palette.highlight else Color.Black.copy(alpha = 0.22f))
            .clickable(enabled = !loading, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        if (loading) CircularProgressIndicator(color = palette.highlight, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
        else Icon(painterResource(icon), contentDescription = null, tint = if (active) palette.background else palette.highlight, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (active) palette.background else palette.highlight, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LyricsControls(
    palette: LyricsPalette,
    position: () -> Long,
    showTranslate: Boolean,
    translationActive: Boolean,
    translating: Boolean,
    onTranslate: () -> Unit,
    onShare: () -> Unit,
) {
    val durationMs = SongPlayer.getDuration().coerceAtLeast(0L)
    val playing = com.music.spotui.di.Graph.currentSongState.playingState.value
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
            LyricsChip(stringResource(R.string.lyrics_fullscreen_chip_label_share), R.drawable.ic_share, false, false, palette, onShare)
            if (showTranslate) LyricsChip(stringResource(R.string.lyrics_fullscreen_chip_label_translate), R.drawable.ic_translate_lyrics, translationActive, translating, palette, onTranslate)
        }
        LyricsSeekBar(
            progress = { if (durationMs > 0) (position().toFloat() / durationMs).coerceIn(0f, 1f) else 0f },
            color = palette.highlight,
            onSeek = { SongPlayer.seekTo((durationMs * it).toLong()) },
        )
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            val pos by remember { derivedStateOf { position() / 1000 } }
            Text(formatLyricsTime(pos * 1000), color = palette.highlight.copy(alpha = 0.75f), fontSize = 12.sp)
            Text(formatLyricsTime(durationMs), color = palette.highlight.copy(alpha = 0.75f), fontSize = 12.sp)
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 4.dp)
                .size(60.dp)
                .clip(CircleShape)
                .background(palette.highlight)
                .pressable(0.92f) { if (SongPlayer.isPlaying()) SongPlayer.pause() else SongPlayer.play() },
        ) {
            com.music.spotui.ui.components.PlayPauseIcon(
                playing = playing,
                contentDescription = if (playing) stringResource(R.string.content_description_pause_button) else stringResource(R.string.content_description_play_button),
                tint = palette.background,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

@Composable
private fun LyricsSeekBar(progress: () -> Float, color: Color, onSeek: (Float) -> Unit) {
    var widthPx by remember { mutableStateOf(1f) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    val dragState = rememberDraggableState { delta -> dragValue = (dragValue + delta / widthPx).coerceIn(0f, 1f) }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(widthPx) {
                detectTapGestures { onSeek((it.x / widthPx).coerceIn(0f, 1f)) }
            }
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                onDragStarted = { dragging = true; dragValue = progress() },
                onDragStopped = { dragging = false; onSeek(dragValue) },
            ),
    ) {
        val value = if (dragging) dragValue else progress()
        val h = 3.dp.toPx()
        val y = size.height / 2f
        drawRoundRect(color.copy(alpha = 0.3f), Offset(0f, y - h / 2), Size(size.width, h), CornerRadius(h / 2))
        drawRoundRect(color, Offset(0f, y - h / 2), Size(size.width * value, h), CornerRadius(h / 2))
        drawCircle(color, radius = 6.dp.toPx(), center = Offset(size.width * value, y))
    }
}

private fun formatLyricsTime(timeMs: Long): String {
    val totalSeconds = timeMs.coerceAtLeast(0L) / 1000L
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}

/** Height of the lines window in the Now Playing card. */
private val CARD_LINES_HEIGHT = 296.dp

/**
 * The lyrics card in the Now Playing scroll. Hidden while loading and when the
 * track has no lyrics, as in the official app.
 */
@Composable
fun InlineLyrics(
    title: String,
    artist: String,
    album: String,
    accentColor: Color,
    onExpand: () -> Unit,
) {
    val context = LocalContext.current
    val vm: LyricsViewModel = viewModel()
    val state = rememberLyricsState(vm, title, artist)
    val s = state as? LyricsViewModel.State.Loaded ?: return
    val l = s.lyrics
    val palette = paletteFor(l, accentColor)
    val position = rememberPosition(com.music.spotui.di.Graph.currentSongState.playingState.value)
    val active by remember(l) { derivedStateOf { activeIndexFor(l, position()) } }
    val listState = rememberLazyListState()
    LaunchedEffect(active, l) {
        if (l.synced && active >= 0) listState.animateScrollToItem((active - 1).coerceAtLeast(0))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp, 8.dp, 16.dp, 40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onExpand() }
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(if (l.uncapped) R.string.lyrics_card_title else R.string.lyrics_card_title_preview),
                color = palette.highlight,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            CardIconButton(palette, stringResource(R.string.lyrics_share), { Icon(painterResource(R.drawable.ic_share), null, tint = palette.highlight, modifier = Modifier.size(16.dp)) }) {
                shareLyrics(context, title, artist, l.lines.getOrNull(active)?.text)
            }
            Spacer(Modifier.width(8.dp))
            CardIconButton(palette, stringResource(R.string.lyrics_fullscreen), { Icon(Icons.Default.OpenInFull, null, tint = palette.highlight, modifier = Modifier.size(16.dp)) }, onExpand)
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            state = listState,
            userScrollEnabled = false,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().height(CARD_LINES_HEIGHT),
        ) {
            itemsIndexed(l.lines, key = { i, _ -> i }) { i, line ->
                LyricLineText(
                    line = line,
                    state = lineState(l, i, active),
                    palette = palette,
                    fontSize = 22.sp,
                    position = position,
                    translation = null,
                    onTap = null,
                )
            }
        }
        if (!l.uncapped) {
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(palette.highlight)
                    .clickable { onExpand() }
                    .padding(16.dp, 8.dp),
            ) {
                Text(stringResource(R.string.lyrics_card_show_lyrics_button), color = palette.background, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CardIconButton(palette: LyricsPalette, description: String, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.25f))
            .clickable(onClick = onClick),
    ) { icon() }
}

/**
 * Current synced lyric line displayed right below the artwork on the Now Playing screen,
 * matching the official Spotify player layout, with a fly-out-to-the-top transition.
 */
@Composable
fun ArtworkCurrentLyricLine(
    title: String,
    artist: String,
    onClick: () -> Unit,
    vm: LyricsViewModel = viewModel(),
) {
    if (!com.music.spotui.data.preferences.LyricsUnderCoverPref.on) return
    val state = rememberLyricsState(vm, title, artist)
    val loaded = state as? LyricsViewModel.State.Loaded ?: return
    val l = loaded.lyrics
    if (l.lines.isEmpty()) return
    val position = rememberPosition(running = l.synced)
    val active by remember(l) { derivedStateOf { activeIndexFor(l, position()) } }
    val currentLine = when {
        !l.synced -> l.lines.firstOrNull { it.text.isNotBlank() && it.text != "♪" }
        active >= 0 -> l.lines.getOrNull(active)?.takeIf { it.text.isNotBlank() && it.text != "♪" }
        else -> l.lines.firstOrNull { it.text.isNotBlank() && it.text != "♪" }
    } ?: return

    androidx.compose.animation.AnimatedContent(
        targetState = currentLine.text,
        transitionSpec = {
            androidx.compose.animation.ContentTransform(
                targetContentEnter = androidx.compose.animation.slideInVertically(
                    animationSpec = androidx.compose.animation.core.tween(300),
                    initialOffsetY = { fullHeight -> fullHeight },
                ) + androidx.compose.animation.fadeIn(
                    animationSpec = androidx.compose.animation.core.tween(300),
                ),
                initialContentExit = androidx.compose.animation.slideOutVertically(
                    animationSpec = androidx.compose.animation.core.tween(280),
                    targetOffsetY = { fullHeight -> -fullHeight },
                ) + androidx.compose.animation.fadeOut(
                    animationSpec = androidx.compose.animation.core.tween(220),
                ),
                sizeTransform = androidx.compose.animation.SizeTransform(clip = true),
            )
        },
        label = "lyricUnderCoverFlyUp",
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 24.dp, vertical = 6.dp),
    ) { lineText ->
        Text(
            text = lineText,
            color = Color.White,
            fontSize = 16.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
