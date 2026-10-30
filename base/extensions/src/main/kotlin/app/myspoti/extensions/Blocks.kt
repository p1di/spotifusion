package app.myspoti.extensions

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * What an extension adds to the app's screens, described as JSON and drawn by the app itself:
 * the app's fonts, colours, spacing and icons, never the extension's. The only free page is
 * [Action.WEBVIEW_PAGE]. See docs/extensions/API.md §6 for the schema.
 *
 * A block opens a Spotify page ([Target.open], a `spotify:` uri the app opens itself) or calls the
 * extension back ([Target.action], delivered to its `tap` hook). Nothing else: no style, no
 * position, no layer; a block cannot reach a [ProtectedSurface].
 */
sealed interface Block {
    /** A titled group of blocks (Home; elsewhere its blocks are shown under its title). */
    data class Section(val title: String, val subtitle: String?, val blocks: List<Block>) : Block

    data class Card(val title: String, val subtitle: String?, val image: String?, val target: Target?) : Block

    /** A list of Spotify items of one [kind], drawn as the app draws its own lists. */
    data class Items(val kind: ItemKind, val title: String?, val items: List<Item>) : Block

    data class Button(val label: String, val icon: String, val action: String) : Block

    /** An entry of a ⋯ menu. */
    data class MenuEntry(val label: String, val icon: String, val action: String) : Block

    data class Text(val text: String, val style: TextStyle) : Block

    data class Image(val url: String, val ratio: Float, val target: Target?) : Block
}

enum class ItemKind(val key: String, val uriType: String) {
    TRACKS("tracks", "track"), ALBUMS("albums", "album"), PLAYLISTS("playlists", "playlist"), ARTISTS("artists", "artist");
}

enum class TextStyle { TITLE, BODY, CAPTION }

data class Item(val uri: String, val title: String, val subtitle: String?, val image: String?)

/** Where a tap goes: a Spotify page the app opens, or the extension's `tap` hook with [action]. */
data class Target(val open: String?, val action: String?)

object Blocks {
    /** The icons a block may name; anything else is drawn as [DEFAULT_ICON]. The app maps each to its own. */
    val ICONS = listOf(
        "add", "album", "artist", "bolt", "bookmark", "chart", "check", "download", "edit", "extension", "favorite", "headphones",
        "info", "language", "link", "lyrics", "mic", "music", "open", "play", "playlist", "queue", "radio", "refresh",
        "repeat", "search", "settings", "share", "shuffle", "star", "timer", "translate", "tune", "volume",
    )
    const val DEFAULT_ICON = "extension"

    const val MAX_TOP = 10
    const val MAX_IN_SECTION = 12
    const val MAX_ITEMS = 50
    const val MAX_MENU = 4
    const val MAX_BUTTONS = 3
    private const val LABEL = 40
    private const val TITLE = 80
    private const val TEXT = 1_000

    private val ACTION_ID = Regex("^[A-Za-z0-9_.:-]{1,64}$")
    private val SPOTIFY_URI = Regex("^spotify:(track|album|playlist|artist|show|episode):[A-Za-z0-9]{1,40}$")

    /** The block types a [Surface] accepts. */
    fun allowed(surface: Surface): Set<String> = when (surface) {
        Surface.HOME, Surface.SEARCH, Surface.TRACK_INFO -> setOf("section", "card", "tracks", "albums", "playlists", "artists", "button", "text", "image")
        Surface.TRACK_MENU, Surface.ALBUM_MENU, Surface.PLAYLIST_MENU, Surface.ARTIST_MENU -> setOf("menu")
        Surface.PLAYER -> setOf("button")
        Surface.PAGE -> emptySet()
    }

