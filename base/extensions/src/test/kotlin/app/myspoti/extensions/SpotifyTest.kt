package app.myspoti.extensions

import app.myspoti.api.AlbumRef
import app.myspoti.api.ArtistRef
import app.myspoti.api.MyspotiApi
import app.myspoti.api.Page
import app.myspoti.api.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class SpotifyTest {
    private val track = TrackInfo("abc", "spotify:track:abc", "Song", "Artist", listOf("Artist"), "Album", 180_000)
    private val why = "Needs it to do what the user asked for"

    /** A [MyspotiApi] that records `area.method(args)` and answers from [answers] (null when there is none). */
    private class FakeApi {
        val calls = mutableListOf<String>()
        val answers = HashMap<String, Any?>()
        val api: MyspotiApi = proxy(MyspotiApi::class.java, "")

        @Suppress("UNCHECKED_CAST")
        private fun <T> proxy(type: Class<T>, prefix: String): T = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, m, args ->
            if (m.parameterCount == 0 && m.name.startsWith("get")) {
                val name = m.name.removePrefix("get").replaceFirstChar { it.lowercase() }
                val key = if (prefix.isEmpty()) name else "$prefix.$name"
                when {
                    StateFlow::class.java.isAssignableFrom(m.returnType) -> MutableStateFlow(answers[key])
                    m.returnType.isInterface && m.returnType.name.startsWith("app.myspoti.api.") -> proxy(m.returnType, name)
                    else -> null
                }
            } else {
                // A suspend function's last argument is its continuation.
                val shown = args.orEmpty().filter { it !is kotlin.coroutines.Continuation<*> }
                val key = "$prefix.${m.name}"
                calls += "$key(${shown.joinToString()})"
                answers[key]
            }
        } as T
    }

    private class FakeRaw : SpotifyRaw {
        val calls = mutableListOf<String>()
        var lastBody: ByteArray? = null
        override suspend fun gql(operation: String, variables: JsonObject): JsonElement { calls += "gql $operation $variables"; return JsonPrimitive("gql-ok") }
        override fun operations() = setOf("searchDesktop", "addToPlaylist")
        override suspend fun service(service: String, method: String, args: List<JsonElement>): JsonElement { calls += "service $service.$method $args"; return JsonPrimitive("svc-ok") }
        override suspend fun get(url: String): JsonElement? { calls += "get $url"; return JsonPrimitive("get-ok") }
        override fun isMutation(operation: String) = operation.startsWith("add")
        override suspend fun request(method: String, url: String, body: ByteArray?, headers: Map<String, String>): JsonElement {
            calls += "request $method $url ${body?.decodeToString()} $headers"
            lastBody = body
            return RawRequest.response(200, mapOf("content-type" to "application/json"), "{}")
        }
    }

    private class SpotifyHost(
        val api: FakeApi, val raw: FakeRaw? = FakeRaw(), var allow: Boolean = false, val thinkMs: Long = 0,
        media: PlayerMedia? = null,
    ) : Host {
        val asked = mutableListOf<String>()
        val grants = mutableListOf<RawGrant>()
        /** Answers a write question with this instead of [allow] when set. */
        var consent: Consent? = null
        val offered = mutableListOf<RawGrant>()
        private val dispatcher = SpotifyDispatcher(api.api, raw, media)
        override fun rawGrants(extensionId: String) = grants.toList()
        override suspend fun confirmWrite(extensionId: String, text: String, offer: RawGrant): Consent {
            val c = consent ?: return super.confirmWrite(extensionId, text, offer)
            asked += text; offered += offer
            if (c == Consent.ALWAYS) grants += offer
            return c
        }
        override fun log(extensionId: String, message: String) {}
        override fun storage(extensionId: String): Storage = MemoryStorage()
        override fun setting(extensionId: String, key: String): String? = null
        override suspend fun spotify(extensionId: String, call: SpotifyCall) = dispatcher.execute(call)
        override fun spotifyMutation(name: String) = dispatcher.isMutation(name)
        override suspend fun confirm(extensionId: String, text: String): Boolean { asked += text; delay(thinkMs); return allow }
    }

    private fun manifest(vararg permissions: String) = Manifest(
        "io.github.test.spotify", "Spotify Test", "1.0", author = "me", provides = listOf("lyrics"),
        permissions = permissions.map { PermissionRequest(it, why = why) },
    )

    /** What [body] (a JS expression run in the lyrics hook) gives back; a thrown error comes back as "error: message". */
    private fun script(body: String, host: Host, m: Manifest, granted: Set<String> = m.asks().map { it.key }.toSet(), timeoutMs: Long = 3_000): String? =
        ExtensionRuntime(m, "myspoti.provide('lyrics', function () { try { return { v: $body }; } catch (e) { return { v: 'error: ' + e.message }; } });", host, granted)
            .use { it.start(); it.call(Hook.LYRICS, track.toJson(), timeoutMs) }

    @Test fun aCatalogReadNeedsTheCatalogRight() {
        val api = FakeApi()
        api.answers["search.tracks"] = Page(listOf(Track("spotify:track:1", "Papaoutai", 232_000, listOf(ArtistRef("spotify:artist:2", "Stromae")), AlbumRef("spotify:album:3", "Racine carrée"))), 0, 1, null)
        val call = "myspoti.spotify.search.tracks('papaoutai').items[0].artists[0].name"
        // Not in the manifest.
        assertTrue(script(call, SpotifyHost(api), manifest("notify"))!!.contains("spotify.catalog is not in this extension's manifest"))
        // In the manifest, the user said no.
        val m = manifest("spotify.catalog")
        assertTrue(script(call, SpotifyHost(api), m, granted = setOf("lyrics"))!!.contains("the user did not grant spotify.catalog"))
        assertTrue(api.calls.isEmpty())
        // Granted: the typed call runs with the API's own defaults, and the model comes back as JSON.
        assertEquals("""{"v":"Stromae"}""", script(call, SpotifyHost(api), m))
        assertEquals(listOf("search.tracks(papaoutai, 0, 30)"), api.calls)
    }

    @Test fun theFourRightsAreSeparate() {
        val api = FakeApi()
        val m = manifest("spotify.catalog", "spotify.write")
        for (call in listOf("myspoti.spotify.library.likedTracks()", "myspoti.spotify.users.me()", "myspoti.spotify.player.state()", "myspoti.spotify.raw.operations()")) {
            assertTrue(call, script(call, SpotifyHost(api), m)!!.contains("is not in this extension's manifest"))
        }
        assertTrue(script("myspoti.spotify.library.add(['spotify:track:1'])", SpotifyHost(api), manifest("spotify.library.read"))!!.contains("library.write is not in"))
        assertTrue(api.calls.isEmpty())
    }

    @Test fun anUnknownMethodDoesNotExist() {
        assertEquals(null, SpotifyMethods.find("library", "nope"))
        assertEquals(null, SpotifyMethods.find("jam", "start"))
        val m = manifest("spotify.write")
        assertTrue(script("myspoti.spotify.library.nope()", SpotifyHost(FakeApi()), m)!!.contains("error"))
        assertTrue(script("typeof myspoti.spotify.jam", SpotifyHost(FakeApi()), m)!!.contains("undefined"))
    }

    @Test fun aWrongArgumentIsNamed() {
        val m = manifest("spotify.catalog")
        assertTrue(script("myspoti.spotify.search.tracks()", SpotifyHost(FakeApi()), m)!!.contains("query (argument 1) must be a string"))
        assertTrue(script("myspoti.spotify.search.tracks('a', 'x')", SpotifyHost(FakeApi()), m)!!.contains("offset (argument 2) must be a number"))
    }

    @Test fun aWriteRunsWithoutAsking() {
        val api = FakeApi()
        val host = SpotifyHost(api)
        script("(myspoti.spotify.library.add(['spotify:track:1', 'spotify:album:2']), myspoti.spotify.playlists.setAccess('spotify:playlist:p', 'private'))", host, manifest("spotify.write"))
        assertTrue(host.asked.isEmpty())
        assertEquals(listOf("library.add([spotify:track:1, spotify:album:2])", "playlists.setAccess(spotify:playlist:p, PRIVATE)"), api.calls)
    }

    @Test fun aDestructiveWriteIsRefusedUnlessTheUserAllowsIt() {
        val api = FakeApi()
        val host = SpotifyHost(api, allow = false)
        val m = manifest("spotify.write")
        val out = script("myspoti.spotify.library.remove(['spotify:track:1'])", host, m)!!
        assertTrue(out, out.contains("the user refused"))
        assertEquals(1, host.asked.size)
        assertTrue(host.asked[0], host.asked[0].contains("Spotify Test wants to remove from the library") && host.asked[0].contains("spotify:track:1"))
        assertTrue(api.calls.isEmpty())

        host.allow = true
        assertEquals("""{"v":"done"}""", script("(myspoti.spotify.library.remove(['spotify:track:1']), 'done')", host, m))
        assertEquals(listOf("library.remove([spotify:track:1])"), api.calls)
    }

    @Test fun unsavingAnEpisodeAsksButSavingDoesNot() {
        val api = FakeApi()
        val host = SpotifyHost(api, allow = false)
        val m = manifest("spotify.write")
        script("myspoti.spotify.podcasts.setEpisodeSaved('spotify:episode:e', true)", host, m)
        assertTrue(host.asked.isEmpty())
        script("myspoti.spotify.podcasts.setEpisodeSaved('spotify:episode:e', false)", host, m)
        assertEquals(1, host.asked.size)
        assertEquals(listOf("podcasts.setEpisodeSaved(spotify:episode:e, true)"), api.calls)
    }

    @Test fun theTimeTheUserTakesIsNotTheScriptsTime() {
        val api = FakeApi()
        val host = SpotifyHost(api, allow = true, thinkMs = 1_500)
        // The call has 1 s; the user needs 1.5 s to answer.
        val out = script("(myspoti.spotify.playlists.delete('spotify:playlist:p'), 'deleted')", host, manifest("spotify.write"), timeoutMs = 1_000)
        assertEquals("""{"v":"deleted"}""", out)
        assertEquals(listOf("playlists.delete(spotify:playlist:p)"), api.calls)
    }

    @Test fun rawNeedsItsOwnRight() {
        val m = manifest("spotify.catalog", "spotify.library.read", "spotify.write")
        for (call in listOf("raw.gql('searchDesktop', {})", "raw.service('a', 'b')", "raw.get('https://api.spotify.com/v1/me')", "raw.operations()")) {
            val raw = FakeRaw()
            assertTrue(call, script("myspoti.spotify.$call", SpotifyHost(FakeApi(), raw), m)!!.contains("spotify.raw is not in this extension's manifest"))
            assertTrue(raw.calls.isEmpty())
        }
    }

    @Test fun rawReadsRunButRawMutationsAndServicesAsk() {
        val m = manifest("spotify.raw")
        val raw = FakeRaw()
        val host = SpotifyHost(FakeApi(), raw, allow = false)
        assertEquals("""{"v":"gql-ok"}""", script("myspoti.spotify.raw.gql('searchDesktop', { searchTerm: 'a' })", host, m))
        assertEquals("""{"v":"get-ok"}""", script("myspoti.spotify.raw.get('https://api.spotify.com/v1/me')", host, m))
        assertEquals("""{"v":["addToPlaylist","searchDesktop"]}""", script("myspoti.spotify.raw.operations()", host, m))
        assertTrue(host.asked.isEmpty())

        assertTrue(script("myspoti.spotify.raw.gql('addToPlaylist', {})", host, m)!!.contains("the user refused"))
        assertTrue(script("myspoti.spotify.raw.service('player', 'stop')", host, m)!!.contains("the user refused"))
        assertEquals(2, host.asked.size)
        assertEquals(2, raw.calls.size)
        host.allow = true
        assertEquals("""{"v":"svc-ok"}""", script("myspoti.spotify.raw.service('player', 'stop', 1, 'x')", host, m))
        assertEquals("service player.stop [1, \"x\"]", raw.calls.last())
    }

    @Test fun rawRequestReadsRunWritesAskAndOnlySpotifyIsReached() {
        val m = manifest("spotify.raw")
        val raw = FakeRaw()
        val host = SpotifyHost(FakeApi(), raw, allow = false)
        assertEquals("""{"v":200}""", script("myspoti.spotify.raw.request('GET', 'https://api.spotify.com/v1/me').status", host, m))
        assertEquals("""{"v":"{}"}""", script("myspoti.spotify.raw.request('head', 'https://open.spotifycdn.com/x.js').body", host, m))
        assertTrue(host.asked.isEmpty())
        assertEquals(listOf("request GET https://api.spotify.com/v1/me null {}", "request HEAD https://open.spotifycdn.com/x.js null {}"), raw.calls)

        // A write asks first, whatever the path; refused, nothing is sent.
        assertTrue(script("myspoti.spotify.raw.request('PUT', 'https://gue1-spclient.spotify.com/x', { a: 1 })", host, m)!!.contains("the user refused"))
        assertEquals(1, host.asked.size)
        assertEquals(2, raw.calls.size)
        host.allow = true
        assertEquals("""{"v":200}""", script("myspoti.spotify.raw.request('POST', 'https://api.spotify.com/v1/x', { a: 1 }, { 'X-Test': 'y' }).status", host, m))
        assertEquals("request POST https://api.spotify.com/v1/x {\"a\":1} {X-Test=y}", raw.calls.last())
        // A binary body comes as base64 and leaves as its bytes; a body that is not base64 never leaves.
        assertEquals("""{"v":200}""", script("myspoti.spotify.raw.request('POST', 'https://spclient.wg.spotify.com/x', 'CgD/', { 'Content-Type': 'application/x-protobuf' }, { encoding: 'base64' }).status", host, m))
        assertEquals(listOf(0x0a, 0x00, 0xff), raw.lastBody!!.map { it.toInt() and 0xff })
        val sent = raw.calls.size
        assertTrue(script("myspoti.spotify.raw.request('POST', 'https://spclient.wg.spotify.com/x', 'no!', {}, { encoding: 'base64' })", host, m)!!.contains("not base64"))
        assertTrue(script("myspoti.spotify.raw.request('POST', 'https://spclient.wg.spotify.com/x', 'x', {}, { encoding: 'hex' })", host, m)!!.contains("encoding"))
        assertEquals(sent, raw.calls.size)

        // Anything that is not https on a Spotify host never leaves.
        for (url in listOf("https://example.com/", "http://api.spotify.com/v1/me", "https://evilspotify.com/", "https://spotify.com.evil.net/")) {
            assertTrue(url, script("myspoti.spotify.raw.request('GET', '$url')", host, m)!!.startsWith("""{"v":"error:"""))
        }
        assertTrue(script("myspoti.spotify.raw.request('TRACE', 'https://api.spotify.com/')", host, m)!!.contains("method"))
        assertEquals(4, raw.calls.size)
    }

    @Test fun anAlwaysGrantCoversItsMethodHostAndPathOnly() {
        val m = manifest("spotify.raw")
        val raw = FakeRaw()
        val host = SpotifyHost(FakeApi(), raw).apply { consent = Consent.ALWAYS }
        val put = "https://gue1-spclient.spotify.com/playlist/v2/playlist/37i9dQZF1DXcBWIGoYBM5M/changes"
        assertEquals("""{"v":200}""", script("myspoti.spotify.raw.request('PUT', '$put?x=1').status", host, m))
        assertEquals(listOf(RawGrant("PUT", "gue1-spclient.spotify.com", "/playlist/v2/playlist/*/changes")), host.grants)

        // The same kind of request on another playlist runs without a question.
        host.consent = Consent.REFUSE
        assertEquals("""{"v":200}""", script("myspoti.spotify.raw.request('PUT', '${put.replace("37i9dQZF1DXcBWIGoYBM5M", "4uLU6hMCjMI75M1A2tKUQC")}').status", host, m))
        assertEquals(1, host.asked.size)

        // Another method, host or path asks again.
        for (call in listOf("'POST', '$put'", "'PUT', 'https://api.spotify.com/playlist/v2/playlist/x1234567/changes'", "'PUT', '${put.removeSuffix("/changes")}'")) {
            assertTrue(call, script("myspoti.spotify.raw.request($call)", host, m)!!.contains("the user refused"))
        }
        assertEquals(4, host.asked.size)
        assertEquals(2, raw.calls.size)
    }

    @Test fun grantPatternsKeepWordsAndStarNames() {
        assertEquals(RawGrant("POST", "api.spotify.com", "/v1/me/player/*"), RawRequest.offer("post", "https://api.spotify.com/v1/me/player/12345"))
        assertEquals("/a/*/b/*", RawRequest.offer("PUT", "https://x.spotify.com/a/spotify:track:1/b/0a1b2c3d4e5f").path)
        assertFalse(RawRequest.covered(listOf(RawGrant("PUT", "x.spotify.com", "/a/*")), "PUT", "https://x.spotify.com/a/b/c"))
        assertTrue(RawRequest.covered(listOf(RawGrant("PUT", "x.spotify.com", "/a/*")), "put", "https://X.spotify.com/a/b?q=1"))
    }

    @Test fun mediaReadsThePlayersManifestWithPlaybackEvents() {
        val manifest = JsonObject(mapOf("file_ids_mp4" to kotlinx.serialization.json.JsonArray(listOf(JsonObject(mapOf("file_id" to JsonPrimitive("f00d"), "format" to JsonPrimitive("10")))))))
        val host = SpotifyHost(FakeApi(), media = PlayerMedia { if (it == "spotify:track:abc") manifest else null })
        val m = manifest("player.read")
        assertEquals("""{"v":"f00d"}""", script("myspoti.spotify.media('spotify:track:abc').manifest.file_ids_mp4[0].file_id", host, m))
        assertEquals("""{"v":"spotify:track:abc"}""", script("myspoti.spotify.player.media('spotify:track:abc').uri", host, m))
        assertEquals("""{"v":null}""", script("myspoti.spotify.media('spotify:track:zzz')", host, m))
        assertTrue(script("myspoti.spotify.media('spotify:album:abc')", host, m)!!.contains("trackUri"))
        assertTrue(script("myspoti.spotify.media('spotify:track:abc')", host, manifest("spotify.catalog"))!!.startsWith("""{"v":"error:"""))
        assertTrue(host.asked.isEmpty())
    }

    @Test fun aHostWithoutAnAccountSaysSo() {
        val host = object : Host {
            override fun log(extensionId: String, message: String) {}
            override fun storage(extensionId: String): Storage = MemoryStorage()
            override fun setting(extensionId: String, key: String): String? = null
        }
        assertTrue(script("myspoti.spotify.search.all('a')", host, manifest("spotify.catalog"))!!.contains("has no Spotify account"))
        // And it never says yes to a destructive change.
        assertTrue(script("myspoti.spotify.library.remove(['spotify:track:1'])", host, manifest("spotify.write"))!!.contains("the user refused"))
    }

    @Test fun theTableIsTheWholeSurface() {
        val all = SpotifyMethods.all()
        fun count(a: Action) = all.count { it.action == a }
        assertEquals(
            setOf(Action.SPOTIFY_CATALOG, Action.LIBRARY_READ, Action.PLAYBACK_EVENTS, Action.PLAYBACK_CONTROL, Action.PLAYBACK_QUEUE, Action.PLAYBACK_MIRROR,
                Action.LIBRARY_WRITE, Action.PLAYLISTS_WRITE, Action.SPOTIFY_RAW),
            all.map { it.action }.toSet(),
        )
        assertEquals(5, count(Action.SPOTIFY_RAW))
        assertTrue(count(Action.SPOTIFY_CATALOG) > 20 && count(Action.LIBRARY_READ) > 20 && count(Action.PLAYLISTS_WRITE) > 15 && count(Action.LIBRARY_WRITE) > 10)
        assertEquals(listOf("addToQueue", "clearQueue", "moveInQueue", "playNext", "removeFromQueue"), all.filter { it.action == Action.PLAYBACK_QUEUE }.map { it.name }.sorted())
        // Every method that is always destructive: the day one is added or dropped, this list is read again.
        val args = SpotifyArgs(kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("x"))), "t")
        val destructive = all.filter { m -> runCatching { m.isDestructive(args) { true } }.getOrDefault(false) }.map { it.key }.sorted()
        assertEquals(
            listOf(
                "blends.leave", "library.deleteFolder", "library.remove", "player.clearQueue", "player.removeFromQueue", "playlists.delete",
                "playlists.removeTracks", "playlists.unfollow", "raw.gql", "raw.service", "search.clearRecent", "users.unfollow",
            ),
            destructive,
        )
        // A destructive method is a write (or raw); reading never asks.
        val writes = setOf(Action.LIBRARY_WRITE, Action.PLAYLISTS_WRITE, Action.PLAYBACK_QUEUE, Action.SPOTIFY_RAW)
        all.filter { it.key in destructive }.forEach { assertTrue(it.key, it.action in writes) }
        all.filter { it.action == Action.SPOTIFY_CATALOG || it.action == Action.LIBRARY_READ || it.action == Action.PLAYBACK_EVENTS }.forEach { assertFalse(it.key, it.key in destructive) }
    }

    @Test fun modelsComeBackAsPlainJson() {
        val json = JsonModel.encode(Page(listOf(Track("u", "n", 1, emptyList(), null, explicit = true)), 0, 1, null)).toString()
        assertEquals("""{"items":[{"uri":"u","name":"n","durationMs":1,"artists":[],"album":null,"explicit":true,"playable":true,"uid":null,"addedAt":null,"hasVideo":false}],"offset":0,"total":1,"nextOffset":null}""", json)
    }

    @Test fun spotifyRightsAreInTheInstallPrompt() {
        val m = manifest("spotify.catalog", "spotify.library.read", "spotify.write", "spotify.raw")
        assertEquals(emptyList<String>(), m.problems())
        // The old permissions become their actions: spotify.write is four of them, three critical.
        assertEquals(
            listOf("lyrics.provide" to Level.NORMAL, "spotify.catalog" to Level.NORMAL, "library.read" to Level.NORMAL, "playback.events" to Level.NORMAL,
                "library.write" to Level.CRITICAL, "playlists.write" to Level.CRITICAL, "playback.control" to Level.NORMAL, "playback.queue" to Level.NORMAL,
                "spotify.raw" to Level.CRITICAL, "storage" to Level.NORMAL),
            m.asks().map { it.key to it.level },
        )
        assertTrue(m.isCritical())
        assertFalse(manifest("spotify.catalog").isCritical())
        // A critical Spotify action has to say why.
        val bare = Manifest("io.github.test.x", "X", "1.0", author = "me", provides = listOf("lyrics"), permissions = listOf(PermissionRequest("spotify.write")))
        assertTrue(bare.problems().toString(), bare.problems().any { it.contains("\"library.write\" is a critical action") })
    }
}
