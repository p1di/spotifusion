package com.music.spotui.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.myspoti.api.PlaylistAccess
import app.myspoti.engine.R
import app.myspoti.host.android.MyspotiRuntime
import com.bumptech.glide.integration.compose.CrossFade
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.data.api.Api
import com.music.spotui.data.api.SpotifySync
import com.music.spotui.data.entity.ContextPageModel
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.ui.screens.ExplicitBadge
import com.music.spotui.ui.theme.AppAccent
import com.music.spotui.ui.theme.AppBackground
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val playlistWriteScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/**
 * A playlist's ⋯ menu. On your own or collaborative playlists: edit playlist (rearrange songs,
 * remove songs, edit name/description/cover), choose who can see it (public, private,
 * collaborative) and delete it. On someone else's: their profile.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistOptionsSheet(
    page: ContextPageModel,
    editable: Boolean = false,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
    onEdited: (name: String, description: String, tracks: List<SongsModel>) -> Unit = { _, _, _ -> onChanged() },
    onDeleted: () -> Unit,
    onProfile: (String) -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    var myId by remember { mutableStateOf(com.music.spotui.data.api.ProfileCache.id) }
    LaunchedEffect(Unit) {
        com.music.spotui.data.api.ProfileCache.ensure(context)
        myId = com.music.spotui.data.api.ProfileCache.id
    }
    val mine = myId?.let { page.ownerUri == "spotify:user:$it" } == true
    val canEdit = mine || editable
    var access by remember { mutableStateOf<PlaylistAccess?>(null) }
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    LaunchedEffect(page.uri, mine) {
        if (mine) access = runCatching { MyspotiRuntime.api.playlists.access(page.uri) }.getOrNull()
    }

    // Taps are sent one at a time, in order; a refused one puts back what Spotify really has.
    val accessWrites = remember { kotlinx.coroutines.sync.Mutex() }
    fun setAccess(target: PlaylistAccess) {
        if (access == target) return
        access = target
        playlistWriteScope.launch {
            accessWrites.withLock {
                runCatching { MyspotiRuntime.api.playlists.setAccess(page.uri, target) }
                    .onSuccess { onChanged() }
                    .onFailure {
                        access = runCatching { MyspotiRuntime.api.playlists.access(page.uri) }.getOrNull()
                        android.widget.Toast.makeText(appContext, R.string.playlist_change_refused, android.widget.Toast.LENGTH_SHORT).show()
                    }
            }
        }
    }

    fun runWrite(block: suspend () -> Unit, after: () -> Unit = onChanged) = playlistWriteScope.launch {
        runCatching { block() }
            .onSuccess { after() }
            .onFailure {
                android.widget.Toast.makeText(appContext, R.string.playlist_change_refused, android.widget.Toast.LENGTH_SHORT).show()
                onChanged()
            }
    }

    if (!editing) {
        ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF282828)) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
                Text(
                    page.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(20.dp, 0.dp, 20.dp, 12.dp),
                )
                if (canEdit) {
                    OptionRow(Icons.Outlined.Edit, stringResource(R.string.playlist_edit_playlist)) { editing = true }
                }
                if (mine) {
                    Text(
                        stringResource(R.string.playlist_access_title), color = Color(0xFFB3B3B3), fontSize = 13.sp,
                        modifier = Modifier.padding(20.dp, 12.dp, 20.dp, 4.dp),
                    )
                    AccessRow(Icons.Outlined.Public, R.string.playlist_access_public, R.string.playlist_access_public_detail, access == PlaylistAccess.PUBLIC) {
                        setAccess(PlaylistAccess.PUBLIC)
                    }
                    AccessRow(Icons.Outlined.Lock, R.string.playlist_access_private, R.string.playlist_access_private_detail, access == PlaylistAccess.PRIVATE) {
                        setAccess(PlaylistAccess.PRIVATE)
                    }
                    AccessRow(Icons.Outlined.Group, R.string.playlist_access_collaborative, R.string.playlist_access_collaborative_detail, access == PlaylistAccess.COLLABORATIVE) {
                        setAccess(PlaylistAccess.COLLABORATIVE)
                    }
                    Spacer(Modifier.height(8.dp))
                    OptionRow(Icons.Outlined.Delete, stringResource(R.string.local_delete_playlist)) { deleting = true }
                } else if (page.ownerUri.startsWith("spotify:user:")) {
                    OptionRow(Icons.Outlined.Person, stringResource(R.string.go_to_profile)) {
                        onDismiss(); onProfile(page.ownerUri.substringAfterLast(':'))
                    }
                }
            }
        }
    }

    if (editing) {
        EditPlaylistDialog(
            page = page,
            canEditDetails = mine,
            onDismiss = {
                editing = false
                onDismiss()
            },
            onSave = { name, description, cover, finalTracks ->
                editing = false
                onDismiss()
                onEdited(name, description, finalTracks)
                runWrite({
                    if (mine) {
                        if (name != page.name) MyspotiRuntime.api.playlists.rename(page.uri, name)
                        if (description != page.description) MyspotiRuntime.api.playlists.setDescription(page.uri, description)
                        when (cover) {
                            is CoverChange.New -> MyspotiRuntime.api.playlists.setCover(page.uri, cover.jpeg)
                            CoverChange.Remove -> MyspotiRuntime.api.playlists.setCover(page.uri, null)
                            CoverChange.Keep -> {}
                        }
                    }
                    SpotifySync.syncPlaylistTrackEdits(page.uri, page.tracks, finalTracks)
                    if (name != page.name || cover !is CoverChange.Keep || finalTracks.size != page.tracks.size) {
                        Api.HomeCache.library = null
                        Api.HomeCache.libraryRoot = null
                    }
                }, {
                    android.widget.Toast.makeText(appContext, R.string.playlist_saved, android.widget.Toast.LENGTH_SHORT).show()
                    if (cover !is CoverChange.Keep) onChanged()
                })
            },
        )
    }

    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        containerColor = Color(0xFF282828),
        text = { Text(stringResource(R.string.playlist_delete_confirm, page.name), color = Color.White) },
        confirmButton = {
            TextButton(onClick = {
                deleting = false
                runWrite({ MyspotiRuntime.api.playlists.delete(page.uri) }, {
                    Api.HomeCache.library = null
                    Api.HomeCache.libraryRoot = null
                    onDismiss()
                    onDeleted()
                })
            }) { Text(stringResource(R.string.actions_picker_delete), color = Color(0xFFF15E6C)) }
        },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel), color = Color.White) } },
    )
}

private sealed interface CoverChange {
    data object Keep : CoverChange
    data object Remove : CoverChange
    class New(val jpeg: ByteArray, val preview: Bitmap) : CoverChange
}

private data class EditableTrackItem(
    val key: String,
    val song: SongsModel,
)

private val EditRowHeight = 60.dp
private val DragHandleZoneWidth = 60.dp
private val EdgeScrollZone = 76.dp
private val EdgeScrollMaxStep = 16.dp

/**
 * Full-screen Spotify-style "Edit playlist" view:
 * - Edit cover image, playlist title, and description (for playlist owners).
 * - Rearrange tracks by dragging the `≡` drag handle on the right (with live swapping & edge auto-scroll).
 * - Remove tracks by tapping the `⊖` button on the left or swiping left (with Undo snackbar).
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun EditPlaylistDialog(
    page: ContextPageModel,
    canEditDetails: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, CoverChange, List<SongsModel>) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    var name by remember { mutableStateOf(page.name) }
    var description by remember { mutableStateOf(page.description) }
    var cover by remember { mutableStateOf<CoverChange>(CoverChange.Keep) }
    var askDiscard by remember { mutableStateOf(false) }
    val snackbarHost = remember { SnackbarHostState() }

    val initialItems = remember(page.tracks) {
        val seen = HashMap<String, Int>()
        page.tracks.mapIndexed { idx, s ->
            val base = s.uid.ifBlank { "${s.id}:${s.spotifyTrackId}" }
            val count = (seen[base] ?: 0) + 1
            seen[base] = count
            val key = if (count == 1) base else "$base#$count#$idx"
            EditableTrackItem(key = key, song = s)
        }
    }
    val items = remember(page.tracks) {
        mutableStateListOf<EditableTrackItem>().apply { addAll(initialItems) }
    }

    val hasChanges = remember(name, description, cover, items.toList(), initialItems) {
        name.trim() != page.name ||
            description.trim() != page.description ||
            cover !is CoverChange.Keep ||
            items.map { it.key } != initialItems.map { it.key }
    }

    fun requestClose() {
        if (hasChanges) askDiscard = true else onDismiss()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val made = withContext(Dispatchers.Default) { runCatching { coverJpeg(context, uri) }.getOrNull() }
            if (made != null) cover = CoverChange.New(made.first, made.second)
        }
    }

    fun removeItemAt(index: Int) {
        if (index !in items.indices) return
        val removed = items.removeAt(index)
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch {
            snackbarHost.currentSnackbarData?.dismiss()
            val res = snackbarHost.showSnackbar(
                message = context.getString(R.string.removed_from, page.name),
                actionLabel = context.getString(R.string.undo),
                duration = SnackbarDuration.Short,
            )
            if (res == SnackbarResult.ActionPerformed) {
                val restoreIndex = index.coerceIn(0, items.size)
                items.add(restoreIndex, removed)
            }
        }
    }

    val listState = rememberLazyListState()
    val headerItemCount = if (canEditDetails) 2 else 1 // [details header (optional), track count header]
    val rowPx = with(density) { EditRowHeight.toPx() }
    val handleWidthPx = with(density) { DragHandleZoneWidth.toPx() }
    val edgePx = with(density) { EdgeScrollZone.toPx() }
    val maxStepPx = with(density) { EdgeScrollMaxStep.toPx() }

    var draggingKey by remember { mutableStateOf<String?>(null) }
    var draggedSong by remember { mutableStateOf<SongsModel?>(null) }
    var pointerY by remember { mutableFloatStateOf(0f) }
    var grabOffsetInRow by remember { mutableFloatStateOf(rowPx / 2f) }
    var autoScrollJob by remember { mutableStateOf<Job?>(null) }

    fun syncSwapAtPointer(y: Float) {
        val key = draggingKey ?: return
        val currentIdx = items.indexOfFirst { it.key == key }
        if (currentIdx < 0) return
        val visibleTracks = listState.layoutInfo.visibleItemsInfo.filter { it.index >= headerItemCount }
        if (visibleTracks.isEmpty()) return

        val targetInfo = visibleTracks.firstOrNull { info ->
            y >= info.offset && y <= info.offset + info.size
        } ?: when {
            y < visibleTracks.first().offset -> visibleTracks.first()
            y > visibleTracks.last().let { it.offset + it.size } -> visibleTracks.last()
            else -> null
        } ?: return

        val targetIdx = (targetInfo.index - headerItemCount).coerceIn(0, items.lastIndex)
        if (targetIdx != currentIdx) {
            val mid = targetInfo.offset + targetInfo.size / 2f
            val shouldSwap = (targetIdx > currentIdx && y >= mid) || (targetIdx < currentIdx && y <= mid)
            if (shouldSwap) {
                val moved = items.removeAt(currentIdx)
                items.add(targetIdx, moved)
                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
            }
        }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = AppAccent,
        focusedBorderColor = AppAccent, unfocusedBorderColor = Color(0xFF484848),
        focusedLabelColor = AppAccent, unfocusedLabelColor = Color(0xFFB3B3B3),
    )

    Dialog(
        onDismissRequest = ::requestClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        BackHandler(onBack = ::requestClose)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AppBackground)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Column(Modifier.fillMaxSize()) {
                // Top Bar: Close (X) | Edit playlist | Save
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 12.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable(onClick = ::requestClose),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.cancel),
                            tint = Color.White,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                    Text(
                        text = stringResource(R.string.playlist_edit_playlist),
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                    )
                    val canSave = name.isNotBlank() && hasChanges
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (canSave) AppAccent else Color(0xFF2A2A2A))
                            .clickable(enabled = name.isNotBlank()) {
                                if (hasChanges) {
                                    onSave(name.trim(), description.trim(), cover, items.map { it.song })
                                } else {
                                    onDismiss()
                                }
                            }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.playlist_save),
                            color = if (canSave) Color.Black else Color.White.copy(alpha = 0.7f),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                HorizontalDivider(color = Color(0xFF222222), thickness = 1.dp)

                // Main List + Viewport-Space Drag Controller
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .pointerInput(headerItemCount) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                if (down.position.x < size.width - handleWidthPx) return@awaitEachGesture
                                val hitInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                                    info.index >= headerItemCount &&
                                        down.position.y >= info.offset &&
                                        down.position.y <= info.offset + info.size
                                } ?: return@awaitEachGesture
                                val trackIdx = hitInfo.index - headerItemCount
                                val entry = items.getOrNull(trackIdx) ?: return@awaitEachGesture

                                down.consume()
                                draggingKey = entry.key
                                draggedSong = entry.song
                                grabOffsetInRow = (down.position.y - hitInfo.offset).coerceIn(0f, rowPx)
                                pointerY = down.position.y
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)

                                autoScrollJob?.cancel()
                                autoScrollJob = scope.launch {
                                    while (isActive && draggingKey != null) {
                                        val layout = listState.layoutInfo
                                        val step = queueEdgeScrollStep(
                                            pointerY = pointerY,
                                            viewportTop = layout.viewportStartOffset.toFloat(),
                                            viewportBottom = layout.viewportEndOffset.toFloat(),
                                            edgePx = edgePx,
                                            maxStepPx = maxStepPx,
                                        )
                                        if (step != 0f) {
                                            val scrolled = listState.scrollBy(step)
                                            if (scrolled != 0f) {
                                                syncSwapAtPointer(pointerY)
                                            }
                                        }
                                        withFrameNanos { }
                                    }
                                }

                                try {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) {
                                            change.consume()
                                            break
                                        }
                                        val dy = change.positionChange().y
                                        if (dy != 0f) {
                                            change.consume()
                                            pointerY = change.position.y.coerceIn(0f, size.height.toFloat())
                                            syncSwapAtPointer(pointerY)
                                        }
                                    }
                                } finally {
                                    autoScrollJob?.cancel()
                                    autoScrollJob = null
                                    if (draggingKey != null) {
                                        haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                    }
                                    draggingKey = null
                                    draggedSong = null
                                }
                            }
                        },
                ) {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(bottom = 88.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (canEditDetails) {
                            item(key = "edit-header") {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 16.dp),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        val c = cover
                                        Box(
                                            modifier = Modifier
                                                .size(104.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color(0xFF282828))
                                                .clickable {
                                                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                                },
                                        ) {
                                            when (c) {
                                                is CoverChange.New -> Image(
                                                    c.preview.asImageBitmap(),
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize(),
                                                )
                                                CoverChange.Remove -> {}
                                                CoverChange.Keep -> GlideImage(
                                                    page.coverUri,
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Crop,
                                                    failure = placeholder(R.drawable.placeholder),
                                                    modifier = Modifier.fillMaxSize(),
                                                )
                                            }
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier
                                                    .align(Alignment.BottomEnd)
                                                    .padding(6.dp)
                                                    .size(28.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(0xCC000000)),
                                            ) {
                                                Icon(
                                                    Icons.Outlined.Edit,
                                                    contentDescription = null,
                                                    tint = Color.White,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            }
                                        }
                                        Spacer(Modifier.width(16.dp))
                                        Column {
                                            TextButton(onClick = {
                                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                            }) {
                                                Text(stringResource(R.string.playlist_change_cover), color = Color.White, fontWeight = FontWeight.SemiBold)
                                            }
                                            TextButton(onClick = { cover = CoverChange.Remove }) {
                                                Text(stringResource(R.string.playlist_remove_cover), color = Color(0xFFB3B3B3))
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(14.dp))
                                    OutlinedTextField(
                                        value = name,
                                        onValueChange = { name = it },
                                        label = { Text(stringResource(R.string.playlist_name_label)) },
                                        singleLine = true,
                                        colors = fieldColors,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    OutlinedTextField(
                                        value = description,
                                        onValueChange = { description = it },
                                        label = { Text(stringResource(R.string.playlist_description_label)) },
                                        minLines = 2,
                                        maxLines = 4,
                                        colors = fieldColors,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }

                        item(key = "tracks-header") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                            ) {
                                Text(
                                    text = "${items.size} songs",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                if (items.size > 1) {
                                    Text(
                                        text = stringResource(R.string.playlist_drag_to_reorder),
                                        color = Color(0xFFB3B3B3),
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                        }

                        itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
                            val isDragged = item.key == draggingKey
                            SwipeRowToRemove(
                                enabled = draggingKey == null,
                                onRemove = { removeItemAt(index) },
                            ) {
                                EditTrackRow(
                                    song = item.song,
                                    modifier = Modifier.alpha(if (isDragged) 0f else 1f),
                                    onRemove = { removeItemAt(index) },
                                )
                            }
                        }
                    }

                    // Floating row preview under the finger while dragging
                    val activeSong = draggedSong
                    if (draggingKey != null && activeSong != null) {
                        EditTrackRow(
                            song = activeSong,
                            elevated = true,
                            onRemove = {},
                            modifier = Modifier
                                .graphicsLayer {
                                    translationY = (pointerY - grabOffsetInRow).coerceAtLeast(0f)
                                }
                                .shadow(14.dp, RoundedCornerShape(6.dp)),
                        )
                    }
                }
            }

            SnackbarHost(
                hostState = snackbarHost,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            ) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = Color(0xFF282828),
                    contentColor = Color.White,
                    actionContentColor = AppAccent,
                )
            }
        }

        if (askDiscard) {
            AlertDialog(
                onDismissRequest = { askDiscard = false },
                containerColor = Color(0xFF282828),
                title = { Text(stringResource(R.string.playlist_discard_title), color = Color.White, fontWeight = FontWeight.Bold) },
                text = { Text(stringResource(R.string.playlist_discard_body), color = Color(0xFFB3B3B3)) },
                confirmButton = {
                    TextButton(onClick = {
                        askDiscard = false
                        onDismiss()
                    }) {
                        Text(stringResource(R.string.playlist_discard), color = Color(0xFFF15E6C), fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { askDiscard = false }) {
                        Text(stringResource(R.string.playlist_keep_editing), color = Color.White)
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun EditTrackRow(
    song: SongsModel,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(EditRowHeight)
            .background(if (elevated) Color(0xFF282828) else AppBackground)
            .padding(start = 12.dp, end = 4.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(onClick = onRemove),
        ) {
            Icon(
                Icons.Default.RemoveCircleOutline,
                contentDescription = stringResource(R.string.aligned_curation_first_save_default_row_remove_action_description),
                tint = Color(0xFFB3B3B3),
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        GlideImage(
            transition = CrossFade,
            model = song.coverUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            failure = placeholder(R.drawable.placeholder),
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF282828)),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                if (song.explicit) {
                    ExplicitBadge()
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    text = song.singer,
                    color = Color(0xFFB3B3B3),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                Icons.Default.DragHandle,
                contentDescription = stringResource(R.string.edit_playlist_items_move_chip),
                tint = if (elevated) Color.White else Color(0xFFB3B3B3),
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

@Composable
private fun SwipeRowToRemove(
    enabled: Boolean,
    onRemove: () -> Unit,
    content: @Composable () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val threshold = with(LocalDensity.current) { 100.dp.toPx() }
    val offset = remember { androidx.compose.animation.core.Animatable(0f) }
    var armed by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        if (offset.value < 0f) {
            Box(
                contentAlignment = Alignment.CenterEnd,
                modifier = Modifier
                    .matchParentSize()
                    .background(if (armed) Color(0xFFE22134) else Color(0xFF7A1F1F)),
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.aligned_curation_first_save_default_row_remove_action_description),
                    tint = Color.White,
                    modifier = Modifier.padding(end = 24.dp).size(24.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .graphicsLayer { translationX = offset.value }
                .background(AppBackground)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (armed) {
                                scope.launch {
                                    offset.animateTo(-size.width.toFloat())
                                    onRemove()
                                    offset.snapTo(0f)
                                }
                            } else {
                                scope.launch { offset.animateTo(0f) }
                            }
                            armed = false
                        },
                        onDragCancel = {
                            armed = false
                            scope.launch { offset.animateTo(0f) }
                        },
                    ) { change, dx ->
                        change.consume()
                        val next = (offset.value + dx).coerceIn(-size.width.toFloat(), 0f)
                        scope.launch { offset.snapTo(next) }
                        val nowArmed = -next >= threshold
                        if (nowArmed && !armed) {
                            haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        }
                        armed = nowArmed
                    }
                },
        ) {
            content()
        }
    }
}

/** The picked image as Spotify takes a cover: square, 640 px, JPEG under 256 KB. */
private fun coverJpeg(context: Context, uri: Uri): Pair<ByteArray, Bitmap> {
    val source = context.contentResolver.openInputStream(uri).use { android.graphics.BitmapFactory.decodeStream(it) }
        ?: error("not an image")
    val side = minOf(source.width, source.height)
    val square = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
    val scaled = Bitmap.createScaledBitmap(square, 640, 640, true)
    var quality = 90
    var bytes: ByteArray
    do {
        bytes = java.io.ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
        quality -= 10
    } while (bytes.size > 256 * 1024 && quality > 30)
    return bytes to scaled
}

@Composable
private fun OptionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, color = Color.White, fontSize = 16.sp)
    }
}

@Composable
private fun AccessRow(icon: ImageVector, label: Int, detail: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Icon(icon, null, tint = if (selected) AppAccent else Color.White, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(label), color = if (selected) AppAccent else Color.White, fontSize = 16.sp)
            Text(stringResource(detail), color = Color(0xFFB3B3B3), fontSize = 13.sp)
        }
        if (selected) Icon(Icons.Default.Check, null, tint = AppAccent, modifier = Modifier.size(20.dp))
    }
}
