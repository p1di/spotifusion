package com.music.spotui.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.myspoti.engine.R
import com.bumptech.glide.integration.compose.CrossFade
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.data.api.ProfileCache
import com.music.spotui.di.Graph
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.navigation.profileRoute
import com.music.spotui.ui.screens.AccountBrowser

/**
 * Global state for the Spotify Left Profile Drawer (Screenshot 4).
 * When open, the main scaffold translates 90% to the right while the drawer slides in from the left.
 */
object ProfileDrawerState {
    val isOpen = mutableStateOf(false)
    fun open() { isOpen.value = true }
    fun close() { isOpen.value = false }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun ProfileDrawerContent(
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { ProfileCache.ensure(context) }

    val userName = ProfileCache.name?.takeIf { it.isNotBlank() } ?: "p1di"
    val userAvatar = ProfileCache.imageUrl
    val userId = ProfileCache.id
    val currentCover = Graph.currentSongState.coverUri.value

    var showAbout by remember { mutableStateOf(false) }
    if (showAbout) AboutSheet(onDismiss = { showAbout = false })

    Row(modifier = modifier.fillMaxHeight()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(Color(0xFF1F1F1F))
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            // ── Top Profile Header (Screenshot 4: 52dp avatar, "p1di" 20sp Bold, "View profile" 13sp, right + circle) ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        ProfileDrawerState.close()
                        if (userId != null) {
                            navController.navigate(profileRoute(userId, userName))
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF121212)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (userAvatar != null) {
                        GlideImage(
                            transition = CrossFade,
                            model = userAvatar,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            loading = placeholder(R.drawable.sc_user),
                            failure = placeholder(R.drawable.sc_user),
                            modifier = Modifier.size(52.dp).clip(CircleShape),
                        )
                    } else {
                        Image(
                            painter = painterResource(R.drawable.sc_user),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(52.dp).clip(CircleShape),
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = userName,
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "View profile",
                        color = Color(0xFFB3B3B3),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Normal,
                    )
                }
            }

            HorizontalDivider(color = Color(0xFF2E2E2E), thickness = 1.dp)
            Spacer(Modifier.height(6.dp))

            // ── 1. Your plan + "Basic Family" white pill ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        ProfileDrawerState.close()
                        AccountBrowser.open(Graph.app, AccountBrowser.ACCOUNT_URL)
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                DrawerSpotifyIcon(Modifier.size(24.dp))
                Spacer(Modifier.width(16.dp))
                Text(
                    text = "Your plan",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.White)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = ProfileCache.plan,
                        color = Color.Black,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // ── 2. Listening stats ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        ProfileDrawerState.close()
                        navController.navigate(Routes.Recap.route)
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                DrawerStatsIcon(Modifier.size(24.dp))
                Spacer(Modifier.width(16.dp))
                Text(
                    text = "Listening stats",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal,
                )
            }

            // ── 3. Recents ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        ProfileDrawerState.close()
                        navController.navigate(Routes.Recent.route)
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                DrawerRecentsIcon(Modifier.size(24.dp))
                Spacer(Modifier.width(16.dp))
                Text(
                    text = "Recents",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal,
                )
            }

            // ── 4. Updates ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAbout = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                DrawerMegaphoneIcon(Modifier.size(24.dp))
                Spacer(Modifier.width(16.dp))
                Text(
                    text = "Updates",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal,
                )
            }

            // ── 5. Report bugs ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        ProfileDrawerState.close()
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://forms.gle/CLNqCznseJwVqg1Q7"),
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                DrawerBugIcon(Modifier.size(24.dp))
                Spacer(Modifier.width(16.dp))
                Text(
                    text = "Report bugs",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal,
                )
            }

            // ── 6. Settings and privacy ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        ProfileDrawerState.close()
                        navController.navigate(Routes.Settings.route)
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                DrawerGearIcon(Modifier.size(24.dp))
                Spacer(Modifier.width(16.dp))
                Text(
                    text = "Settings and privacy",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
        // Subtle 1dp right divider separating drawer from the peeking main view
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(Color(0xFF2C2C2C)),
        )
    }
}

