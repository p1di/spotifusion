package app.myspoti.extensions

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** GitHub sources, the update gate, the kill switch, the store's discovery and ranking. */
class GitHubTest {
    private fun req(name: String, why: String = "Because it needs it", hosts: List<String> = emptyList()) = ActionRequest(name, why, hosts)

    private fun manifest(code: Int, vararg actions: ActionRequest, author: String = "me", replaces: List<String> = emptyList()) =
        Manifest("io.github.me.ext", "Ext", "1.$code", code, author = author, actions = actions.toList(), replaces = replaces)

    private fun snap(m: Manifest, repo: String? = "me/ext") = Snapshot.of(m, repo)

    private val lyrics = req("lyrics.provide", "Synced lyrics from LRCLIB")
    private val net = req("network", "Asks LRCLIB", listOf("lrclib.net"))

    // ── The update gate ──

    @Test fun anUpdateAskingNothingNewIsApplied() {
        val accepted = snap(manifest(1, lyrics, net))
        assertEquals(UpdateGate.Decision.Apply, UpdateGate.decide(accepted, snap(manifest(2, lyrics, net))))
        // Asking less is not a change either.
        assertEquals(UpdateGate.Decision.Apply, UpdateGate.decide(accepted, snap(manifest(2, lyrics))))
    }

    @Test fun everyNewRequestHoldsTheUpdateBack() {
        val accepted = snap(manifest(1, lyrics, net))
        fun changes(vararg a: ActionRequest, author: String = "me", repo: String? = "me/ext", replaces: List<String> = emptyList()) =
            (UpdateGate.decide(accepted, snap(manifest(2, *a, author = author, replaces = replaces), repo)) as UpdateGate.Decision.Ask).changes

        changes(lyrics, net, req("home.sections", "Your top songs")).single().let { assertEquals(Change.Kind.ACTION, it.kind); assertFalse(it.critical) }
        changes(lyrics, net, req("library.write", "Saves the songs you like")).single().let { assertTrue(it.critical) }
        changes(lyrics, net, req("webview.page", "Its own page")).single().let { assertEquals("webview.page", it.key); assertTrue(it.critical) }
        changes(lyrics, net, req("download.source", "Downloads")).single().let { assertEquals(Change.Kind.ACTION, it.kind); assertTrue(it.critical) }
        changes(lyrics, req("network", "Asks LRCLIB", listOf("lrclib.net", "evil.example"))).single().let { assertEquals(Change.Kind.HOST, it.kind); assertEquals("evil.example", it.key) }
        changes(lyrics, req("network", "Asks LRCLIB", listOf("*"))).single().let { assertTrue(it.critical) }
        // Any change of a why counts, even a small one.
        changes(req("lyrics.provide", "Synced lyrics from LRCLIB."), net).single().let { assertEquals(Change.Kind.WHY, it.kind); assertEquals("Synced lyrics from LRCLIB", it.before) }
        assertEquals(Change.Kind.AUTHOR, changes(lyrics, net, author = "someone else").single().kind)
        assertEquals(Change.Kind.REPO, changes(lyrics, net, repo = "thief/ext").single().kind)
        assertEquals(Change.Kind.REPLACES, changes(lyrics, net, replaces = listOf("io.github.other.lyrics")).single().kind)
    }

    @Test fun aSourceIsNotAnExclusiveRole() {
        // Sources are asked in the user's order now: an update keeping audio.source asks nothing new.
        val m = manifest(1, req("audio.source", "Plays from my server"))
        assertTrue(UpdateGate.decide(snap(m), snap(manifest(2, req("audio.source", "Plays from my server")))) is UpdateGate.Decision.Apply)
        // A new source action is listed once, as an action.
        val fresh = (UpdateGate.decide(snap(manifest(1, lyrics)), snap(manifest(2, lyrics, req("audio.source", "x")))) as UpdateGate.Decision.Ask).changes
        assertEquals(listOf(Change.Kind.ACTION), fresh.map { it.kind })
    }

    @Test fun theDiffIsAgainstTheAcceptedSnapshotNotThePreviousVersion() {
        val accepted = snap(manifest(1, lyrics, net))
        // v2 drops network: applied by itself; the accepted snapshot stays the user's.
        assertEquals(UpdateGate.Decision.Apply, UpdateGate.decide(accepted, snap(manifest(2, lyrics))))
        // v3 brings it back with another reason and a new host: against v2 everything would look new,
        // against v1 only what the user never accepted is.
        val v3 = snap(manifest(3, lyrics, req("network", "Sends your plays to my server", listOf("lrclib.net", "tracker.example"))))
        val c = (UpdateGate.decide(accepted, v3) as UpdateGate.Decision.Ask).changes
        assertEquals(listOf(Change.Kind.WHY to "network", Change.Kind.HOST to "tracker.example"), c.map { it.kind to it.key })
        // Back exactly as accepted: nothing to ask.
        assertEquals(UpdateGate.Decision.Apply, UpdateGate.decide(accepted, snap(manifest(4, lyrics, net))))
    }

