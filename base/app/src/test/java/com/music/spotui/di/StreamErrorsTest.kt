package com.music.spotui.di

import com.music.spotui.di.StreamErrors.Step
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamErrorsTest {
    @Test fun `a failing song is asked again once`() {
        val e = StreamErrors()
        assertEquals(Step.RETRY, e.failed("a", true))
        assertEquals(Step.SKIP, e.failed("a", true))
    }

    @Test fun `each song gets its own retry`() {
        val e = StreamErrors()
        e.failed("a", true); e.failed("a", true)
        assertEquals(Step.RETRY, e.failed("b", true))
    }

    @Test fun `no more than two songs in a row are skipped`() {
        val e = StreamErrors()
        listOf("a", "b", "c").forEach { e.failed(it, true) }
        assertEquals(Step.SKIP, e.failed("a", true))
        assertEquals(Step.SKIP, e.failed("b", true))
        assertEquals(Step.STOP, e.failed("c", true))
    }

    @Test fun `a song that played resets the count`() {
        val e = StreamErrors()
        e.failed("a", true); e.failed("a", true); e.failed("b", true); e.failed("b", true)
        e.played()
        e.failed("c", true)
        assertEquals(Step.SKIP, e.failed("c", true))
    }

    @Test fun `the last song of the queue stops`() {
        val e = StreamErrors()
        e.failed("a", false)
        assertEquals(Step.STOP, e.failed("a", false))
    }
}
