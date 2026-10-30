package com.music.spotui.ui.components

/**
 * B25: the `user-profile-view` endpoints hand out `spotify:image:<id>` URIs (the form their
 * protobuf payload carries) instead of an https URL. Nothing in the app turned those into a
 * URL, so Glide was given a string it cannot fetch and kept its grey placeholder: the profile
 * picture and the covers of a user's public playlists never loaded, while the same covers
 * appeared as soon as the playlist page itself was opened (it builds https URLs).
 * A real URL passes through untouched.
 */
fun spotifyImageUrl(url: String?): String? = when {
    url.isNullOrBlank() -> null
    url.startsWith("spotify:image:") -> "https://i.scdn.co/image/" + url.removePrefix("spotify:image:")
    // A playlist without a cover of its own: the 2x2 mosaic of its first albums, which the
    // profile endpoints also hand out as a URI (reported: profile playlists left grey).
    url.startsWith("spotify:mosaic:") -> "https://mosaic.scdn.co/640/" + url.removePrefix("spotify:mosaic:").split(':').joinToString("")
    else -> url
}

/**
 * The cover of a playlist that has none of its own, made from its songs' covers
 * (`i.scdn.co/image/<id>` addresses) as the official app does: the 2x2 mosaic of four different
 * albums, or the first album's cover when fewer exist (the mosaic host answers 404 for two or
 * three images, measured). "" when no song has a Spotify cover.
 */
fun mosaicCoverFor(songCovers: List<String>): String {
    val prefix = "https://i.scdn.co/image/"
    val ids = LinkedHashSet<String>()
    for (c in songCovers) {
        if (c.startsWith(prefix)) ids += c.removePrefix(prefix)
        if (ids.size == 4) break
    }
    return when {
        ids.size == 4 -> "https://mosaic.scdn.co/640/" + ids.joinToString("")
        ids.isNotEmpty() -> prefix + ids.first()
        else -> ""
    }
}
