package app.myspoti.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ManifestTest {
    private val good = """
        {
          "id": "io.github.someone.lyrics",
          "name": "Some lyrics",
          "version": "1.0.0",
          "versionCode": 3,
          "author": "someone",
          "provides": ["lyrics"],
          "permissions": [{ "name": "network", "hosts": ["api.example.com"] }],
          "settings": [{ "key": "lang", "type": "choice", "label": "Language", "options": ["en", "fr"] }],
          "futureField": true
        }
    """.trimIndent()

    @Test fun aGoodManifestHasNoProblems() {
        val m = Manifest.parse(good)
        assertEquals(emptyList<String>(), m.problems())
        assertFalse(m.isCritical())
        // An old manifest: its hooks and permissions become actions, plus its settings page and storage.
        assertTrue(m.isLegacy)
        assertEquals(listOf("lyrics.provide", "network", "settings.page", "storage"), m.asks().map { it.key })
    }

    @Test fun problemsAreSaidPlainly() {
        val m = Manifest.parse(good).copy(id = "Bad", provides = listOf("lyrics", "everything"),
            permissions = listOf(PermissionRequest("network"), PermissionRequest("root")))
        val p = m.problems().joinToString("\n")
        assertTrue(p, p.contains("id \"Bad\""))
        assertTrue(p, p.contains("provides \"everything\": unknown"))
        assertTrue(p, p.contains("network: list the hosts"))
        assertTrue(p, p.contains("permission \"root\": unknown"))
    }

    @Test fun aWelcomeIsShortAndOptional() {
        assertNull(Manifest.parse(good).welcome)
        val m = Manifest.parse(good.replace("\"futureField\": true", "\"welcome\": { \"message\": \"Lyrics for every song.\", \"steps\": [\"Pick a language in Settings\"] }"))
        assertEquals(Welcome("Lyrics for every song.", listOf("Pick a language in Settings")), m.welcome)
        assertEquals(emptyList<String>(), m.problems())
        val p = m.copy(welcome = Welcome("x".repeat(601), List(6) { "step" } + "")).problems().joinToString("\n")
        assertTrue(p, p.contains("welcome.message: 600"))
        assertTrue(p, p.contains("welcome.steps: 5 at most"))
        assertTrue(p, p.contains("welcome.steps: each 1 to 160"))
        assertTrue(m.copy(welcome = Welcome()).problems().any { it.startsWith("welcome: a message or steps") })
    }

    @Test fun aSensitiveRightNeedsItsReason() {
        val m = Manifest.parse(good).copy(provides = listOf("audio-source"), permissions = listOf(PermissionRequest("network", listOf("*"))))
        assertTrue(m.isCritical())
        val p = m.problems()
        assertTrue(p.toString(), p.any { it.contains("\"audio.source\" is a critical action") })
        assertTrue(p.toString(), p.any { it.contains("\"network.any\" is a critical action") })
        val fixed = m.copy(permissions = listOf(
            PermissionRequest("network", listOf("*"), "The songs are found on whichever site has them"),
            PermissionRequest("audio-source", why = "Plays the songs from my own server instead of Spotify"),
        ))
        assertEquals(emptyList<String>(), fixed.problems())
        assertEquals("Plays the songs from my own server instead of Spotify", fixed.asks().first { it.key == "audio.source" }.why)
    }

    @Test fun packRoundTripsWithAStableHash() {
        val dir = Files.createTempDirectory("ext").toFile()
        File(dir, "manifest.json").writeText(good)
        File(dir, "main.js").writeText("myspoti.provide('lyrics', function () { return null; });")
        val a = ExtensionPackage.pack(dir)
        Thread.sleep(1_100)
        val b = ExtensionPackage.pack(dir)
        val p = ExtensionPackage.read(a)
        assertEquals(ExtensionPackage.sha256(b), p.sha256)
        assertEquals("io.github.someone.lyrics", p.manifest.id)
        File(dir, "manifest.json").writeText(good.replace("\"lyrics\"]", "\"lyrics\", \"nope\"]"))
        try { ExtensionPackage.read(ExtensionPackage.pack(dir)); fail("bad manifest read") } catch (e: ExtensionException) { assertTrue(e.message!!.contains("nope")) }
        dir.deleteRecursively()
    }

    @Test fun catalogBlocksByIdOrPackage() {
        val c = Catalog.parse("""
            { "name": "Official", "extensions": [], "blocked": [
              { "id": "a.b.c", "reason": "steals data" },
              { "id": "d.e.f", "sha256": ["aa"], "reason": "one bad build" } ] }
        """)
        assertNotNull(c.blocks("a.b.c", "zz"))
        assertNotNull(c.blocks("d.e.f", "aa"))
        assertNull(c.blocks("d.e.f", "bb"))
        assertEquals("https://x.org/repo/pkgs/a.zip", Catalog.resolve("https://x.org/repo/index.json", "pkgs/a.zip"))
    }
}