    @Test fun anOldManifestIsComparedThroughItsActions() {
        val old = Manifest("io.github.me.ext", "Ext", "1", 1, author = "me", provides = listOf("lyrics"),
            permissions = listOf(PermissionRequest("lyrics", why = "Synced lyrics from LRCLIB"), PermissionRequest("network", listOf("lrclib.net"), "Asks LRCLIB")))
        val accepted = snap(old)
        assertEquals(UpdateGate.Decision.Apply, UpdateGate.decide(accepted, snap(manifest(2, lyrics, net, req("storage", "")))))
        assertEquals(accepted, Snapshot.of(old, "me/ext"))
        assertEquals(old.asks().map { it.key }, accepted.toManifest().asks().map { it.key })
    }

    // ── The kill switch ──

    @Test fun blockedJsonTurnsOffByIdOrPackage() {
        val b = BlockList.parse("""{ "blocked": [ { "id": "a.b.all", "reason": "malware" }, { "id": "a.b.some", "sha256": ["ABCD"] } ],
            "flagged": [ { "id": "a.b.flag", "reason": "stolen" } ], "later": 1 }""")
        assertEquals("malware", b.blocks("a.b.all", "anything")!!.reason)
        assertNotNull(b.blocks("a.b.some", "abcd"))
        assertNull(b.blocks("a.b.some", "ffff"))
        assertNull(b.blocks("a.b.flag", null))
        assertEquals("stolen", b.flags("a.b.flag")!!.reason)
        // Not created yet: an empty list, nothing turned off.
        assertEquals(BlockList.EMPTY, BlockList.of(404, "Not Found"))
        assertEquals(BlockList.EMPTY, BlockList.parse(""))
    }

    // ── Ranking ──

    private fun ranked(id: String, up: Int, down: Int, updated: Long = 0) = Ranking.Ranked(id, id, null, Rating(up, down, 1), updated)

    @Test fun theScoreIsBayesian() {
        assertEquals(Ranking.PRIOR_MEAN, Ranking.score(null), 1e-9)
        // Three 👍 do not outrank a hundred votes at 95 %.
        assertTrue(Ranking.score(Rating(95, 5)) > Ranking.score(Rating(3, 0)))
        assertTrue(Ranking.score(Rating(3, 0)) > Ranking.score(null))
        assertEquals(5.0, Ranking.stars(Rating(10_000, 0)), 0.05)
        assertEquals(3.8, Ranking.stars(null), 1e-9)
    }

    @Test fun orderIsScoreThenRecency() {
        val list = listOf(ranked("new", 0, 0, 2000), ranked("old", 0, 0, 1000), ranked("loved", 95, 5, 10), ranked("hated", 2, 30, 3000))
        assertEquals(listOf("loved", "new", "old", "hated"), Ranking.order(list).map { it.id })
    }

    @Test fun showAllRevealsTheHiddenOnes() {
        val now = 100L * 365 * 24 * 3600 * 1000
        val blocks = BlockList(listOf(Catalog.Block("blocked")), listOf(BlockList.Flag("flagged")))
        val list = listOf(
            ranked("fine", 0, 0, now), ranked("blocked", 50, 0, now), ranked("flagged", 50, 0, now),
            ranked("hated", 1, 20, now), ranked("fewvotes", 0, 3, now), ranked("stale", 5, 0, now - Ranking.STALE_MS - 1),
        )
        assertEquals(Ranking.Hidden.BLOCKED, Ranking.hidden(list[1], blocks, now))
        assertEquals(Ranking.Hidden.FLAGGED, Ranking.hidden(list[2], blocks, now))
        assertEquals(Ranking.Hidden.LOW_SCORE, Ranking.hidden(list[3], blocks, now))
        assertEquals(Ranking.Hidden.STALE, Ranking.hidden(list[5], blocks, now))
        assertEquals(setOf("fine", "fewvotes"), Ranking.listed(list, blocks, now, showAll = false).map { it.id }.toSet())
        assertEquals(6, Ranking.listed(list, blocks, now, showAll = true).size)
        assertEquals(Ranking.time("2026-10-23"), Ranking.time("2026-10-23T00:00:00Z"))
        assertEquals(0L, Ranking.time("soon"))
    }

