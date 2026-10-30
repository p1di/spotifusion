package com.music.spotui.data.offline

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class OfflineIndexTest {
    private fun entry(id: String) = JSONObject().put("id", id).put("file", "$id.m4a").put("cover", "$id.jpg")

    private fun folder(index: String?, vararg ids: String): File {
        val root = Files.createTempDirectory("offline").toFile()
        File(root, "audio").mkdirs(); File(root, "covers").mkdirs()
        ids.forEach { File(root, "audio/$it.m4a").writeText("x"); File(root, "covers/$it.jpg").writeText("x") }
        index?.let { File(root, "index.json").writeText(it) }
        return root
    }

    @Test fun theProvidingExtensionIsKeptAndOlderEntriesHaveNone() {
        val old = OfflineTrack.fromJson(entry("a"))
        assertEquals(null, old.providerId)
        val kept = OfflineTrack.fromJson(old.copy(providerId = "io.github.x.dl").toJson())
        assertEquals("io.github.x.dl", kept.providerId)
        assertFalse(old.toJson().has("providerId"))
    }

    @Test fun truncatedIndexKeepsEveryFile() {
        val root = folder("[{\"id\":\"a\",\"file\":\"a.m4a\"", "a", "b")
        OfflineStore.load(root)
        assertEquals(2, File(root, "audio").list()!!.size)
        assertEquals(2, File(root, "covers").list()!!.size)
        assertTrue(root.list()!!.any { it.startsWith("index.corrupt-") })
    }

    @Test fun unreadableEntryKeepsEveryFile() {
        val root = folder(JSONArray().put(entry("a")).put(JSONObject().put("title", "no id")).toString(), "a", "b")
        OfflineStore.load(root)
        assertEquals(setOf("a"), OfflineStore.tracks.keys.toSet())
        assertEquals(2, File(root, "audio").list()!!.size)
    }

    @Test fun backupIsUsedWhenTheIndexIsDamaged() {
        val root = folder("{oops", "a", "b")
        File(root, "index.json.bak").writeText(JSONArray().put(entry("a")).put(entry("b")).toString())
        OfflineStore.load(root)
        assertEquals(setOf("a", "b"), OfflineStore.tracks.keys.toSet())
        assertEquals(2, File(root, "audio").list()!!.size)
    }

    @Test fun validIndexStillRemovesLeftovers() {
        val root = folder(JSONArray().put(entry("a")).toString(), "a", "b")
        OfflineStore.load(root)
        assertEquals(setOf("a"), OfflineStore.tracks.keys.toSet())
        assertFalse(File(root, "audio/b.m4a").exists())
        assertTrue(File(root, "audio/a.m4a").exists())
    }
}
