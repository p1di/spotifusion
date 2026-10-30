package com.music.spotui.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * B25: the profile endpoints hand out `spotify:image:<id>` identifiers rather than a picture's
 * address; the image loader was given them as they were and kept its grey placeholder, so a user's
 * profile picture and their public playlists never showed. These cases pin what the fix promises:
 * an identifier becomes the URL it stands for, and anything already usable is left alone.
 */
class SpotifyImagesTest {
    @Test fun identifierBecomesTheUrlItStandsFor() =
        assertEquals("https://i.scdn.co/image/ab67616d0000b273", spotifyImageUrl("spotify:image:ab67616d0000b273"))

    @Test fun aRealUrlPassesThrough() =
        assertEquals("https://i.scdn.co/image/abc123", spotifyImageUrl("https://i.scdn.co/image/abc123"))

    @Test fun missingStaysMissing() {
        assertNull(spotifyImageUrl(null))
        assertNull(spotifyImageUrl(""))
        assertNull(spotifyImageUrl("   "))
    }

    // Measured on a public profile: the URL below answers 200 image/jpeg.
    @Test fun mosaicBecomesTheMosaicOfItsFourCovers() = assertEquals(
        "https://mosaic.scdn.co/640/ab67616d00001e025b436ba28d283bc720bb86b8ab67616d00001e02707a76900ebe2c7f5fad3615",
        spotifyImageUrl("spotify:mosaic:ab67616d00001e025b436ba28d283bc720bb86b8:ab67616d00001e02707a76900ebe2c7f5fad3615"),
    )

    @Test fun anythingElseIsUntouched() =
        assertEquals("spotify:track:4cOdK2wGLETKBW3PvgPWqT", spotifyImageUrl("spotify:track:4cOdK2wGLETKBW3PvgPWqT"))

    private fun c(id: String) = "https://i.scdn.co/image/$id"

    @Test fun fourDifferentAlbumsMakeTheMosaicOfAPlaylistWithoutCover() = assertEquals(
        "https://mosaic.scdn.co/640/aabbccdd",
        mosaicCoverFor(listOf(c("aa"), c("aa"), c("bb"), c("cc"), c("dd"), c("ee"))),
    )

    // Two or three images are refused by the mosaic host: the first album's cover stands in.
    @Test fun fewerThanFourAlbumsUseTheFirstCover() = assertEquals(c("aa"), mosaicCoverFor(listOf(c("aa"), c("bb"), c("aa"))))

    @Test fun noSpotifyCoverGivesNothing() {
        assertEquals("", mosaicCoverFor(emptyList()))
        assertEquals("", mosaicCoverFor(listOf("", "https://example.com/x.jpg")))
    }
}
