package app.myspoti.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorTest {
    private var now = 1_000_000L
    private val g = MirrorGuard { now }

    @Test fun setLocalMuteNeedsTheCriticalMirrorAction() {
        val m = SpotifyMethods.find("player", "setLocalMute")!!
        assertEquals(Action.PLAYBACK_MIRROR, m.action)
        assertEquals(Level.CRITICAL, Action.PLAYBACK_MIRROR.level)
        // Control alone does not unlock it; legacy keys never grant it.
        assertFalse(Action.migrateGranted(setOf("spotify.write", "player.control")).contains("playback.mirror"))
    }

    @Test fun onlyWhileTheLocalPlayerPlays() {
        assertNotNull(g.enable("a", false, "local:1", 1000))
        assertNotNull(g.enable("a", true, null, 1000))
        assertNull(g.enable("a", true, "local:1", 1000))
    }

    @Test fun oneExtensionAtATimeAndRateLimited() {
        assertNull(g.enable("a", true, "local:1", 100_000))
        assertNotNull(g.enable("b", true, "local:1", 100_000))
        now += 5_000
        assertNotNull(g.enable("a", true, "local:1", 100_000))
        now += MirrorGuard.MIN_INTERVAL_MS
        assertNull(g.enable("a", true, "local:1", 100_000))
    }

    @Test fun clearsOnTrackChangePauseDurationAndExtensionGone() {
        g.enable("a", true, "local:1", 10_000)
        assertNull(g.check(true, "local:1"))
        assertEquals("the track changed", g.check(true, "local:2"))
        assertNull(g.active)
        now += 30_000
        g.enable("a", true, "local:2", 10_000)
        assertNotNull(g.check(false, "local:2"))
        now += 30_000
        g.enable("a", true, "local:2", 10_000)
        now += 16_000
        assertEquals("the track's duration ended", g.check(true, "local:2"))
        now += 30_000
        g.enable("a", true, "local:2", 10_000)
        assertFalse(g.extensionGone("b"))
        assertTrue(g.extensionGone("a"))
        assertNull(g.active)
    }

    // ── raw.request grants per operation ──

    private val gql = "https://api-partner.spotify.com/pathfinder/v1/query"
    private fun body(op: String) = """{"operationName":"$op","variables":{}}"""

    @Test fun sameOperationIsCoveredOtherIsNot() {
        val grant = RawRequest.offerFor("POST", gql, body("addToPlaylist"))!!
        assertEquals("addToPlaylist", grant.op)
        assertTrue(RawRequest.covered(listOf(grant), "POST", gql, body("addToPlaylist")))
        assertFalse(RawRequest.covered(listOf(grant), "POST", gql, body("removeFromPlaylist")))
        assertFalse(RawRequest.covered(listOf(grant), "POST", gql, "{}"))
    }

    @Test fun persistedQueryHashNamesTheOperation() {
        val b = """{"extensions":{"persistedQuery":{"version":1,"sha256Hash":"abc123"}}}"""
        assertEquals("sha256:abc123", RawRequest.operationOf(b))
    }

    @Test fun noOperationOnAMultiplexedEndpointMeansNoAlways() {
        assertNull(RawRequest.offerFor("POST", gql, "{}"))
        assertNull(RawRequest.offerFor("POST", gql, null))
        assertNull(RawRequest.offerFor("POST", gql, "not json"))
        // An old grant without an operation covers nothing there, and is flagged for dropping.
        val old = RawGrant("POST", "api-partner.spotify.com", "/pathfinder/v1/query")
        assertTrue(old.tooBroad)
        assertFalse(RawRequest.covered(listOf(old), "POST", gql, body("x")))
        assertFalse(RawRequest.covered(listOf(old), "POST", gql, null))
    }

    @Test fun plainEndpointsKeepTheirGrants() {
        val url = "https://api.spotify.com/v1/me/player/12345"
        val g = RawRequest.offerFor("POST", url, null)!!
        assertEquals("", g.op)
        assertTrue(RawRequest.covered(listOf(g), "POST", url, null))
        assertFalse(g.tooBroad)
    }
}
