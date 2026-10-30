package com.music.spotui.ui.immersive.sheets

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.components.rememberImmersiveOverscrollFactory
import com.music.spotui.ui.immersive.shell.immersiveGlassSurface

/**
 * A bottom sheet on glass: [ImmersiveSheetShape] (rounded top) drawn with the shared
 * `immersiveGlassSurface`, so it takes whatever the glass looks like, with the grab handle on it.
 * The sheet opens and settles on Material's own spring-anchored drag. The content brings its own
 * rows and insets (the Classic sheets' contents work unchanged on it).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImmersiveGlassSheet(
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        CompositionLocalProvider(LocalOverscrollFactory provides rememberImmersiveOverscrollFactory()) {
            Column(Modifier.fillMaxWidth().immersiveGlassSurface(ImmersiveSheetShape)) {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .size(width = 34.dp, height = 4.dp)
                            .clip(CircleShape)
                            .background(Immersive.Text.copy(alpha = 0.35f)),
                    )
                }
                content()
            }
        }
    }
}
