package com.music.spotui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import com.music.spotui.ui.components.pressable
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.graphicsLayer
import android.annotation.SuppressLint
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.music.spotui.ui.navigation.MainBottomNavigation
import com.music.spotui.ui.navigation.MyNavHost
import com.music.spotui.ui.navigation.Routes


@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun App() {
    val bottomBarState = rememberSaveable { (mutableStateOf(true)) }
    val bottomBarPlayerState = rememberSaveable { (mutableStateOf(true)) }
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()

    // Control TopBar and BottomBar: on a route change (writing state during composition
    // forces an extra recomposition pass).
    val route = navBackStackEntry?.destination?.route
    LaunchedEffect(route) {
        when (route) {
            Routes.Home.route, Routes.Search.route, Routes.Library.route -> bottomBarState.value = true
            Routes.Album.route -> bottomBarState.value = false
        }
    }

    // UpdatePrompt and SupportPrompt disabled so no non-Spotify dialogs interrupt the experience.

    val drawerOpen by com.music.spotui.ui.components.ProfileDrawerState.isOpen
    val drawerFraction by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (drawerOpen) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(240),
        label = "drawerSlide",
    )
    if (drawerOpen) {
        androidx.activity.compose.BackHandler {
            com.music.spotui.ui.components.ProfileDrawerState.close()
        }
    }

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val drawerWidthPx = constraints.maxWidth * 0.90f
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxSize()
                .let { mod ->
                    if (drawerFraction > 0f) {
                        mod.then(
                            androidx.compose.ui.Modifier.graphicsLayer {
                                translationX = drawerWidthPx * drawerFraction
                            }
                        )
                    } else mod
                }
        ) {
            Scaffold(
                modifier = Modifier,
                bottomBar = {
                    MainBottomNavigation(navController = navController, bottomBarState = bottomBarState, bottomBarPlayerState)
                }
            ) {
                MyNavHost(navHostController = navController, bottomBarState = bottomBarState, bottomBarPlayerState)
            }
            if (drawerFraction > 0f) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f * drawerFraction))
                        .clickable(
                            interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                        ) { com.music.spotui.ui.components.ProfileDrawerState.close() }
                )
            }
        }
        if (drawerFraction > 0f) {
            com.music.spotui.ui.components.ProfileDrawerContent(
                navController = navController,
                modifier = Modifier
                    .fillMaxWidth(0.90f)
                    .let { mod ->
                        mod.then(
                            androidx.compose.ui.Modifier.graphicsLayer {
                                translationX = -drawerWidthPx * (1f - drawerFraction)
                            }
                        )
                    },
            )
        }
        // Over the pages and the bottom bar: pulling it down shows them, already in place.
        com.music.spotui.ui.navigation.PlayerOverlay(navController)
    }
}



/** The update card is on screen: the donation reminder waits for another launch. */
private val updateCardOpen = mutableStateOf(false)

/**
 * The release the update card shows. The launch check fills it; so does the manual check in
 * Settings, which is why it is not private to [UpdatePrompt].
 */
internal val updateRelease = mutableStateOf<com.music.spotui.data.api.UpdateChecker.Release?>(null)

/**
 * The donation reminder (see SupportReminder): a card a few seconds after launch, only
 * when due, signed in, and no update card is open. "No thanks" closes it for 2 weeks.
 */
@Composable
private fun SupportPrompt() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var show by androidx.compose.runtime.remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(12_000)
        val signedIn = context.getSharedPreferences("myspoti_prefs", android.content.Context.MODE_PRIVATE).getBoolean("LoggedIn", false)
        show = signedIn && !updateCardOpen.value && com.music.spotui.data.api.SupportReminder.isDue(context)
    }
    if (!show) return
    val close = { com.music.spotui.data.api.SupportReminder.shown(context); show = false }
    SupportDialog(
        supporter = com.music.spotui.data.api.SupportReminder.level != com.music.spotui.data.api.SupportReminder.Level.NONE,
        onSupport = { close(); com.music.spotui.data.api.SupportReminder.open(context) },
        onSponsor = { close(); com.music.spotui.data.api.SupportReminder.openSponsors(context) },
        onDecline = close,
        onSupported = { level -> com.music.spotui.data.api.SupportReminder.setLevel(context, level); close() },
    )
}

/**
 * The reminder card: a request, or a thank-you for someone who already gave. "I've
 * supported" (on trust) asks once or monthly, and unlocks the supporter thank-yous.
 */
