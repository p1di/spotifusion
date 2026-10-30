package com.music.spotui.data.offline

/**
 * Whether a song picked in a list plays from the phone (the downloaded file) or from the Web Player.
 *
 * Offline, or with the data saver on mobile data, the Web Player cannot stream: the list's
 * downloaded songs play. Online, a downloaded song also plays from its file (the one
 * the user chose to keep) as long as the whole list is on the phone: the local queue holds only
 * downloaded songs, so a list that is only partly downloaded would lose the others, and it keeps
 * streaming from Spotify's own queue.
 */
object LocalPlayPlan {
    /**
     * The ids to play locally, in play order, or null when the Web Player plays.
     * [ordered]: the list's track ids in the order to play them; [complete]: the whole list is loaded.
     */
    fun localIds(
        ordered: List<String>,
        picked: String,
        complete: Boolean,
        offline: Boolean,
        dataSaver: Boolean,
        isDownloaded: (String) -> Boolean,
    ): List<String>? {
        if (!isDownloaded(picked)) return null
        val ids = ordered.filter { it.isNotBlank() }.distinct()
        val downloaded = ids.filter(isDownloaded)
        if (downloaded.isEmpty()) return null
        if (offline || dataSaver) return downloaded
        return if (complete && downloaded.size == ids.size) downloaded else null
    }
}
