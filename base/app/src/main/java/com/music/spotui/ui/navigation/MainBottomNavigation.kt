package com.music.spotui.ui.navigation

import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.compose.ui.res.stringResource
import app.myspoti.engine.R

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.music.spotui.ui.components.MiniPlayer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

import androidx.compose.foundation.layout.navigationBarsPadding

/**
 * What re-tapping the tab you are already on does in the official app: Home and Library
 * scroll their list back to the top without reloading anything, Search focuses its field.
 * The bar only sees the tap; the list state and the text field live inside the screens,
 * so the tap is published here and each screen collects its own event.
 */
object TabReselect {
    // replay = 0 + a one-slot buffer: a tap is never held back, and a tap that lands while
    // the screen is not composed is simply dropped rather than replayed on the next visit.
    private val _home = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(
        extraBufferCapacity = 1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    private val _library = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(
        extraBufferCapacity = 1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    private val _search = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(
        extraBufferCapacity = 1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    val home: kotlinx.coroutines.flow.SharedFlow<Unit> = _home
    val library: kotlinx.coroutines.flow.SharedFlow<Unit> = _library
    val search: kotlinx.coroutines.flow.SharedFlow<Unit> = _search

    fun emit(route: String) {
        when (route) {
            Routes.Home.route -> _home.tryEmit(Unit)
            Routes.Library.route -> _library.tryEmit(Unit)
            Routes.Search.route -> _search.tryEmit(Unit)
        }
    }
}

class NoRippleInteractionSource : MutableInteractionSource {

    override val interactions: Flow<Interaction> = emptyFlow()

    override suspend fun emit(interaction: Interaction) {}

    override fun tryEmit(interaction: Interaction) = true
}

@Composable
fun MainBottomNavigation(navController: NavHostController, bottomBarState: MutableState<Boolean>, bottomBarPlayerState : MutableState<Boolean>) {

    val navItems = listOf(
        Routes.Home,
        Routes.Search,
        Routes.Library
    )
    // Visibility follows the screen actually shown (not flags each screen sets,
    // which raced during transitions and left the mini player over the full one).
    val shownRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    // The SpotiFLAC verification too: under the bar its retry and skip buttons were out of reach.
    val fullScreen = shownRoute == Routes.Player.route || shownRoute == Routes.Queue.route || shownRoute == Routes.Spotiflac.route
    val hasTrack = com.music.spotui.di.Graph.currentSongState.title.value.isNotBlank()
    // No motion of its own: the bar sits above the pages, so sliding it while the
    // player rises (or collapses behind it) made one gesture look like two.
    AnimatedVisibility(
        visible = !fullScreen,
        enter = androidx.compose.animation.EnterTransition.None,
        exit = androidx.compose.animation.ExitTransition.None,
        content = {
            Box(
                contentAlignment = Alignment.BottomCenter,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (com.music.spotui.data.preferences.SolidNavBar.on) Modifier.background(Color(0xFF121212))
                        else Modifier.background(Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.36f to Color(0x99000000),
                            0.72f to Color(0xEB000000),
                            1f to Color(0xFF000000),
                        ))
                    )
            ) {

                Column(modifier = Modifier.navigationBarsPadding()) {

                    AnimatedVisibility(
                        visible = hasTrack,
                        enter = slideInVertically(initialOffsetY = { it }),
                        exit = slideOutVertically(targetOffsetY = { it }),
                        content = {
                            MiniPlayer(navController)
                        }
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                    val navStack by navController.currentBackStackEntryAsState()
                    val currentRoute = navStack?.destination?.route
                    // The tab the user is in, even on one of its sub-pages (album, artist…).
                    var selectedTab by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(Routes.Home.route) }
                    if (currentRoute in navItems.map { it.route }) selectedTab = currentRoute!!

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(NavBarHeight)
                            .padding(top = 6.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        navItems.forEach { item ->
                            val isSelected = selectedTab == item.route
                            val iconDrawable = when (item.route) {
                                Routes.Home.route -> if (isSelected) R.drawable.ic_spot_home_filled else R.drawable.ic_spot_home_outline
                                Routes.Search.route -> if (isSelected) R.drawable.ic_spot_search_filled else R.drawable.ic_spot_search_outline
                                Routes.Library.route -> if (isSelected) R.drawable.ic_spot_library_filled else R.drawable.ic_spot_library_outline
                                else -> item.icon
                            }
                            NavBarTab(
                                iconRes = iconDrawable,
                                label = if (item.labelRes != 0) stringResource(item.labelRes) else item.label,
                                selected = isSelected,
                                onClick = {
                                    if (item.route == selectedTab) {
                                        if (currentRoute == item.route) {
                                            TabReselect.emit(item.route)
                                        } else if (!navController.popBackStack(item.route, inclusive = false)) {
                                            navController.navigate(item.route) { launchSingleTop = true }
                                        }
                                    } else {
                                        selectedTab = item.route
                                        navController.navigate(item.route) {
                                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = item.route != Routes.Home.route
                                        }
                                    }
                                },
                            )
                        }
                    }

                }

            }
        }
    )
}

// Measured from Screenshot 3: 22.dp icon, 4.dp gap, 11.sp label, #B3B3B3 idle / #FFFFFF active.
private val NavBarHeight = 56.dp
private val NavIconSize = 22.dp

@Composable
private fun RowScope.NavBarTab(
    iconRes: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (selected) Color.White else Color(0xFFB3B3B3)
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = label,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(NavIconSize),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                color = tint,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}
