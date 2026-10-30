/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later,
 * app/src/main/java/com/music/bitchord/ui/components/floatingtabbar/FloatingTabBar.kt at edc1e6f.
 * Modified for Myspoti, 2026-10: only the expanded layout is kept (the tab pill and the standalone
 * tab; no inline/accessory states and no shared transitions, the mini player is stacked above the
 * bar by the host), the pill is BitChord's refracting lens while Liquid Glass is on and the plain
 * lifted pill of GlassSelectionPill.kt otherwise, haptics and "reduce animation" come from the
 * system, and the surfaces are Myspoti's glass (immersiveGlassSurface). The minimized (inline)
 * state lives in ImmersiveChrome.kt.
 */

/*
 * FloatingTabBar v1.0.1 by Elyes Mansour
 * https://github.com/elyesmansour/compose-floating-tab-bar
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Vendored from the v1.0.1 tag: the published AAR is compiled against
 * Compose 1.8 and crashes with NoSuchMethodError on
 * SharedTransitionScope.sharedElement under the Compose version this app uses.
 * Compiling the source here keeps it binary-compatible.
 *
 * Local modifications on top of v1.0.1:
 * - Package renamed.
 * - InlineBar/ExpandedBar use Row/Column instead of ConstraintLayout, and the
 *   tab pill + standalone tab cluster is centered (iOS 26 style) instead of
 *   being pinned to opposite screen edges.
 * - The inline/expanded AnimatedContent is center-aligned so the collapse
 *   animation morphs around the center instead of the start edge.
 *
 * Taken from EchoMusicApp/Echo-Music, which carries the three modifications
 * listed above; BitChord's own use of it is in
 * [com.music.bitchord.ui.components.GlassNavBar]. One further change here:
 *
 * - `tabBarContentModifier` is a `@Composable () -> Modifier` factory rather
 *   than a single Modifier value. It is applied to three surfaces of three
 *   different sizes (the tab pill, the circular standalone tab, the accessory),
 *   and a liquid glass Modifier carries a shape-outline cache keyed on the size
 *   it last saw — so one shared instance is invalidated by whichever surface
 *   drew last and re-creates all three outlines every frame. A factory gives
 *   each surface its own.
 * - The expanded tab group's selection pill is a lifted glass lens in motion
 *   (see [GlassSelectionPill]): a tap inflates it past the bar, carries it to
 *   the tab on a spring, squashes it with its acceleration, and sheds the glass
 *   back into a flat fill on landing. Horizontal drags pick it up the same way,
 *   resist at the ends, tick across tab boundaries, and settle on release.
 */

package com.music.spotui.ui.immersive.shell

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import com.music.spotui.ui.immersive.shell.backdrop.backdrops.layerBackdrop
import com.music.spotui.ui.immersive.shell.backdrop.backdrops.rememberLayerBackdrop
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Hoisted so the default is one stable lambda rather than a new one per call. */
private val DefaultTabBarContentModifier: @Composable () -> Modifier = { Modifier }

/**
 * The spring the selection glyphs travel on.
 *
 * Damping 0.72 rather than half-damped: it settles on the second approach — one soft pass
 * beyond the mark and done — which is the difference between bouncy and alive.
 */
private val GlassSpring = com.music.spotui.ui.immersive.motion.ImmersiveMotion.glassSpring<Float>()

/**
 * A floating tab bar: a pill of tabs, and optionally one standalone tab in a circle of its own
 * beside it. The selected tab's pill is a physical object (see [GlassPillMotion]).
 *
 * @param selectedTabKey the key of the currently selected tab (a [FloatingTabBarScope.tab] or the
 * standalone one — with the standalone tab selected the pill is hidden)
 * @param modifier the modifier to be applied to the tab bar
 * @param tabBarContentModifier surface of the tab pill and of the standalone tab, applied after the
 * default styling (background, clip) but before any content padding. A factory so each surface gets
 * its own instance.
 * @param reduceMotion every move lands at once, with no lift
 * @param contentKey optional key that when changed retriggers the content lambda
 * @param content the content defining the tabs
 */