@Composable
private fun SupportDialog(supporter: Boolean, onSupport: () -> Unit, onSponsor: () -> Unit, onDecline: () -> Unit, onSupported: (com.music.spotui.data.api.SupportReminder.Level) -> Unit) {
    var choosing by androidx.compose.runtime.remember { mutableStateOf(false) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDecline) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .background(androidx.compose.ui.graphics.Color(0xFF242424))
                .padding(horizontal = 24.dp, vertical = 28.dp),
        ) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(app.myspoti.engine.R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(72.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                    .background(androidx.compose.ui.graphics.Color(0xFF181818)),
            )
            androidx.compose.material3.Text(
                androidx.compose.ui.res.stringResource(if (supporter) app.myspoti.engine.R.string.supporter_thanks_title else app.myspoti.engine.R.string.support_title),
                color = androidx.compose.ui.graphics.Color.White, fontFamily = com.music.spotui.ui.theme.AppSans,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 20.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = 18.dp),
            )
            androidx.compose.material3.Text(
                androidx.compose.ui.res.stringResource(if (supporter) app.myspoti.engine.R.string.supporter_thanks_body else app.myspoti.engine.R.string.support_body),
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f),
                fontFamily = com.music.spotui.ui.theme.AppSans, fontSize = 14.sp, lineHeight = 20.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
            androidx.compose.foundation.layout.Box(
                contentAlignment = androidx.compose.ui.Alignment.Center,
                modifier = Modifier
                    .padding(top = 24.dp)
                    .fillMaxWidth()
                    .height(48.dp)
                    .pressable(0.96f, onClick = onSupport)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                    .background(com.music.spotui.ui.theme.AppAccent),
            ) {
                androidx.compose.material3.Text(
                    androidx.compose.ui.res.stringResource(if (supporter) app.myspoti.engine.R.string.support_again else app.myspoti.engine.R.string.support_action),
                    color = androidx.compose.ui.graphics.Color.Black, fontFamily = com.music.spotui.ui.theme.AppSans,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 16.sp,
                )
            }
            com.music.spotui.ui.components.GitHubSponsorButton(Modifier.padding(top = 10.dp), onClick = onSponsor)
            androidx.compose.material3.Text(
                androidx.compose.ui.res.stringResource(app.myspoti.engine.R.string.support_decline),
                color = androidx.compose.ui.graphics.Color.White, fontFamily = com.music.spotui.ui.theme.AppSans,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 15.sp,
                modifier = Modifier.padding(top = 8.dp).pressable(0.96f, onClick = onDecline).padding(horizontal = 24.dp, vertical = 12.dp),
            )
            if (!supporter && com.music.spotui.data.api.SupportReminder.PERKS_ENABLED) {
                if (choosing) androidx.compose.foundation.layout.Column(
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 8.dp),
                ) { com.music.spotui.ui.components.SupportChoice(onChosen = onSupported) }
                else androidx.compose.material3.Text(
                    androidx.compose.ui.res.stringResource(app.myspoti.engine.R.string.supporter_i_supported),
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f), fontFamily = com.music.spotui.ui.theme.AppSans,
                    fontSize = 13.sp,
                    modifier = Modifier.pressable(0.96f) { choosing = true }.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** "New version" dialog: checked a few seconds after launch (not on the startup path). */
@Composable
private fun UpdatePrompt() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var release by updateRelease
    androidx.compose.runtime.LaunchedEffect(Unit) {
        // Turned off in Settings: no check at launch. The manual check there still asks.
        if (!com.music.spotui.data.preferences.AutoUpdateCheckPref.on) return@LaunchedEffect
        kotlinx.coroutines.delay(5_000)
        release = com.music.spotui.data.api.UpdateChecker.check(context)
    }
    updateCardOpen.value = release != null
    val r = release ?: return
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val updater = com.music.spotui.data.api.AppUpdater
    val state by updater.state.collectAsState()
    val start = { scope.launch { updater.update(context, r) }; Unit }
    // Back from the "Install unknown apps" page with the switch on: carry on by itself.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME &&
                updater.state.value == com.music.spotui.data.api.AppUpdater.State.NeedsPermission &&
                updater.canInstall(context)) start()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val dismiss = { updater.reset(); com.music.spotui.data.api.UpdateChecker.dismiss(context, r); release = null }
    val res = context.resources
    val status = when (val s = state) {
        is com.music.spotui.data.api.AppUpdater.State.Downloading ->
            res.getString(app.myspoti.engine.R.string.update_downloading, (s.progress.coerceAtLeast(0f) * 100).toInt())
        com.music.spotui.data.api.AppUpdater.State.Installing -> res.getString(app.myspoti.engine.R.string.update_installing)
        com.music.spotui.data.api.AppUpdater.State.NeedsPermission -> res.getString(app.myspoti.engine.R.string.update_needs_permission)
        is com.music.spotui.data.api.AppUpdater.State.Failed -> res.getString(app.myspoti.engine.R.string.update_failed, s.message)
        com.music.spotui.data.api.AppUpdater.State.Idle -> null
    }
    val busy = state is com.music.spotui.data.api.AppUpdater.State.Downloading || state == com.music.spotui.data.api.AppUpdater.State.Installing
    UpdateDialog(
        version = r.version,
        notes = r.notes,
        status = status,
        progress = (state as? com.music.spotui.data.api.AppUpdater.State.Downloading)?.progress,
        button = res.getString(when (state) {
            com.music.spotui.data.api.AppUpdater.State.NeedsPermission -> app.myspoti.engine.R.string.update_allow
            is com.music.spotui.data.api.AppUpdater.State.Failed -> app.myspoti.engine.R.string.update_retry
            else -> app.myspoti.engine.R.string.version_sunsetting_dialog_cta_update
        }),
        onUpdate = {
            when {
                busy -> Unit
                // A release without an APK: its page, as before.
                r.apkUrl.isBlank() -> {
                    release = null
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(r.pageUrl))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                state == com.music.spotui.data.api.AppUpdater.State.NeedsPermission && !updater.canInstall(context) ->
                    updater.openInstallSettings(context)
                else -> start()
            }
        },
        onLater = { if (!busy) dismiss() },
    )
}