    /**
     * An extension's answer for [surface]: a list of blocks (or one block). Home's loose blocks
     * are gathered in one untitled section. A block of an unknown type is left out (a newer
     * extension on an older app); one that breaks the rules fails the whole answer.
     */
    fun parse(json: String, surface: Surface): List<Block> {
        val root = runCatching { ExtJson.parseToJsonElement(json) }.getOrNull() ?: throw ExtensionException("blocks: not JSON")
        val list = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["blocks"] as? JsonArray) ?: JsonArray(listOf(root))
            else -> throw ExtensionException("blocks: a list of blocks")
        }
        val max = when (surface) {
            Surface.TRACK_MENU, Surface.ALBUM_MENU, Surface.PLAYLIST_MENU, Surface.ARTIST_MENU -> MAX_MENU
            Surface.PLAYER -> MAX_BUTTONS
            else -> MAX_TOP
        }
        if (list.size > max) throw ExtensionException("blocks: $max at most here (${surface.key}), ${list.size} given")
        val out = list.mapNotNull { block(it, allowed(surface), top = true) }
        if (surface != Surface.HOME) return out
        // Home is a list of sections: loose blocks go together under no title.
        val loose = out.filterNot { it is Block.Section }
        return out.filterIsInstance<Block.Section>() + if (loose.isEmpty()) emptyList() else listOf(Block.Section("", null, loose))
    }

    private fun block(e: JsonElement, allowed: Set<String>, top: Boolean): Block? {
        val o = e as? JsonObject ?: throw ExtensionException("blocks: each block is an object { type, … }")
        val type = str(o, "type") ?: throw ExtensionException("blocks: a block without its \"type\"")
        if (type !in ALL) return null
        if (type !in allowed) throw ExtensionException("blocks: \"$type\" is not allowed here (${allowed.joinToString().ifEmpty { "nothing" }})")
        return when (type) {
            "section" -> {
                if (!top) throw ExtensionException("blocks: a section cannot hold another section")
                val inner = o["blocks"] as? JsonArray ?: throw ExtensionException("blocks: a section needs \"blocks\"")
                if (inner.size > MAX_IN_SECTION) throw ExtensionException("blocks: $MAX_IN_SECTION blocks at most in a section")
                Block.Section(text(o, "title", TITLE).orEmpty(), text(o, "subtitle", TITLE), inner.mapNotNull { block(it, allowed - "section", top = false) })
            }
            "card" -> Block.Card(need(o, "title", TITLE, type), text(o, "subtitle", TITLE), image(o, "image"), target(o, type))
            "tracks", "albums", "playlists", "artists" -> {
                val kind = ItemKind.entries.first { it.key == type }
                val raw = o["items"] as? JsonArray ?: throw ExtensionException("blocks: \"$type\" needs \"items\"")
                if (raw.size > MAX_ITEMS) throw ExtensionException("blocks: $MAX_ITEMS items at most")
                Block.Items(kind, text(o, "title", TITLE), raw.map { item(it, kind) })
            }
            "button" -> Block.Button(need(o, "label", LABEL, type), icon(o), action(o, type) ?: throw ExtensionException("blocks: a button needs its \"action\""))
            "menu" -> Block.MenuEntry(need(o, "label", LABEL, type), icon(o), action(o, type) ?: throw ExtensionException("blocks: a menu entry needs its \"action\""))
            "text" -> Block.Text(need(o, "text", TEXT, type), when (str(o, "style")) { "title" -> TextStyle.TITLE; "caption" -> TextStyle.CAPTION; else -> TextStyle.BODY })
            "image" -> Block.Image(image(o, "url") ?: throw ExtensionException("blocks: an image needs its \"url\""),
                ((o["ratio"] as? JsonPrimitive)?.doubleOrNull?.toFloat() ?: 1.78f).coerceIn(0.5f, 3f), target(o, type))
            else -> null
        }
    }

    private val ALL = setOf("section", "card", "tracks", "albums", "playlists", "artists", "button", "menu", "text", "image")

    private fun item(e: JsonElement, kind: ItemKind): Item {
        val o = e as? JsonObject ?: throw ExtensionException("blocks: each item is an object { uri, title }")
        val uri = str(o, "uri") ?: throw ExtensionException("blocks: an item needs its \"uri\"")
        if (!SPOTIFY_URI.matches(uri) || uri.split(':')[1] != kind.uriType) throw ExtensionException("blocks: \"$uri\" is not a spotify:${kind.uriType}: uri")
        return Item(uri, need(o, "title", TITLE, "item"), text(o, "subtitle", TITLE), image(o, "image"))
    }

    private fun target(o: JsonObject, type: String): Target? {
        val open = str(o, "open")?.also { if (!SPOTIFY_URI.matches(it)) throw ExtensionException("blocks: \"open\" is a spotify: uri of a song, album, playlist, artist, show or episode") }
        val action = action(o, type)
        if (open != null && action != null) throw ExtensionException("blocks: \"$type\" has \"open\" or \"action\", not both")
        return if (open == null && action == null) null else Target(open, action)
    }

    private fun action(o: JsonObject, type: String): String? = str(o, "action")?.also {
        if (!ACTION_ID.matches(it)) throw ExtensionException("blocks: $type action \"$it\": 1 to 64 letters, digits, _ . : -")
    }

    private fun icon(o: JsonObject): String = str(o, "icon")?.takeIf { it in ICONS } ?: DEFAULT_ICON

    /** An https image on a public host. */
    private fun image(o: JsonObject, key: String): String? = str(o, key)?.also {
        if (!it.startsWith("https://")) throw ExtensionException("blocks: $key must be https")
        checkUrl("blocks", it)
    }

    private fun need(o: JsonObject, key: String, max: Int, type: String): String =
        text(o, key, max)?.takeIf { it.isNotBlank() } ?: throw ExtensionException("blocks: \"$type\" needs \"$key\"")

    private fun text(o: JsonObject, key: String, max: Int): String? = str(o, key)?.trim()?.take(max)

    private fun str(o: JsonObject, key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}

