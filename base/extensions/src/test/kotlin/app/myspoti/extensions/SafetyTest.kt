package app.myspoti.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The network allowlist, the private-address guard and the crash-proofing of the sandbox. */
class SafetyTest {
    private val track = TrackInfo("abc", "spotify:track:abc", "Song", "Artist", listOf("Artist"), "Album", 180_000)

    private class Host2 : Host {
        val logs = mutableListOf<String>()
        val notes = mutableListOf<String>()
        var nowPlaying: () -> String? = { null }
        override fun log(extensionId: String, message: String) { logs += message }
        override fun storage(extensionId: String): Storage = MemoryStorage()
        override fun setting(extensionId: String, key: String): String? = null
        override fun nowPlaying(): String? = nowPlaying.invoke()
        override fun notify(extensionId: String, text: String) { notes += text }
    }

    private fun manifest(actions: List<ActionRequest>, media: Media? = null) =
        Manifest("io.github.test.safe", "Safe", "1.0", author = "me", actions = actions, media = media)

    private fun req(name: String, vararg hosts: String) = ActionRequest(name, "because it needs to, for the test", hosts.toList())

    private fun run(script: String, m: Manifest, host: Host = Host2(), hook: Hook = Hook.AUDIO_SOURCE): Pair<ExtensionRuntime, String?> {
        val rt = ExtensionRuntime(m, script, host, m.asks().map { it.key }.toSet()).also { it.start() }
        return rt to rt.call(hook, track.toJson(), 3_000)
    }

    private fun audio(url: String) = "myspoti.provide('audio-source', function () { return { url: '$url' }; });"

    // ── Private addresses, in every spelling ──

    @Test fun privateAddressForms() {
        listOf(
            "localhost", "foo.localhost", "printer.local", "127.0.0.1", "127.1", "2130706433", "0x7f000001", "0x7f.1", "0177.0.0.1", "017700000001",
            "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.0.1", "169.254.169.254", "100.64.0.1", "100.127.255.255", "0.0.0.0", "0",
            "[::1]", "::1", "::", "[::ffff:127.0.0.1]", "::ffff:7f00:1", "::ffff:10.0.0.1", "fc00::1", "fd12:3456::1", "fe80::1", "[fe80::1%eth0]",
            "64:ff9b::7f00:1", "2002:7f00:1::1", "224.0.0.1", "169.254.1.1.", "192.168.1",
        ).forEach { assertTrue("$it should be private", SafeNet.isPrivateHost(it)) }
    }

    @Test fun publicAddressesPass() {
        listOf("example.com", "api.zarz.moe", "8.8.8.8", "100.128.0.1", "100.63.255.255", "172.32.0.1", "172.15.0.1", "2001:4860:4860::8888", "[2606:4700::1111]", "1.1.1.1", "999.1.1.1")
            .forEach { assertFalse("$it should be public", SafeNet.isPrivateHost(it)) }
    }

    @Test fun dnsRefusesLocalNamesAndLiterals() {
        listOf("localhost", "127.0.0.1", "2130706433", "[::1]").forEach {
            try { SafeNet.SafeDns.lookup(it); fail("$it resolved") } catch (_: java.net.UnknownHostException) { /* refused */ }
        }
    }

    @Test fun basicHttpNeverConnectsToLocalAddresses() {
        listOf("http://127.0.0.1:9/", "http://2130706433:9/", "http://0x7f.1:9/", "http://[::1]:9/", "http://localhost:9/", "http://[::ffff:127.0.0.1]:9/").forEach {
            try { BasicHttp.send(HttpRequest(it, timeout = 1_000), "t"); fail("$it was sent") } catch (e: java.io.IOException) { assertTrue(e.message, e.message!!.contains("local")) }
        }
    }

    @Test fun checkUrlRefusesObscureLoopback() {
        for (u in listOf("http://2130706433/a.mp3", "https://0x7f000001/a", "http://127.1/a", "https://[::ffff:7f00:1]/a")) {
            try { checkUrl("audio-source", u); fail(u) } catch (_: ExtensionException) { /* refused */ }
        }
        checkUrl("audio-source", "https://cdn.example.org/a.flac")
    }

