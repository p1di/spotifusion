package com.music.spotui.di

import java.util.Collections
import java.util.IdentityHashMap
import kotlin.random.Random

/**
 * The play orders [LocalPlayer] switches between, as plain list operations (the player itself
 * needs a device). Songs are compared by identity: a list may hold equal songs twice.
 */
internal object QueueOrder {
    /** [list] with the song at [start] first and every other song after it, shuffled. */
    fun <T> shuffledFrom(list: List<T>, start: Int, random: Random = Random.Default): List<T> {
        if (list.isEmpty()) return list
        val first = start.coerceIn(0, list.lastIndex)
        val rest = ArrayList<T>(list.size - 1)
        for (i in list.indices) if (i != first) rest += list[i]
        rest.shuffle(random)
        return ArrayList<T>(list.size).also { it += list[first]; it += rest }
    }

    /**
     * The order shuffle replaced: the songs of [original] still in [current], in their old order,
     * then the songs added since (they are in [current] only).
     */
    fun <T> restored(original: List<T>, current: List<T>): List<T> {
        val inCurrent = identitySet(current)
        val inOriginal = identitySet(original)
        val out = ArrayList<T>(current.size)
        for (t in original) if (t in inCurrent) out += t
        for (t in current) if (t !in inOriginal) out += t
        return out
    }

    private fun <T> identitySet(items: List<T>): MutableSet<T> =
        Collections.newSetFromMap(IdentityHashMap<T, Boolean>()).also { it.addAll(items) }
}
