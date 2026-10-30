package app.myspoti.host.android

/**
 * Spotify's album covers come in 64, 300 and 640 px under the same image id, told apart by its
 * prefix. The queue, the lists and a song picked from them carry the small ones; what plays is
 * shown large (player, notification, lock screen), as the song Spotify plays is.
 */
object Covers {
    private val SMALL_ALBUM_COVER = Regex("ab67616d0000(?:1e02|4851)")
    private val CDN_IMAGE = Regex("^https://[a-z0-9-]+\\.spotifycdn\\.com/image/")
    private const val LARGE_ALBUM_COVER_PREFIX = "ab67616d0000b273"

    /**
     * [url]'s 640 px album cover when it is a smaller one of `i.scdn.co`, of an `image-cdn-*.spotifycdn.com`
     * host (or `spotify:image:`); anything else as it is.
     */
    fun large(url: String): String {
        val image = url.startsWith("https://i.scdn.co/image/") || url.startsWith("spotify:image:") ||
            CDN_IMAGE.containsMatchIn(url)
        if (!image) return url
        return SMALL_ALBUM_COVER.find(url)?.let { url.replaceRange(it.range, LARGE_ALBUM_COVER_PREFIX) } ?: url
    }
}