@Composable
internal fun FloatingTabBar(
    selectedTabKey: Any?,
    modifier: Modifier = Modifier,
    tabBarContentModifier: @Composable () -> Modifier = DefaultTabBarContentModifier,
    colors: FloatingTabBarColors = FloatingTabBarDefaults.colors(),
    shapes: FloatingTabBarShapes = FloatingTabBarDefaults.shapes(),
    sizes: FloatingTabBarSizes = FloatingTabBarDefaults.sizes(),
    reduceMotion: Boolean = false,
    contentKey: Any? = null,
    content: FloatingTabBarScope.() -> Unit,
) {
    val scope = remember(contentKey) { FloatingTabBarScopeImpl().apply { content() } }
    val standaloneTab = scope.standaloneTab
    val hasTabGroup = scope.tabs.isNotEmpty()

    // Full width, and the tab pill takes whatever the standalone tab leaves, so the bar measures
    // like a plain nav bar: the pill is given a width to divide rather than growing to fit labels.
    Row(
        horizontalArrangement = Arrangement.spacedBy(sizes.componentSpacing),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Max),
    ) {
        if (hasTabGroup) {
            ExpandedTabs(
                scope = scope,
                selectedTabKey = selectedTabKey,
                shapes = shapes,
                sizes = sizes,
                colors = colors,
                reduceMotion = reduceMotion,
                tabBarContentModifier = tabBarContentModifier,
                modifier = Modifier.weight(1f),
            )
        }
        if (standaloneTab != null) {
            ExpandedStandaloneTab(
                standaloneTab = standaloneTab,
                shapes = shapes,
                colors = colors,
                tabBarContentModifier = tabBarContentModifier,
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f),
            )
        }
    }
}

internal interface FloatingTabBarScope {
    /**
     * Adds a regular tab to the floating tab bar.
     *
     * @param key Unique identifier for the tab
     * @param title Composable content for the tab title
     * @param icon Composable content for the tab icon
     * @param onClick Callback invoked when the tab is clicked
     */
    fun tab(
        key: Any,
        title: @Composable () -> Unit,
        icon: @Composable () -> Unit,
        onClick: () -> Unit,
    )

    /**
     * Adds a standalone tab to the floating tab bar.
     *
     * Note: Calling this method more than once will override the previous standalone tab value.
     *
     * @param key Unique identifier for the standalone tab
     * @param icon Composable content for the tab icon
     * @param onClick Callback invoked when the tab is clicked
     */
    fun standaloneTab(
        key: Any,
        icon: @Composable () -> Unit,
        onClick: () -> Unit,
    )
}

