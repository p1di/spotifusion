package com.music.spotui.data

import com.music.spotui.data.api.SpotifySync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistMoveTest {
    private fun applyMoves(initial: List<String>, moves: List<Pair<String, String?>>): List<String> {
        val list = initial.toMutableList()
        for ((uid, beforeUid) in moves) {
            list.remove(uid)
            if (beforeUid == null) {
                list.add(uid)
            } else {
                val idx = list.indexOf(beforeUid)
                check(idx >= 0) { "beforeUid $beforeUid not found in $list" }
                list.add(idx, uid)
            }
        }
        return list
    }

    @Test
    fun moveLastToTopIsSingleMove() {
        val from = listOf("u1", "u2", "u3", "u4")
        val to = listOf("u4", "u1", "u2", "u3")
        val moves = SpotifySync.computeMinimalMoves(from, to)
        assertEquals(1, moves.size)
        assertEquals("u4" to "u1", moves.single())
        assertEquals(to, applyMoves(from, moves))
    }

    @Test
    fun moveFirstToBottomIsSingleMove() {
        val from = listOf("u1", "u2", "u3", "u4")
        val to = listOf("u2", "u3", "u4", "u1")
        val moves = SpotifySync.computeMinimalMoves(from, to)
        assertEquals(1, moves.size)
        assertEquals("u1" to null, moves.single())
        assertEquals(to, applyMoves(from, moves))
    }

    @Test
    fun complexPermutationProducesExactOrderWithMinimalMoves() {
        val from = listOf("a", "b", "c", "d", "e", "f", "g")
        val to = listOf("b", "f", "a", "c", "e", "g", "d")
        val moves = SpotifySync.computeMinimalMoves(from, to)
        assertTrue(moves.size <= 3)
        assertEquals(to, applyMoves(from, moves))
    }
}
