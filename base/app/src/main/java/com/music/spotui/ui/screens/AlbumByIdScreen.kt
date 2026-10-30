package com.music.spotui.ui.screens

import com.music.spotui.ui.theme.AppText

import app.myspoti.engine.R

import androidx.compose.ui.res.stringResource

import com.music.spotui.ui.components.SkeletonKind
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.spotui.ui.components.ErrorRetry
import com.music.spotui.ui.components.pressable
import androidx.navigation.NavController
import com.music.spotui.di.Graph
import com.music.spotui.ui.components.Loader

/** Opens the album page by Spotify id, resolving it from the name only when needed. */
@Composable
fun AlbumByIdScreen(navController: NavController, name: String, artist: String, id: String) {
    var resolved by remember(name, artist, id) { mutableStateOf(id.ifBlank { null }) }
    var failed by remember { mutableStateOf(false) }
    // Bumped by Try again: the look-up runs once more.
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(name, artist, id, attempt) {
        if (resolved == null) {
            resolved = Graph.api.resolveAlbumId(name, artist)
            if (resolved == null) failed = true
        }
    }
    val albumId = resolved
    when {
        albumId != null -> ContextScreen(navController, type = "album", id = albumId)
        failed -> Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.accessibility_navigate_back), tint = AppText,
                modifier = Modifier.pressable(0.96f) { navController.navigateUp() }.padding(16.dp).size(26.dp),
            )
            ErrorRetry(stringResource(R.string.album_not_found)) { failed = false; attempt++ }
        }
        else -> Loader(SkeletonKind.Header)
    }
}
