package com.music.spotui.data.api

import com.music.spotui.data.entity.SongsModel
import org.junit.Assert.assertEquals
import org.junit.Test

/** A like or unlike puts Liked Songs right from its first page only. */
class LikedMergeTest {
    private fun song(id: String) = SongsModel(id = id.hashCode(), title = id, singer = "", album = "", coverUri = "", url = "spotify:track:$id").copy(spotifyTrackId = id)
    private fun ids(list: List<SongsModel>) = list.map { it.spotifyTrackId }

    @Test fun aNewLikeGoesOnTop() {
        val known = listOf("b", "c").map(::song)
        assertEquals(listOf("a", "b", "c"), ids(Api.LikedCache.merge(listOf("a", "b", "c").map(::song), known)))
    }

    @Test fun anUnlikedSongLeavesTheList() {
        val known = listOf("a", "b", "c").map(::song)
        assertEquals(listOf("a", "c"), ids(Api.LikedCache.merge(listOf("a", "c").map(::song), known)))
    }

    @Test fun olderSongsAfterAFullFirstPageStay() {
        val head = (1..50).map { song("n$it") }
        val known = (2..50).map { song("n$it") } + listOf(song("old1"), song("old2"))
        val merged = Api.LikedCache.merge(head, known)
        assertEquals(52, merged.size)
        assertEquals(listOf("old1", "old2"), ids(merged).takeLast(2))
    }

    @Test fun aShortHeadIsTheWholeList() {
        assertEquals(true, Api.LikedCache.reaches(listOf("a", "b").map(::song), listOf("z").map(::song)))
    }

    @Test fun aFullHeadReachesTheListOnlyThroughASongKnown() {
        val head = (1..50).map { song("n$it") }
        assertEquals(true, Api.LikedCache.reaches(head, listOf(song("n50"), song("old"))))
        // More than a page of new likes: nothing links the head to the list known.
        assertEquals(false, Api.LikedCache.reaches(head, listOf(song("old1"), song("old2"))))
    }
}
