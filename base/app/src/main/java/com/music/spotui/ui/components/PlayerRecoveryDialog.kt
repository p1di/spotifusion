package com.music.spotui.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.music.spotui.data.local.DeveloperLogStore
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.theme.AppAccent
import com.music.spotui.ui.theme.AppSans
import kotlinx.coroutines.launch

/**
 * Global modal state that prompts the user to restart the internal player engine when:
 * 1. A clicked song fails to start playing after a timeout.
 * 2. Playlists get stuck on "Loading playlists..." in the Add to Playlist / Saved In sheet.
 */
object PlayerRecoveryModal {
    enum class Reason {
        PLAYBACK_STUCK,
        PLAYLISTS_STUCK,
    }

    val visible = mutableStateOf(false)
    val reason = mutableStateOf(Reason.PLAYBACK_STUCK)
    val trackTitle = mutableStateOf("")
    private var onRestartCallback: (suspend () -> Unit)? = null
    private var lastDismissedAtMs = 0L

    fun promptPlaybackStuck(title: String = "", onRestart: (suspend () -> Unit)? = null) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (visible.value || now - lastDismissedAtMs < 8_000L) return
        DeveloperLogStore.warn(
            "PlayerWatchdog",
            "Song playback stalled${if (title.isNotBlank()) " for '$title'" else ""}; prompting player restart modal",
        )
        reason.value = Reason.PLAYBACK_STUCK
        trackTitle.value = title
        onRestartCallback = onRestart
        visible.value = true
    }

    fun promptPlaylistsStuck(onRestart: (suspend () -> Unit)? = null) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (visible.value || now - lastDismissedAtMs < 6_000L) return
        DeveloperLogStore.warn(
            "PlaylistWatchdog",
            "Playlists load timed out in playlist sheet; prompting player restart modal",
        )
        reason.value = Reason.PLAYLISTS_STUCK
        trackTitle.value = ""
        onRestartCallback = onRestart
        visible.value = true
    }

    fun dismiss() {
        lastDismissedAtMs = android.os.SystemClock.elapsedRealtime()
        visible.value = false
    }

    suspend fun executeRestart() {
        val callback = onRestartCallback
        SongPlayer.restartPlayerEngine()
        callback?.invoke()
    }
}

@Composable
fun PlayerRecoveryDialog() {
    val open by PlayerRecoveryModal.visible
    if (!open) return

    val currentReason by PlayerRecoveryModal.reason
    val titleText by PlayerRecoveryModal.trackTitle
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var restarting by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = { if (!restarting) PlayerRecoveryModal.dismiss() }) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF242424))
                .padding(horizontal = 24.dp, vertical = 26.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(AppAccent.copy(alpha = 0.16f)),
            ) {
                if (restarting) {
                    CircularProgressIndicator(
                        color = AppAccent,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(26.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = AppAccent,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = when (currentReason) {
                    PlayerRecoveryModal.Reason.PLAYBACK_STUCK -> "Playback is taking longer than usual"
                    PlayerRecoveryModal.Reason.PLAYLISTS_STUCK -> "Playlists aren't loading"
                },
                color = Color.White,
                fontFamily = AppSans,
                fontWeight = FontWeight.Bold,
                fontSize = 19.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text = when (currentReason) {
                    PlayerRecoveryModal.Reason.PLAYBACK_STUCK ->
                        if (titleText.isNotBlank()) {
                            "\"$titleText\" hasn't started playing yet. Would you like to restart the player engine to fix playback?"
                        } else {
                            "This song hasn't started playing yet. Would you like to restart the player engine to fix playback?"
                        }
                    PlayerRecoveryModal.Reason.PLAYLISTS_STUCK ->
                        "The player connection seems stuck while loading your playlists. Restarting the player engine usually fixes this right away."
                },
                color = Color.White.copy(alpha = 0.78f),
                fontFamily = AppSans,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(24.dp))

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (restarting) AppAccent.copy(alpha = 0.6f) else AppAccent)
                    .clickable(enabled = !restarting) {
                        restarting = true
                        scope.launch {
                            runCatching { PlayerRecoveryModal.executeRestart() }
                            restarting = false
                            PlayerRecoveryModal.visible.value = false
                            android.widget.Toast
                                .makeText(context, "Player engine restarted", android.widget.Toast.LENGTH_SHORT)
                                .show()
                        }
                    },
            ) {
                Text(
                    text = if (restarting) "Restarting player…" else "Restart player",
                    color = Color.Black,
                    fontFamily = AppSans,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                )
            }

            if (!restarting) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                ) {
                    Text(
                        text = "Keep waiting",
                        color = Color.White.copy(alpha = 0.75f),
                        fontFamily = AppSans,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { PlayerRecoveryModal.dismiss() }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}
