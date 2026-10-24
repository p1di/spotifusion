package com.music.spotui.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.myspoti.api.UserProfile
import app.myspoti.api.best
import app.myspoti.engine.R
import app.myspoti.host.android.MyspotiRuntime
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.CrossFade
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.data.api.ProfileCache
import com.music.spotui.ui.components.edgeSwipeBack
import com.music.spotui.ui.components.pressable
import com.music.spotui.ui.components.spotifyImageUrl
import com.music.spotui.ui.navigation.playlistRoute
import com.music.spotui.ui.theme.AppAccent
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.di.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A Spotify user's profile, laid out like the official mobile app: top colored gradient,
 * left-aligned profile avatar with name + white clickable follower/following counts on the right,
 * Edit Profile / Follow button, and public playlists.
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun ProfileScreen(navController: NavController, userId: String, userName: String = "") {
    val context = LocalContext.current
    val uri = "spotify:user:$userId"
    var profile by remember(userId) { mutableStateOf<UserProfile?>(null) }
    var failed by remember(userId) { mutableStateOf(false) }
    var following by remember(userId) { mutableStateOf<Boolean?>(null) }
    val me = ProfileCache.id == userId || userId.isBlank()
    val scope = rememberCoroutineScope()
    var attempt by remember(userId) { mutableStateOf(0) }
    var showEditDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        ProfileCache.loadCustomOverrides(context)
    }

    LaunchedEffect(userId, attempt) {
        failed = false
        runCatching { MyspotiRuntime.api.users.profile(uri) }
            .onSuccess { profile = it }.onFailure { failed = true }
        if (!me) following = runCatching { MyspotiRuntime.api.users.isFollowing(listOf(uri)).single() }.getOrNull()
    }

    val p = profile
    val rawSpotifyAvatar = spotifyImageUrl(p?.images?.best(300)?.url)
    val avatarModel = if (me && !ProfileCache.customImageUrl.isNullOrBlank()) {
        ProfileCache.customImageUrl
    } else {
        rawSpotifyAvatar
    }
    val displayName = if (me && !ProfileCache.customName.isNullOrBlank()) {
        ProfileCache.customName!!
    } else {
        p?.name ?: userName.ifBlank { ProfileCache.name.orEmpty() }
    }

    var tintTarget by remember(avatarModel) {
        mutableStateOf(avatarModel?.let { Palette.cachedSecond(it) } ?: Color(0xFF425468))
    }
    LaunchedEffect(avatarModel) {
        if (!avatarModel.isNullOrBlank()) {
            Palette().extractSecondColorFromCoverUrl(context, avatarModel) { tintTarget = it }
        }
    }
    val dominantTint by androidx.compose.animation.animateColorAsState(
        tintTarget,
        androidx.compose.animation.core.tween(350),
        label = "profile_tint",
    )

    if (showEditDialog) {
        EditProfileDialog(
            initialName = displayName,
            initialImageUri = avatarModel.orEmpty(),
            onDismiss = { showEditDialog = false },
            onSave = { newName, newImageUri ->
                ProfileCache.saveCustomProfile(context, newName, newImageUri)
                showEditDialog = false
            },
            onReset = {
                ProfileCache.saveCustomProfile(context, null, null)
                showEditDialog = false
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground)
            .edgeSwipeBack { navController.navigateUp() }
    ) {
        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = com.music.spotui.ui.components.bottomBarsSpace()),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    lerp(dominantTint, Color.Black, 0.18f),
                                    lerp(dominantTint, AppBackground, 0.55f),
                                    AppBackground,
                                )
                            )
                        )
                        .statusBarsPadding()
                        .padding(bottom = 12.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        stringResource(R.string.accessibility_navigate_back),
                        tint = Color.White,
                        modifier = Modifier
                            .pressable(0.96f) { navController.navigateUp() }
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                            .size(26.dp),
                    )
                    // Left-aligned profile avatar + right-side name and white follower/following numbers
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        GlideImage(
                            transition = CrossFade,
                            model = avatarModel,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            failure = placeholder(R.drawable.placeholder),
                            loading = placeholder(R.drawable.placeholder),
                            modifier = Modifier
                                .size(108.dp)
                                .clip(CircleShape)
                                .then(if (me) Modifier.clickable { showEditDialog = true } else Modifier),
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            com.music.spotui.ui.components.FitText(
                                text = displayName,
                                fontSize = 26.sp,
                                minSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                            )
                            if (p != null) {
                                Row(
                                    modifier = Modifier.padding(top = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    val followersCount = p.followers ?: 0
                                    val followingCount = p.following ?: 0
                                    val followersLabel = pluralStringResource(R.plurals.profile_followers, followersCount, followersCount)
                                    val followingLabel = stringResource(R.string.profile_following_count, followingCount)

                                    Text(
                                        text = highlightNumberWhite(followersLabel, followersCount),
                                        fontSize = 13.sp,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .pressable(0.96f) { navController.navigate(followListRoute(userId, followers = true)) }
                                            .padding(vertical = 4.dp),
                                    )
                                    Text(
                                        text = " • ",
                                        color = Color(0xFFB3B3B3),
                                        fontSize = 13.sp,
                                    )
                                    Text(
                                        text = highlightNumberWhite(followingLabel, followingCount),
                                        fontSize = 13.sp,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .pressable(0.96f) { navController.navigate(followListRoute(userId, followers = false)) }
                                            .padding(vertical = 4.dp),
                                    )
                                }
                            }
                        }
                    }

                    // Action row: "Edit" pill on own profile, or "Follow"/"Following" pill on other profiles
                    val f = following
                    if (me) {
                        Spacer(Modifier.height(14.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .height(34.dp)
                                    .clip(RoundedCornerShape(50))
                                    .border(1.dp, Color(0xFF727272), RoundedCornerShape(50))
                                    .pressable(0.96f) { showEditDialog = true }
                                    .padding(horizontal = 20.dp),
                            ) {
                                Text(
                                    text = "Edit",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    } else if (f != null) {
                        Spacer(Modifier.height(14.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        ) {
                            Text(
                                stringResource(if (f) R.string.curation_button_following_state_text else R.string.curation_button_follow_state_text),
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .border(1.dp, if (f) Color.White else Color(0xFF727272), RoundedCornerShape(50))
                                    .pressable(0.96f) {
                                        com.music.spotui.di.Haptics.confirm()
                                        following = !f
                                        scope.launch {
                                            runCatching {
                                                if (f) MyspotiRuntime.api.users.unfollow(listOf(uri)) else MyspotiRuntime.api.users.follow(listOf(uri))
                                            }.onFailure { following = f }
                                        }
                                    }
                                    .padding(horizontal = 20.dp, vertical = 7.dp),
                            )
                        }
                    }
                }
            }
            when {
                p == null && !failed -> item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AppAccent)
                    }
                }
                p == null -> item {
                    com.music.spotui.ui.components.ErrorRetry(stringResource(R.string.page_load_failed)) { attempt++ }
                }
                p.publicPlaylists.isNotEmpty() -> {
                    item {
                        Text(
                            stringResource(R.string.profile_public_playlists),
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(16.dp, 12.dp),
                        )
                    }
                    items(p.publicPlaylists, key = { it.uri }) { pl ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Start,
                            modifier = Modifier
                                .fillMaxWidth()
                                .pressable(0.98f) { navController.navigate(playlistRoute(pl.uri.substringAfterLast(':'), pl.name)) }
                                .padding(16.dp, 8.dp),
                        ) {
                            GlideImage(
                                transition = CrossFade,
                                model = spotifyImageUrl(pl.images.best(160)?.url),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                failure = placeholder(R.drawable.placeholder),
                                loading = placeholder(R.drawable.placeholder),
                                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(4.dp)),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(pl.name, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                pl.followers?.let {
                                    Text(
                                        pluralStringResource(R.plurals.profile_followers, it.toInt(), it.toInt()),
                                        color = Color(0xFFB3B3B3),
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Colors the numeric count inside a localized "N followers" / "N following" string white & bold. */
private fun highlightNumberWhite(fullText: String, number: Int) = buildAnnotatedString {
    val numStr = number.toString()
    val idx = fullText.indexOf(numStr)
    if (idx >= 0) {
        if (idx > 0) {
            withStyle(SpanStyle(color = Color(0xFFB3B3B3))) { append(fullText.substring(0, idx)) }
        }
        withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.Bold)) {
            append(numStr)
        }
        val after = idx + numStr.length
        if (after < fullText.length) {
            withStyle(SpanStyle(color = Color(0xFFB3B3B3))) { append(fullText.substring(after)) }
        }
    } else {
        withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.Bold)) { append(fullText) }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun EditProfileDialog(
    initialName: String,
    initialImageUri: String,
    onDismiss: () -> Unit,
    onSave: (String?, String?) -> Unit,
    onReset: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var nameInput by remember { mutableStateOf(initialName) }
    var imageUriInput by remember { mutableStateOf(initialImageUri) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { pickedUri ->
        if (pickedUri != null) {
            scope.launch {
                val savedFileUri = withContext(Dispatchers.IO) {
                    runCatching {
                        val dest = File(context.filesDir, "custom_avatar_${System.currentTimeMillis()}.jpg")
                        context.contentResolver.openInputStream(pickedUri)?.use { input ->
                            dest.outputStream().use { output -> input.copyTo(output) }
                        }
                        android.net.Uri.fromFile(dest).toString()
                    }.getOrNull()
                }
                if (savedFileUri != null) {
                    imageUriInput = savedFileUri
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF282828),
        titleContentColor = Color.White,
        textContentColor = Color.White,
        title = {
            Text("Edit profile", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF181818))
                        .clickable { imagePicker.launch("image/*") },
                ) {
                    GlideImage(
                        transition = CrossFade,
                        model = imageUriInput,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        failure = placeholder(R.drawable.placeholder),
                        loading = placeholder(R.drawable.placeholder),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Change photo",
                    color = AppAccent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { imagePicker.launch("image/*") }.padding(4.dp),
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("Display name", color = Color(0xFFB3B3B3)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = AppAccent,
                        unfocusedBorderColor = Color(0xFF727272),
                        cursorColor = AppAccent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onReset) {
                    Text("Reset to Spotify default", color = Color(0xFFB3B3B3), fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(nameInput, imageUriInput) }) {
                Text("Save", color = AppAccent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = Color.White)
            }
        },
    )
}
