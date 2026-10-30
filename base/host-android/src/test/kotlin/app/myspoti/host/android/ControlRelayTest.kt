package app.myspoti.host.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlRelayTest {

    @Test fun `playback commands and states leave from the app`() {
        assertTrue(ControlRelay.isRelayed("https://gew4-spclient.spotify.com/connect-state/v1/player/command/from/a/to/b"))
        assertTrue(ControlRelay.isRelayed("https://gew4-spclient.spotify.com/track-playback/v1/devices/a/state"))
    }

    // The play reports count the listen: relayed (the page sends them only once its device is registered).
    @Test fun `play reports leave from the app`() {
        assertTrue(ControlRelay.isRelayed("https://gew4-spclient.spotify.com/melody/v1/msg/batch"))
    }

    // Analytics, the server clock and the Widevine licence address stay with the WebView, as in 2.0.1.
    @Test fun `the rest of the telemetry stays with the WebView`() {
        assertFalse(ControlRelay.isRelayed("https://gew4-spclient.spotify.com/melody/v1/time"))
        assertFalse(ControlRelay.isRelayed("https://gew4-spclient.spotify.com/melody/v1/license_url?keySystem=com.widevine.alpha"))
        assertFalse(ControlRelay.isRelayed("https://gew4-spclient.spotify.com/gabo-receiver-service/v3/events"))
        assertFalse(ControlRelay.isRelayed("https://gew4-spclient.spotify.com/gabo-receiver-service/public/v3/events"))
    }

    @Test fun `sign-in, audio and pages stay with the WebView`() {
        assertFalse(ControlRelay.isRelayed("https://accounts.spotify.com/melody/v1/x"))
        assertFalse(ControlRelay.isRelayed("https://audio-fa.scdn.co/audio/abc"))
        assertFalse(ControlRelay.isRelayed("https://gew4-spclient.spotify.com/widevine-license/v1/audio/license"))
    }

    // 402 diagnostics: what a refused session's report carries, never an id, a token or a name.
    @Test fun `the registration answer keeps its plain fields and hides ids`() {
        val s = ControlRelay.registrationSummary(
            """{"initial_seq_num":12345,"effective_license":"tft","supports_observing":true,"device_keep_alive_update_seconds":21600,"file_format_filter":["mp3"],"registration_token":"secret"}""",
        )
        assertTrue(s, s.contains("effective_license=tft"))
        assertTrue(s, s.contains("supports_observing=true"))
        assertTrue(s, s.contains("file_format_filter=[mp3]"))
        assertTrue(s, s.contains("registration_token") && !s.contains("secret"))
        assertFalse(s, s.contains("12345"))
    }

    @Test fun `the cluster names this player and the active one without ids`() {
        val self = "a".repeat(40)
        val other = "b".repeat(40)
        val s = ControlRelay.clusterSummary(
            """{"active_device_id":"$other","devices":{
              "$self":{"device_type":"COMPUTER","license":"tft","can_play":true,"name":"Pixel","capabilities":{"is_controllable":true,"can_be_player":true}},
              "$other":{"device_type":"SMARTPHONE","license":"mft","can_play":true,"name":"Alex","public_ip":"1.2.3.4","device_software_version":"Spotify 9.0.1"}},
              "player_state":{"restrictions":{"disallow_resuming_reasons":["not_paused"],"disallow_seeking_reasons":[]}}}""",
            self,
        )!!
        assertTrue(s, s.startsWith("devices=2 active=other self_listed=true player_state=present(context=none track=no restrictions=some) " +
            "[self COMPUTER license=tft can_play=true controllable=true player=true; foreign active SMARTPHONE license=mft"))
        assertTrue(s, s.contains("Spotify 9.0.1"))
        assertTrue(s, s.endsWith("restrictions: resuming=not_paused"))
        assertFalse(s, s.contains(self) || s.contains(other) || s.contains("Alex") || s.contains("Pixel") || s.contains("1.2.3.4"))
    }

    // The failing accounts' clusters held no player state; a second device is told apart as the
    // one this app's previous start left, another Web Player, or something else.
    @Test fun `the cluster says whether it has a player state and what the other devices are`() {
        val self = "a".repeat(40)
        val ghost = "c".repeat(40)
        val older = "d".repeat(40)
        val phone = "e".repeat(40)
        val s = ControlRelay.clusterSummary(
            """{"devices":{
              "$self":{"device_type":"COMPUTER","license":"tft","device_software_version":"harmony:4.81.1"},
              "$ghost":{"device_type":"COMPUTER","license":"tft","device_software_version":"harmony:4.81.1"},
              "$older":{"device_type":"COMPUTER","license":"tft","device_software_version":"harmony:4.81.1"},
              "$phone":{"device_type":"SMARTPHONE","license":"tft","device_software_version":"Spotify 9.0.1"}}}""",
            self,
            ghost,
        )!!
        assertTrue(s, s.startsWith("devices=4 active=none self_listed=true player_state=absent [self COMPUTER"))
        assertTrue(s, s.contains("; stale-own COMPUTER") && s.contains("; foreign-webplayer COMPUTER") && s.contains("; foreign SMARTPHONE"))
        assertFalse(s, listOf(self, ghost, older, phone).any { s.contains(it) })
        val playing = ControlRelay.clusterSummary(
            """{"devices":{"$self":{}},"player_state":{"context_uri":"spotify:playlist:secret","track":{"uri":"spotify:track:secret"}}}""",
            self,
        )!!
        assertTrue(playing, playing.contains("player_state=present(context=playlist track=yes restrictions=none)"))
        assertFalse(playing, playing.contains("secret"))
    }

    @Test fun `the 402 summary's cluster counts types and kinds without ids`() {
        val self = "a".repeat(40)
        val ghost = "c".repeat(40)
        val phone = "e".repeat(40)
        val b = ControlRelay.clusterBrief(
            """{"devices":{
              "$self":{"device_type":"COMPUTER","device_software_version":"harmony:4.81.1"},
              "$ghost":{"device_type":"COMPUTER","device_software_version":"harmony:4.81.1"},
              "$phone":{"device_type":"SMARTPHONE","device_software_version":"Spotify 9.0.1"}},
              "player_state":{"context_uri":"spotify:album:secret","restrictions":{"disallow_pausing_reasons":["already_paused"]}}}""",
            self,
            ghost,
        )!!
        assertEquals("devices=3 types=COMPUTER×2,SMARTPHONE×1 kinds=foreign×1,self×1,stale-own×1 active=none " +
            "player_state=present(context=album track=no restrictions=some)", b)
        assertNull(ControlRelay.clusterBrief("""{"ack_id":"x"}""", null, null))
    }

    @Test fun `a command's kind is its endpoint or transfer`() {
        assertEquals("transfer", ControlRelay.commandKind("https://x.spotify.com/connect-state/v1/connect/transfer/from/a/to/b", "{}"))
        assertEquals("seek_to", ControlRelay.commandKind("https://x.spotify.com/connect-state/v1/player/command/from/a/to/b", """{"command":{"endpoint":"seek_to"}}"""))
        assertEquals("command", ControlRelay.commandKind("https://x.spotify.com/connect-state/v1/player/command/from/a/to/b", "not json"))
    }

    // Request parity (402 on some free accounts): a relayed command carries the header set desktop
    // Chrome's carries, with Chrome's values for what the page cannot set, and no cookie.
    @Test fun `a relayed command carries the headers of Chrome's own`() {
        val chrome = javaClass.getResource("/chrome-command-headers.txt")!!.readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        // What the page's fetch hands the relay for a command (the "command headers" log line).
        val given = org.json.JSONObject(
            """{"authorization":"Bearer t","client-token":"c","app-platform":"WebPlayer","spotify-app-version":"1.2.3","content-type":"application/json","Cookie":"sp_dc=x","X-Requested-With":"app.myspoti"}""",
        )
        val page = org.json.JSONObject(
            """{"userAgent":"Mozilla/5.0 (X11; Linux x86_64) Chrome/155.0.0.0","brands":"\"Chromium\";v=\"155\"","mobile":"?0","platform":"Linux","acceptLanguage":"fr-CH,fr;q=0.9"}""",
        )
        val h = ControlRelay.relayHeaders(given, page, "fallback", "en", "fallback")
        // OkHttp adds Content-Length for the body, the decoding interceptor Accept-Encoding.
        val sent = (h.names().map { it.lowercase() } + "content-length" + "accept-encoding").toSortedSet()
        assertEquals(chrome.getValue("names").split(',').toSortedSet(), sent)
        assertNull(h["Cookie"])
        assertNull(h["X-Requested-With"])
        listOf("accept", "priority", "origin", "referer", "sec-fetch-site", "sec-fetch-mode", "sec-fetch-dest", "sec-ch-ua-mobile", "sec-ch-ua-platform")
            .forEach { name -> assertEquals(name, chrome.getValue(name), h[name]) }
        assertEquals(chrome.getValue("accept-encoding"), ControlRelay.ACCEPT_ENCODING)
        assertEquals(ControlRelay.ACCEPT_ENCODING, ControlRelay.DECODERS.joinToString(", ") { it.encoding })
        assertEquals("fr-CH,fr;q=0.9", h["Accept-Language"])
    }

    @Test fun `the page's own Accept is kept`() {
        val h = ControlRelay.relayHeaders(org.json.JSONObject("""{"Accept":"application/json"}"""), null, "ua", "en", "b")
        assertEquals("application/json", h["accept"])
        assertEquals("ua", h["User-Agent"])
    }

    @Test fun `a body that is not a cluster says nothing`() {
        assertNull(ControlRelay.clusterSummary("""{"ack_id":"x"}""", null))
        assertNull(ControlRelay.clusterSummary("not json", null))
    }

    @Test fun `header names only, sorted`() {
        val h = org.json.JSONObject("""{"Authorization":"Bearer x","client-token":"y","Content-Type":"application/json"}""")
        assertEquals("authorization,client-token,content-type", ControlRelay.headerNames(h))
    }
}
