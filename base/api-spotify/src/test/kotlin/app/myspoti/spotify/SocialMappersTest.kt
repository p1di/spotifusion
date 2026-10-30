package app.myspoti.spotify

import app.myspoti.api.FriendActivity
import app.myspoti.api.TimeRange
import app.myspoti.spotify.bridge.Bridge
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Listening stats (`userTopContent`) and friend activity (`presence-view/v1/buddylist`) parsing. */
class SocialMappersTest {
    private fun json(s: String) = Bridge.json.parseToJsonElement(s)

    @Test fun `time ranges use the Web Player enum words`() {
        assertEquals(listOf("SHORT_TERM", "MID_TERM", "LONG_TERM"), TimeRange.entries.map { it.gqlName })
    }

    // Shape seen live (2026-10-23), trimmed.
    private val top = json("""
        {"topArtists":{"__typename":"ArtistPageV2","items":[
          {"data":{"__typename":"Artist","profile":{"name":"Artist A"},"uri":"spotify:artist:aaa",
            "visuals":{"avatarImage":{"sources":[{"height":640,"url":"https://i.scdn.co/image/a640","width":640},{"height":160,"url":"https://i.scdn.co/image/a160","width":160}]}}}},
          {"data":{"__typename":"Artist","profile":{"name":"Artist B"},"uri":"spotify:artist:bbb","visuals":{"avatarImage":null}}}
        ],"totalCount":46},
        "topTracks":{"__typename":"TrackPageV2","items":[
          {"data":{"__typename":"Track","albumOfTrack":{"coverArt":{"sources":[{"height":300,"url":"https://i.scdn.co/image/c300","width":300}]},"name":"Album X","uri":"spotify:album:xxx"},
            "artists":{"items":[{"profile":{"name":"Artist A"},"uri":"spotify:artist:aaa"},{"profile":{"name":"Artist C"},"uri":"spotify:artist:ccc"}]},
            "contentRating":{"label":"EXPLICIT"},"duration":{"totalMilliseconds":116230},"mediaType":"AUDIO","name":"Song 1","saved":true,"uri":"spotify:track:t1"}}
        ],"totalCount":50}}
    """)

    @Test fun `top content maps artists, tracks and totals`() {
        val t = topContent(top)
        assertEquals(listOf("Artist A", "Artist B"), t.artists.map { it.name })
        assertEquals("spotify:artist:aaa", t.artists[0].uri)
        assertEquals(2, t.artists[0].images.size)
        assertEquals(46, t.artistTotal)
        assertEquals(50, t.trackTotal)
        val song = t.tracks.single()
        assertEquals("Song 1", song.name)
        assertEquals(listOf("Artist A", "Artist C"), song.artists.map { it.name })
        assertEquals("Album X", song.album?.name)
        assertEquals(116230L, song.durationMs)
    }

    @Test fun `top content without data is empty`() {
        val t = topContent(json("""{"topArtists":null}"""))
        assertTrue(t.artists.isEmpty() && t.tracks.isEmpty())
        assertNull(t.artistTotal)
    }

    private val buddies = json("""
        {"friends":[
          {"timestamp":1000,"user":{"uri":"spotify:user:old","name":"Old Friend","imageUrl":""},
           "track":{"uri":"spotify:track:t9","name":"Older","imageUrl":"http://i.scdn.co/image/c9",
             "album":{"uri":"spotify:album:a9","name":"Alb9"},"artist":{"uri":"spotify:artist:r9","name":"Art9"},
             "context":{"uri":"spotify:playlist:p9","name":"Road trip","index":0}}},
          {"timestamp":5000,"user":{"uri":"spotify:user:new","name":"New Friend","imageUrl":"https://i.scdn.co/image/u1"},
           "track":{"uri":"spotify:track:t1","name":"Newer","imageUrl":"http://i.scdn.co/image/c1",
             "album":{"uri":"spotify:album:a1","name":"Alb1"},"artist":{"uri":"spotify:artist:r1","name":"Art1"},
             "context":{"uri":"spotify:album:a1","name":"","index":3}}},
          {"timestamp":7000,"user":{"name":"no uri"}}
        ]}
    """)

    @Test fun `buddylist maps friends latest first`() {
        val list = buddyList(buddies)
        assertEquals(listOf("New Friend", "Old Friend"), list.map { it.user.name })
        val n = list[0]
        assertEquals("https://i.scdn.co/image/u1", n.user.imageUrl)
        assertEquals("Newer", n.track?.name)
        assertEquals("Art1", n.track?.artists?.single()?.name)
        assertEquals("spotify:album:a1", n.track?.album?.uri)
        assertEquals("spotify:album:a1", n.contextUri)
        assertNull(n.contextName)
        assertEquals(5000L, n.timestampMs)
        val o = list[1]
        assertNull(o.user.imageUrl)
        assertEquals("Road trip", o.contextName)
        assertEquals("http://i.scdn.co/image/c9", o.trackImageUrl)
    }

    @Test fun `empty or refused feed maps to no friends`() {
        assertTrue(buddyList(json("""{"friends":[]}""")).isEmpty())
        assertTrue(buddyList(null).isEmpty())
    }

    @Test fun `now playing lasts 15 minutes`() {
        val f = FriendActivity(app.myspoti.api.UserRef("spotify:user:x", "x", null), null, null, timestampMs = 1_000_000)
        assertTrue(f.isNowPlaying(1_000_000 + 14 * 60_000))
        assertFalse(f.isNowPlaying(1_000_000 + 15 * 60_000))
        assertFalse(f.isNowPlaying(999_000))
    }
}