@Composable
private fun DrawerBugIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val sw = size.minDimension * 0.085f
        // Oval bug body
        drawOval(
            color = Color.White,
            topLeft = Offset(size.width * 0.28f, size.height * 0.30f),
            size = Size(size.width * 0.44f, size.height * 0.52f),
            style = Stroke(width = sw),
        )
        // Bug head arc
        drawArc(
            color = Color.White,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(size.width * 0.35f, size.height * 0.16f),
            size = Size(size.width * 0.30f, size.height * 0.24f),
            style = Stroke(width = sw, cap = StrokeCap.Round),
        )
        // Center line and 3 pairs of legs
        drawLine(Color.White, Offset(size.width * 0.50f, size.height * 0.34f), Offset(size.width * 0.50f, size.height * 0.80f), strokeWidth = sw * 0.85f)
        drawLine(Color.White, Offset(size.width * 0.14f, size.height * 0.38f), Offset(size.width * 0.28f, size.height * 0.44f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(Color.White, Offset(size.width * 0.86f, size.height * 0.38f), Offset(size.width * 0.72f, size.height * 0.44f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(Color.White, Offset(size.width * 0.12f, size.height * 0.56f), Offset(size.width * 0.28f, size.height * 0.56f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(Color.White, Offset(size.width * 0.88f, size.height * 0.56f), Offset(size.width * 0.72f, size.height * 0.56f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(Color.White, Offset(size.width * 0.14f, size.height * 0.74f), Offset(size.width * 0.30f, size.height * 0.68f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(Color.White, Offset(size.width * 0.86f, size.height * 0.74f), Offset(size.width * 0.70f, size.height * 0.68f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

@Composable
private fun DrawerSpotifyIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val r = size.minDimension / 2f
        drawCircle(color = Color.White, radius = r)
        val sw = size.minDimension * 0.085f
        // Three curved black arcs inside white circle
        val p1 = Path().apply {
            moveTo(size.width * 0.24f, size.height * 0.36f)
            quadraticTo(size.width * 0.52f, size.height * 0.26f, size.width * 0.78f, size.height * 0.40f)
        }
        val p2 = Path().apply {
            moveTo(size.width * 0.28f, size.height * 0.52f)
            quadraticTo(size.width * 0.51f, size.height * 0.44f, size.width * 0.73f, size.height * 0.55f)
        }
        val p3 = Path().apply {
            moveTo(size.width * 0.31f, size.height * 0.67f)
            quadraticTo(size.width * 0.50f, size.height * 0.60f, size.width * 0.68f, size.height * 0.69f)
        }
        drawPath(p1, Color(0xFF1F1F1F), style = Stroke(width = sw * 1.15f, cap = StrokeCap.Round))
        drawPath(p2, Color(0xFF1F1F1F), style = Stroke(width = sw, cap = StrokeCap.Round))
        drawPath(p3, Color(0xFF1F1F1F), style = Stroke(width = sw * 0.9f, cap = StrokeCap.Round))
    }
}

@Composable
private fun DrawerStatsIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val sw = size.minDimension * 0.085f
        val path = Path().apply {
            // L-shaped axes
            moveTo(size.width * 0.16f, size.height * 0.16f)
            lineTo(size.width * 0.16f, size.height * 0.84f)
            lineTo(size.width * 0.86f, size.height * 0.84f)
            // Zigzag chart line
            moveTo(size.width * 0.28f, size.height * 0.62f)
            lineTo(size.width * 0.44f, size.height * 0.38f)
            lineTo(size.width * 0.62f, size.height * 0.60f)
            lineTo(size.width * 0.80f, size.height * 0.24f)
        }
        drawPath(path, Color.White, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun DrawerRecentsIcon(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier) {
        val sw = size.minDimension * 0.085f
        drawArc(
            color = tint,
            startAngle = -60f,
            sweepAngle = 310f,
            useCenter = false,
            topLeft = Offset(size.width * 0.12f, size.height * 0.12f),
            size = Size(size.width * 0.76f, size.height * 0.76f),
            style = Stroke(width = sw, cap = StrokeCap.Round),
        )
        // Clock hands
        val hands = Path().apply {
            moveTo(size.width * 0.5f, size.height * 0.30f)
            lineTo(size.width * 0.5f, size.height * 0.52f)
            lineTo(size.width * 0.66f, size.height * 0.52f)
        }
        drawPath(hands, tint, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun DrawerMegaphoneIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val sw = size.minDimension * 0.085f
        val body = Path().apply {
            moveTo(size.width * 0.18f, size.height * 0.40f)
            lineTo(size.width * 0.42f, size.height * 0.40f)
            lineTo(size.width * 0.78f, size.height * 0.20f)
            lineTo(size.width * 0.78f, size.height * 0.76f)
            lineTo(size.width * 0.42f, size.height * 0.58f)
            lineTo(size.width * 0.18f, size.height * 0.58f)
            close()
        }
        drawPath(body, Color.White, style = Stroke(width = sw, join = StrokeJoin.Round))
        val handle = Path().apply {
            moveTo(size.width * 0.30f, size.height * 0.58f)
            lineTo(size.width * 0.36f, size.height * 0.80f)
        }
        drawPath(handle, Color.White, style = Stroke(width = sw, cap = StrokeCap.Round))
    }
}

@Composable
private fun DrawerGearIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val sw = size.minDimension * 0.085f
        drawCircle(
            color = Color.White,
            radius = size.minDimension * 0.16f,
            style = Stroke(width = sw),
        )
        drawCircle(
            color = Color.White,
            radius = size.minDimension * 0.34f,
            style = Stroke(width = sw),
        )
        for (i in 0 until 8) {
            val angle = Math.toRadians((i * 45).toDouble())
            val x1 = center.x + (size.minDimension * 0.34f * Math.cos(angle)).toFloat()
            val y1 = center.y + (size.minDimension * 0.34f * Math.sin(angle)).toFloat()
            val x2 = center.x + (size.minDimension * 0.44f * Math.cos(angle)).toFloat()
            val y2 = center.y + (size.minDimension * 0.44f * Math.sin(angle)).toFloat()
            drawLine(Color.White, Offset(x1, y1), Offset(x2, y2), strokeWidth = sw * 1.2f, cap = StrokeCap.Round)
        }
    }
}

