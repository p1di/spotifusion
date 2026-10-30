package com.music.spotui.video

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

internal data class ClipVerification<T>(val value: T? = null, val failed: Boolean = false)
internal data class ClipResolution<T>(val value: T?, val failed: Boolean)

/** Rank is preserved; at most two classifications and one audio candidate at a time. */
internal suspend fun <C, T> firstVerifiedClip(
    candidates: List<C>,
    /** The YouTube Music types worth a check: official music videos only, unless told otherwise. */
    accepts: (String) -> Boolean = { it == "MUSIC_VIDEO_TYPE_OMV" },
    classify: suspend (C) -> String?,
    verify: suspend (C) -> ClipVerification<T>,
): ClipResolution<T> = coroutineScope {
    var failed = false
    var verified = 0
    for (batch in candidates.chunked(2)) {
        val types = batch.map { candidate -> async { classify(candidate) } }
        for ((index, candidate) in batch.withIndex()) {
            val type = types[index].await()
            if (type == null) { failed = true; continue }
            if (!accepts(type)) continue
            verified++
            val result = verify(candidate)
            failed = failed || result.failed
            if (result.value != null) {
                types.forEach { it.cancel() }
                return@coroutineScope ClipResolution(result.value, failed)
            }
            if (verified == 3) {
                types.forEach { it.cancel() }
                return@coroutineScope ClipResolution(null, failed)
            }
        }
    }
    ClipResolution(null, failed)
}
