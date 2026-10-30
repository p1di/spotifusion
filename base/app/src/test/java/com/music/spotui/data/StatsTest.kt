package com.music.spotui.data

import app.myspoti.api.TimeRange
import com.music.spotui.data.api.TopStats
import com.music.spotui.data.local.ListenHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** The "your top artist" badge rule and the minutes-per-week chart of the stats screen. */
class StatsTest {
    @Test fun rankPrefersShortestWindowInTopTen() {
        val ranked = mapOf(
            TimeRange.SHORT_TERM to listOf("a", "b", "c"),
            TimeRange.MEDIUM_TERM to listOf("c", "a"),
            TimeRange.LONG_TERM to listOf("c"),
        )
        assertEquals(TimeRange.SHORT_TERM to 3, TopStats.rankOf("c", ranked))
        assertEquals(TimeRange.SHORT_TERM to 1, TopStats.rankOf("a", ranked))
        assertNull(TopStats.rankOf("z", ranked))
    }

    @Test fun rankOutsideTopTenTakesTheBestRank() {
        val filler = (1..30).map { "x$it" }
        val ranked = mapOf(
            TimeRange.SHORT_TERM to filler.take(20) + "a",
            TimeRange.MEDIUM_TERM to filler.take(14) + "a",
            TimeRange.LONG_TERM to filler.take(25) + "a",
        )
        assertEquals(TimeRange.MEDIUM_TERM to 15, TopStats.rankOf("a", ranked))
        assertEquals(TimeRange.LONG_TERM to 1, TopStats.rankOf("b", mapOf(TimeRange.LONG_TERM to listOf("b"))))
    }

    private val utc = TimeZone.getTimeZone("UTC")
    private fun at(y: Int, m: Int, d: Int, h: Int = 12) = Calendar.getInstance(utc).apply {
        clear(); set(y, m - 1, d, h, 0)
    }.timeInMillis
    private fun listen(atMs: Long, minutes: Long) =
        ListenHistory.Listen(atMs, "k", "t", "a", "al", "", minutes * 60_000, ListenHistory.Source.SPOTIFY)

    @Test fun weeklyGroupsByCalendarWeekOldestFirst() {
        // Thursday 2026-10-22; weeks start on Monday: 19 Oct, 12 Oct, 5 Oct.
        val now = at(2026, 10, 22)
        val listens = listOf(
            listen(at(2026, 10, 19, 0), 10),   // Monday 00:00, this week
            listen(at(2026, 10, 21), 5),       // this week
            listen(at(2026, 10, 18, 23), 7),   // Sunday, last week
            listen(at(2026, 10, 5), 3),        // two weeks ago
            listen(at(2026, 9, 1), 99),        // too old
            listen(at(2026, 10, 23), 50),      // after now
        )
        val w = ListenHistory.weekly(listens, 3, now, utc, Calendar.MONDAY)
        assertEquals(listOf(at(2026, 10, 5, 0), at(2026, 10, 12, 0), at(2026, 10, 19, 0)), w.map { it.startMs })
        assertEquals(listOf(3L, 7L, 15L), w.map { it.minutes })
    }

    @Test fun weeklyFollowsTheLocaleFirstDay() {
        val now = at(2026, 10, 22)
        val w = ListenHistory.weekly(listOf(listen(at(2026, 10, 18, 9), 4)), 1, now, utc, Calendar.SUNDAY)
        assertEquals(at(2026, 10, 18, 0), w.single().startMs)
        assertEquals(4L, w.single().minutes)
    }
}