@Composable
private fun ExpandedTabs(
    scope: FloatingTabBarScopeImpl,
    selectedTabKey: Any?,
    shapes: FloatingTabBarShapes,
    sizes: FloatingTabBarSizes,
    colors: FloatingTabBarColors,
    reduceMotion: Boolean,
    modifier: Modifier,
    tabBarContentModifier: @Composable () -> Modifier,
) {
    val selectedTabIndex = scope.tabs.indexOfFirst { it.key == selectedTabKey }
    val currentSelectedTabIndex by rememberUpdatedState(selectedTabIndex)
    // The drag handler outlives the scope it was started with (a tab change builds a new one).
    val currentScope by rememberUpdatedState(scope)
    val selectionSpec: AnimationSpec<Float> = if (reduceMotion) snap() else GlassSpring
    val haptics = LocalHapticFeedback.current

    val coroutineScope = rememberCoroutineScope()
    val pill = remember { GlassPillMotion(coroutineScope, selectedTabIndex.coerceAtLeast(0)) }
    pill.reduceMotion = reduceMotion
    // With Liquid Glass on, the lifted pill is a lens refracting the bar's glass and its tabs
    // (recorded below); without it, the plain lifted pill carries the whole motion.
    val page = LocalImmersiveBackdrop.current
    val glassPill = page != null
    val barGlass = rememberLayerBackdrop()
    val tabRow = rememberLayerBackdrop()

    var rowSize by remember { mutableStateOf(IntSize.Zero) }
    var lastHapticTab by remember { mutableIntStateOf(selectedTabIndex) }
    var lastSelectedTabIndex by remember { mutableIntStateOf(selectedTabIndex) }
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val isRtl = layoutDirection == LayoutDirection.Rtl
    val tabCount = scope.tabs.size
    val tabSpacingPx = with(density) { sizes.tabSpacing.toPx() }
    val tabWidthPx = if (rowSize.width > 0 && tabCount > 0) {
        (rowSize.width - tabSpacingPx * (tabCount - 1)) / tabCount
    } else {
        0f
    }
    val tabStepPx = if (rowSize.width > 0 && tabCount > 0) {
        (rowSize.width + tabSpacingPx) / tabCount
    } else {
        0f
    }
    pill.stepDp = tabStepPx / density.density

    // The pill rests in its tab's cell and lifts to stand [PILL_GROW_HEIGHT]
    // taller than the whole bar, keeping the cell's proportions as it grows.
    val contentPadding = sizes.tabBarContentPadding
    val padLeftPx = with(density) { contentPadding.calculateLeftPadding(layoutDirection).toPx() }
    val padTopPx = with(density) { contentPadding.calculateTopPadding().toPx() }
    val padBottomPx = with(density) { contentPadding.calculateBottomPadding().toPx() }
    val rowHeightPx = rowSize.height.toFloat()
    val restSize = Size(tabWidthPx, rowHeightPx)
    val liftedHeightPx = rowHeightPx + padTopPx + padBottomPx +
        with(density) { PILL_GROW_HEIGHT.toPx() }
    val liftedSize = if (rowHeightPx > 0f) {
        Size(liftedHeightPx * tabWidthPx / rowHeightPx, liftedHeightPx)
    } else {
        restSize
    }
    // Where the pill's centre is in the row, for a position in tab steps.
    val pillCenterInRow: (Float) -> Offset = { position ->
        val x = position * tabStepPx + tabWidthPx / 2f
        Offset(if (isRtl) rowSize.width - x else x, rowHeightPx / 2f)
    }
    val showPill = selectedTabIndex >= 0 && tabWidthPx > 0f

    LaunchedEffect(selectedTabIndex) {
        if (selectedTabIndex >= 0) {
            // Back from the standalone tab there is no tab to travel from.
            if (lastSelectedTabIndex < 0) {
                pill.snapTo(selectedTabIndex)
            } else {
                pill.animateTo(selectedTabIndex)
            }
        }
        lastSelectedTabIndex = selectedTabIndex
        lastHapticTab = selectedTabIndex
    }

    // Unclipped: the lifted pill stands past the bar's edges, so it is drawn
    // here, over the bar, rather than inside the bar's clip.
    Box(modifier = modifier) {
        // The bar's glass is exported for the lens to refract.
        CompositionLocalProvider(LocalGlassExport provides if (glassPill) barGlass else null) {
        Box(
            modifier = Modifier
                .background(colors.backgroundColor, shapes.tabBarShape)
                // No clip() ahead of the glass: it clips itself (as the mini player's does), and a
                // clipping layer around the glass's own offscreen effect layer is what differs
                // from the surface that renders correctly.
                .then(tabBarContentModifier())
                .padding(contentPadding),
        ) {
            if (showPill && (!glassPill || pill.isFlat)) {
                FlatSelectionPill(
                    color = colors.indicatorColor,
                    modifier = Modifier
                        .matchParentSize()
                        .pillPlacement(
                            center = { pillCenterInRow(pill.position) },
                            size = { pill.liveSize(restSize, liftedSize) },
                        ),
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(sizes.tabSpacing),
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { rowSize = it }
                    .then(if (glassPill) Modifier.layerBackdrop(tabRow) else Modifier)
                    // Keyed on whether a pill tab is selected too: the bar can be (re)composed with
                    // Search selected (it has no pill), and a handler that returned then would stay
                    // dead after the user came back to Home or Library.
                    .pointerInput(tabStepPx, isRtl, selectedTabIndex >= 0) {
                        if (currentSelectedTabIndex < 0 || tabStepPx <= 0f) return@pointerInput

                        // In tab order, whichever way the row runs.
                        val direction = if (isRtl) -1f else 1f
                        var totalDrag = 0f
                        var dragStartIndex = currentSelectedTabIndex
                        detectHorizontalDragGestures(
                            onDragStart = {
                                totalDrag = 0f
                                dragStartIndex = currentSelectedTabIndex
                                pill.startDrag()
                            },
                            onDragCancel = { pill.release(currentSelectedTabIndex) },
                            onDragEnd = {
                                val absoluteIndexFloat = (dragStartIndex + totalDrag / tabStepPx)
                                    .coerceIn(0f, currentScope.tabs.lastIndex.toFloat())
                                val newIndex = absoluteIndexFloat.roundToInt()
                                pill.release(newIndex)
                                if (newIndex != currentSelectedTabIndex) {
                                    currentScope.tabs[newIndex].onClick()
                                }
                            },
                            onHorizontalDrag = { _, delta ->
                                totalDrag += delta * direction
                                val absoluteIndexFloat = (dragStartIndex + totalDrag / tabStepPx)
                                    .coerceIn(0f, currentScope.tabs.lastIndex.toFloat())
                                pill.dragTo(absoluteIndexFloat)

                                val approximateTab = absoluteIndexFloat.roundToInt()
                                if (approximateTab != lastHapticTab) {
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    lastHapticTab = approximateTab
                                }
                            },
                        )
                    },
            ) {
                scope.tabs.forEach { tab ->
                    val isSelected = tab.key == selectedTabKey
                    val iconScale by animateFloatAsState(
                        targetValue = if (isSelected) 1.08f else 1f,
                        animationSpec = selectionSpec,
                        label = "immersiveTabScale",
                    )
                    Tab(
                        icon = {
                            Box(
                                Modifier.graphicsLayer {
                                    scaleX = iconScale
                                    scaleY = iconScale
                                },
                            ) {
                                tab.icon()
                            }
                        },
                        title = tab.title,
                        modifier = Modifier
                            // An equal share of the pill each, the way the plain nav bar
                            // divides its own width, rather than each tab being as wide as
                            // its label happens to be.
                            .weight(1f)
                            .clip(shapes.tabShape)
                            .clickable(
                                onClick = tab.onClick,
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                            )
                            .padding(sizes.tabExpandedContentPadding),
                    )
                }
            }
        }
        }

        if (showPill && !pill.isFlat) {
            val placement = Modifier
                .matchParentSize()
                .pillPlacement(
                    center = { pillCenterInRow(pill.position) + Offset(padLeftPx, padTopPx) },
                    size = { pill.liveSize(restSize, liftedSize) },
                )
            if (page != null) {
                GlassSelectionPill(
                    motion = pill,
                    fillColor = colors.indicatorColor,
                    page = page,
                    barGlass = barGlass,
                    tabRow = tabRow,
                    modifier = placement,
                )
            } else {
                LiftedSelectionPill(
                    motion = pill,
                    fillColor = colors.indicatorColor,
                    modifier = placement,
                )
            }
        }
    }
}

@Composable
private fun ExpandedStandaloneTab(
    standaloneTab: FloatingTabBarTab,
    shapes: FloatingTabBarShapes,
    colors: FloatingTabBarColors,
    modifier: Modifier,
    tabBarContentModifier: @Composable () -> Modifier,
) {
    Tab(
        icon = standaloneTab.icon,
        title = standaloneTab.title,
        isStandalone = true,
        modifier = modifier
            .background(colors.backgroundColor, shapes.standaloneTabShape)
            .then(tabBarContentModifier())
            .clickable(
                onClick = standaloneTab.onClick,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ),
    )
}

@Composable
private fun Tab(
    icon: @Composable () -> Unit,
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    isStandalone: Boolean = false,
) {
    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        icon()
        if (!isStandalone) {
            title()
        }
    }
}

