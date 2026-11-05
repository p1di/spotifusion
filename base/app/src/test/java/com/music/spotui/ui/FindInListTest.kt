package com.music.spotui.ui

import com.music.spotui.ui.components.findWords
import com.music.spotui.ui.components.matchesFind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FindInListTest {
    @Test fun blankMatchesEverything() = assertTrue(matchesFind("  ", "Halo", "Beyoncé"))
    @Test fun ignoresCaseAndAccents() = assertTrue(matchesFind("BEYONCE", "Halo", "Beyoncé"))
    @Test fun everyWordInAnyField() = assertTrue(matchesFind("beyonce hal", "Halo", "Beyoncé", "I Am... Sasha Fierce"))
    @Test fun missingWordFails() = assertFalse(matchesFind("beyonce crazy", "Halo", "Beyoncé"))
    @Test fun matchesPartOfAWord() = assertTrue(matchesFind("sava", "J'SAVAIS", "Ikaz Boi"))
    @Test fun wordsAreFoldedAndSplit() = assertEquals(listOf("beyonce", "hal"), findWords("  BeyoncÉ   hal "))
    @Test fun blankQueryHasNoWords() = assertTrue(findWords(" \t ").isEmpty())
    @Test fun asciiAndAccentedFoldAlike() {
        assertTrue(matchesFind("cafe", "Café del Mar"))
        assertTrue(matchesFind("café", "CAFE DEL MAR"))
        assertFalse(matchesFind("cafes", "Café del Mar"))
    }
}