    // ── Host allowlist of media and images ──

    private val audioActions = listOf(req("audio.source"), req("network", "api.example.com"))

    @Test fun audioUrlMustBeOnADeclaredHost() {
        val m = manifest(audioActions, Media(listOf("*.cdn.example.org")))
        assertNotNull(run(audio("https://api.example.com/a.mp3"), m).second)
        assertNotNull(run(audio("https://eu.cdn.example.org/a.mp3"), m).second)
        try { run(audio("https://evil.example.net/a.mp3"), m); fail("undeclared host") } catch (e: ExtensionException) { assertTrue(e.message, e.message!!.contains("evil.example.net")) }
    }

    @Test fun downloadFilesAreChecked() {
        val m = manifest(listOf(req("download.source"), req("network", "api.example.com")), Media(listOf("files.example.org")))
        val ok = "myspoti.provide('download-source', function () { return [{ url: 'https://files.example.org/x.flac', format: 'flac' }]; });"
        val bad = "myspoti.provide('download-source', function () { return [{ url: 'https://other.example.org/x.flac', format: 'flac' }]; });"
        assertNotNull(run(ok, m, hook = Hook.DOWNLOAD_SOURCE).second)
        try { run(bad, m, hook = Hook.DOWNLOAD_SOURCE); fail() } catch (_: ExtensionException) { }
    }

    @Test fun dashManifestUrlsAreChecked() {
        val mpd = """<MPD><SegmentTemplate initialization="https://cdn.other.org/i.mp4" media="https://cdn.other.org/m.mp4"/></MPD>"""
        val b64 = java.util.Base64.getEncoder().encodeToString(mpd.toByteArray())
        val script = "myspoti.provide('audio-source', function () { return { manifest: '$b64' }; });"
        try { run(script, manifest(audioActions, Media(listOf("cdn.example.org")))); fail() } catch (_: ExtensionException) { }
        assertNotNull(run(script, manifest(audioActions, Media(listOf("cdn.other.org")))).second)
    }

    @Test fun olderManifestWithoutMediaKeepsPublicHosts() {
        // Migration: an extension published before `media` still gets audio from any public host.
        val m = manifest(audioActions, media = null)
        assertNotNull(run(audio("https://some.cdn.example.net/a.mp3"), m).second)
    }

    @Test fun blockImagesAreHeldToDeclaredHosts() {
        val m = manifest(listOf(req("home.sections"), req("network", "img.example.com")))
        fun home(url: String) = "myspoti.provide('home', function () { return [{ type: 'image', url: '$url' }]; });"
        assertNotNull(run(home("https://img.example.com/a.png"), m, hook = Hook.HOME).second)
        try { run(home("https://tracker.example.net/a.png"), m, hook = Hook.HOME); fail() } catch (e: ExtensionException) { assertTrue(e.message, e.message!!.contains("tracker.example.net")) }
        val withMedia = manifest(listOf(req("home.sections")), Media(listOf("*.images.example.org")))
        assertNotNull(run(home("https://a.images.example.org/a.png"), withMedia, hook = Hook.HOME).second)
    }

    @Test fun mediaHostsAreShownAndValidated() {
        val m = manifest(audioActions, Media(listOf("cdn.example.org")))
        assertTrue(m.asks().first { it.key == "audio.source" }.hosts.contains("cdn.example.org"))
        assertTrue(manifest(audioActions, Media(listOf("*"))).problems().any { it.contains("media host") })
    }

    @Test fun mediaHeadersAreLimited() {
        val h = headersOf(ExtJson.parseToJsonElement("""{"Referer":"https://x.org","Cookie":"a=b","Proxy-Authorization":"x","X-A":"line\nbreak"}"""))
        assertEquals(setOf("Referer"), h.keys)
    }

    // ── Crash-proofing ──

    private val playerManifest = manifest(listOf(req("lyrics.provide"), req("playback.events"), req("notify")))
    private val lyricsScript = "myspoti.provide('lyrics', function () { return { v: myspoti.player.current() }; });"