    // ── Discovery ──

    @Test fun searchRatingsAndDownloadsAreRead() {
        val found = Discovery.parseSearch("""{ "total_count": 3, "items": [
            { "full_name": "me/ext", "owner": { "login": "me" }, "default_branch": "main", "pushed_at": "2026-10-01T10:00:00Z", "topics": ["myspoti-extension"] },
            { "full_name": "old/gone", "owner": { "login": "old" }, "archived": true } ] }""")
        assertEquals(listOf("me/ext"), found.map { it.fullName })
        assertEquals("https://raw.githubusercontent.com/me/ext/main/manifest.json", found.single().manifestUrl())
        val e = Discovery.entry(found.single(), manifest(3, lyrics).copy(category = "lyrics"))
        assertEquals("me/ext", e.repo)
        assertEquals("", e.sha256)
        assertEquals(Category.LYRICS, e.category())
        assertEquals("https://codeload.github.com/me/ext/zip/refs/heads/main", e.url)

        val ratings = Discovery.parseRatings("""{ "items": [
            { "number": 7, "user": { "login": "intruder" }, "repository_url": "https://api.github.com/repos/me/ext", "reactions": { "+1": 900, "-1": 0 } },
            { "number": 3, "user": { "login": "ME" }, "repository_url": "https://api.github.com/repos/me/ext", "reactions": { "+1": 12, "-1": 2 } },
            { "number": 1, "user": { "login": "x" }, "repository_url": "https://api.github.com/repos/x/unknown", "reactions": { "+1": 5 } } ] }""", setOf("me/ext"))
        assertEquals(mapOf("me/ext" to Rating(12, 2, 3)), ratings)

        val gitlab = Discovery.parseGitLab("""[ { "path_with_namespace": "grp/ext", "description": "d", "web_url": "https://gitlab.com/grp/ext", "default_branch": "main",
            "last_activity_at": "2026-01-02T00:00:00Z", "star_count": 3, "archived": false, "topics": ["myspoti-extension"] },
            { "path_with_namespace": "grp/old", "archived": true } ]""").single()
        assertEquals("gitlab:grp/ext", gitlab.repo!!.slug)
        assertEquals("https://gitlab.com/grp/ext/-/raw/main/manifest.json", gitlab.manifestUrl())
        val codeberg = Discovery.parseCodeberg("""{ "ok": true, "data": [ { "full_name": "me/ext", "html_url": "https://codeberg.org/me/ext", "default_branch": "trunk",
            "updated_at": "2026-01-02T00:00:00Z", "stars_count": 1, "archived": false, "empty": false }, { "full_name": "me/none", "empty": true } ] }""").single()
        assertEquals("codeberg:me/ext", codeberg.repo!!.slug)
        assertEquals("https://codeberg.org/me/ext/raw/trunk/manifest.json", codeberg.manifestUrl())
        assertEquals("trunk", Discovery.parseDefaultBranch("""{ "default_branch": "trunk" }"""))
        assertEquals(41L, Discovery.parseDownloads("""{ "assets": [ { "name": "extension.zip", "download_count": 41 }, { "name": "other.zip", "download_count": 9 } ] }"""))
        assertEquals(9L, Discovery.parseDownloads("""{ "assets": [ { "name": "other.zip", "download_count": 9 } ] }"""))
        assertNull(Discovery.parseDownloads("""{ "assets": [] }"""))
    }

    @Test fun deviceFlowAnswersAreRead() {
        assertTrue(GitHubAuth.enabled)
        assertTrue(GitHubAuth.tokenBody("d").startsWith("client_id=Ov23liFBqyO4elV0s5gq&"))
        assertEquals(GitHubAuth.Poll.Token("gho_x"), GitHubAuth.parsePoll("""{ "access_token": "gho_x", "token_type": "bearer" }"""))
        assertEquals(GitHubAuth.Poll.Pending, GitHubAuth.parsePoll("""{ "error": "authorization_pending" }"""))
        assertEquals(GitHubAuth.Poll.SlowDown, GitHubAuth.parsePoll("""{ "error": "slow_down" }"""))
        assertTrue(GitHubAuth.parsePoll("""{ "error": "access_denied" }""") is GitHubAuth.Poll.Failed)
        assertEquals("ABCD-1234", GitHubAuth.parseDeviceCode("""{ "device_code": "d", "user_code": "ABCD-1234", "verification_uri": "https://github.com/login/device" }""").userCode)
    }

    // ── Sources ──

