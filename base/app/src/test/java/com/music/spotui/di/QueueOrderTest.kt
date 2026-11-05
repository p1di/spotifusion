package com.music.spotui.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QueueOrderTest {
    /** No equals(): two of them are never equal, like two entries of one song in a playlist. */
    private class Song(val n: Int)

    private fun songs(n: Int) = List(n) { Song(it) }

    @Test fun shuffleStartsWithTheChosenSong() {
        val list = songs(50)
        assertSame(list[17], QueueOrder.shuffledFrom(list, 17, Random(1)).first())
    }

    @Test fun shuffleKeepsEverySongOnce() {
        val list = songs(4000)
        val out = QueueOrder.shuffledFrom(list, 1234, Random(2))
        assertEquals(list.size, out.size)
        assertEquals(list.toSet(), out.toSet())
    }

    @Test fun shuffleReallyReorders() {
        val list = songs(100)
        assertTrue(QueueOrder.shuffledFrom(list, 0, Random(3)) != list)
    }

    @Test fun shuffleOfOneOrNoneIsTheSame() {
        assertEquals(emptyList<Song>(), QueueOrder.shuffledFrom(emptyList<Song>(), 0))
        val one = songs(1)
        assertEquals(one, QueueOrder.shuffledFrom(one, 5))
    }

    @Test fun outOfRangeStartIsClamped() {
        val list = songs(5)
        assertSame(list.last(), QueueOrder.shuffledFrom(list, 99, Random(4)).first())
        assertSame(list.first(), QueueOrder.shuffledFrom(list, -3, Random(4)).first())
    }

    @Test fun restoreBringsBackTheOriginalOrder() {
        val list = songs(200)
        val shuffled = QueueOrder.shuffledFrom(list, 40, Random(5))
        assertEquals(list, QueueOrder.restored(list, shuffled))
    }

    @Test fun restoreDropsRemovedSongsAndKeepsAddedOnesLast() {
        val list = songs(10)
        val shuffled = QueueOrder.shuffledFrom(list, 0, Random(6)).toMutableList()
        shuffled.remove(list[3])
        val added = Song(99)
        shuffled.add(2, added)
        val out = QueueOrder.restored(list, shuffled)
        assertEquals(list.filter { it !== list[3] } + added, out)
    }

    @Test fun equalLookingSongsAreTreatedApart() {
        data class Row(val title: String)
        val a = Row("same"); val b = Row("same")
        // Same value, two entries: identity keeps both.
        val out = QueueOrder.restored(listOf(a, b), listOf(b, a))
        assertEquals(2, out.size)
        assertSame(a, out[0]); assertSame(b, out[1])
    }
}
