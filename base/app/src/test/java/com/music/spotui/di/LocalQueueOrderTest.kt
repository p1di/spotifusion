package com.music.spotui.di

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalQueueOrderTest {
    // C is playing; Q1, Q2 were queued by the user; A, B, D are the list's next songs.
    private val order = listOf("P", "C", "Q1", "Q2", "A", "B", "D")
    private val queued = { s: String -> s.startsWith("Q") }

    @Test fun addToQueueGoesAfterTheQueuedSongsAndPlayNextFirst() {
        assertEquals(4, LocalQueueOrder.insertIndex(order, 1, next = false, isQueued = queued))
        assertEquals(2, LocalQueueOrder.insertIndex(order, 1, next = true, isQueued = queued))
        assertEquals(2, LocalQueueOrder.queuedAfter(order, 1, queued))
        // Nothing queued: "Add to queue" is the very next song, not the end of the list.
        assertEquals(2, LocalQueueOrder.insertIndex(listOf("P", "C", "A"), 1, next = false, isQueued = queued))
    }

    @Test fun aTapOnASongOfTheListKeepsTheQueuedSongsRightAfterIt() {
        val (after, at) = LocalQueueOrder.skip(order, 1, 5, queued)
        assertEquals(listOf("P", "C", "A", "B", "Q1", "Q2", "D"), after)
        assertEquals("B", after[at])
        // A tap on a queued song: it plays, nothing moves.
        assertEquals(order to 3, LocalQueueOrder.skip(order, 1, 3, queued))
    }

    @Test fun shuffleLeavesTheQueuedSongsInPlaceAndOffPutsTheListBack() {
        val on = LocalQueueOrder.shuffled(order, 1, queued) { it.reversed() }
        assertEquals(listOf("P", "C", "Q1", "Q2", "D", "B", "A"), on)
        // Back in the list's order, the queued songs still next, a song added meanwhile at the end.
        val before = listOf("A", "P", "C", "B", "D")
        val now = listOf("P", "C", "Q1", "Q2", "D", "X", "B", "A")
        assertEquals(listOf("A", "P", "C", "Q1", "Q2", "B", "D", "X"), LocalQueueOrder.unshuffled(now, 1, before, queued))
    }

    @Test fun aSongDraggedAmongTheQueuedOnesIsMovedUp() {
        assertEquals(listOf("P", "C", "Q1", "Q2", "Q3", "A", "B"), LocalQueueOrder.settle(listOf("P", "C", "Q1", "A", "Q2", "B", "Q3"), 1, queued))
    }
}
