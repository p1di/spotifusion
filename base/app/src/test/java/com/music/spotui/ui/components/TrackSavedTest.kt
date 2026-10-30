package com.music.spotui.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackSavedTest {
    @Test fun likedIsAlwaysSaved() = assertTrue(savedSomewhere(true, true, false, false, false))

    @Test fun theSongPlayingFromAnOwnListIsSaved() = assertTrue(savedSomewhere(false, false, true, true, true))

    // The search rows: another song's + was checked because the player's list was an own playlist.
    @Test fun anotherSongIsNotSavedByTheListThatPlays() = assertFalse(savedSomewhere(false, false, false, true, true))

    @Test fun aQueuedSongIsNotARowOfTheList() = assertFalse(savedSomewhere(false, false, true, true, false))

    @Test fun aSmartShuffleSuggestionIsNotARowOfTheList() = assertFalse(savedSomewhere(false, true, true, true, true))

    @Test fun aListThatIsNotOwnedSavesNothing() = assertFalse(savedSomewhere(false, false, true, false, true))
}
