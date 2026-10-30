package com.music.spotui.data.entity

@kotlinx.serialization.Serializable
data class SongsModel(
    val id : Int,
    val title : String,
    val album : String,
    val singer : String,
    val coverUri : String,
    val url : String,
    // Real Spotify track id (e.g. "3n3Ppam7vgaVa1iaRUc9Lp"), kept so playback can
    // seed Spotify's recommendations endpoint for autoplay radio. Empty when unknown.
    val spotifyTrackId : String = "",
    // Whether the Spotify track is the explicit version (the E badge).
    val explicit : Boolean = false,
    // Track length in ms (from Spotify), 0 when unknown.
    val durationMs : Int = 0,
    // Spotify album id of the track, when known (opens the album page by id).
    val albumId : String = "",
    // When it was added to the playlist or to Liked Songs (ISO 8601), for "Sort by date added".
    val addedAt : String = "",
    // False when Spotify marks the track unavailable (region, removed); rows are dimmed.
    val playable : Boolean = true,
    // Spotify ids of the artists, for "liked songs by this artist". Empty in older caches.
    val artistIds : List<String> = emptyList(),
    // ── Podcast episodes. Empty / false on tracks: the show page and the episode page
    // read these from the row itself instead of asking Spotify again.
    /** Episode description, as Spotify publishes it (the row snippet and the episode page). */
    val description : String = "",
    /** Release date (ISO 8601), shown as "24 juil." on the episode page. */
    val releaseDate : String = "",
    /** Spotify keeps this episode as finished (playedState, or the page's own mark). */
    val played : Boolean = false,
    /** Where the user stopped listening, in ms (0 when never started). */
    val playedPositionMs : Long = 0L,
    /** Raw Spotify id of the show the episode belongs to. */
    val showId : String = "",
    /**
     * This row's own id in a Spotify playlist. The same song twice in a playlist has the same
     * [id] and [spotifyTrackId] but two row ids, and a removal must name the row, not the song.
     * Empty outside playlists and in older caches.
     */
    val rowUid : String = "",
){
    constructor() : this(-1 ,"" ,"" ,"" ,"" ,"", "")

    /** A podcast episode (url = "episode:<raw id>"), not a music track. */
    val isEpisode : Boolean get() = url.startsWith("episode:")
}
