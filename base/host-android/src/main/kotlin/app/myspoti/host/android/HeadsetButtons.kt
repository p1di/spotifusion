package app.myspoti.host.android

import android.view.KeyEvent

/**
 * What a transport command from the system (earbuds, car, notification) does, by what plays.
 * A podcast episode skips 15 s like the official app (right earbud forward, left back) unless the
 * setting asks for the next and previous episode; music keeps next / previous.
 */
object HeadsetButtons {
    const val SKIP_MS = 15_000L

    enum class Command(val label: String) {
        NEXT("next"), PREVIOUS("previous"), FAST_FORWARD("fast-forward"), REWIND("rewind"),
    }

    enum class Action { NEXT, PREVIOUS, SEEK_FORWARD, SEEK_BACK, NONE }

    fun map(episode: Boolean, skipsOnEpisodes: Boolean, command: Command): Action = when (command) {
        Command.NEXT -> if (episode && skipsOnEpisodes) Action.SEEK_FORWARD else Action.NEXT
        Command.PREVIOUS -> if (episode && skipsOnEpisodes) Action.SEEK_BACK else Action.PREVIOUS
        Command.FAST_FORWARD -> if (episode) Action.SEEK_FORWARD else Action.NONE
        Command.REWIND -> if (episode) Action.SEEK_BACK else Action.NONE
    }

    /** The keys the platform does not turn into a callback by itself. */
    fun commandOf(keyCode: Int): Command? = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> Command.FAST_FORWARD
        KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD, KeyEvent.KEYCODE_MEDIA_REWIND -> Command.REWIND
        else -> null
    }

    /** The logbook line of a mapped command. */
    fun logLine(command: Command, episode: Boolean, action: Action): String = when (action) {
        Action.SEEK_FORWARD -> "${command.label} on ${if (episode) "episode" else "music"} -> +15 s"
        Action.SEEK_BACK -> "${command.label} on ${if (episode) "episode" else "music"} -> -15 s"
        Action.NONE -> "${command.label} ignored on music"
        else -> command.label
    }
}
