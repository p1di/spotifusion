package com.music.spotui.data.api

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.myspoti.engine.R

/**
 * What the About section shows about the update check: the spinner, the last answer and the
 * time of the last successful check. Shared by the Classic and Immersive settings.
 */
class UpdateCheckState(private val context: Context) {
    var checking by mutableStateOf(false)
        private set
    /** Result line of the last manual check ("Up to date (checked just now)", an error...). */
    var message by mutableStateOf<String?>(null)
        private set
    var isError by mutableStateOf(false)
        private set
    private var checkedAt by mutableLongStateOf(UpdateChecker.lastChecked(context))

    /** "Last checked: 5 minutes ago", or "Never checked". */
    val lastCheckedText: String
        get() = if (checkedAt <= 0L) context.getString(R.string.settings_update_never)
        else context.getString(R.string.settings_update_last_checked,
            DateUtils.getRelativeTimeSpanString(checkedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))

    /** Asks GitHub now; a newer release opens the app's update card (tap -> download flow). */
    suspend fun run() {
        if (checking) return
        checking = true; message = null; isError = false
        val outcome = UpdateChecker.refresh(context)
        checking = false
        checkedAt = UpdateChecker.lastChecked(context)
        when (outcome) {
            is UpdateChecker.Outcome.Found -> {
                message = context.getString(R.string.settings_update_available, outcome.release.version)
                com.music.spotui.updateRelease.value = outcome.release
            }
            UpdateChecker.Outcome.UpToDate -> message = context.getString(R.string.settings_update_uptodate_now)
            is UpdateChecker.Outcome.Failed -> {
                isError = true
                message = context.getString(if (outcome.rateLimited) R.string.settings_update_rate_limited else R.string.settings_update_failed)
            }
        }
    }
}

@Composable
fun rememberUpdateCheckState(context: Context): UpdateCheckState = remember { UpdateCheckState(context) }