private class FloatingTabBarScopeImpl : FloatingTabBarScope {
    val tabs = mutableStateListOf<FloatingTabBarTab>()
    var standaloneTab: FloatingTabBarTab? by mutableStateOf(null)
        private set

    override fun tab(
        key: Any,
        title: @Composable () -> Unit,
        icon: @Composable () -> Unit,
        onClick: () -> Unit,
    ) {
        tabs.add(FloatingTabBarTab(key = key, title = title, icon = icon, onClick = onClick))
    }

    override fun standaloneTab(
        key: Any,
        icon: @Composable () -> Unit,
        onClick: () -> Unit,
    ) {
        standaloneTab = FloatingTabBarTab(key = key, title = {}, icon = icon, onClick = onClick)
    }
}

private data class FloatingTabBarTab(
    val key: Any,
    val title: @Composable () -> Unit,
    val icon: @Composable () -> Unit,
    val onClick: () -> Unit,
)

/**
 * Represents the colors used in [FloatingTabBar].
 */
@Immutable
internal data class FloatingTabBarColors(
    val backgroundColor: Color,
    val indicatorColor: Color,
)

/**
 * Represents the shapes used in [FloatingTabBar].
 */
@Immutable
internal data class FloatingTabBarShapes(
    val tabBarShape: Shape,
    val tabShape: Shape,
    val standaloneTabShape: Shape,
)

