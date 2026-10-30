package com.music.spotui.data.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalPlayPlanTest {
    private val have = setOf("a", "b", "c")
    private fun plan(ordered: List<String>, picked: String, complete: Boolean = true, offline: Boolean = false, saver: Boolean = false) =
        LocalPlayPlan.localIds(ordered, picked, complete, offline, saver) { it in have }

    @Test fun onlineFullyDownloadedListPlaysLocally() =
        assertEquals(listOf("c", "a", "b"), plan(listOf("c", "a", "b", "a"), "a"))

    @Test fun onlinePartlyDownloadedListStreams() = assertNull(plan(listOf("a", "b", "x"), "a"))

    @Test fun onlineListNotFullyLoadedStreams() = assertNull(plan(listOf("a", "b"), "a", complete = false))

    @Test fun onlinePickedSongNotDownloadedStreams() = assertNull(plan(listOf("a", "b", "x"), "x"))

    @Test fun offlinePlaysTheDownloadedSongsOnly() = assertEquals(listOf("a", "b"), plan(listOf("a", "x", "b"), "a", offline = true))

    @Test fun dataSaverPlaysTheDownloadedSongsOnly() = assertEquals(listOf("b", "a"), plan(listOf("b", "x", "a"), "a", saver = true))

    @Test fun offlinePickedSongNotDownloadedIsLeftToTheCaller() = assertNull(plan(listOf("a", "x"), "x", offline = true))

    @Test fun blankIdsAreIgnored() = assertEquals(listOf("a"), plan(listOf("a", ""), "a"))
}
