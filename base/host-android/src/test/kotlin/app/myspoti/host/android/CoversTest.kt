package app.myspoti.host.android

import org.junit.Assert.assertEquals
import org.junit.Test

class CoversTest {
    @Test fun aSmallAlbumCoverIsAskedLarge() {
        assertEquals("https://i.scdn.co/image/ab67616d0000b273aa11", Covers.large("https://i.scdn.co/image/ab67616d00001e02aa11"))
        assertEquals("https://i.scdn.co/image/ab67616d0000b273aa11", Covers.large("https://i.scdn.co/image/ab67616d00004851aa11"))
        assertEquals("spotify:image:ab67616d0000b273aa11", Covers.large("spotify:image:ab67616d00001e02aa11"))
        assertEquals("https://image-cdn-ak.spotifycdn.com/image/ab67616d0000b273aa11", Covers.large("https://image-cdn-ak.spotifycdn.com/image/ab67616d00004851aa11"))
    }

    @Test fun anythingElseIsKept() {
        for (u in listOf("https://i.scdn.co/image/ab67616d0000b273aa11", "https://mosaic.scdn.co/640/ab67616d00001e02aaab67616d00001e02bb",
                "content://media/external/audio/albumart/3", "https://i.scdn.co/image/ab6761610000e5ebcc", "")) assertEquals(u, Covers.large(u))
    }
}
