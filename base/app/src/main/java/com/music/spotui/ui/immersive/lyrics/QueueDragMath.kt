package com.music.spotui.ui.immersive.lyrics

/**
 * The index arithmetic of dragging a queue row, kept free of Compose so it can be tested.
 * Rows are one flat list (the queued rows, then the context's); "others" is that list without
 * the dragged row, and a slot is a gap in it: 0 before the first, others.size after the last.
 */
internal object QueueDragMath {
    /** The gap the dragged row's centre is in: how many of the other rows' midpoints lie above it. */
    fun slot(center: Float, otherMidpoints: List<Float>): Int = otherMidpoints.count { it < center }

    /**
     * How far the row at flat [index] steps aside (-1 up, 1 down, 0 stays) while the row at
     * [from] hovers over gap [slot] of the others.
     */
    fun step(index: Int, from: Int, slot: Int): Int = when {
        index == from -> 0
        index > from && index - 1 < slot -> -1
        index < from && index >= slot -> 1
        else -> 0
    }

    /** Where a gap lands: in the queued rows ([queueSize] others) or the context's, and at which index there. */
    data class Place(val toQueue: Boolean, val index: Int)

    /**
     * Gap [slot] as a place. The gap between the two sections belongs to the queue when the row's
     * centre is above the context heading's midpoint ([aboveBoundary]), else to the context.
     */
    fun place(slot: Int, queueSize: Int, aboveBoundary: Boolean): Place = when {
        slot < queueSize -> Place(true, slot)
        slot > queueSize -> Place(false, slot - queueSize)
        aboveBoundary -> Place(true, queueSize)
        else -> Place(false, 0)
    }

    /** [list] with the element at [from] taken out and put at [to] of the rest. */
    fun <T> moved(list: List<T>, from: Int, to: Int): List<T> =
        list.toMutableList().apply { add(to, removeAt(from)) }

    /** Pixels to scroll this frame for a finger at [y] in a viewport of [height]: 0 outside the edge zones, growing towards the edge. */
    fun edgeStep(y: Float, height: Float, zone: Float, maxStep: Float): Float {
        val up = ((zone - y) / zone).coerceIn(0f, 1f)
        val down = ((y - (height - zone)) / zone).coerceIn(0f, 1f)
        return (down - up) * maxStep
    }

    /** Which of the section's other rows an engine call names for the drop at [index]: its position and whether to go after it; null when the section has no other row. */
    fun target(size: Int, index: Int): Pair<Int, Boolean>? = when {
        size == 0 -> null
        index < size -> index to false
        else -> size - 1 to true
    }

    /** The two sections after [dragged] (taken out of both) is dropped at [place]. */
    fun <T> apply(queued: List<T>, next: List<T>, dragged: T, place: Place): Pair<List<T>, List<T>> {
        val q = queued.filter { it != dragged }
        val n = next.filter { it != dragged }
        fun <E> List<E>.with(i: Int, e: E) = toMutableList().apply { add(i.coerceIn(0, size), e) }
        return if (place.toQueue) q.with(place.index, dragged) to n else q to n.with(place.index, dragged)
    }

    /**
     * Songs of the Spotify queue move between its two parts only one way: out of "next from" into
     * the queue (a song added to it). A queued song cannot go back, so its drop is held in the queue.
     */
    fun held(place: Place, queueSize: Int, canLeaveQueue: Boolean, fromQueue: Boolean): Place =
        if (fromQueue && !canLeaveQueue && !place.toQueue) Place(true, queueSize) else place

    /** What the engine's latest report means for a [Pending] order. */
    sealed interface Verdict {
        /** Nothing to do: the pending order stays (the engine is still before / half-way through the move, or settling). */
        data object Keep : Verdict
        /** The engine now shows the expected order. */
        data object Matched : Verdict
        /** The pending order is over; the engine's is trusted from now. */
        data class Drop(val why: String) : Verdict
    }

    /**
     * The order just dropped, shown until the engine can be trusted again. [queued] / [next] are the
     * rows, the uris the same songs in order: [preQueued] / [preNext] before the move, [expQueued] /
     * [expNext] after it. [dragged] is the moved song's uri, [current] what played then.
     *
     * The engine reports the move late and in steps (Spotify: the song out of one part, then
     * queued at the end, then placed), and may repeat or reorder reports. So the order is kept:
     * through reports of the queue as it was or half-way through this move (however long the
     * round-trip takes, up to [MAX_MS]), through [GRACE_MS] after the expected order is reported
     * (a late report of an older state is ignored), and ended by a report that is none of those,
     * by another song playing, or by those limits. A drop made before the last one was reported
     * chains to it: its reports are expected on the way.
     */
    class Pending<T>(
        val queued: List<T>, val next: List<T>,
        private val preQueued: List<String>, private val preNext: List<String>,
        private val expQueued: List<String>, private val expNext: List<String>,
        private val dragged: String, private val current: String?, private val setAt: Long,
        private val previous: Pending<T>? = null,
    ) {
        var matchedAt: Long? = null
            private set

        private fun isExpected(q: List<String>, n: List<String>) = q == expQueued && n == expNext
        private fun isBefore(q: List<String>, n: List<String>) = q == preQueued && n == preNext

        /** Half-way: the song is out of both parts, or queued at the end only; the rest as it ends up. */
        private fun isHalfWay(q: List<String>, n: List<String>): Boolean {
            if (q.filter { it != dragged } != expQueued.filter { it != dragged } ||
                n.filter { it != dragged } != expNext.filter { it != dragged }) return false
            val count = q.count { it == dragged } + n.count { it == dragged }
            return count == 0 || (count == 1 && n.none { it == dragged } && q.lastOrNull() == dragged)
        }

        /** A report of the queue before this move or part-way through it (or through the drops it follows). */
        private fun passing(q: List<String>, n: List<String>): Boolean =
            isBefore(q, n) || isHalfWay(q, n) ||
                previous?.let { it.isExpected(q, n) || it.passing(q, n) } == true

        fun report(q: List<String>, n: List<String>, reportedCurrent: String?, now: Long): Verdict {
            if (reportedCurrent != current) return Verdict.Drop("another song plays")
            expired(now)?.let { return Verdict.Drop(it) }
            val matched = matchedAt
            return when {
                isExpected(q, n) -> if (matched == null) { matchedAt = now; Verdict.Matched } else Verdict.Keep
                passing(q, n) -> Verdict.Keep
                else -> Verdict.Drop(if (matched == null) "engine shows another order" else "queue changed after the move")
            }
        }

        /** Why the order is over by time alone, else null. */
        fun expired(now: Long): String? = when {
            matchedAt?.let { now - it >= GRACE_MS } == true -> "settled"
            now - setAt >= MAX_MS -> "no final report in ${MAX_MS / 1000}s"
            else -> null
        }

        /** Chains are not needed once this one is reported: [previous] is let go. */
        fun detach(): Pending<T> = Pending(queued, next, preQueued, preNext, expQueued, expNext, dragged, current, setAt, null)
    }

    /** After the expected order is reported, reports of older states are ignored this long. */
    const val GRACE_MS = 1_500L
    /** The longest the engine is waited for. */
    const val MAX_MS = 10_000L
}
