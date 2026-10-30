package com.music.spotui.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.music.spotui.ui.immersive.sheets.ImmersiveGlassSheet
import com.music.spotui.ui.navigation.isImmersive

/**
 * The bottom sheet of the screens shared by both looks: Material's [ModalBottomSheet] on
 * [containerColor] in Classic (exactly as before), a glass sheet in Immersive.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LookSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
    containerColor: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (isImmersive()) ImmersiveGlassSheet(onDismissRequest, sheetState, content)
    else ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState, containerColor = containerColor, content = content)
}