    @Test fun outOfMemoryTurnsTheExtensionOffNotTheApp() {
        val host = Host2().apply { nowPlaying = { throw OutOfMemoryError("simulated") } }
        val rt = ExtensionRuntime(playerManifest, lyricsScript, host, playerManifest.asks().map { it.key }.toSet()).also { it.start() }
        try { rt.call(Hook.LYRICS, track.toJson(), 3_000); fail() } catch (e: ExtensionFault) { assertTrue(e.message, e.message!!.contains("memory")) }
        assertNotNull(rt.faulted)
        assertTrue(host.logs.any { it.startsWith("turned off:") })
        host.nowPlaying = { "{}" }
        try { rt.call(Hook.LYRICS, track.toJson(), 3_000); fail("still off") } catch (_: ExtensionFault) { }
        rt.close()
    }

    @Test fun stackOverflowTurnsTheExtensionOff() {
        val host = Host2().apply { nowPlaying = { throw StackOverflowError() } }
        val rt = ExtensionRuntime(playerManifest, lyricsScript, host, playerManifest.asks().map { it.key }.toSet()).also { it.start() }
        try { rt.call(Hook.LYRICS, track.toJson(), 3_000); fail() } catch (e: ExtensionFault) { assertTrue(e.message, e.message!!.contains("stack")) }
        rt.close()
    }

    @Test fun anInfiniteLoopIsStillAPlainTimeout() {
        val m = manifest(listOf(req("lyrics.provide")))
        val rt = ExtensionRuntime(m, "myspoti.provide('lyrics', function () { while (true) {} });", Host2(), m.asks().map { it.key }.toSet()).also { it.start() }
        try { rt.call(Hook.LYRICS, track.toJson(), 300); fail() } catch (e: ExtensionTimeout) { /* not a fault */ }
        assertNull(rt.faulted)
        rt.close()
    }

    private fun lyrics(expr: String): String? {
        val m = manifest(listOf(req("lyrics.provide")))
        return run("myspoti.provide('lyrics', function () { return { v: $expr }; });", m, hook = Hook.LYRICS).second
    }

    @Test fun hugeStringsAreRefused() {
        assertEquals("""{"v":"ababab"}""", lyrics("'ab'.repeat(3)"))
        assertEquals("""{"v":"009"}""", lyrics("'9'.padStart(3, '0')"))
        for (e in listOf("'x'.repeat(1e9)", "'x'.repeat(5000000)", "'x'.padStart(1e9, 'ab')", "'x'.padEnd(1e9)", "new Array(3e7).join('abc')", "(function () { var s = '1234567'; for (var i = 0; i < 21; i++) s += s; return JSON.parse(s); })()")) {
            try { lyrics(e); fail(e) } catch (x: ExtensionException) { assertTrue(x.message, x.message!!.contains("too large")) }
        }
    }

    @Test fun regexInputIsCapped() {
        try { lyrics("/a+/.test('a'.repeat(3000000) + 'b'.repeat(0)) ? 1 : 0"); fail() } catch (x: ExtensionException) { assertTrue(x.message, x.message!!.contains("too large")) }
        assertEquals("""{"v":true}""", lyrics("/a+b/.test('aaab')"))
    }

    @Test fun notifyIsRateLimited() {
        val host = Host2()
        val m = manifest(listOf(req("lyrics.provide"), req("notify")))
        val rt = ExtensionRuntime(m, "myspoti.provide('lyrics', function () { myspoti.notify('hi'); return {}; });", host, m.asks().map { it.key }.toSet()).also { it.start() }
        repeat(ExtensionRuntime.MAX_NOTIFY_PER_HOUR) { rt.call(Hook.LYRICS, track.toJson(), 3_000) }
        try { rt.call(Hook.LYRICS, track.toJson(), 3_000); fail() } catch (e: ExtensionException) { assertTrue(e.message, e.message!!.contains("an hour")) }
        assertEquals(ExtensionRuntime.MAX_NOTIFY_PER_HOUR, host.notes.size)
        rt.close()
    }
}
