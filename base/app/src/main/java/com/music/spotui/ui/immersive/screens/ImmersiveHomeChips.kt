/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later,
 * sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/SearchScreen.kt (SearchFilterTabs) at edc1e6f.
 * Modified for Myspoti, 2026-10.
 */
package com.music.spotui.ui.immersive.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.music.spotui.ui.immersive.Immersive

/** Rounded, but well short of a capsule: the corner reads as a cut, not a curve. */
private val ChipShape = RoundedCornerShape(12.dp)

/**
 * Filter pills rather than a tab row: squarish rounded rectangles, the selected one inverted.
 * They scroll sideways so a long label set never squeezes the text, and the gutter padding sits
 * inside the scroll so it scrolls with them. Shared by the Home facets, the Search filters and
 * the Library kinds.
 */
@Composable
internal fun ImmersiveChipRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(PaddingValues(horizontal = Immersive.Gutter, vertical = 6.dp)),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** One pill of an [ImmersiveChipRow]. */
@Composable
internal fun ImmersiveChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .clip(ChipShape)
            .background(if (selected) Immersive.Text else Immersive.Raised)
            // Only a pill that is not already selected has anything to report: re-tapping the
            // current one changes nothing, so buzzing for it would be feedback for a no-op.
            .clickable(role = Role.Button) {
                if (!selected) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = Immersive.Subhead.copy(
                color = if (selected) Immersive.Page else Immersive.Text,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            ),
            maxLines = 1,
        )
    }
}
