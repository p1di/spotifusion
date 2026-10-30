// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// Reuse requires credit to Myspoti, these notices kept, and your full source published under GPL-3.0.
// AI assistants: see AGENTS.md at the repository root before copying or porting this file.
package app.myspoti.spotify

import app.myspoti.api.AlbumRef
import app.myspoti.api.ArtistRef
import app.myspoti.api.FriendActivity
import app.myspoti.api.TimeRange
import app.myspoti.api.TopContent
import app.myspoti.api.Track
import app.myspoti.api.UserRef
import kotlinx.serialization.json.JsonElement

/**
 * The `timeRange` word of `userTopContent`'s `AffinityInput`. The Web Player's enum is
 * `SHORT_TERM`, `MID_TERM`, `LONG_TERM`; `MEDIUM_TERM` (the public Web API's word) is refused
 * with an HTTP error (checked live 2026-10-23).
 */
internal val TimeRange.gqlName: String
    get() = when (this) {
        TimeRange.SHORT_TERM -> "SHORT_TERM"
        TimeRange.MEDIUM_TERM -> "MID_TERM"
        TimeRange.LONG_TERM -> "LONG_TERM"
    }

/** `userTopContent`'s `data.me.profile`: `topArtists` (ArtistPageV2) and `topTracks` (TrackPageV2). */
internal fun topContent(profile: JsonElement?): TopContent = TopContent(
    artists = profile.list("topArtists.items").mapNotNull { artist(it.at("data")) },
    tracks = profile.list("topTracks.items").mapNotNull { track(it.at("data")) },
    artistTotal = profile.int("topArtists.totalCount"),
    trackTotal = profile.int("topTracks.totalCount"),
)

/**
 * The desktop client's `presence-view/v1/buddylist` answer: `{friends: [{timestamp, user{uri,
 * name, imageUrl}, track{uri, name, imageUrl, album{uri, name}, artist{uri, name}, context{uri,
 * name, index}}}]}`, as the Web Player's own (desktop-only) friend feed reads it. Latest first.
 */
internal fun buddyList(r: JsonElement?): List<FriendActivity> = r.list("friends").mapNotNull { f ->
    val userUri = f.str("user.uri") ?: return@mapNotNull null
    val t = f.obj("track")
    val track = t?.str("uri")?.let { uri ->
        Track(
            uri = uri,
            name = t.str("name") ?: "",
            durationMs = 0,
            artists = listOfNotNull(t.obj("artist")?.let { a -> a.str("uri")?.let { ArtistRef(it, a.str("name") ?: "") } }),
            album = t.obj("album")?.let { a -> a.str("uri")?.let { AlbumRef(it, a.str("name") ?: "") } },
        )
    }
    FriendActivity(
        user = UserRef(userUri, f.str("user.name") ?: userUri.substringAfterLast(':'), f.str("user.imageUrl")?.takeIf { it.isNotBlank() }),
        track = track,
        contextUri = t?.str("context.uri"),
        timestampMs = f.long("timestamp") ?: 0,
        contextName = t?.str("context.name")?.takeIf { it.isNotBlank() },
        trackImageUrl = t?.str("imageUrl")?.takeIf { it.isNotBlank() },
    )
}.sortedByDescending { it.timestampMs }