    @Test fun repositoriesAndTagsAreRead() {
        assertEquals("me/ext", GitHubRepo.parse("https://github.com/me/ext.git/")?.slug)
        assertEquals("me/ext", GitHubRepo.parse("github.com/me/ext/releases")?.slug)
        assertEquals("me/ext", GitHubRepo.parse("me/ext")?.slug)
        val gl = GitHubRepo.parse("https://gitlab.com/grp/sub/ext.git/-/tree/main")!!
        assertEquals("gitlab:grp/sub/ext", gl.slug)
        assertEquals("https://gitlab.com/grp/sub/ext/-/raw/v1/manifest.json", gl.raw("v1", "manifest.json"))
        assertEquals("https://gitlab.com/grp/sub/ext/-/archive/main/ext-main.zip", gl.archive("main", false))
        assertEquals(gl, GitHubRepo.parse(gl.slug))
        val cb = GitHubRepo.parse("https://codeberg.org/me/ext/src/branch/main")!!
        assertEquals("codeberg:me/ext", cb.slug)
        assertEquals("https://codeberg.org/me/ext/raw/main/manifest.json", cb.raw("main", "manifest.json"))
        assertEquals("https://codeberg.org/me/ext/archive/v1.zip", cb.archive("v1", true))
        assertEquals("https://codeberg.org/me/ext/releases/download/v1/extension.zip", cb.releaseAsset("v1"))
        assertNull(gl.releaseAsset("v1"))
        assertEquals("v2", GitHub.tagFromLocation("https://gitlab.com/grp/ext/-/releases/v2"))
        assertNull(GitHub.tagFromLocation("https://gitlab.com/grp/ext/-/releases/permalink/latest"))
        assertEquals("codeberg:me/ext", GitHub.repoOf("https://codeberg.org/me/ext/archive/main.zip"))
        assertNull(GitHubRepo.parse("https://example.com/me/ext"))
        assertNull(GitHubRepo.parse("https://example.com/ext.zip"))
        assertNull(GitHubRepo.parse("me"))
        assertEquals("v1.2.0", GitHub.tagFromLocation("https://github.com/me/ext/releases/tag/v1.2.0"))
        assertNull(GitHub.tagFromLocation("https://github.com/me/ext/releases"))
        assertEquals("new/ext", GitHub.repoOf("https://codeload.github.com/new/ext/zip/refs/heads/main"))
        assertNull(GitHub.repoOf("https://objects.githubusercontent.com/x/y"))
        val report = GitHub.reportUrl("a.b.c", "1.0", "me/ext", "ff")
        assertTrue(report.startsWith("https://github.com/kota-zo/myspoti-extensions/issues/new?template=report-extension.yml&labels=report&title=Report%3A%20a.b.c&extension=a.b.c&version=1.0"))
        assertTrue(report.endsWith("&source=https%3A%2F%2Fgithub.com%2Fme%2Fext&sha256=ff"))
        assertFalse(report.contains(' '))
    }

    /** The files of the official repository (kota-zo/myspoti-extensions) are read by the app as they are. */
    @Test fun theOfficialRepositoryFilesAreReadByTheApp() {
        val blocked = BlockList.parse("""{ "blocked": [ { "id": "io.github.a.b", "reason": "x" }, { "id": "io.github.c.d", "sha256": ["AB"], "reason": "y" } ], "flagged": [ { "id": "io.github.e.f", "reason": "z" } ] }""")
        assertNotNull(blocked.blocks("io.github.a.b", "anything"))
        assertNotNull(blocked.blocks("io.github.c.d", "ab"))
        assertNull(blocked.blocks("io.github.c.d", "cd"))
        assertNotNull(blocked.flags("io.github.e.f"))
        val empty = BlockList.parse("""{ "blocked": [], "flagged": [] }""")
        assertTrue(empty.blocked.isEmpty() && empty.flagged.isEmpty())
        val index = Catalog.parse("""{ "format": 1, "name": "Myspoti verified extensions", "extensions": [] }""")
        assertTrue(index.extensions.isEmpty())
        val verified = Catalog.parse("""{ "format": 1, "name": "v", "extensions": [ { "id": "io.github.someone.name", "name": "Name", "version": "1.0.0", "versionCode": 1, "author": "Someone", "description": "d", "actions": [ { "name": "lyrics.provide", "why": "Synced lyrics" } ], "repo": "someone/name", "url": "https://github.com/someone/name/releases/latest/download/extension.zip", "sha256": "" } ] }""")
        assertEquals("someone/name", verified.extensions.single().repo)
    }

