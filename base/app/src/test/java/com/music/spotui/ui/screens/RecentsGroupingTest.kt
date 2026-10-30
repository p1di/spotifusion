package com.music.spotui.ui.screens

import app.myspoti.api.MediaKind
import app.myspoti.api.RecentEntry
import app.myspoti.api.RecentItem
import app.myspoti.api.groupRecents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grouping of the "Récents" page, checked against the shapes the real `recents` response
 * sends: a context row announcing `children_group_id`, its plays tagged `group_id_N`, and the
 * `group_metadata` count on the context.
 */
class RecentsGroupingTest {
    private val today = 1_000_000L
    private val yesterday = 0L

    private fun item(
        uri: String,
        kind: MediaKind = MediaKind.TRACK,
        title: String = uri,
        day: Long? = today,
    ) = RecentItem(uri = uri, kind = kind, title = title, subtitle = "artist", images = emptyList(), playedAtMs = day)

    private fun context(
        uri: String,
        kind: MediaKind,
        group: String,
        count: Int? = null,
        saved: Boolean = false,
        day: Long? = today,
    ) = RecentEntry(
        item = item(uri, kind, title = uri, day = day),
        childrenGroup = group,
        groupCount = count,
        saved = saved,
    )

    private fun play(uri: String, group: String, day: Long? = today) =
        RecentEntry(item = item(uri, day = day), group = group)

    @Test
    fun aContextAndItsPlaysMakeOneRow() {
        val sections = groupRecents(
            listOf(
                context("spotify:album:ep", MediaKind.ALBUM, "1", count = 3),
                play("spotify:track:a", "1"),
                play("spotify:track:b", "1"),
                play("spotify:track:c", "1"),
            ),
        )
        assertEquals(1, sections.size)
        val group = sections.single().groups.single()
        assertEquals("spotify:album:ep", group.context.uri)
        assertEquals(3, group.count)
        assertEquals(listOf("spotify:track:a", "spotify:track:b", "spotify:track:c"), group.plays.map { it.uri })
    }

    @Test
    fun onePlaylistPlayedOnTwoDaysIsOneRowWithTheSum() {
        // The real list had "Classical Bangers" as a 1-play group of today and an 8-play group of
        // yesterday; Spotify's page showed a single "9 titres lus" row under today.
        val entries = buildList {
            add(context("spotify:playlist:bangers", MediaKind.PLAYLIST, "2", count = 1))
            add(play("spotify:track:s", "2"))
            add(context("spotify:playlist:bangers", MediaKind.PLAYLIST, "3", count = 4, day = yesterday))
            repeat(4) { add(play("spotify:track:y$it", "3", day = yesterday)) }
        }
        val sections = groupRecents(entries)
        assertEquals(1, sections.size)
        val group = sections.single().groups.single()
        assertEquals(5, group.count)
        assertEquals(5, group.plays.size)
        assertEquals(today, group.dayMs)
    }

    @Test
    fun theSameTrackTwiceIsListedOnceWhenGroupsMerge() {
        val entries = listOf(
            context("spotify:playlist:bangers", MediaKind.PLAYLIST, "2", count = 1),
            play("spotify:track:same", "2"),
            context("spotify:playlist:bangers", MediaKind.PLAYLIST, "3", count = 1, day = yesterday),
            play("spotify:track:same", "3", day = yesterday),
        )
        val group = groupRecents(entries).single().groups.single()
        assertEquals(2, group.count)
        assertEquals(listOf("spotify:track:same"), group.plays.map { it.uri })
    }

    @Test
    fun aSavedContextIsTheLikedSongsRow() {
        val entries = buildList {
            add(
                RecentEntry(
                    item = item("spotify:collection:tracks", MediaKind.PLAYLIST, "Titres likés", day = yesterday),
                    childrenGroup = "4",
                    groupCount = 2,
                    saved = true,
                ),
            )
            add(RecentEntry(item = item("spotify:track:x", day = yesterday), group = "4"))
            add(RecentEntry(item = item("spotify:track:y", day = yesterday), group = "4"))
        }
        val group = groupRecents(entries).single().groups.single()
        assertTrue(group.saved)
        assertEquals(2, group.count)
        assertEquals("spotify:collection:tracks", group.context.uri)
    }

    @Test
    fun aContextCutByThePageKeepsTheServersCount() {
        // The last group of a page can arrive with fewer plays than it holds: group_metadata is
        // the count the row must print.
        val sections = groupRecents(listOf(context("spotify:playlist:blend", MediaKind.PLAYLIST, "6", count = 43)))
        assertEquals(43, sections.single().groups.single().count)
        assertTrue(sections.single().groups.single().plays.isEmpty())
    }

    @Test
    fun aRowWithoutGroupKeysStaysItsOwnRow() {
        val sections = groupRecents(
            listOf(
                context("spotify:album:ep", MediaKind.ALBUM, "1", count = 1),
                play("spotify:track:a", "1"),
                RecentEntry(item = item("spotify:track:lonely")),
            ),
        )
        val groups = sections.single().groups
        assertEquals(2, groups.size)
        assertEquals("spotify:track:lonely", groups[1].context.uri)
        assertEquals(1, groups[1].count)
    }

    @Test
    fun aPlayWhoseContextWasNotAnnouncedStaysItsOwnRow() {
        // A page that starts mid-group (the context row sits on the page before), or a play filed
        // under another group than the open one: the play is listed, not dropped.
        val sections = groupRecents(
            listOf(
                play("spotify:track:orphan", "9"),
                context("spotify:album:ep", MediaKind.ALBUM, "1", count = 2),
                play("spotify:track:a", "1"),
                play("spotify:track:stray", "8"),
                play("spotify:track:b", "1"),
            ),
        )
        val groups = sections.single().groups
        assertEquals(
            listOf("spotify:track:orphan", "spotify:track:stray", "spotify:album:ep"),
            groups.map { it.context.uri },
        )
        assertEquals(listOf("spotify:track:a", "spotify:track:b"), groups.last().plays.map { it.uri })
    }

    @Test
    fun daysAreSectionsNewestFirst() {
        val sections = groupRecents(
            listOf(
                context("spotify:album:ep", MediaKind.ALBUM, "1", count = 1),
                play("spotify:track:a", "1"),
                context("spotify:artist:someone", MediaKind.ARTIST, "5", count = 1, day = yesterday),
                play("spotify:track:b", "5", day = yesterday),
            ),
        )
        assertEquals(listOf(today, yesterday), sections.map { it.dayMs })
        assertEquals(1, sections[0].groups.size)
        assertEquals(1, sections[1].groups.size)
    }

    @Test
    fun thePillSplitFollowsTheEntryFlag() {
        val entries = listOf(
            context("spotify:album:ep", MediaKind.ALBUM, "1", count = 1),
            RecentEntry(
                item = item("spotify:show:pod", MediaKind.SHOW, "A show"),
                childrenGroup = "7",
                groupCount = 1,
                music = false,
            ),
        )
        val groups = groupRecents(entries).single().groups
        assertEquals(listOf(true, false), groups.map { it.music })
    }
}
