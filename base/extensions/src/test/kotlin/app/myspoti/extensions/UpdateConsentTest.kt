package app.myspoti.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The update consent: code changes, the 24 h wait, the repository's pinned ids. */
class UpdateConsentTest {
    private fun req(name: String, hosts: List<String> = emptyList()) = ActionRequest(name, "because", hosts)
    private fun manifest(vararg a: ActionRequest) = Manifest("io.github.me.ext", "Ext", "1.0", 1, author = "me", actions = a.toList())
    private fun snap(script: String, vararg a: ActionRequest, repoId: String? = "10", ownerId: String? = "20") =
        Snapshot.of(manifest(*a), "me/ext", script, Origin("me/ext", repoId = repoId, ownerId = ownerId))

    private val harmless = arrayOf(req("lyrics.provide"), req("network", listOf("lrclib.net")))
    private val code = CodeDiff.of(mapOf("main.js" to "a\nb"), mapOf("main.js" to "a\nc\nd"))
    private val h = 3600_000L

    @Test fun codeOnlyChangeWithACriticalActionAsks() {
        val d = UpdateGate.decide(snap("v1", req("library.write")), snap("v2", req("library.write")), code, firstSeen = 0, now = 100 * h)
        val c = (d as UpdateGate.Decision.Ask).changes.single()
        assertEquals(Change.Kind.CODE, c.kind)
        assertEquals("main.js", c.key); assertEquals(2, c.added); assertEquals(1, c.removed)
    }

    @Test fun codeOnlyChangeWithWildcardNetworkAsks() {
        val a = req("network", listOf("*"))
        assertTrue(UpdateGate.decide(snap("v1", a), snap("v2", a), code) is UpdateGate.Decision.Ask)
        val b = req("network", listOf("*.example.com"))
        assertTrue(UpdateGate.decide(snap("v1", b), snap("v2", b), code) is UpdateGate.Decision.Ask)
    }

    @Test fun codeOnlyChangeWithHarmlessRightsAppliesAfterADay() {
        val a = snap("v1", *harmless)
        val b = snap("v2", *harmless)
        assertEquals(UpdateGate.Decision.Wait(UpdateGate.DELAY_MS), UpdateGate.decide(a, b, code, firstSeen = 0, now = 23 * h))
        assertEquals(UpdateGate.Decision.Apply, UpdateGate.decide(a, b, code, firstSeen = 0, now = 24 * h))
        // The user asked for it: no wait.
        assertEquals(UpdateGate.Decision.Apply, UpdateGate.decide(a, b, code, firstSeen = null, now = 0))
    }

    @Test fun anotherRepositoryOrOwnerIdBlocks() {
        val a = snap("v1", *harmless)
        assertTrue(UpdateGate.decide(a, snap("v2", *harmless, repoId = "11")) is UpdateGate.Decision.Block)
        assertTrue(UpdateGate.decide(a, snap("v2", *harmless, ownerId = "21")) is UpdateGate.Decision.Block)
        // Not pinned yet (or not readable now): nothing to compare, nothing blocked.
        assertEquals(UpdateGate.Decision.Apply, UpdateGate.decide(a, snap("v2", *harmless, repoId = null, ownerId = null)))
        assertNull(UpdateGate.identityChange(snap("v1", *harmless, repoId = null), snap("v2", *harmless)))
    }

    @Test fun codeDiffCountsLinesAndFiles() {
        assertEquals(CodeDiff(0, 0, emptyList()), CodeDiff.of(mapOf("a" to "x"), mapOf("a" to "x")))
        val d = CodeDiff.of(mapOf("a" to "1\n2\n3", "b" to "k"), mapOf("a" to "1\n3\n4", "c" to "z"))
        assertEquals(listOf("a", "b", "c"), d.files)
        assertEquals(2, d.added); assertEquals(2, d.removed)
    }

    // ── Pinned ids, per host ──

    @Test fun identityIsParsedForTheThreeHosts() {
        assertEquals(RepoIds("1296269", "583231"), Discovery.parseIdentity(CodeHost.GITHUB, """{"id":1296269,"name":"x","owner":{"login":"octocat","id":583231}}"""))
        assertEquals(RepoIds("278964", "1385"), Discovery.parseIdentity(CodeHost.GITLAB, """{"id":278964,"path":"x","namespace":{"id":1385,"path":"g"},"owner":{"id":7}}"""))
        assertEquals(RepoIds("42", "7"), Discovery.parseIdentity(CodeHost.CODEBERG, """{"id":42,"full_name":"o/x","owner":{"id":7,"login":"o"}}"""))
        assertNull(Discovery.parseIdentity(CodeHost.GITHUB, """{"message":"Not Found"}"""))
        assertNull(Discovery.parseIdentity(CodeHost.GITLAB, "not json"))
    }

    @Test fun discoveryGivesTheIdsToPin() {
        val gh = Discovery.parse(CodeHost.GITHUB, """{"items":[{"id":5,"full_name":"me/ext","owner":{"login":"me","id":6}}]}""").single()
        assertEquals(RepoIds("5", "6"), gh.ids)
        val gl = Discovery.parse(CodeHost.GITLAB, """[{"id":8,"path_with_namespace":"grp/ext","namespace":{"id":9}}]""").single()
        assertEquals(RepoIds("8", "9"), gl.ids)
        val cb = Discovery.parse(CodeHost.CODEBERG, """{"data":[{"id":3,"full_name":"me/ext","owner":{"id":4,"login":"me"}}]}""").single()
        assertEquals(RepoIds("3", "4"), cb.ids)
    }

    @Test fun identityUrls() {
        assertEquals("https://api.github.com/repos/me/ext", Discovery.identityUrl(GitHubRepo("me", "ext")))
        assertEquals("https://gitlab.com/api/v4/projects/grp%2Fsub%2Fext", Discovery.identityUrl(GitHubRepo("grp/sub", "ext", CodeHost.GITLAB)))
        assertEquals("https://codeberg.org/api/v1/repos/me/ext", Discovery.identityUrl(GitHubRepo("me", "ext", CodeHost.CODEBERG)))
    }
}
