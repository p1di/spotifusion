package com.music.spotui.ui.components

/**
 * Whether a track's + shows as saved (the green check), as in the official app: in Liked Songs,
 * or one of the rows of an own playlist that the player is playing from.
 *
 * The playlist rule describes the song the player is on: the context belongs to that song alone.
 * Read for any other button (a row of a search result, say) it checked every one of them while an
 * own playlist played. A song Smart Shuffle mixed in is not a row of the playlist, and a queued
 * song plays with the list still as its context ([fromList] false).
 */
internal fun savedSomewhere(
    liked: Boolean,
    isRecommendation: Boolean,
    isPlayingTrack: Boolean,
    playingFromOwnList: Boolean,
    fromList: Boolean,
): Boolean = liked || (!isRecommendation && isPlayingTrack && playingFromOwnList && fromList)
