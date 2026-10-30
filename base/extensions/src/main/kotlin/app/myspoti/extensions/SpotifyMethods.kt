package app.myspoti.extensions

import app.myspoti.api.Blends
import app.myspoti.api.ItemKind
import app.myspoti.api.LibraryFilter
import app.myspoti.api.LibrarySort
import app.myspoti.api.MyspotiApi
import app.myspoti.api.NowPlaying
import app.myspoti.api.PlaylistAccess
import app.myspoti.api.PlaylistSort
import app.myspoti.api.Repeat
import app.myspoti.api.TimeRange
import app.myspoti.api.HomeFilter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** One `myspoti.spotify.<area>.<method>(...)` call, as the sandbox hands it to the host. */
data class SpotifyCall(val area: String, val method: String, val argsJson: String)

/** What the raw door reaches: the Web Player's GraphQL operations, page services and Spotify URLs. */
interface SpotifyRaw {
    suspend fun gql(operation: String, variables: JsonObject): JsonElement
    fun operations(): Set<String>
    suspend fun service(service: String, method: String, args: List<JsonElement>): JsonElement
    /** A GET on a Spotify address, with the account's token (the Spotify layer refuses other hosts). */
    suspend fun get(url: String): JsonElement?
    /** Whether the GraphQL operation changes something. */
    fun isMutation(operation: String): Boolean
    /**
     * Any method on a Spotify address ([RawRequest] checked it): `*.spotify.com` with the account's
     * credentials, `*.spotifycdn.com` without. `{ status, headers, body }`, a 4xx/5xx included.
     * [body] is sent as these bytes (a script's text as UTF-8, or the bytes of its base64).
     */
    suspend fun request(method: String, url: String, body: ByteArray?, headers: Map<String, String>): JsonElement =
        throw UnsupportedOperationException("this app has no raw Spotify requests for extensions")
}

/** The rules of `raw.request`, checked before the host is asked. */
object RawRequest {
    val METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")
    /** What only reads: never confirmed. */
    val READS = setOf("GET", "HEAD")

    /** The upper-case method, or why the request is refused. */
    fun check(method: String, url: String): String {
        val m = method.uppercase()
        require(m in METHODS) { "method \"$method\" (${METHODS.joinToString()})" }
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: throw IllegalArgumentException("not a URL: $url")
        val host = uri.host?.lowercase() ?: throw IllegalArgumentException("no host in $url")
        require(uri.scheme == "https") { "https only" }
        require(isSpotifyHost(host)) { "$host is not a Spotify address (*.spotify.com, *.spotifycdn.com)" }
        return m
    }

    /**
     * The bytes of a script's body: its text as UTF-8, or, with `{ encoding: 'base64' }` as the
     * fifth argument, the bytes that base64 stands for (a binary body: a script has only text).
     */
    fun bytes(body: String, a: SpotifyArgs): ByteArray {
        val encoding = if (a.size > 4) (a.obj(4, "options")["encoding"] as? JsonPrimitive)?.contentOrNull else null
        return when (encoding) {
            null, "utf-8" -> body.toByteArray()
            "base64" -> runCatching { java.util.Base64.getDecoder().decode(body) }
                .getOrElse { throw IllegalArgumentException("raw.request: the body is not base64") }
            else -> throw IllegalArgumentException("raw.request: encoding \"$encoding\" (utf-8, base64)")
        }
    }

    /** The answer as scripts get it: `{ status, headers, body }`. */
    fun response(status: Int, headers: Map<String, String>, body: String): JsonElement = JsonObject(mapOf(
        "status" to JsonPrimitive(status),
        "headers" to JsonObject(headers.mapValues { JsonPrimitive(it.value) }),
        "body" to JsonPrimitive(body),
    ))

    fun isSpotifyHost(host: String): Boolean =
        host == "spotify.com" || host.endsWith(".spotify.com") || host == "spotifycdn.com" || host.endsWith(".spotifycdn.com")

    /**
     * The operation a request body names, for endpoints that multiplex many operations on one
     * path (GraphQL): `operationName`, else the persisted query's hash (`sha256:...`). A JSON
     * list (a batch) gives all of them joined by ",". Null when none can be determined.
     */
    fun operationOf(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val root = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(body) }.getOrNull() ?: return null
        fun one(e: JsonElement): String? {
            val o = e as? JsonObject ?: return null
            (o["operationName"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.take(120) }
            val hash = (((o["extensions"] as? JsonObject)?.get("persistedQuery") as? JsonObject)?.get("sha256Hash") as? JsonPrimitive)
                ?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
            return hash?.let { "sha256:" + it.take(64) }
        }
        if (root is JsonArray) {
            if (root.isEmpty() || root.size > 16) return null
            val ops = root.map { one(it) ?: return null }
            return ops.distinct().sorted().joinToString(",")
        }
        return one(root)
    }

