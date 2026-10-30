package com.music.spotui.di

/**
 * What the local player does when a song an extension streams fails: asked once more (the answer
 * kept may be stale), then moved past, but never through more than [MAX_SKIPS] songs in a row: a
 * source that is down would otherwise chain through the whole queue, silently.
 */
class StreamErrors(private val maxSkips: Int = MAX_SKIPS) {
    enum class Step { RETRY, SKIP, STOP }

    private val retried = HashSet<String>()
    private var skips = 0

    /** True the first time a stream failure of [uri] is told: it is then asked again; false when it already was. */
    fun alreadyRetried(uri: String): Boolean = !retried.add(uri)

    /** A song failed: [uri] is its address, [hasNext] whether the queue goes on after it. */
    fun failed(uri: String, hasNext: Boolean): Step = when {
        retried.add(uri) -> Step.RETRY
        hasNext && skips < maxSkips -> { skips++; Step.SKIP }
        else -> Step.STOP
    }

    /** A song played: the failures before it are forgotten. */
    fun played() { skips = 0; retried.clear() }

    companion object { const val MAX_SKIPS = 2 }
}
