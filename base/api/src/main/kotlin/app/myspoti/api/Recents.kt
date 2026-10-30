package app.myspoti.api

/**
 * One row of the account's recents list, with the group keys Spotify sends beside it.
 *
 * The list is not flat. The row of a context (an album, a playlist, an artist, a show) announces
 * `children_group_id: N`, and every following row that was played from it carries `group_id_N`.
 * Those two keys are the whole grouping — they are what says which plays belong under which
 * context, and they are why the page must not guess a play's container from its album: a playlist
 * plays other albums' tracks, and the list still files them under the playlist.
 */
data class RecentEntry(
    val item: RecentItem,
    /** On a context row: the group id its plays carry. */
    val childrenGroup: String? = null,
    /** On a play row: the group it was played from. */
    val group: String? = null,
    /** A context row's own count (`group_metadata`): how many plays it collected. */
    val groupCount: Int? = null,
    /** Saved rather than played (Liked Songs): the page says "titres ajoutés", not "lus". */
    val saved: Boolean = false,
    /** Music, or a podcast/audiobook: what the two pills of the page split on. */
    val music: Boolean = true,
)

/** A context and the plays it collected: one row of the "Récents" page. */
data class RecentGroup(
    /** The context row itself: its uri opens the page, its cover and type fill the row. */
    val context: RecentItem,
    /** How many plays are counted for the row ("N titres lus"). */
    val count: Int,
    val saved: Boolean,
    val music: Boolean,
    /** The plays, newest first: the row expands to them. */
    val plays: List<RecentItem>,
    /** The day the row sits under (epoch ms, local midnight), from its newest play. */
    val dayMs: Long?,
)

/** One date heading ("Aujourd'hui", "Hier", …) and its rows, newest first. */
data class RecentSection(val dayMs: Long?, val groups: List<RecentGroup>)

/**
 * Rebuilds the page's rows from the list's raw order.
 *
 * Two rules, both taken from the real response:
 * - a context row opens a group, and the rows tagged with the id it announced fill it;
 * - a context played on several days is one row, not one per day: the "Classical Bangers" group
 *   of 1 play today and of 8 plays yesterday were a single "9 titres lus" row under the newest
 *   day. Merging on the context uri is what reproduces that, so the same playlist never appears
 *   twice on the page.
 *
 * A play row whose group no context announced (a page that starts mid-group) and any row without
 * group keys stay their own row: nothing the server sent is dropped.
 */
fun groupRecents(entries: List<RecentEntry>): List<RecentSection> {
    val rows = mutableListOf<RecentGroup>()
    var openContext: RecentEntry? = null
    var openGroup: String? = null
    var openPlays = mutableListOf<RecentItem>()

    fun closeOpen() {
        val context = openContext ?: return
        // The server's count wins when it is larger: the last group of a page is often cut by the
        // page size, and its own count still names the plays it really holds.
        val count = maxOf(context.groupCount ?: 0, openPlays.size)
        rows += RecentGroup(
            context = context.item,
            count = count.coerceAtLeast(1),
            saved = context.saved,
            music = context.music,
            plays = openPlays.toList(),
            dayMs = context.item.playedAtMs,
        )
        openContext = null
        openGroup = null
        openPlays = mutableListOf()
    }

    for (entry in entries) {
        val children = entry.childrenGroup
        if (children != null) {
            closeOpen()
            openContext = entry
            openGroup = children
            continue
        }
        val group = entry.group
        if (group != null && group == openGroup) {
            openPlays += entry.item
            continue
        }
        // No group key at all, or a key no open context announced (a page that starts mid-group, a
        // play listed away from its context): the row is its own, never dropped. A keyed stray
        // leaves the open group as it is, so the plays that follow still join it.
        if (group == null) closeOpen()
        rows += RecentGroup(
            context = entry.item,
            count = 1,
            saved = entry.saved,
            music = entry.music,
            plays = listOf(entry.item),
            dayMs = entry.item.playedAtMs,
        )
    }
    closeOpen()

    val merged = LinkedHashMap<String, RecentGroup>()
    for (row in rows) {
        val seen = merged[row.context.uri]
        merged[row.context.uri] = if (seen == null) row else seen.copy(
            count = seen.count + row.count,
            // A track played twice keeps one entry in the expansion: the row is a context, not a
            // play log, and the same uri twice under one row reads as a duplicate.
            plays = seen.plays + row.plays.filter { play -> seen.plays.none { it.uri == play.uri } },
        )
    }
    // groupBy keeps the insertion order, which is the list's own (newest first).
    return merged.values.groupBy { it.dayMs }.map { (day, groups) -> RecentSection(day, groups) }
}