    private fun zip(files: List<Pair<String, String>>, comment: String? = null): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            files.forEach { (n, t) -> z.putNextEntry(ZipEntry(n)); if (!n.endsWith("/")) z.write(t.toByteArray()); z.closeEntry() }
            comment?.let { z.setComment(it) }
        }
        return out.toByteArray()
    }

    private val manifestJson = """{ "id": "io.github.me.ext", "name": "Ext", "version": "1", "author": "me", "actions": [ { "name": "lyrics.provide", "why": "Synced lyrics" } ] }"""

    @Test fun anArchiveIsCutDownToThePackage() {
        val commit = "0123456789abcdef0123456789abcdef01234567"
        val archive = zip(listOf(
            "ext-main/" to "", "ext-main/manifest.json" to manifestJson, "ext-main/main.js" to "myspoti.provide('lyrics', function () { return null; });",
            "ext-main/README.md" to "# Ext", "ext-main/screenshot.jpg" to "jpeg", "ext-main/.github/workflows/ci.yml" to "on: push", "ext-main/src/a.ts" to "x",
        ), commit)
        assertEquals(commit, Archive.commit(archive))
        val pkgBytes = Archive.toPackage(archive)
        val p = ExtensionPackage.read(pkgBytes)
        assertEquals("io.github.me.ext", p.manifest.id)
        val names = ZipInputStream(pkgBytes.inputStream()).use { z -> generateSequence { z.nextEntry?.name }.toList() }
        assertEquals(listOf("manifest.json", "main.js", "README.md"), names)
        // The same archive always gives the same package (its sha256 can be listed in an index).
        assertArrayEquals(pkgBytes, Archive.fromArchive(archive))
        // A release's own package is kept as it is.
        assertArrayEquals(pkgBytes, Archive.toPackage(pkgBytes))
        assertNull(Archive.commit(pkgBytes))
    }

    @Test fun anArchiveWithoutManifestIsRefused() {
        val e = runCatching { Archive.toPackage(zip(listOf("r-main/README.md" to "hi"))) }.exceptionOrNull()
        assertTrue(e?.message, e is ExtensionException && "manifest.json" in e.message!!)
        val two = runCatching { Archive.fromArchive(zip(listOf("a/manifest.json" to manifestJson, "b/main.js" to ""))) }.exceptionOrNull()
        assertTrue(two?.message, "several top folders" in two?.message.orEmpty())
    }

    @Test fun spotifyWritesAreDeclaredCheckedGrantedAndAskedAgainWhenNew() {
        val events = "POST https://spclient.wg.spotify.com/gabo-receiver-service/v3/events/"
        val raw = ActionRequest("spotify.raw", "Sends your listens to Spotify", writes = listOf(events))
        val m = manifest(1, raw)
        assertTrue(m.problems().none { it.contains("write") })
        assertEquals(listOf(RawGrant("POST", "spclient.wg.spotify.com", "/gabo-receiver-service/v3/events")), m.declaredWrites())
        // Shown before install, under the action.
        assertTrue(m.asks().first { it.key == "spotify.raw" }.hosts.contains("POST https://spclient.wg.spotify.com/gabo-receiver-service/v3/events"))
        // An id in the path becomes *: the same request on another playlist, nothing else.
        val put = manifest(1, ActionRequest("spotify.raw", "Edits playlists", writes = listOf("PUT https://spclient.wg.spotify.com/playlist/v2/playlist/37i9dQZF1DXcBWIGoYBM5M/changes")))
        assertEquals("/playlist/v2/playlist/*/changes", put.declaredWrites().single().path)

        for (bad in listOf("GET https://api.spotify.com/v1/me", "POST https://example.com/x", "POST", "TRACE https://api.spotify.com/")) {
            assertTrue(bad, manifest(1, ActionRequest("spotify.raw", "x", writes = listOf(bad))).problems().any { it.contains("write") })
        }
        assertTrue(manifest(1, ActionRequest("spotify.raw", "x", writes = List(9) { "POST https://api.spotify.com/v1/x$it" })).problems().any { it.contains("8 writes") })
        assertTrue(manifest(1, ActionRequest("network", "x", listOf("a.org"), writes = listOf(events))).problems().any { it.contains("only spotify.raw takes writes") })

        // An update that adds a write waits for the user; the same writes again apply by themselves.
        val accepted = snap(manifest(1, ActionRequest("spotify.raw", "Sends your listens to Spotify")))
        val changes = (UpdateGate.decide(accepted, snap(manifest(2, raw))) as UpdateGate.Decision.Ask).changes
        assertEquals(listOf(Change.Kind.WRITE), changes.map { it.kind })
        assertTrue(changes.single().critical)
        assertEquals(UpdateGate.Decision.Apply, UpdateGate.decide(snap(m), snap(manifest(2, raw))))
    }
}
