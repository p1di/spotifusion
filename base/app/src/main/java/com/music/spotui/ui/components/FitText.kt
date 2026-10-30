package com.music.spotui.ui.components

import com.music.spotui.ui.theme.AppText

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * A title that fits the room it has: it shrinks (down to [minSize]) until it holds in
 * [maxLines] for the width given, then ends with "…" if it is still too long. Long
 * playlist names and artist mixes keep the layout as designed, on any screen and font size.
 */
@Composable
fun FitText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = AppText,
    fontSize: TextUnit,
    minSize: TextUnit = 14.sp,
    fontWeight: FontWeight? = null,
    maxLines: Int = 2,
    textAlign: TextAlign? = null,
) {
    var size by remember(text, fontSize) { mutableStateOf(fontSize) }
    var ready by remember(text, fontSize) { mutableStateOf(false) }
    Text(
        text = text,
        color = color,
        fontSize = size,
        lineHeight = size * 1.15f,
        fontWeight = fontWeight,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
        softWrap = true,
        // Not drawn until its size is settled: no flash of the too-big text.
        modifier = modifier.drawWithContent { if (ready) drawContent() },
        onTextLayout = { layout ->
            if (layout.hasVisualOverflow && size.value > minSize.value) size = (size.value * 0.9f).coerceAtLeast(minSize.value).sp
            else ready = true
        },
    )
}