/** The update card, in the app's own style (dark sheet, green pill), not a system dialog. */
@Composable
private fun UpdateDialog(
    version: String, notes: String, status: String?, progress: Float?, button: String,
    onUpdate: () -> Unit, onLater: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onLater) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .background(androidx.compose.ui.graphics.Color(0xFF242424))
                .padding(horizontal = 24.dp, vertical = 28.dp),
        ) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(app.myspoti.engine.R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(72.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                    .background(androidx.compose.ui.graphics.Color(0xFF181818)),
            )
            androidx.compose.material3.Text(
                androidx.compose.ui.res.stringResource(app.myspoti.engine.R.string.main_update_available),
                color = androidx.compose.ui.graphics.Color.White, fontFamily = com.music.spotui.ui.theme.AppSans,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 20.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = 18.dp),
            )
            androidx.compose.material3.Text(
                "Spotifusion $version", color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f),
                fontFamily = com.music.spotui.ui.theme.AppSans, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp),
            )
            // What's new (the release's CHANGELOG section), scrollable if long.
            if (notes.isNotBlank()) androidx.compose.material3.Text(
                notes, color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f),
                fontFamily = com.music.spotui.ui.theme.AppSans, fontSize = 14.sp, lineHeight = 20.sp,
                modifier = Modifier.padding(top = 16.dp).fillMaxWidth().heightIn(max = 240.dp)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState()),
            )
            // The download and install, under the notes.
            if (status != null) androidx.compose.material3.Text(
                status, color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f),
                fontFamily = com.music.spotui.ui.theme.AppSans, fontSize = 14.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp).fillMaxWidth(),
            )
            if (progress != null) {
                val bar = Modifier.padding(top = 10.dp).fillMaxWidth().height(4.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                if (progress < 0f) androidx.compose.material3.LinearProgressIndicator(
                    modifier = bar, color = com.music.spotui.ui.theme.AppAccent, trackColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.15f))
                else androidx.compose.material3.LinearProgressIndicator(
                    progress = { progress }, modifier = bar, color = com.music.spotui.ui.theme.AppAccent,
                    trackColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.15f), drawStopIndicator = {})
            }
            androidx.compose.foundation.layout.Box(
                contentAlignment = androidx.compose.ui.Alignment.Center,
                modifier = Modifier
                    .padding(top = 24.dp)
                    .fillMaxWidth()
                    .height(48.dp)
                    .pressable(0.96f, onClick = onUpdate)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                    .background(com.music.spotui.ui.theme.AppAccent),
            ) {
                androidx.compose.material3.Text(
                    button,
                    color = androidx.compose.ui.graphics.Color.Black, fontFamily = com.music.spotui.ui.theme.AppSans,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 16.sp,
                )
            }
            androidx.compose.material3.Text(
                androidx.compose.ui.res.stringResource(app.myspoti.engine.R.string.age_assurance_intro_cta_not_now),
                color = androidx.compose.ui.graphics.Color.White, fontFamily = com.music.spotui.ui.theme.AppSans,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 15.sp,
                modifier = Modifier.padding(top = 8.dp).pressable(0.96f, onClick = onLater).padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    }
}