    /** Whether the path itself is a multiplexed endpoint (GraphQL / pathfinder / generic query). */
    fun isMultiplexedPath(path: String): Boolean {
        val p = path.lowercase()
        return "pathfinder" in p || "graphql" in p || p.trimEnd('/').endsWith("/query")
    }

    /** The text of a request's body argument, as [bytes] decodes it; null without one. */
    fun bodyText(a: SpotifyArgs): String? {
        val e = if (a.size > 2) a.json(2, "body").takeUnless { it is JsonNull } else null
        return when (e) {
            null -> null
            is JsonPrimitive -> if (e.isString) e.content else e.toString()
            else -> e.toString()
        }
    }

    /**
     * What "always allow" would grant for this write, or null when it must be asked each time:
     * on a multiplexed endpoint ([isMultiplexedPath], or any body naming an operation) the grant
     * names the operation, and without a determinable operation there is no grant to offer.
     */
    fun offerFor(method: String, url: String, body: String?): RawGrant? {
        val op = operationOf(body)
        val base = offer(method, url, op)
        if (op == null && isMultiplexedPath(java.net.URI(url).rawPath.orEmpty())) return null
        return base
    }

    /** Up to this many segments in a path a grant covers. */
    private const val MAX_SEGMENTS = 16

    /**
     * The grant the user is offered for a write: its method and host, and its path with every
     * segment that names one thing (an id, a number, a uri) as `*`, so the same kind of request on
     * another song or playlist is covered and nothing else.
     */
    fun offer(method: String, url: String, op: String? = null): RawGrant {
        val uri = java.net.URI(url)
        val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }.take(MAX_SEGMENTS)
        return RawGrant(method.uppercase(), uri.host.lowercase(), "/" + segments.joinToString("/") { if (isNameOfOne(it)) "*" else it }, op.orEmpty())
    }

    /**
     * Whether one of [grants] covers [method] on [url] (the query is not looked at). On a
     * multiplexed request (its path, or an operation in the [body]) only a grant naming that very
     * operation covers it; one without an operation never does.
     */
    fun covered(grants: List<RawGrant>, method: String, url: String, body: String? = null): Boolean {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
        val op = operationOf(body)
        val multiplexed = op != null || isMultiplexedPath(uri.rawPath.orEmpty())
        if (multiplexed && op == null) return false
        return grants.any { it.method == method.uppercase() && it.host == host && it.matches(segments) && (!multiplexed || it.op == op) }
    }

    private fun isNameOfOne(segment: String): Boolean =
        segment.any { it.isDigit() } && (segment.length >= 8 || segment.all { it.isDigit() }) || ':' in segment || segment.contains("%3A", ignoreCase = true)
}

/**
 * A write the user allowed for good to one extension: [method] on [host], on a path matching
 * [path], where a `*` segment stands for any one segment. Kept by the host, listed on the
 * extension's page and revocable there; a request it covers runs without asking.
 */
@kotlinx.serialization.Serializable
data class RawGrant(val method: String, val host: String, val path: String, val op: String = "") {
    fun matches(segments: List<String>): Boolean {
        val mine = path.split('/').filter { it.isNotEmpty() }
        return mine.size == segments.size && mine.zip(segments).all { (p, s) -> p == "*" || p == s }
    }

    override fun toString() = "$method https://$host$path" + if (op.isNotEmpty()) " [operation $op]" else ""

    /** An old grant on a multiplexed endpoint, without an operation: it covered every operation, so it is dropped. */
    val tooBroad: Boolean get() = op.isEmpty() && RawRequest.isMultiplexedPath(path)
}

/** The user's answer to a write: no, this once, or always for the offered [RawGrant]. */
enum class Consent { REFUSE, ONCE, ALWAYS }

/**
 * The player's media manifests: what Spotify sent the player for a song it has queued (formats
 * and file ids), as it sent it. Read only; null for a song the player has not been sent.
 */
fun interface PlayerMedia {
    fun manifest(trackUri: String): JsonElement?
}

/** What a method runs on: the typed API, the raw door and the player's media when the host has them. */
class SpotifyBackend(val api: MyspotiApi, val raw: SpotifyRaw?, val media: PlayerMedia? = null)

