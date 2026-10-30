package com.music.spotui.ui.immersive.people

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import app.myspoti.api.UserRef
import app.myspoti.engine.R
import app.myspoti.host.android.MyspotiRuntime
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.components.ImmersiveListRow
import com.music.spotui.ui.immersive.components.ImmersiveMessageState
import com.music.spotui.ui.immersive.components.immersiveSongListSkeleton
import com.music.spotui.ui.immersive.podcast.ImmersiveOverscrollHost
import com.music.spotui.ui.immersive.podcast.rowPlacement
import com.music.spotui.ui.immersive.shell.ImmersiveTopBar
import com.music.spotui.ui.immersive.shell.immersiveHazeSource
import com.music.spotui.ui.immersive.shell.immersiveLargeTitle
import com.music.spotui.ui.immersive.shell.immersiveTopContentPadding
import com.music.spotui.ui.navigation.profileRoute

/**
 * The people who follow a user, or the people that user follows, in the Immersive look: a large
 * title over a list of round pictures and names; a tap opens that person's profile. Same data as
 * the Classic list.
 */
@Composable
fun ImmersiveFollowListScreen(navController: NavController, userId: String, followers: Boolean) {
    var people by remember(userId, followers) { mutableStateOf<List<UserRef>?>(null) }
    LaunchedEffect(userId, followers) {
        val uri = "spotify:user:$userId"
        people = runCatching {
            if (followers) MyspotiRuntime.api.users.followers(uri) else MyspotiRuntime.api.users.following(uri)
        }.getOrDefault(emptyList())
    }
    val list = people
    val listState = rememberLazyListState()
    val title = stringResource(if (followers) R.string.profile_followers_title else R.string.profile_following_title)

    ImmersiveOverscrollHost {
        Box(Modifier.fillMaxSize().background(Immersive.Page)) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = immersiveTopContentPadding(), bottom = Immersive.BottomChrome),
                modifier = Modifier.fillMaxSize().immersiveHazeSource(),
            ) {
                immersiveLargeTitle(title = title)
                when {
                    list == null -> immersiveSongListSkeleton(count = 8, keyPrefix = "skeleton:follow", circular = true)
                    list.isEmpty() -> item(key = "empty") { ImmersiveMessageState(stringResource(R.string.profile_list_empty)) }
                    else -> items(list, key = { it.uri }) { u ->
                        ImmersiveListRow(
                            title = u.name,
                            subtitle = null,
                            imageUrl = u.imageUrl,
                            round = true,
                            onClick = { navController.navigate(profileRoute(u.uri.substringAfterLast(':'), u.name)) },
                            modifier = rowPlacement(),
                        )
                    }
                }
            }
            ImmersiveTopBar(title = title, listState = listState, onBack = { navController.navigateUp() })
        }
    }
}
