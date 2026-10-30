package app.myspoti.spotify

import app.myspoti.spotify.bridge.AdBlock
import app.myspoti.spotify.bridge.Bridge
import app.myspoti.spotify.bridge.BridgeHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A pick made while an ad is on or due must reach the page after the ad, in its order (reported
 * on build 2022: the old queue went on and the song had to be picked again).
 */
class PickDuringAdTest {
    private class FakeHost : BridgeHost {
        override val messages = MutableSharedFlow<String>(extraBufferCapacity = 64)
        /** Every call the player made: method to its arguments (json text). */
        val calls = CopyOnWriteArrayList<Pair<String, String>>()
        override suspend fun evaluate(script: String) {
            val m = Regex("""Myspoti\.call\((\d+), "([^"]+)", (.*)\)\.catch""", RegexOption.DOT_MATCHES_ALL).find(script) ?: return
            calls += m.groupValues[2] to m.groupValues[3]
            messages.emit("""{"type":"result","id":${m.groupValues[1]},"ok":true,"value":null}""")
        }
        suspend fun post(json: String) = messages.emit(json)
    }

    private lateinit var scope: CoroutineScope
    private lateinit var host: FakeHost
    private lateinit var player: SpotifyPlayer

    @BeforeEach fun up() {
        AdBlock.ahead.value = null
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        host = FakeHost()
        player = SpotifyPlayer(Bridge(host, scope), scope)
        // A message posted before the bridge listens is lost (a slow CI machine): wait for it.
        waitFor("bridge listening") { host.messages.subscriptionCount.value > 0 }
    }

    @AfterEach fun down() {
        AdBlock.ahead.value = null
        scope.cancel()
    }

    private fun track(name: String, ms: Long = 200_000) =
        """{"kind":"track","uri":"spotify:track:$name","uid":"u$name","name":"$name","durationMs":$ms}"""

    private fun state(item: String, next: String? = null, ctx: String = "spotify:playlist:C") =
        """{"type":"state","state":{"track":$item,"contextUri":"$ctx","next":${next ?: "null"},"isPaused":false,"isBuffering":false,
            |"positionMs":0,"timestamp":${System.currentTimeMillis()},"restrictions":{}}}""".trimMargin()

    private val ad = """{"kind":"ad","uri":"spotify:ad:1","name":"ad","durationMs":30000}"""

    private fun waitFor(what: String, ms: Long = 3_000, cond: () -> Boolean) = runBlocking {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out: $what" }
            delay(20)
        }
    }

    private fun plays() = host.calls.filter { it.first == "player.play" }.map { it.second }

    @Test fun `a pick during an ad is held and played in its order once the ad is over`() = runBlocking {
        host.post("""{"type":"ready"}""")
        host.post(state(track("A"), track("B")))
        host.post(state(ad))
        waitFor("ad flag") { player.adPlaying.value }
        player.play("spotify:playlist:C", trackUri = "spotify:track:P", order = listOf("spotify:track:P", "spotify:track:Q"))
        delay(300)
        assertTrue(plays().isEmpty(), "a play during an ad is refused by the page: nothing sent, got ${plays()}")
        host.post(state(track("B"), track("C2")))
        waitFor("the held pick played") { plays().isNotEmpty() }
        val sent = plays().single()
        assertTrue("spotify:track:P" in sent && "spotify:track:Q" in sent, "pick and its order sent: $sent")
    }

    @Test fun `a pick on a song the page already plays after the ad is not played twice`() = runBlocking {
        host.post("""{"type":"ready"}""")
        host.post(state(track("A"), track("B")))
        host.post(state(ad))
        waitFor("ad flag") { player.adPlaying.value }
        player.play("spotify:playlist:C", trackUri = "spotify:track:P")
        host.post(state(track("P"), track("Q")))
        delay(400)
        assertTrue(plays().isEmpty(), "P plays by itself: ${plays()}")
    }

    @Test fun `a pick outside an ad is sent at once`() = runBlocking {
        host.post("""{"type":"ready"}""")
        host.post(state(track("A"), track("B")))
        waitFor("state") { player.state.value != null }
        player.play("spotify:playlist:C", trackUri = "spotify:track:P")
        waitFor("play sent") { plays().isNotEmpty() }
        assertEquals(1, plays().size)
    }

    @Test fun `a pick while the ad-avoiding play is waiting for the song's end is not overridden`() = runBlocking {
        host.post("""{"type":"ready"}""")
        host.post(state(track("A", ms = 1_500), track("B")))
        waitFor("state") { player.state.value != null }
        AdBlock.ahead.value = AdBlock.AdAhead("uA", "spotify:track:A", "spotify:track:B")
        delay(150)
        player.play("spotify:playlist:C", trackUri = "spotify:track:P")
        // The page has not reported P yet when A's last second comes.
        delay(1_800)
        assertEquals(1, plays().size, "only the pick is played: ${host.calls.map { it.first }}")
        assertTrue("spotify:track:P" in plays().single())
        assertTrue(host.calls.none { it.first == "queue.skipTo" }, "no skip to the old queue's next song")
    }

    @Test fun `the fallback after a refused pick keeps the order it was picked in`() = runBlocking {
        host.post("""{"type":"ready"}""")
        host.post(state(track("A"), track("B")))
        waitFor("state") { player.state.value != null }
        player.play("spotify:playlist:C", trackUri = "spotify:track:P", order = listOf("spotify:track:P", "spotify:track:Q"))
        host.post("""{"type":"refused"}""")
        waitFor("fallback play") { plays().size == 2 }
        val fallback = plays()[1]
        assertTrue("spotify:track:Q" in fallback && "trackUri" !in fallback, "the list from its start, same order: $fallback")
    }
}
