package com.music.spotui.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale

private val MARKS = Regex("""\p{M}+""")
private val SPACES = Regex("""\s+""")

/** Lower case without accents. Plain ASCII (most titles) skips the Unicode normalisation. */
private fun fold(s: String): String {
    for (c in s) if (c.code >= 128) {
        return MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)
    }
    return s.lowercase(Locale.ROOT)
}

/** The words of a "Find in…" query, folded once; empty for a blank query. */
internal fun findWords(query: String): List<String> = fold(query).split(SPACES).filter { it.isNotEmpty() }

/**
 * "Find in…" match: every word of [query] appears in one of [fields], ignoring case and
 * accents ("beyonce hal" finds "Halo" by Beyoncé). A blank query matches everything.
 */
fun matchesFind(query: String, vararg fields: String): Boolean {
    val words = findWords(query)
    if (words.isEmpty()) return true
    val text = fields.joinToString(" ") { fold(it) }
    return words.all { it in text }
}

/** An item's searchable text, folded once, so that a keystroke is only a substring test. */
private class FindIndex<T>(val source: List<T>, val keys: List<String>)

/**
 * [items] that match [query] (same rules as [matchesFind], on the [text] of each item), in
 * their order. The search text of a list is built once, off the main thread; each query is
 * matched in the background after [debounceMs] without a new keystroke, and the previous
 * result stays on screen meanwhile, so typing in a list of thousands of songs never stalls
 * a frame. A blank query returns [items] itself.
 */
@Composable
fun <T> rememberFound(items: List<T>, query: String, debounceMs: Long = 150L, text: (T) -> String): List<T> {
    val textOf by rememberUpdatedState(text)
    val index by produceState<FindIndex<T>?>(null, items) {
        value = withContext(Dispatchers.Default) { FindIndex(items, items.map { fold(textOf(it)) }) }
    }
    val words = remember(query) { findWords(query) }
    val found by produceState(items, items, index, words) {
        if (words.isEmpty()) { value = items; return@produceState }
        // Not built yet (or for the previous list): the next run, keyed on the index, filters.
        val ix = index?.takeIf { it.source === items } ?: return@produceState
        delay(debounceMs)
        value = withContext(Dispatchers.Default) {
            ArrayList<T>().also { out -> for (i in items.indices) if (words.all { it in ix.keys[i] }) out += items[i] }
        }
    }
    return if (words.isEmpty()) items else found
}

/** The "Find in…" field at the top of a list of songs. */
@Composable
fun FindInListField(query: String, onQuery: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0x33FFFFFF)).padding(horizontal = 12.dp),
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 15.sp, fontFamily = com.music.spotui.ui.theme.AppSans),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.weight(1f).padding(8.dp, 10.dp),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) Text(hint, color = Color(0xFFB3B3B3), fontSize = 15.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    inner()
                }
            },
        )
        if (query.isNotEmpty()) Icon(
            Icons.Default.Close, contentDescription = null, tint = Color(0xFFB3B3B3),
            modifier = Modifier.size(18.dp).pressable(0.92f) { onQuery("") },
        )
    }
}

/** "Couldn't find …" under a "Find in…" field with no match. */
@Composable
fun FindInListEmpty(text: String) {
    Text(text, color = Color(0xFFB3B3B3), fontSize = 14.sp, modifier = Modifier.fillMaxWidth().padding(16.dp, 24.dp))
}