/**
 * One method an extension may call. [action] is what the user must have granted;
 * [destructive] says whether it removes or clears something, so the user is asked first
 * (its second argument tells whether a raw GraphQL operation is a mutation).
 */
class SpotifyMethod internal constructor(
    val area: String,
    val name: String,
    val action: Action,
    /** Finishes "… wants to <label>" in the confirmation. */
    val label: String,
    internal val destructive: (SpotifyArgs, (String) -> Boolean) -> Boolean,
    internal val run: suspend (SpotifyBackend, SpotifyArgs) -> Any?,
) {
    val key get() = "$area.$name"

    fun isDestructive(args: SpotifyArgs, isMutation: (String) -> Boolean): Boolean = destructive(args, isMutation)
}

/** The positional arguments of a call, read the way the typed API wants them; a wrong one fails with its name. */
class SpotifyArgs(private val list: JsonArray, private val where: String) {
    val size get() = list.size

    private fun at(i: Int): JsonElement? = list.getOrNull(i)?.takeUnless { it is JsonNull }
    private fun bad(i: Int, name: String, wants: String): Nothing =
        throw IllegalArgumentException("$where: $name (argument ${i + 1}) must be $wants")
    private fun prim(i: Int, name: String, wants: String): JsonPrimitive? =
        at(i)?.let { it as? JsonPrimitive ?: bad(i, name, wants) }

    fun str(i: Int, name: String): String = strOrNull(i, name) ?: bad(i, name, "a string")
    fun strOrNull(i: Int, name: String): String? = prim(i, name, "a string")?.takeIf { it.isString }?.content ?: if (at(i) != null) bad(i, name, "a string") else null

    fun int(i: Int, name: String, default: Int): Int = longOrNull(i, name)?.toInt() ?: default
    fun long(i: Int, name: String): Long = longOrNull(i, name) ?: bad(i, name, "a number")
    fun longOrNull(i: Int, name: String): Long? = prim(i, name, "a number")?.let { it.longOrNull ?: it.doubleOrNull?.toLong() ?: bad(i, name, "a number") }
    fun double(i: Int, name: String): Double = prim(i, name, "a number")?.doubleOrNull ?: bad(i, name, "a number")
    fun bool(i: Int, name: String): Boolean = prim(i, name, "true or false")?.booleanOrNull ?: bad(i, name, "true or false")
    fun bool(i: Int, name: String, default: Boolean): Boolean = if (at(i) == null) default else bool(i, name)

