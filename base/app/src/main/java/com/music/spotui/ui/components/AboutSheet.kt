package com.music.spotui.ui.components

import com.music.spotui.ui.theme.tone
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.AppMuted

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

const val DISCORD_URL = "https://discord.gg/jXKnGVFnGS"
private val repo get() = BuildConfig.UPDATE_REPO.ifBlank { "p1di/spotifusion" }

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** About: the version, the Discord community, the source code and Ko-fi. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    com.music.spotui.extensions.ProtectedSurfaceEffect(app.myspoti.extensions.ProtectedSurface.ABOUT)
    // Opened in full: half open, it hid the support buttons at the bottom.
    LookSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = tone(0xFF282828),
    ) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 20.dp)) {
                Image(
                    painterResource(R.drawable.ic_launcher_foreground), contentDescription = null,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(tone(0xFF121212)),
                )
                Column(Modifier.padding(start = 14.dp)) {
                    Text("Spotifusion", color = AppText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("${stringResource(R.string.settings_item_version_title)} ${BuildConfig.VERSION_NAME}",
                        color = AppMuted, fontSize = 13.sp)
                }
            }
            Text(stringResource(R.string.about_free), color = AppMuted, fontSize = 13.sp,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp))
            AboutRow(stringResource(R.string.about_discord), stringResource(R.string.about_discord_detail)) { openUrl(context, DISCORD_URL) }
            AboutRow(stringResource(R.string.about_github), stringResource(R.string.about_github_detail)) { openUrl(context, "https://github.com/$repo") }
            Text(stringResource(R.string.support_body), color = AppMuted, fontSize = 13.sp,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 12.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(48.dp).clip(RoundedCornerShape(50))
                    .background(AppAccent).pressable(0.96f) { SupportReminder.open(context) },
            ) { Text(stringResource(R.string.support_action), color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            GitHubSponsorButton(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp))
        }
    }
}

/** GitHub Sponsors, under the Ko-fi button: outlined, so Ko-fi stays the main one. */
@Composable
fun GitHubSponsorButton(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val context = LocalContext.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(50))
            .border(1.dp, AppText.copy(alpha = 0.6f), RoundedCornerShape(50))
            .pressable(0.96f) { onClick?.invoke() ?: SupportReminder.openSponsors(context) },
    ) { Text(stringResource(R.string.support_github_action), color = AppText, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
}

/** "Support Myspoti" from the profile menu: Ko-fi or GitHub Sponsors, the user picks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    com.music.spotui.extensions.ProtectedSurfaceEffect(app.myspoti.extensions.ProtectedSurface.SUPPORT)
    LookSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = tone(0xFF282828),
    ) {
        androidx.compose.foundation.layout.Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text(stringResource(R.string.support_title), color = AppText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.support_body), color = AppMuted, fontSize = 14.sp,
                modifier = Modifier.padding(top = 10.dp, bottom = 18.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(50))
                    .background(AppAccent).pressable(0.96f) { onDismiss(); SupportReminder.open(context) },
            ) { Text(stringResource(R.string.support_action), color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            GitHubSponsorButton(Modifier.padding(top = 10.dp)) { onDismiss(); SupportReminder.openSponsors(context) }
        }
    }
}

@Composable
private fun AboutRow(title: String, detail: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(title, color = AppText, fontSize = 16.sp)
        Text(detail, color = AppMuted, fontSize = 12.sp)
    }
}
