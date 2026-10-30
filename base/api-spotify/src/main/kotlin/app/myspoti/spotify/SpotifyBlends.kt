package app.myspoti.spotify

import app.myspoti.api.BlendGroup
import app.myspoti.api.BlendInvitation
import app.myspoti.api.BlendInvitationState
import app.myspoti.api.BlendInvite
import app.myspoti.api.BlendMember
import app.myspoti.api.BlendStory
import app.myspoti.api.Blends
import app.myspoti.api.Uri
import app.myspoti.spotify.bridge.Bridge
import app.myspoti.spotify.gql.GqlException
import app.myspoti.spotify.gql.HttpException
import app.myspoti.spotify.gql.SpotifyHttp
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Blends over Spotify's own `blend-invitation` and `blend-writer` services (spclient): the Web
 * Player's Blend routes call generate, view-invitation and join; the Blend page's members, its
 * taste-match card, renaming and leaving are the calls the mobile apps make for the same page.
 * There is no GraphQL operation and no page service for it.
 * An invitation made with [invite] shows as a placeholder in the library of its maker until
 * someone joins; only then does the Blend playlist exist on Spotify's side.
 */
class SpotifyBlends(private val http: SpotifyHttp) : Blends {
    private companion object {
        const val BASE = "https://spclient.wg.spotify.com/blend-invitation"
        const val WRITER = "https://spclient.wg.spotify.com/blend-writer"
        /** The link's path: `/blend/taste-match/{id}` (what the page generates) or `/blend/invitation/{id}`. */
        val LINK_ID = Regex("""/blend/(?:taste-match|invitation)/([A-Za-z0-9]+)""")
        val BARE_ID = Regex("""[A-Za-z0-9]{8,}""")
    }

    override suspend fun invite(): BlendInvite {
        val body = send("POST", "$BASE/v1/generate")
        val link = body.str("invite") ?: throw GqlException("blend-invitation", "no invite link")
        return BlendInvite(idOf(link), link)
    }

    override suspend fun invitation(linkOrId: String): BlendInvitation =
        parseInvitation(send("GET", "$BASE/v3/view-invitation/${idOf(linkOrId)}"))

    /** The `view-invitation` answer, as the page's three Blend screens read it. */
    internal fun parseInvitation(v: JsonElement): BlendInvitation {
        fun member(m: JsonElement?) = m.str("username")?.let { BlendMember(it, m.str("name") ?: it, m.str("image_url")) }
        return BlendInvitation(
            state = when (v.str("page_type")) {
                "PENDING_INVITATION" -> BlendInvitationState.WAITING
                "READY_TO_JOIN_EMPTY_BLEND", "READY_TO_JOIN_ALREADY_CREATED_BLEND" -> BlendInvitationState.JOINABLE
                "MAX_MEMBERS" -> BlendInvitationState.FULL
                "ALREADY_JOINED" -> BlendInvitationState.JOINED
                "DELETED" -> BlendInvitationState.DELETED
                else -> BlendInvitationState.UNKNOWN
            },
            title = v.str("title"), subtitle = v.str("subtitle"),
            inviter = member(v.at("recipient")),
            members = v.list("members").mapNotNull { member(it) },
            playlist = v.str("playlist_uri"),
        )
    }

    override suspend fun join(linkOrId: String): Uri {
        val r = send("PUT", "$BASE/v2/join/${idOf(linkOrId)}")
        return r.str("playlist_uri") ?: throw GqlException("blend-invitation", "no playlist after joining")
    }

    override suspend fun group(playlist: Uri): BlendGroup = parseGroup(send("GET", "$BASE/v3/get-invitation/${idOfPlaylist(playlist)}"))

    internal fun parseGroup(v: JsonElement) = BlendGroup(
        title = v.str("title"), inviteLabel = v.str("button_text"), link = v.str("invitation_link"),
        members = v.list("members").mapNotNull { m -> m.str("username")?.let { BlendMember(it, m.str("name") ?: it, m.str("image_url")?.takeIf { u -> u.isNotBlank() }) } },
    )

    override suspend fun story(playlist: Uri): BlendStory? = parseStory(send("GET", "$BASE/v3/data-stories/${idOfPlaylist(playlist)}"))

    /** The intro card (the match score) with the first story's line on the song that brings them together. */
    internal fun parseStory(v: JsonElement): BlendStory? {
        val intro = v.at("intro_story")
        val first = v.list("stories").firstOrNull()
        val title = intro.at("title1").str("text") ?: first.at("subtitle").str("text") ?: return null
        return BlendStory(
            title = title,
            subtitle = intro.at("subtitle1").str("text"),
            detail = first.at("body").str("text"),
            background = intro.str("background_color") ?: first.str("background_color"),
            titleColor = intro.at("title1").str("text_color"),
            textColor = intro.at("subtitle1").str("text_color") ?: first.at("body").str("text_color"),
            image = first.str("image"),
        )
    }

    override suspend fun rename(playlist: Uri, name: String) {
        send("PUT", "$WRITER/v1/rename/${idOfPlaylist(playlist)}", buildJsonObject { put("name", name) }.toString())
    }

    override suspend fun leave(playlist: Uri) {
        send("PUT", "$BASE/v1/leave", buildJsonObject { put("playlist_uri", playlist) }.toString())
    }

    private fun idOfPlaylist(playlist: Uri) = playlist.substringAfterLast(':')

    /** The invitation id inside a pasted link, or the text itself when it is already an id. */
    internal fun idOf(linkOrId: String): String {
        val t = linkOrId.trim()
        return LINK_ID.find(t)?.groupValues?.get(1)
            ?: t.takeIf { BARE_ID.matches(it) }
            ?: throw IllegalArgumentException("not a Blend invitation: $t")
    }

    private suspend fun send(method: String, url: String, body: String? = null): JsonElement {
        val (code, text) = http.send(method, url, body)
        if (code !in 200..299) throw HttpException(code, text.take(200))
        return if (text.isBlank()) JsonNull else Bridge.json.parseToJsonElement(text)
    }
}
