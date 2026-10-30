package app.myspoti.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Extensions together: additive merges, exclusive choices, the playback arbiter, quotas, conflicts, isolation. */
class ConflictsTest {
    private fun m(id: String, vararg actions: String, conflictsWith: List<String> = emptyList(), replaces: List<String> = emptyList()) =
        Manifest(id, id.substringAfterLast('.'), "1.0", author = "me", actions = actions.map { ActionRequest(it, "Because it is needed here") },
            conflictsWith = conflictsWith, replaces = replaces)

    @Test fun modes() {
        assertEquals(setOf(Action.AUDIO_SOURCE, Action.DOWNLOAD_SOURCE), Action.entries.filter { it.mode == Mode.ORDERED }.toSet())
        assertTrue(Action.entries.none { it.mode == Mode.EXCLUSIVE })
        assertTrue(listOf(Action.LYRICS_PROVIDE, Action.MENU_ITEMS, Action.HOME_SECTIONS, Action.SEARCH_PROVIDE, Action.TRACKINFO_PROVIDE).all { it.mode == Mode.ADDITIVE })
        assertEquals(Mode.SHARED, Action.NETWORK.mode)
    }

    @Test fun sourcesAreAskedInOrderAndNeverClash() {
        val a = m("io.x.a", "audio.source")
        val b = m("io.x.b", "audio.source", "download.source")
        val lyrics = m("io.x.l", "lyrics.provide")
        // An exclusive action (none today): the user's choice while it is there, else the first.
        assertEquals("io.x.a", Conflicts.activeFor(listOf("io.x.a", "io.x.b"), null))
        assertEquals("io.x.b", Conflicts.activeFor(listOf("io.x.a", "io.x.b"), "io.x.b"))
        assertEquals("io.x.a", Conflicts.activeFor(listOf("io.x.a"), "io.x.gone"))
        assertNull(Conflicts.activeFor(emptyList(), "io.x.b"))
        // A second audio source goes after the first in the order: nothing to choose at install.
        assertEquals(emptyList<Clash>(), Conflicts.clashes(b, listOf(a, lyrics), emptyMap()))
        assertEquals(emptyList<Clash>(), Conflicts.clashes(m("io.x.l2", "lyrics.provide"), listOf(lyrics), emptyMap()))
    }

    @Test fun conflictsWithAndReplacesAreAskedEitherWay() {
        val a = m("io.x.a", "notify")
        val b = m("io.x.b", "notify", conflictsWith = listOf("io.x.a"))
        val c = m("io.x.c", "notify", replaces = listOf("io.x.a"))
        assertEquals(listOf(Clash(Clash.Kind.CONFLICT, "io.x.a")), Conflicts.clashes(b, listOf(a), emptyMap()))
        // Declared by the one installed: still a conflict.
        assertEquals(listOf(Clash(Clash.Kind.CONFLICT, "io.x.b")), Conflicts.clashes(a, listOf(b), emptyMap()))
        assertEquals(listOf(Clash(Clash.Kind.REPLACES, "io.x.a")), Conflicts.clashes(c, listOf(a), emptyMap()))
        assertTrue(Conflicts.clashes(b, listOf(b), emptyMap()).isEmpty())
        val bad = m("io.x.d", "notify", conflictsWith = listOf("io.x.d", "Not An Id")).problems().joinToString()
        assertTrue(bad, bad.contains("not its own id") && bad.contains("\"Not An Id\""))
    }

    @Test fun additiveBlocksAreMergedDeduplicatedAndQuotaed() {
        fun menu(vararg labels: String) = labels.map { Block.MenuEntry(it, "add", it.lowercase().replace(' ', '-')) }
        val merged = Conflicts.merge(listOf(
            "a" to menu("Count", "Share lyrics", "Third"),
            "b" to menu("count", "Find sample"),
            "c" to menu("Translate", "Shazam it"),
        ), Surface.TRACK_MENU)
        // a keeps 2 on screen (its quota), b's "count" is a's again, the menu holds 4: the rest is under "Extensions ⋯".
        assertEquals(listOf("a" to "Count", "a" to "Share lyrics", "b" to "Find sample", "c" to "Translate"), merged.shown.map { it.first to (it.second as Block.MenuEntry).label })
        assertEquals(listOf("a" to "Third", "c" to "Shazam it"), merged.overflow.map { it.first to (it.second as Block.MenuEntry).label })
        // Items: the same song from a lower extension is dropped; Home drops what is over its quota.
        val song = Item("spotify:track:1", "One", null, null)
        val home = Conflicts.merge(listOf(
            "a" to listOf(Block.Section("A", null, listOf(Block.Items(ItemKind.TRACKS, null, listOf(song))))),
            "b" to listOf(
                Block.Section("B", null, listOf(Block.Items(ItemKind.TRACKS, null, listOf(song, Item("spotify:track:2", "Two", null, null))))),
                Block.Section("B2", null, listOf(Block.Text("x", TextStyle.BODY))),
                Block.Section("B3", null, listOf(Block.Text("y", TextStyle.BODY))),
            ),
        ), Surface.HOME)
        assertEquals(listOf("A", "B", "B2"), home.shown.map { (it.second as Block.Section).title })
        assertEquals(listOf("Two"), ((home.shown[1].second as Block.Section).blocks.single() as Block.Items).items.map { it.title })
        assertTrue(home.overflow.isEmpty())
        // The player: one button each on screen.
        val player = Conflicts.merge(listOf("a" to listOf(Block.Button("A1", "add", "1"), Block.Button("A2", "add", "2")), "b" to listOf(Block.Button("B1", "add", "1"))), Surface.PLAYER)
        assertEquals(listOf("A1", "B1"), player.shown.map { (it.second as Block.Button).label })
        assertEquals(listOf("A2"), player.overflow.map { (it.second as Block.Button).label })
    }

