package com.music.spotui.ui.components

import com.music.spotui.ui.theme.tone
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.AppMuted

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.engine.R
import com.music.spotui.data.api.SupportReminder
import com.music.spotui.data.api.SupportReminder.Accent
import com.music.spotui.data.preferences.LookPref
import com.music.spotui.data.api.SupportReminder.Level
import com.music.spotui.ui.theme.AppAccent

/** The small "Supporter" pill shown next to the profile name. */
@Composable
fun SupporterBadge(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.supporter_badge), color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.clip(RoundedCornerShape(50)).background(AppAccent).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

private fun accentName(a: Accent) = when (a) {
    Accent.GREEN -> R.string.color_green
    Accent.BLUE -> R.string.color_blue
    Accent.PURPLE -> R.string.color_purple
    Accent.PINK -> R.string.color_pink
    Accent.ORANGE -> R.string.color_orange
}

private fun iconMark(a: Accent) = when (a) {
    Accent.GREEN -> R.drawable.ic_launcher_foreground
    Accent.BLUE -> R.drawable.ic_launcher_foreground_blue
    Accent.PURPLE -> R.drawable.ic_launcher_foreground_purple
    Accent.PINK -> R.drawable.ic_launcher_foreground_pink
    Accent.ORANGE -> R.drawable.ic_launcher_foreground_orange
}

/** "I've supported": on trust, a donation or a monthly support. */
@Composable
fun SupportChoice(onChosen: (Level) -> Unit) {
    Text(stringResource(R.string.supporter_how), color = AppText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
        for ((level, label) in listOf(Level.ONCE to R.string.supporter_once, Level.MONTHLY to R.string.supporter_monthly)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.height(40.dp).clip(RoundedCornerShape(50)).background(tone(0xFF3A3A3A))
                    .pressable(0.96f) { onChosen(level) }.padding(horizontal = 18.dp),
            ) { Text(stringResource(label), color = AppText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** Supporter perks: thank-yous only (colour, icon, badge); nothing in the app is locked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupporterSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    com.music.spotui.extensions.ProtectedSurfaceEffect(app.myspoti.extensions.ProtectedSurface.SUPPORT)
    var choosing by remember { mutableStateOf(false) }
    val level = SupportReminder.level
    // Opened in full: half open, it hid the support buttons at the bottom.
    LookSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = tone(0xFF282828),
    ) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.supporter_perks_title), color = AppText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (level != Level.NONE) { Spacer(Modifier.width(10.dp)); SupporterBadge() }
            }
            if (level == Level.NONE) {
                Text(stringResource(R.string.supporter_perks_locked), color = AppMuted, fontSize = 14.sp,
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
                if (choosing) SupportChoice { SupportReminder.setLevel(context, it); choosing = false }
                else {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(50)).background(AppAccent)
                            .pressable(0.96f) { SupportReminder.open(context) },
                    ) { Text(stringResource(R.string.support_action), color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                    GitHubSponsorButton(Modifier.padding(top = 10.dp))
                    Text(stringResource(R.string.supporter_i_supported), color = AppText, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp)
                            .pressable(0.96f) { choosing = true }.padding(horizontal = 24.dp, vertical = 12.dp))
                }
                return@Column
            }
            Text(stringResource(R.string.supporter_thanks_title), color = AppMuted, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))

            Text(stringResource(R.string.supporter_accent), color = AppText, fontSize = 16.sp, modifier = Modifier.padding(top = 20.dp, bottom = 10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for (a in Accent.entries) {
                    val selected = SupportReminder.accentOf(LookPref.look) == a
                    val name = stringResource(accentName(a))
                    Box(
                        Modifier.size(40.dp).semantics { contentDescription = name }.clip(CircleShape).background(a.color)
                            .then(if (selected) Modifier.border(3.dp, AppText, CircleShape) else Modifier)
                            .pressable(0.92f) { SupportReminder.setAccent(context, LookPref.look, a) },
                    )
                }
            }

            Text(stringResource(R.string.supporter_icon), color = AppText, fontSize = 16.sp, modifier = Modifier.padding(top = 22.dp, bottom = 10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (a in Accent.entries) {
                    val selected = SupportReminder.icon == a
                    val name = stringResource(accentName(a))
                    Image(
                        painterResource(iconMark(a)), contentDescription = name,
                        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(tone(0xFF121212))
                            .then(if (selected) Modifier.border(3.dp, AppText, RoundedCornerShape(14.dp)) else Modifier)
                            .pressable(0.92f) { SupportReminder.setIcon(context, a) },
                    )
                }
            }
            Text(stringResource(R.string.supporter_icon_note), color = AppMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp).toggleable(level == Level.MONTHLY, role = Role.Switch) { on ->
                    SupportReminder.setLevel(context, if (on) Level.MONTHLY else Level.ONCE)
                },
            ) {
                Text(stringResource(R.string.supporter_monthly_switch), color = AppText, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Switch(checked = level == Level.MONTHLY, onCheckedChange = null)
            }
        }
    }
}
