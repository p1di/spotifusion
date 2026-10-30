package com.music.spotui.data.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class StreamHeadTest {
    private fun head(key: String, url: String = "https://cdn.example.com/$key.flac", until: Long = 1_000) =
        StreamHead.Head(key, url, ByteArray(4), 100, until)

    @Test fun aHeadIsServedOnceToItsSongAndAddress() {
        val h = head("a")
        StreamHead.put(h)
        assertNull(StreamHead.take("b", "https://cdn.example.com/b.flac", now = 10))
        assertSame(h, StreamHead.take("a", "https://cdn.example.com/a.flac", now = 10))
        assertNull(StreamHead.take("a", "https://cdn.example.com/a.flac", now = 10))
    }

    @Test fun aStaleOrMovedHeadIsDropped() {
        StreamHead.put(head("a"))
        // The song got another address (asked again): the head is of the old one.
        assertNull(StreamHead.take("a", "https://cdn.example.com/other.flac", now = 10))
        assertNull(StreamHead.take("a", "https://cdn.example.com/a.flac", now = 10))
        StreamHead.put(head("a", until = 100))
        assertNull(StreamHead.take("a", "https://cdn.example.com/a.flac", now = 100))
        StreamHead.put(head("a", until = 100))
        assertNull(StreamHead.take("b", "https://cdn.example.com/b.flac", now = 200))
        assertNull(StreamHead.take("a", "https://cdn.example.com/a.flac", now = 50))
    }

    @Test fun theTotalIsReadFromTheRange() {
        assertEquals(9_876_543L, StreamHead.total("bytes 0-524287/9876543"))
        assertNull(StreamHead.total("bytes 0-524287/*"))
        assertNull(StreamHead.total("bytes 10-20/30"))
        assertNull(StreamHead.total(null))
    }
}
