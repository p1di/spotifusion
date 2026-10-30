package com.music.spotui.video

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class OrderedClipResolverTest {
    @Test fun firstMatchCancelsSlowSiblingAndNeverDownloadsOtherCandidates() = runBlocking {
        val requests = mutableListOf<Int>(); val verified = mutableListOf<Int>(); var siblingCancelled = false
        val result = withTimeout(1000) {
            firstVerifiedClip((0..5).toList(), classify = {
                requests += it
                try { delay(if (it == 0) 10L else 10_000L); "MUSIC_VIDEO_TYPE_OMV" }
                catch (e: CancellationException) { siblingCancelled = true; throw e }
            }, verify = { verified += it; ClipVerification("clip-$it") })
        }
        assertEquals("clip-0", result.value)
        assertEquals(listOf(0, 1), requests)
        assertEquals(listOf(0), verified)
        assertTrue(siblingCancelled)
    }
    @Test fun audioMismatchFallsBackInRankOrder() = runBlocking {
        val tried = mutableListOf<Int>()
        val result = firstVerifiedClip(listOf(1, 2, 3), classify = { "MUSIC_VIDEO_TYPE_OMV" }) {
            tried += it; ClipVerification(if (it == 2) "matched" else null)
        }
        assertEquals("matched", result.value); assertEquals(listOf(1, 2), tried)
    }
    @Test fun identityWorkIsBoundedWhenEveryOfficialCandidateMismatches() = runBlocking {
        var downloaded = 0
        val result = firstVerifiedClip((0..9).toList(), classify = { "MUSIC_VIDEO_TYPE_OMV" }) {
            downloaded++; ClipVerification<String>()
        }
        assertNull(result.value); assertEquals(3, downloaded)
    }
    @Test fun classificationNeverAcceptsUnofficialUploads() = runBlocking {
        var downloaded = 0
        val result = firstVerifiedClip(listOf(0, 1), classify = { "MUSIC_VIDEO_TYPE_UGC" }) {
            downloaded++; ClipVerification("bad")
        }
        assertNull(result.value); assertFalse(result.failed); assertEquals(0, downloaded)
    }
    @Test fun unknownClassificationMustNotBeCachedAsUnavailable() = runBlocking {
        val result = firstVerifiedClip(listOf(0, 1), classify = { if (it == 0) null else "MUSIC_VIDEO_TYPE_ATV" }) { ClipVerification("unused") }
        assertNull(result.value); assertTrue(result.failed)
    }
    @Test fun failedDownloadAllowsAnotherOfficialCandidate() = runBlocking {
        val result = firstVerifiedClip(listOf(0, 1), classify = { "MUSIC_VIDEO_TYPE_OMV" }) {
            if (it == 0) ClipVerification(failed = true) else ClipVerification("next")
        }
        assertEquals("next", result.value)
    }
}