    @Test fun theArbiterSettlesContradictionsByPriority() {
        var now = 0L
        val rank = mapOf("high" to 0, "low" to 1)
        val arbiter = PlaybackArbiter({ rank.getValue(it) }, { now })
        assertTrue(arbiter.submit("low", "next").allowed)
        // The higher one says otherwise within the window: it wins.
        now += 500
        assertEquals(PlaybackArbiter.Verdict(true, "overrides", winner = "high"), arbiter.submit("high", "previous"))
        // The lower one answers back: dropped.
        now += 500
        assertEquals(PlaybackArbiter.Verdict(false, "overridden", winner = "high"), arbiter.submit("low", "next"))
        // Another group, or after the window: no contest.
        assertTrue(arbiter.submit("low", "pause").allowed)
        now += 5_000
        assertTrue(arbiter.submit("low", "next").allowed)
    }

    @Test fun aLoopSuspendsTheLowerOne() {
        var now = 0L
        val arbiter = PlaybackArbiter({ if (it == "high") 0 else 1 }, { now })
        var suspended: String? = null
        repeat(6) { i ->
            now += 1_000
            val v = arbiter.submit(if (i % 2 == 0) "low" else "high", if (i % 2 == 0) "next" else "previous")
            v.suspend?.let { suspended = it }
        }
        assertEquals("low", suspended)
        assertTrue(arbiter.isSuspended("low"))
        assertEquals("suspended", arbiter.submit("low", "next").reason)
        assertTrue(arbiter.submit("high", "next").allowed)
        arbiter.resume("low")
        now += 60_000
        assertTrue(arbiter.submit("low", "next").allowed)
    }

    @Test fun eachExtensionHasACommandRate() {
        var now = 0L
        val arbiter = PlaybackArbiter({ 0 }, { now }, perMinute = 5)
        repeat(5) { now += 100; assertTrue(arbiter.submit("a", "seek").allowed) }
        assertEquals("rate", arbiter.submit("a", "seek").reason)
        assertTrue(arbiter.submit("b", "pause").allowed)
        now += 61_000
        assertTrue(arbiter.submit("a", "seek").allowed)
    }

    @Test fun theActivityLogIsPerArea() {
        var now = 0L
        val log = ActivityLog(max = 3) { now++ }
        log.add(Area.LYRICS, "a", "Lyrics from A")
        log.add(Area.PLAYBACK, "b", "Skipped by B")
        log.add(Area.LYRICS, "c", "Lyrics from C")
        log.add(Area.LYRICS, "d", "Lyrics from D")
        assertEquals(listOf("d", "c"), log.forArea(Area.LYRICS).map { it.extensionId })
        assertEquals(listOf("Skipped by B"), log.forArea(Area.PLAYBACK).map { it.text })
    }

    @Test fun oneExtensionCannotReachAnothersData() {
        val stores = HashMap<String, MemoryStorage>()
        val host = object : Host {
            override fun log(extensionId: String, message: String) {}
            override fun storage(extensionId: String): Storage = stores.getOrPut(extensionId) { MemoryStorage() }
            override fun setting(extensionId: String, key: String): String? = "\"$extensionId\""
        }
        val a = m("io.x.a", "storage", "lyrics.provide")
        val b = m("io.x.b", "storage", "lyrics.provide")
        val write = "myspoti.storage.set('secret', 42); myspoti.provide('lyrics', function () { return { plain: String(myspoti.storage.get('secret')) }; });"
        val read = "myspoti.provide('lyrics', function () { return { plain: String(myspoti.storage.get('secret')) + ' ' + myspoti.settings.get('x') }; });"
        val track = TrackInfo("t", "spotify:track:t", "T", "A", listOf("A"), "B", 1).toJson()
        ExtensionRuntime(a, write, host, a.asks().map { it.key }.toSet()).use { it.start(); assertEquals("""{"plain":"42"}""", it.call(Hook.LYRICS, track, 3_000)) }
        ExtensionRuntime(b, read, host, b.asks().map { it.key }.toSet()).use { it.start(); assertEquals("""{"plain":"undefined io.x.b"}""", it.call(Hook.LYRICS, track, 3_000)) }
        assertEquals(setOf("io.x.a", "io.x.b"), stores.keys)
        assertFalse(stores.getValue("io.x.b").snapshot().containsKey("secret"))
    }
}
