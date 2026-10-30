package app.myspoti.spotify.gql

import app.myspoti.spotify.gql.SpotifyHttp.RawTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RawTargetTest {
    @Test fun `the account's credentials only go to spotify dot com`() {
        assertEquals(RawTarget.ACCOUNT, SpotifyHttp.rawTarget("https", "api.spotify.com"))
        assertEquals(RawTarget.ACCOUNT, SpotifyHttp.rawTarget("https", "gue1-spclient.spotify.com"))
        assertEquals(RawTarget.ACCOUNT, SpotifyHttp.rawTarget("https", "spotify.com"))
        assertEquals(RawTarget.PUBLIC, SpotifyHttp.rawTarget("https", "open.spotifycdn.com"))
    }

    @Test fun `other hosts, look-alikes and plain http are refused`() {
        assertNull(SpotifyHttp.rawTarget("http", "api.spotify.com"))
        assertNull(SpotifyHttp.rawTarget("https", "evilspotify.com"))
        assertNull(SpotifyHttp.rawTarget("https", "spotify.com.evil.net"))
        assertNull(SpotifyHttp.rawTarget("https", "example.com"))
    }
}