/**
 * Represents the sizes and spacing used in [FloatingTabBar].
 */
@Immutable
internal data class FloatingTabBarSizes(
    val tabBarContentPadding: PaddingValues,
    val tabExpandedContentPadding: PaddingValues,
    val componentSpacing: Dp,
    val tabSpacing: Dp,
)

/**
 * Contains the default values used by [FloatingTabBar].
 */
internal object FloatingTabBarDefaults {
    /**
     * @param backgroundColor the color used for the tab bar background
     * @param indicatorColor the color used behind the selected tab
     */
    @Composable
    fun colors(
        backgroundColor: Color = Color.Transparent,
        indicatorColor: Color = Color.White.copy(alpha = 0.12f),
    ): FloatingTabBarColors = FloatingTabBarColors(
        backgroundColor = backgroundColor,
        indicatorColor = indicatorColor,
    )

    /**
     * @param tabBarShape the shape used to clip the tab bar
     * @param tabShape the shape used to clip individual tabs
     * @param standaloneTabShape the shape used to clip the standalone tab
     */
    @Composable
    fun shapes(
        tabBarShape: Shape = RoundedCornerShape(100),
        tabShape: Shape = RoundedCornerShape(100),
        standaloneTabShape: Shape = CircleShape,
    ): FloatingTabBarShapes = FloatingTabBarShapes(
        tabBarShape = tabBarShape,
        tabShape = tabShape,
        standaloneTabShape = standaloneTabShape,
    )

    /**
     * @param tabBarContentPadding the padding applied to the tab bar content
     * @param tabExpandedContentPadding the padding applied to each tab
     * @param componentSpacing the spacing between the pill and the standalone tab
     * @param tabSpacing the spacing between tabs
     */
    @Composable
    fun sizes(
        tabBarContentPadding: PaddingValues = PaddingValues(vertical = 4.dp, horizontal = 4.dp),
        tabExpandedContentPadding: PaddingValues = PaddingValues(vertical = 6.dp, horizontal = 20.dp),
        componentSpacing: Dp = 8.dp,
        tabSpacing: Dp = 0.dp,
    ): FloatingTabBarSizes = FloatingTabBarSizes(
        tabBarContentPadding = tabBarContentPadding,
        tabExpandedContentPadding = tabExpandedContentPadding,
        componentSpacing = componentSpacing,
        tabSpacing = tabSpacing,
    )
}
