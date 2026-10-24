package com.music.spotui.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.border
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.engine.BuildConfig
import app.myspoti.engine.R
import com.music.spotui.data.api.SupportReminder
import com.music.spotui.ui.theme.AppAccent

const val INSTAGRAM_URL = "https://instagram.com/pidi.mp3"
const val GITHUB_PROFILE_URL = "https://github.com/redretep"
const val SPOTIFY_ARTIST_URL = "https://open.spotify.com/artist/2d9WIClyp1sMoZU9GyLiNj"

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** About / Updates sheet: version, Instagram, About me, AI disclaimer, Spotify & GitHub buttons. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    // Opened in full: half open, it hid the support buttons at the bottom.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF282828),
    ) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 20.dp)) {
                Image(
                    painterResource(R.drawable.ic_launcher_foreground), contentDescription = null,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF121212)),
                )
                Column(Modifier.padding(start = 14.dp)) {
                    Text("Spotifusion", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("${stringResource(R.string.settings_item_version_title)} ${BuildConfig.VERSION_NAME}",
                        color = Color(0xFFB3B3B3), fontSize = 13.sp)
                }
            }
            Text(stringResource(R.string.about_free), color = Color(0xFFB3B3B3), fontSize = 13.sp,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp))
            AboutRow("Instagram", "instagram.com/pidi.mp3") { openUrl(context, INSTAGRAM_URL) }
            AboutRow("About me", "github.com/redretep") { openUrl(context, GITHUB_PROFILE_URL) }
            Text("Made with help of AI", color = Color(0xFFB3B3B3), fontSize = 13.sp,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 12.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(48.dp).clip(RoundedCornerShape(50))
                    .background(AppAccent).pressable(0.96f) { openUrl(context, SPOTIFY_ARTIST_URL) },
            ) { Text("Support me on Spotify", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            GitHubSponsorButton(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp)) { openUrl(context, GITHUB_PROFILE_URL) }
        }
    }
}

/** GitHub button under the Spotify button: outlined, so Spotify stays the main one. */
@Composable
fun GitHubSponsorButton(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val context = LocalContext.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(50))
            .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(50))
            .pressable(0.96f) { onClick?.invoke() ?: openUrl(context, GITHUB_PROFILE_URL) },
    ) { Text("Open on GitHub", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
}

/** "Support Spotifusion" from the profile menu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF282828),
    ) {
        androidx.compose.foundation.layout.Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text(stringResource(R.string.support_title), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Made with help of AI", color = Color(0xFFB3B3B3), fontSize = 14.sp,
                modifier = Modifier.padding(top = 10.dp, bottom = 18.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(50))
                    .background(AppAccent).pressable(0.96f) { onDismiss(); openUrl(context, SPOTIFY_ARTIST_URL) },
            ) { Text("Support me on Spotify", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            GitHubSponsorButton(Modifier.padding(top = 10.dp)) { onDismiss(); openUrl(context, GITHUB_PROFILE_URL) }
        }
    }
}

@Composable
private fun AboutRow(title: String, detail: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(title, color = Color.White, fontSize = 16.sp)
        Text(detail, color = Color(0xFFB3B3B3), fontSize = 12.sp)
    }
}
