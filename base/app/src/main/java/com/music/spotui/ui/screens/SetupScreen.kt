package com.music.spotui.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.engine.R
import app.myspoti.power.BatteryExemption
import com.music.spotui.data.local.LocalMusic
import com.music.spotui.ui.theme.AppBackground

/**
 * Shown once after signing in: what Myspoti needs from the phone, each with its own button.
 * Nothing is required; "Continue" goes on with whatever was allowed.
 */
object Setup {
    private const val KEY = "SetupDone"
    /** The permissions step, or the sound quality one (new in 2.0: shown once after the update too). */
    fun needed(context: Context) = !permissionsDone(context) || !com.music.spotui.data.preferences.isQualityChosen(context)
    fun permissionsDone(context: Context) = context.getSharedPreferences("myspoti_prefs", Context.MODE_PRIVATE).getBoolean(KEY, false)
    fun done(context: Context) = context.getSharedPreferences("myspoti_prefs", Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
}

private fun granted(context: Context, permission: String) =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

@Composable
fun SetupScreen(onDone: () -> Unit, onSpotiflac: () -> Unit = {}) {
    val context = LocalContext.current
    // Re-read when coming back from a system dialog or the settings.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(800); tick++ } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    // First the sound quality, then the permissions (skipped when done before an update).
    var qualityStep by remember { mutableStateOf(!com.music.spotui.data.preferences.isQualityChosen(context)) }
    if (qualityStep) {
        QualityStep(onNext = {
            com.music.spotui.data.preferences.setQualityChosen(context)
            if (Setup.permissionsDone(context)) onDone() else qualityStep = false
        })
        return
    }

    Column(
        Modifier.fillMaxSize().background(AppBackground).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.setup_title), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.setup_subtitle), color = Color(0xFFB3B3B3), fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        @Suppress("UNUSED_EXPRESSION") tick
        if (Build.VERSION.SDK_INT >= 33) SetupRow(
            stringResource(R.string.setup_notifications_title), stringResource(R.string.setup_notifications_detail),
            granted(context, Manifest.permission.POST_NOTIFICATIONS),
        ) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        SetupRow(
            stringResource(R.string.setup_local_title), stringResource(R.string.setup_local_detail),
            LocalMusic.hasPermission(context),
        ) { launcher.launch(LocalMusic.permission) }
        if (Build.VERSION.SDK_INT >= 31) SetupRow(
            stringResource(R.string.setup_bluetooth_title), stringResource(R.string.setup_bluetooth_detail),
            granted(context, Manifest.permission.BLUETOOTH_CONNECT),
        ) { launcher.launch(Manifest.permission.BLUETOOTH_CONNECT) }
        SetupRow(
            stringResource(R.string.setup_battery_title), stringResource(R.string.setup_battery_detail),
            BatteryExemption.isExempt(context),
        ) { BatteryExemption.request(context) }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { Setup.done(context); onDone() },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954), contentColor = Color.Black),
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) { Text(stringResource(R.string.setup_continue), fontWeight = FontWeight.Bold, fontSize = 16.sp) }
    }
}

/** Onboarding, step one: how Myspoti streams and saves music (changed later in Settings). */
@Composable
private fun QualityStep(onNext: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(AppBackground).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.setup_quality_title), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp))
        Text(stringResource(R.string.setup_quality_subtitle), color = Color(0xFFB3B3B3), fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 20.dp))
        com.music.spotui.ui.components.StreamingQualityPicker(Modifier.padding(top = 8.dp))
        com.music.spotui.ui.components.DownloadQualityPicker()
        Button(
            onClick = onNext,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954), contentColor = Color.Black),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(50.dp),
        ) { Text(stringResource(R.string.setup_continue), fontWeight = FontWeight.Bold, fontSize = 16.sp) }
    }
}

@Composable
private fun SetupRow(title: String, detail: String, on: Boolean, onEnable: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp)).padding(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Color(0xFFB3B3B3), fontSize = 13.sp)
        }
        if (on) Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF1DB954), modifier = Modifier.padding(start = 12.dp).size(28.dp))
        else TextButton(onClick = onEnable) { Text(stringResource(R.string.setup_enable), color = Color(0xFF1DB954), fontWeight = FontWeight.Bold) }
    }
}