/** What a page hook answers: a title and the HTML the app shows in its sandboxed WebView. */
data class PageContent(val title: String, val html: String) {
    companion object {
        const val MAX_HTML = 512 * 1024

        fun parse(json: String, fallbackTitle: String): PageContent {
            val o = runCatching { ExtJson.parseToJsonElement(json) as JsonObject }.getOrNull() ?: throw ExtensionException("page: an object { title, html }")
            val html = (o["html"] as? JsonPrimitive)?.contentOrNull ?: throw ExtensionException("page: \"html\" is needed")
            if (html.length > MAX_HTML) throw ExtensionException("page: html over ${MAX_HTML / 1024} KB")
            val title = (o["title"] as? JsonPrimitive)?.contentOrNull?.trim()?.take(40)?.ifBlank { null } ?: fallbackTitle
            return PageContent(title, html)
        }
    }
}

/**
 * What an extension's web page ([Action.WEBVIEW_PAGE]) may load: GET over https from the hosts
 * its network action lists (any host only when `network.any` is granted), never a local address
 * nor a Spotify one (the app's own WebView is signed in there). The app fetches what is allowed
 * itself, without cookies, and refuses the rest; [csp] is put at the top of the page as well.
 */
object PageNetwork {
    fun allows(url: String, method: String, listed: List<String>, networkGranted: Boolean, anyGranted: Boolean): Boolean {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (uri.scheme != "https" || !method.equals("GET", ignoreCase = true)) return false
        if (ExtensionRuntime.isPrivate(host) || RawRequest.isSpotifyHost(host)) return false
        return (anyGranted && "*" in listed) || (networkGranted && listed.any { it != "*" && ExtensionRuntime.hostMatches(it, host) })
    }

    /** No navigation, no frames, no forms; scripts and styles of the page itself; loads only from the hosts allowed. */
    fun csp(listed: List<String>, networkGranted: Boolean, anyGranted: Boolean): String {
        val hosts = when {
            anyGranted && "*" in listed -> "https:"
            networkGranted -> listed.filter { it != "*" && !RawRequest.isSpotifyHost(it.removePrefix("*.")) }.joinToString(" ") { "https://$it" }
            else -> ""
        }
        return "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: $hosts; media-src data: $hosts; " +
            "font-src data: $hosts; connect-src $hosts; frame-src 'none'; form-action 'none'; base-uri 'none'"
    }

    /** [html] with the [csp] first, so it applies before anything of the page. */
    fun wrap(html: String, csp: String): String =
        "<meta http-equiv=\"Content-Security-Policy\" content=\"${csp.replace("\"", "")}\">\n$html"
}
