package com.music.spotui.extensions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.engine.R
import com.music.spotui.ui.theme.AppAccent
import com.music.spotui.ui.theme.AppMuted
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.tone
import app.myspoti.extensions.Consent
import app.myspoti.extensions.RawGrant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The question an extension's destructive Spotify call waits on ("X wants to remove from the
 * library."). The extension's own thread waits; the dialog is drawn over every screen by
 * [ExtensionConfirmHost]. No answer (the sandbox's 120 s), dismissing it, or a later question
 * replacing it: refused.
 */
object ExtensionConfirm {
    /** [offer]: a `raw.request` write, which may be allowed for good ("Always"). */
    class Question(val extensionId: String, val text: String, val offer: RawGrant?, internal val answer: CompletableDeferred<Consent>)

    /** The questions waiting, oldest first; the first one is shown. */
    val waiting = MutableStateFlow<List<Question>>(emptyList())

    suspend fun ask(extensionId: String, text: String): Boolean = ask(extensionId, text, null) != Consent.REFUSE

    suspend fun ask(extensionId: String, text: String, offer: RawGrant?): Consent {
        val q = Question(extensionId, text, offer, CompletableDeferred())
        synchronized(this) { waiting.value = waiting.value + q }
        return try {
            q.answer.await()
        } finally {
            q.answer.complete(Consent.REFUSE)
            synchronized(this) { waiting.value = waiting.value - q }
        }
    }

    fun answer(q: Question, allow: Boolean) = answer(q, if (allow) Consent.ONCE else Consent.REFUSE)

    fun answer(q: Question, consent: Consent) {
        q.answer.complete(consent)
        synchronized(this) { waiting.value = waiting.value - q }
    }
}

@Composable
fun ExtensionConfirmHost() {
    SuspendedNotice()
    WelcomeNotice()
    val waiting by ExtensionConfirm.waiting.collectAsState()
    val q = waiting.firstOrNull() ?: return
    ProtectedSurfaceEffect(app.myspoti.extensions.ProtectedSurface.CONFIRM)
    AlertDialog(
        onDismissRequest = { ExtensionConfirm.answer(q, false) },
        containerColor = tone(0xFF282828),
        title = { Text(stringResource(R.string.extension_confirm_title), color = AppText) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val first = q.text.lineSequence().first()
                Text(first, color = AppText)
                val rest = q.text.substringAfter('\n', "")
                if (rest.isNotBlank()) Text(rest, color = AppMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 8.dp))
                Text(stringResource(R.string.extension_confirm_detail), color = AppMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                q.offer?.let { g ->
                    TextButton(onClick = { ExtensionConfirm.answer(q, Consent.ALWAYS) }, modifier = Modifier.padding(top = 8.dp)) {
                        Text(stringResource(R.string.extension_confirm_always, g.toString()), color = AppAccent, fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { ExtensionConfirm.answer(q, true) }) { Text(stringResource(if (q.offer != null) R.string.extension_confirm_once else R.string.extension_confirm_allow), color = AppAccent) } },
        dismissButton = { TextButton(onClick = { ExtensionConfirm.answer(q, false) }) { Text(stringResource(R.string.extension_confirm_refuse), color = AppText) } },
    )
}

/**
 * The playback arbiter suspended an extension (it kept undoing another one): say so, with a
 * button to turn it off, or to let it go on.
 */
@Composable
private fun SuspendedNotice() {
    val notice by Extensions.suspendedNotice.collectAsState()
    val (id, other) = notice ?: return
    val name = { x: String -> Extensions.registry.value.find(x)?.name ?: x }
    AlertDialog(
        onDismissRequest = { Extensions.suspendedNotice.value = null },
        containerColor = tone(0xFF282828),
        title = { Text(stringResource(R.string.ext_suspended_title, name(id)), color = AppText) },
        text = { Text(stringResource(R.string.ext_suspended_body, name(other)), color = AppMuted) },
        confirmButton = { TextButton(onClick = { Extensions.suspendedNotice.value = null; Extensions.setEnabled(id, false) }) { Text(stringResource(R.string.area_turn_off), color = AppAccent) } },
        dismissButton = { TextButton(onClick = { Extensions.suspendedNotice.value = null; Extensions.resumePlayback(id) }) { Text(stringResource(R.string.ext_suspended_keep), color = AppText) } },
    )
}

/** An extension's own few words after its first install: what it does, then what is left to do. */
@Composable
private fun WelcomeNotice() {
    val shown by Extensions.welcome.collectAsState()
    val (id, w) = shown ?: return
    val name = Extensions.registry.value.find(id)?.name ?: id
    AlertDialog(
        onDismissRequest = { Extensions.welcome.value = null },
        containerColor = tone(0xFF282828),
        title = { Text(stringResource(R.string.ext_welcome_title, name), color = AppText) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (w.message.isNotBlank()) Text(w.message, color = AppText)
                if (w.steps.isNotEmpty()) Text(stringResource(R.string.ext_welcome_steps), color = AppMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                w.steps.forEachIndexed { i, step -> Text("${i + 1}. $step", color = AppText, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp)) }
                Text(stringResource(R.string.ext_welcome_from), color = AppMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp))
            }
        },
        confirmButton = { TextButton(onClick = { Extensions.welcome.value = null }) { Text(stringResource(R.string.ext_welcome_ok), color = AppAccent) } },
    )
}