    fun strings(i: Int, name: String): List<String> {
        val a = at(i) as? JsonArray ?: bad(i, name, "a list of strings")
        if (a.size > MAX_LIST) bad(i, name, "a list of $MAX_LIST strings at most")
        return a.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: bad(i, name, "a list of strings") }
    }

    fun json(i: Int, name: String): JsonElement = at(i) ?: bad(i, name, "given")
    fun obj(i: Int, name: String): JsonObject = (at(i) ?: JsonObject(emptyMap())) as? JsonObject ?: bad(i, name, "an object")
    fun rest(from: Int): List<JsonElement> = list.drop(from)

    /** A queue item: `{ uri, uid? }`, or the whole item `player.queue()` gave. */
    fun queueItem(i: Int, name: String): NowPlaying {
        val o = at(i) as? JsonObject ?: bad(i, name, "an object { uri, uid }")
        val uri = (o["uri"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: bad(i, name, "an object with a uri")
        val uid = (o["uid"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return NowPlaying(ItemKind.TRACK, uri, uid, name = "", artists = emptyList(), album = null, imageUrl = null, durationMs = 0)
    }

    fun queueItems(i: Int, name: String): List<NowPlaying> {
        val a = at(i) as? JsonArray ?: bad(i, name, "a list of { uri, uid }")
        if (a.size > MAX_LIST) bad(i, name, "a list of $MAX_LIST items at most")
        return a.indices.map { k -> SpotifyArgs(JsonArray(listOf(a[k])), where).queueItem(0, name) }
    }

    inline fun <reified E : Enum<E>> enum(i: Int, name: String, default: E): E = enumIn(i, name, enumValues<E>(), default)

    fun <E : Enum<E>> enumIn(i: Int, name: String, values: Array<E>, default: E): E {
        val s = strOrNull(i, name) ?: return default
        return values.firstOrNull { it.name.equals(s, ignoreCase = true) }
            ?: throw IllegalArgumentException("$where: $name must be one of ${values.joinToString { it.name.lowercase() }}")
    }

    companion object {
        const val MAX_LIST = 1_000
    }
}

/**
 * Every method of `myspoti.spotify`, with the right it needs and whether the user is asked before
 * it runs. The one place where that is decided: a method that is not here is refused, and the
 * script's `myspoti.spotify` is built from this table.
 *
 * Reading Spotify's public content is [Action.SPOTIFY_CATALOG]; what belongs to the account is
 * [Action.LIBRARY_READ], what plays and the queue [Action.PLAYBACK_EVENTS]; driving playback is
 * [Action.PLAYBACK_CONTROL], the queue [Action.PLAYBACK_QUEUE]; changing the library and account
 * [Action.LIBRARY_WRITE], playlists and Blends [Action.PLAYLISTS_WRITE]; the raw door is
 * [Action.SPOTIFY_RAW]. They are separate: writing does not give reading. Not exposed: Jam (a live shared session, not a thing to script).
 */
object SpotifyMethods {
    private val CATALOG = Action.SPOTIFY_CATALOG
    private val READ = Action.LIBRARY_READ
    private val NOW = Action.PLAYBACK_EVENTS
    private val CONTROL = Action.PLAYBACK_CONTROL
    private val QUEUE = Action.PLAYBACK_QUEUE
    private val LIBRARY = Action.LIBRARY_WRITE
    private val PLAYLISTS = Action.PLAYLISTS_WRITE
    private val RAW = Action.SPOTIFY_RAW

    private val table = LinkedHashMap<String, SpotifyMethod>()

    fun find(area: String, name: String): SpotifyMethod? = table["$area.$name"]
    fun all(): Collection<SpotifyMethod> = table.values

    /** `{ area: [method, ...] }`, what the script's `myspoti.spotify` is built from. */
    fun names(): Map<String, List<String>> = table.values.groupBy({ it.area }, { it.name })

    private fun add(
        area: String, name: String, action: Action, label: String,
        destructive: (SpotifyArgs, (String) -> Boolean) -> Boolean = { _, _ -> false },
        run: suspend (SpotifyBackend, SpotifyArgs) -> Any?,
    ) {
        val m = SpotifyMethod(area, name, action, label, destructive, run)
        check(table.put(m.key, m) == null) { "${m.key} listed twice" }
    }

    /** A method of the typed API: [body] has the [MyspotiApi] as its receiver. */
    private fun typed(
        area: String, name: String, action: Action, label: String = name,
        destructive: Boolean = false, body: suspend MyspotiApi.(SpotifyArgs) -> Any?,
    ) = add(area, name, action, label, { _, _ -> destructive }) { b, a -> b.api.body(a) }

    /** Like [typed], destructive only when [whenArgs] says so for these arguments. */
    private fun typedIf(
        area: String, name: String, action: Action, label: String,
        whenArgs: (SpotifyArgs) -> Boolean, body: suspend MyspotiApi.(SpotifyArgs) -> Any?,
    ) = add(area, name, action, label, { a, _ -> whenArgs(a) }) { b, a -> b.api.body(a) }

    private fun raw(b: SpotifyBackend): SpotifyRaw = b.raw ?: throw UnsupportedOperationException("this app has no raw Spotify door for extensions")

    init {
        // ── player ──
        typed("player", "state", NOW, "read what is playing") { player.state.value }
        typed("player", "queue", NOW, "read the queue") { player.queue.value }
        typed("player", "volume", NOW, "read the volume") { player.volume() }
        // Also myspoti.spotify.media(trackUri) in the script.
        add("player", "media", NOW, "read a song's media manifest") { b, a ->
            val uri = a.str(0, "trackUri")
            require(uri.startsWith("spotify:track:")) { "spotify.player.media: trackUri must be a spotify:track: uri" }
            val m = b.media ?: throw UnsupportedOperationException("this app has no player media for extensions")
            m.manifest(uri)?.let { JsonObject(mapOf("uri" to JsonPrimitive(uri), "manifest" to it)) }
        }
        typed("player", "play", CONTROL, "start playing something") { a ->
            player.play(a.str(0, "contextUri"), a.strOrNull(1, "trackUri"), a.longOrNull(2, "index")?.toInt(), a.longOrNull(3, "positionMs"),
                if (a.size > 4 && a.json(4, "order") !is JsonNull) a.strings(4, "order") else null)
        }
        typed("player", "pause", CONTROL, "pause playback") { player.pause() }
        typed("player", "resume", CONTROL, "resume playback") { player.resume() }
        typed("player", "seek", CONTROL, "move in the song") { a -> player.seek(a.long(0, "positionMs")) }
        typed("player", "next", CONTROL, "skip to the next song") { player.next() }
        typed("player", "previous", CONTROL, "go back to the previous song") { player.previous() }
        typed("player", "setShuffle", CONTROL, "change shuffle") { a -> player.setShuffle(a.bool(0, "on")) }
        typed("player", "setRepeat", CONTROL, "change repeat") { a -> player.setRepeat(a.enum(0, "mode", Repeat.OFF)) }
        typed("player", "addToQueue", QUEUE, "add songs to the queue") { a -> player.addToQueue(a.strings(0, "uris")) }
        typed("player", "playNext", QUEUE, "put songs next in the queue") { a -> player.playNext(a.strings(0, "uris")) }
        typed("player", "removeFromQueue", QUEUE, "remove songs from the queue", destructive = true) { a -> player.removeFromQueue(a.queueItems(0, "items")) }
        typed("player", "clearQueue", QUEUE, "clear the queue", destructive = true) { player.clearQueue() }
        typed("player", "skipTo", CONTROL, "jump to a song of the queue") { a -> player.skipTo(a.queueItem(0, "item")) }
        typed("player", "moveInQueue", QUEUE, "reorder the queue") { a -> player.moveInQueue(a.queueItem(0, "item"), a.queueItem(1, "target"), a.bool(2, "after", false)) }
        typed("player", "setVolume", CONTROL, "change the volume") { a -> player.setVolume(a.double(0, "level")) }
        typed("player", "setSpeed", CONTROL, "change the playback speed") { a -> player.setSpeed(a.double(0, "speed")) }
        typed("player", "setLocalMute", Action.PLAYBACK_MIRROR, "keep playing unheard on this phone") { a -> player.setLocalMute(a.bool(0, "on")) }

        // ── search ──
        typed("search", "all", CATALOG) { a -> search.all(a.str(0, "query"), a.int(1, "limit", 10)) }
        fun paged(name: String, call: suspend MyspotiApi.(String, Int, Int) -> Any?) =
            typed("search", name, CATALOG) { a -> call(a.str(0, "query"), a.int(1, "offset", 0), a.int(2, "limit", 30)) }
        paged("tracks") { q, o, l -> search.tracks(q, o, l) }
        paged("artists") { q, o, l -> search.artists(q, o, l) }
        paged("albums") { q, o, l -> search.albums(q, o, l) }
        paged("playlists") { q, o, l -> search.playlists(q, o, l) }
        paged("shows") { q, o, l -> search.shows(q, o, l) }
        paged("episodes") { q, o, l -> search.episodes(q, o, l) }
        paged("audiobooks") { q, o, l -> search.audiobooks(q, o, l) }
        paged("users") { q, o, l -> search.users(q, o, l) }
        paged("genres") { q, o, l -> search.genres(q, o, l) }
        typed("search", "suggestions", CATALOG) { a -> search.suggestions(a.str(0, "query"), a.int(1, "limit", 10)) }
        typed("search", "recent", READ, "read the account's recent searches") { search.recent() }
        typed("search", "clearRecent", LIBRARY, "clear the account's recent searches", destructive = true) { search.clearRecent() }

        // ── catalog ──
        typed("catalog", "track", CATALOG) { a -> catalog.track(a.str(0, "uri")) }
        typed("catalog", "album", CATALOG) { a -> catalog.album(a.str(0, "uri")) }
        typed("catalog", "albumTracks", CATALOG) { a -> catalog.albumTracks(a.str(0, "uri"), a.int(1, "offset", 0), a.int(2, "limit", 50)) }
        typed("catalog", "artist", CATALOG) { a -> catalog.artist(a.str(0, "uri")) }
        typed("catalog", "artistTopTracks", CATALOG) { a -> catalog.artistTopTracks(a.str(0, "uri")) }
        typed("catalog", "artistPage", CATALOG) { a -> catalog.artistPage(a.str(0, "uri")) }
        typed("catalog", "isrc", CATALOG) { a -> catalog.isrc(a.str(0, "trackUri")) }
        typed("catalog", "discography", CATALOG) { a -> catalog.discography(a.str(0, "uri"), a.int(1, "offset", 0), a.int(2, "limit", 50)) }
        typed("catalog", "relatedArtists", CATALOG) { a -> catalog.relatedArtists(a.str(0, "uri")) }
        typed("catalog", "appearsOn", CATALOG) { a -> catalog.appearsOn(a.str(0, "uri")) }
        typed("catalog", "similarAlbums", CATALOG) { a -> catalog.similarAlbums(a.str(0, "trackUri"), a.int(1, "limit", 20)) }

        // ── library ──
        typed("library", "contains", READ, "check what is in the library") { a -> library.contains(a.strings(0, "uris")) }
        typed("library", "items", READ, "read the library") { a ->
            library.items(a.enum(0, "filter", LibraryFilter.ALL), a.enum(1, "sort", LibrarySort.RECENTS), a.strOrNull(2, "folderUri"), a.int(3, "offset", 0), a.int(4, "limit", 50))
        }
        typed("library", "likedTracks", READ, "read the liked songs") { a -> library.likedTracks(a.int(0, "offset", 0), a.int(1, "limit", 50)) }
        typed("library", "add", LIBRARY, "save to the library") { a -> library.add(a.strings(0, "uris")) }
        typed("library", "remove", LIBRARY, "remove from the library", destructive = true) { a -> library.remove(a.strings(0, "uris")) }
        typed("library", "pin", LIBRARY, "pin in the library") { a -> library.pin(a.str(0, "uri")) }
        typed("library", "unpin", LIBRARY, "unpin from the library") { a -> library.unpin(a.str(0, "uri")) }
        typed("library", "createFolder", LIBRARY, "create a library folder") { a -> library.createFolder(a.str(0, "name"), a.strOrNull(1, "parentFolderUri")) }
        typed("library", "renameFolder", LIBRARY, "rename a library folder") { a -> library.renameFolder(a.str(0, "folderUri"), a.str(1, "name")) }
        typed("library", "deleteFolder", LIBRARY, "delete a library folder", destructive = true) { a -> library.deleteFolder(a.str(0, "folderUri")) }
        typed("library", "moveToFolder", LIBRARY, "move to a library folder") { a -> library.moveToFolder(a.str(0, "uri"), a.strOrNull(1, "folderUri")) }

        // ── playlists ──
        typed("playlists", "playlist", CATALOG) { a -> playlists.playlist(a.str(0, "uri")) }
        typed("playlists", "tracks", CATALOG) { a -> playlists.tracks(a.str(0, "uri"), a.int(1, "offset", 0), a.int(2, "limit", 100)) }
        typed("playlists", "sortedTracks", CATALOG) { a -> playlists.sortedTracks(a.str(0, "uri"), a.enum(1, "sort", PlaylistSort.CUSTOM), a.bool(2, "descending", false)) }
        typed("playlists", "recommended", CATALOG) { a ->
            playlists.recommended(a.str(0, "uri"), a.int(1, "limit", 10), if (a.size > 2) a.strings(2, "skip") else emptyList())
        }
        typed("playlists", "mine", READ, "read the account's playlists") { playlists.mine() }
        typed("playlists", "editable", READ, "read the playlists it may add to") { playlists.editable() }
        typed("playlists", "access", READ, "read who may see a playlist") { a -> playlists.access(a.str(0, "uri")) }
        typed("playlists", "published", READ, "read whether a playlist is on the profile") { a -> playlists.published(a.str(0, "uri")) }
        typed("playlists", "members", READ, "read who can change a playlist") { a -> playlists.members(a.str(0, "uri")) }
        typed("playlists", "addedBy", READ, "read who added a playlist's songs") { a -> playlists.addedBy(a.str(0, "uri")) }
        typed("playlists", "create", PLAYLISTS, "create a playlist") { a -> playlists.create(a.str(0, "name"), a.strOrNull(1, "folderUri")) }
        typed("playlists", "rename", PLAYLISTS, "rename a playlist") { a -> playlists.rename(a.str(0, "uri"), a.str(1, "name")) }
        typed("playlists", "setDescription", PLAYLISTS, "change a playlist's description") { a -> playlists.setDescription(a.str(0, "uri"), a.str(1, "description")) }
        typed("playlists", "setAccess", PLAYLISTS, "change who may see a playlist") { a -> playlists.setAccess(a.str(0, "uri"), a.enum(1, "access", PlaylistAccess.PUBLIC)) }
        typed("playlists", "setPublished", PLAYLISTS, "show or hide a playlist on the profile") { a -> playlists.setPublished(a.str(0, "uri"), a.bool(1, "published")) }
        typed("playlists", "setPublic", PLAYLISTS, "make a playlist public or private") { a -> playlists.setPublic(a.str(0, "uri"), a.bool(1, "public")) }
        typed("playlists", "setCollaborative", PLAYLISTS, "make a playlist collaborative or not") { a -> playlists.setCollaborative(a.str(0, "uri"), a.bool(1, "collaborative")) }
        typed("playlists", "setCover", PLAYLISTS, "change a playlist's cover") { a ->
            playlists.setCover(a.str(0, "uri"), a.strOrNull(1, "jpegBase64")?.let { s ->
                runCatching { java.util.Base64.getDecoder().decode(s) }.getOrElse { throw IllegalArgumentException("spotify.playlists.setCover: jpegBase64 is not base64") }
            })
        }
        typed("playlists", "delete", PLAYLISTS, "delete a playlist", destructive = true) { a -> playlists.delete(a.str(0, "uri")) }
        typed("playlists", "follow", PLAYLISTS, "follow a playlist") { a -> playlists.follow(a.str(0, "uri")) }
        typed("playlists", "unfollow", PLAYLISTS, "unfollow a playlist", destructive = true) { a -> playlists.unfollow(a.str(0, "uri")) }
        typed("playlists", "join", PLAYLISTS, "join a collaborative playlist") { a -> playlists.join(a.str(0, "uri"), a.str(1, "invitationToken")) }
        typed("playlists", "collaboratorInvite", PLAYLISTS, "make an invitation link for a playlist") { a -> playlists.collaboratorInvite(a.str(0, "uri")) }
        typed("playlists", "addTracks", PLAYLISTS, "add songs to a playlist") { a -> playlists.addTracks(a.str(0, "uri"), a.strings(1, "trackUris")) }
        typed("playlists", "removeTracks", PLAYLISTS, "remove songs from a playlist", destructive = true) { a -> playlists.removeTracks(a.str(0, "uri"), a.strings(1, "uids")) }
        typed("playlists", "moveTracks", PLAYLISTS, "reorder a playlist") { a -> playlists.moveTracks(a.str(0, "uri"), a.strings(1, "uids"), a.strOrNull(2, "beforeUid")) }

        // ── home ──
        typed("home", "feed", CATALOG) { a -> home.feed(a.enum(0, "filter", HomeFilter.ALL), a.int(1, "itemsPerShelf", 10)) }
        typed("home", "categories", CATALOG) { home.categories() }
        typed("home", "category", CATALOG) { a -> home.category(a.str(0, "uri")) }
        typed("home", "whatsNew", READ, "read the new releases of the artists it follows") { a -> home.whatsNew(a.int(0, "offset", 0), a.int(1, "limit", 50)) }

        // ── podcasts ──
        typed("podcasts", "show", CATALOG) { a -> podcasts.show(a.str(0, "uri")) }
        typed("podcasts", "episodes", CATALOG) { a -> podcasts.episodes(a.str(0, "uri"), a.int(1, "offset", 0), a.int(2, "limit", 50)) }
        typed("podcasts", "episode", CATALOG) { a -> podcasts.episode(a.str(0, "uri")) }
        typed("podcasts", "audioUrl", CATALOG) { a -> podcasts.audioUrl(a.str(0, "uri")) }
        typed("podcasts", "audiobook", CATALOG) { a -> podcasts.audiobook(a.str(0, "uri")) }
        typed("podcasts", "chapters", CATALOG) { a -> podcasts.chapters(a.str(0, "uri"), a.int(1, "offset", 0), a.int(2, "limit", 50)) }
        typed("podcasts", "savedEpisodes", READ, "read the saved episodes") { a -> podcasts.savedEpisodes(a.int(0, "offset", 0), a.int(1, "limit", 50)) }
        typedIf("podcasts", "setEpisodeSaved", LIBRARY, "save or unsave an episode", whenArgs = { !it.bool(1, "saved") }) { a ->
            podcasts.setEpisodeSaved(a.str(0, "uri"), a.bool(1, "saved"))
        }

        // ── users ──
        typed("users", "me", READ, "read the account") { users.me() }
        typed("users", "profile", READ, "read a profile") { a -> users.profile(a.str(0, "userUri")) }
        typed("users", "followers", READ, "read a user's followers") { a -> users.followers(a.str(0, "userUri")) }
        typed("users", "following", READ, "read who a user follows") { a -> users.following(a.str(0, "userUri")) }
        typed("users", "top", READ, "read the account's top artists and songs") { a -> users.top(a.enum(0, "range", TimeRange.SHORT_TERM), a.int(1, "limit", 10)) }
        typed("users", "isFollowing", READ, "check who it follows") { a -> users.isFollowing(a.strings(0, "userUris")) }
        typed("users", "friendActivity", READ, "read what friends listen to") { users.friendActivity() }
        typed("users", "history", READ, "read the listening history") { users.history() }
        typed("users", "follow", LIBRARY, "follow users") { a -> users.follow(a.strings(0, "userUris")) }
        typed("users", "unfollow", LIBRARY, "unfollow users", destructive = true) { a -> users.unfollow(a.strings(0, "userUris")) }
        typed("users", "setPrivateSession", LIBRARY, "change the private session") { a -> users.setPrivateSession(a.bool(0, "on")) }

        // ── extras ──
        typed("extras", "lyrics", CATALOG) { a -> extras.lyrics(a.str(0, "trackUri")) }
        typed("extras", "canvas", CATALOG) { a -> extras.canvas(a.str(0, "trackUri")) }
        typed("extras", "credits", CATALOG) { a -> extras.credits(a.str(0, "trackUri")) }
        typed("extras", "radio", CATALOG) { a -> extras.radio(a.str(0, "seedUri")) }
        typed("extras", "isExcludedFromTaste", READ, "read which songs are kept out of its taste") { a -> extras.isExcludedFromTaste(a.strings(0, "trackUris")) }
        typed("extras", "isArtistBanned", READ, "read which artists are banned") { a -> extras.isArtistBanned(a.strings(0, "artistUris")) }
        typed("extras", "setExcludedFromTaste", LIBRARY, "keep a song out of its taste") { a -> extras.setExcludedFromTaste(a.str(0, "trackUri"), a.bool(1, "excluded")) }
        typed("extras", "setArtistBanned", LIBRARY, "ban or allow an artist") { a -> extras.setArtistBanned(a.str(0, "artistUri"), a.bool(1, "banned")) }

        // ── devices ──
        typed("devices", "list", READ, "read the Spotify Connect devices") { devices.refresh(); devices.devices.value }
        typed("devices", "transfer", CONTROL, "move playback to a device") { a -> devices.transfer(a.str(0, "deviceId"), a.bool(1, "play", true)) }
        typed("devices", "pullHere", CONTROL, "bring playback back to this app") { devices.pullHere() }

        // ── recents ──
        typed("recents", "recentEntries", READ, "read the recently played") { a -> recents.recentEntries(a.int(0, "offset", 0), a.int(1, "limit", 50)) }

        // ── blends ──
        fun blends(name: String, action: Action, label: String, destructive: Boolean = false, body: suspend Blends.(SpotifyArgs) -> Any?) =
            typed("blends", name, action, label, destructive) { a -> blends.body(a) }
        blends("invitation", READ, "read a Blend invitation") { a -> invitation(a.str(0, "linkOrId")) }
        blends("group", READ, "read who is in a Blend") { a -> group(a.str(0, "playlist")) }
        blends("story", READ, "read a Blend's taste match") { a -> story(a.str(0, "playlist")) }
        blends("invite", PLAYLISTS, "make a Blend invitation") { invite() }
        blends("join", PLAYLISTS, "join a Blend") { a -> join(a.str(0, "linkOrId")) }
        blends("rename", PLAYLISTS, "rename a Blend") { a -> rename(a.str(0, "playlist"), a.str(1, "name")) }
        blends("leave", PLAYLISTS, "leave a Blend", destructive = true) { a -> leave(a.str(0, "playlist")) }

        // ── raw ──
        // A GraphQL mutation and any page service call can change the account in ways nothing
        // here can describe: both are confirmed. A GraphQL query and a GET are not.
        add("raw", "operations", RAW, "list the Web Player operations") { b, _ -> raw(b).operations().sorted() }
        add("raw", "gql", RAW, "run a Web Player GraphQL operation", destructive = { a, isMutation -> isMutation(a.str(0, "operation")) }) { b, a ->
            raw(b).gql(a.str(0, "operation"), a.obj(1, "variables"))
        }
        add("raw", "service", RAW, "call a Web Player service", destructive = { _, _ -> true }) { b, a ->
            raw(b).service(a.str(0, "service"), a.str(1, "method"), a.rest(2))
        }
        add("raw", "get", RAW, "read a Spotify address") { b, a -> raw(b).get(a.str(0, "url")) }
        // Any method on a Spotify address: a read runs, anything that may change the account asks.
        add("raw", "request", RAW, "send a request to Spotify", destructive = { a, _ ->
            RawRequest.check(a.str(0, "method"), a.str(1, "url")) !in RawRequest.READS
        }) { b, a ->
            val method = RawRequest.check(a.str(0, "method"), a.str(1, "url"))
            val body = RawRequest.bodyText(a)
            val headers = if (a.size > 3) a.obj(3, "headers").mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: v.toString() } else emptyMap()
            raw(b).request(method, a.str(1, "url"), body?.let { RawRequest.bytes(it, a) }, headers)
        }
    }
}
