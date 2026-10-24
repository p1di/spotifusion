package com.music.spotui.ui.components

import app.myspoti.engine.R

import androidx.compose.ui.res.stringResource

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.music.spotui.data.entity.SongsModel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Spotify's gesture: swipe a song row sideways to add it to the queue. The row
 * follows the finger over a green strip, ticks once with a vibration when it
 * passes the threshold, adds the song ONCE on release, then springs back.
 */
@Composable
fun SwipeToQueue(song: SongsModel, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    val maxDrag = threshold * 1.4f
    val offset = remember { Animatable(0f) }
    var armed by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        if (offset.value != 0f) {
            Box(
                contentAlignment = if (offset.value > 0) Alignment.CenterStart else Alignment.CenterEnd,
                modifier = Modifier
                    .matchParentSize()
                    .background(if (armed) com.music.spotui.ui.theme.AppAccent else com.music.spotui.ui.theme.AppAccent.copy(alpha = 0.6f)),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.PlaylistAdd,
                    contentDescription = stringResource(R.string.add_to_queue_action_content_description),
                    tint = Color.Black,
                    modifier = Modifier.padding(horizontal = 24.dp).size(26.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .background(com.music.spotui.ui.theme.AppBackground)
                .pointerInput(song.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (armed && song.spotifyTrackId.isNotBlank()) {
                                com.music.spotui.di.SongPlayer.addToQueue(song.spotifyTrackId)
                                android.widget.Toast.makeText(context, context.getString(R.string.snackbar_added_to_queue), android.widget.Toast.LENGTH_SHORT).show()
                            }
                            armed = false
                            scope.launch { offset.animateTo(0f, spring()) }
                        },
                        onDragCancel = {
                            armed = false
                            scope.launch { offset.animateTo(0f, spring()) }
                        },
                    ) { change, dx ->
                        change.consume()
                        val next = (offset.value + dx).coerceIn(-maxDrag, maxDrag)
                        scope.launch { offset.snapTo(next) }
                        val nowArmed = abs(next) >= threshold
                        if (nowArmed && !armed) haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        armed = nowArmed
                    }
                },
        ) { content() }
    }
}

/**
 * Snapchat-style swipe-from-left-edge to go back that never conflicts with [SwipeToQueue]:
 * - Touches starting within the left 36.dp edge intercept horizontal rightward drags in
 *   PointerEventPass.Initial (before SwipeToQueue), navigating back once dragged past 68.dp.
 * - Touches starting outside the left edge only navigate back if no child (like SwipeToQueue)
 *   consumed the horizontal drag.
 */
fun Modifier.edgeSwipeBack(onBack: () -> Unit): Modifier = this.pointerInput(onBack) {
    val edgeThresholdPx = 36.dp.toPx()
    val triggerDistancePx = 68.dp.toPx()
    val touchSlopPx = viewConfiguration.touchSlop
    awaitPointerEventScope {
        while (true) {
            val downEvent = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            val down = downEvent.changes.firstOrNull { it.changedToDownIgnoreConsumed() } ?: continue
            val isLeftEdge = down.position.x <= edgeThresholdPx
            val pointerId = down.id
            var totalDx = 0f
            var totalDy = 0f
            var intercepting = false
            var cancelled = false
            var triggered = false

            while (!cancelled && !triggered) {
                val pass = if (isLeftEdge) {
                    androidx.compose.ui.input.pointer.PointerEventPass.Initial
                } else {
                    androidx.compose.ui.input.pointer.PointerEventPass.Main
                }
                val event = awaitPointerEvent(pass)
                val change = event.changes.firstOrNull { it.id == pointerId }
                if (change == null || !change.pressed) {
                    break
                }
                if (!isLeftEdge && change.isConsumed) {
                    cancelled = true
                    break
                }
                val dx = change.position.x - change.previousPosition.x
                val dy = change.position.y - change.previousPosition.y
                totalDx += dx
                totalDy += dy

                if (!intercepting) {
                    if (abs(totalDy) > touchSlopPx && abs(totalDy) > totalDx) {
                        cancelled = true
                        break
                    }
                    if (totalDx > touchSlopPx && totalDx > abs(totalDy) * 1.35f) {
                        if (isLeftEdge || down.position.x <= edgeThresholdPx * 3.5f) {
                            intercepting = true
                        } else {
                            cancelled = true
                            break
                        }
                    }
                }

                if (intercepting) {
                    change.consume()
                    if (totalDx >= triggerDistancePx) {
                        triggered = true
                        onBack()
                    }
                }
            }
        }
    }
}

private val URL_REGEX = Regex(
    """((?:https?://|www\.)[^\s<>()]+|[a-zA-Z0-9][-a-zA-Z0-9]*\.(?:com|org|net|io|co|me|gg|ee|tv|fm|link|ly|be|gl|to|gle|app|dev|music)(?:/[^\s<>()]*)?)""",
    RegexOption.IGNORE_CASE
)

/**
 * Renders description/about text with clickable URLs that open in the browser.
 */
@Composable
fun ClickableDescriptionText(
    text: String,
    color: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: androidx.compose.ui.text.style.TextOverflow = androidx.compose.ui.text.style.TextOverflow.Clip,
) {
    val context = LocalContext.current
    val annotated = remember(text, color) {
        androidx.compose.ui.text.buildAnnotatedString {
            var lastIndex = 0
            for (match in URL_REGEX.findAll(text)) {
                val range = match.range
                if (range.first > lastIndex) {
                    append(text.substring(lastIndex, range.first))
                }
                val rawUrl = match.value.trimEnd('.', ',', ';', ':', ')', ']', '!', '?')
                val fullUrl = if (rawUrl.startsWith("http://", ignoreCase = true) || rawUrl.startsWith("https://", ignoreCase = true)) {
                    rawUrl
                } else {
                    "https://$rawUrl"
                }
                pushStringAnnotation(tag = "URL", annotation = fullUrl)
                withStyle(
                    androidx.compose.ui.text.SpanStyle(
                        color = Color.White,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                    )
                ) {
                    append(rawUrl)
                }
                pop()
                val trailingStart = range.first + rawUrl.length
                if (trailingStart <= range.last) {
                    append(text.substring(trailingStart, range.last + 1))
                }
                lastIndex = range.last + 1
            }
            if (lastIndex < text.length) {
                append(text.substring(lastIndex))
            }
        }
    }
    var layoutResult by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    val hasLinks = remember(annotated) {
        annotated.getStringAnnotations("URL", 0, annotated.length).isNotEmpty()
    }
    androidx.compose.material3.Text(
        text = annotated,
        color = color,
        fontSize = fontSize,
        maxLines = maxLines,
        overflow = overflow,
        onTextLayout = { layoutResult = it },
        modifier = if (hasLinks) {
            modifier.pointerInput(annotated) {
                awaitPointerEventScope {
                    while (true) {
                        val downEvent = awaitPointerEvent()
                        val down = downEvent.changes.firstOrNull { it.changedToDownIgnoreConsumed() } ?: continue
                        val pos = down.position
                        val offset = layoutResult?.getOffsetForPosition(pos) ?: continue
                        val link = annotated.getStringAnnotations(tag = "URL", start = offset, end = offset).firstOrNull() ?: continue
                        var upPos = pos
                        var released = false
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            upPos = ch.position
                            if (!ch.pressed) {
                                released = true
                                ch.consume()
                                break
                            }
                        }
                        if (released && (upPos - pos).getDistance() < viewConfiguration.touchSlop * 2f) {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(link.item)
                                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                    }
                }
            }
        } else {
            modifier
        },
    )
}

