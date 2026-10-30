package com.music.spotui.ui.immersive.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.myspoti.engine.R
import app.myspoti.extensions.StoreAction
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.components.immersiveClickable
import com.music.spotui.ui.immersive.shell.ImmersiveTopBar
import com.music.spotui.ui.immersive.shell.immersiveTopContentPadding

/**
 * One row of an extension list (store, installed): the [leading] icon, a column of lines
 * ([content]) and, at the end, [trailing] controls. Meant for the rows of an `immersiveGroup`; the
 * whole row opens something when [onClick] is set.
 */
@Composable
internal fun ImmersiveExtRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    leading: @Composable () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.immersiveClickable(onClick = onClick, pressedScale = 0.99f) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        leading()
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), content = content)
        trailing?.invoke()
    }
}

/** A switch in the Immersive colours, for the end of a row that also opens something. */
@Composable
internal fun ImmersiveSwitch(checked: Boolean, onChecked: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChecked,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = Immersive.Accent,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = Color(0xFF3A3A3F),
            uncheckedBorderColor = Color.Transparent,
        ),
    )
}

/** A bare 32 dp icon button for the end of a row (a bin, an arrow, a refresh). */
@Composable
internal fun ImmersiveRowIcon(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = Immersive.TextSecondary,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .immersiveClickable(onClick = onClick, enabled = enabled, pressedScale = 0.9f),
    ) {
        Icon(icon, contentDescription, tint = tint.copy(alpha = if (enabled) 1f else 0.3f), modifier = Modifier.size(22.dp))
    }
}

/**
 * The frame of an extension's own page (its WebView): the page colour and the glass top bar with
 * the page's [title] and a line saying whose page it is, the [content] under them.
 */
@Composable
internal fun ImmersiveExtensionPageFrame(title: String, subtitle: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    ImmersivePageFrame {
        Column(Modifier.fillMaxSize().padding(top = immersiveTopContentPadding()).navigationBarsPadding()) {
            Text(
                subtitle,
                style = Immersive.Caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Immersive.Gutter, vertical = 4.dp),
            )
            Box(Modifier.fillMaxWidth().weight(1f)) { content() }
        }
        ImmersiveTopBar(title = title, collapse = { 1f }, onBack = onBack)
    }
}

/**
 * The one state button of a store row or page: Install (filled), Update (tinted), Up to date
 * (quiet, opens the installed page), or a spinner while the package downloads. [large] fills the
 * width, for the page of one extension.
 */
@Composable
internal fun ImmersiveStateButton(
    action: StoreAction,
    busy: Boolean,
    large: Boolean = false,
    onInstall: () -> Unit,
    onOpen: () -> Unit,
) {
    val sizing = if (large) Modifier.fillMaxWidth().heightIn(min = 46.dp) else Modifier.widthIn(min = 84.dp).heightIn(min = 32.dp)
    if (busy) {
        Box(sizing, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(20.dp), color = Immersive.Accent, strokeWidth = 2.dp)
        }
        return
    }
    val (label, fill, content) = when (action) {
        StoreAction.INSTALL -> Triple(R.string.extensions_install, Immersive.Accent, Color.White)
        StoreAction.UPDATE -> Triple(R.string.extensions_update, Immersive.Accent.copy(alpha = 0.22f), Immersive.Accent)
        StoreAction.INSTALLED -> Triple(R.string.extensions_up_to_date, Immersive.Raised, Immersive.TextSecondary)
        StoreAction.INCOMPATIBLE -> Triple(R.string.extensions_install, Immersive.Raised, Immersive.TextTertiary)
    }
    val click = when (action) {
        StoreAction.INSTALL, StoreAction.UPDATE -> onInstall
        StoreAction.INSTALLED -> onOpen
        StoreAction.INCOMPATIBLE -> ({})
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = sizing
            .clip(if (large) Immersive.Button else CircleShape)
            .background(fill)
            .immersiveClickable(onClick = click, enabled = action != StoreAction.INCOMPATIBLE)
            .padding(horizontal = if (large) 20.dp else 14.dp),
    ) {
        Text(
            stringResource(label),
            style = Immersive.Subhead.copy(color = content, fontWeight = FontWeight.SemiBold),
            maxLines = 1,
        )
    }
}
