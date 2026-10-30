package app.myspoti.host.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class PlayerDeviceRecordTest {
    private val base = "https://gew4-spclient.spotify.com/track-playback/v1/devices"

    private fun file() = File.createTempFile("player-device", ".json").apply { delete(); deleteOnExit() }

    @Test fun `the first page has nothing to clean`() {
        assertNull(PlayerDeviceRecord(file()).registering("a"))
    }

    // A page killed before its unload handler: the next start says its goodbye, with the next sequence number.
    @Test fun `the device a killed page left is named at the next start`() {
        val f = file()
        PlayerDeviceRecord(f).apply {
            registering("a")
            answered(1)
            sent("$base/a/state", """{"seq_num":4,"state_ref":null}""")
            sent("$base/a/state_conflict", """{"seq_nums":[5,6]}""")
            sent("$base/b/state", """{"seq_num":90}""")
        }
        val next = PlayerDeviceRecord(f)
        assertEquals("a" to 7L, next.registering("b"))
        assertNull(PlayerDeviceRecord(f).registering("b"))
    }

    @Test fun `a page that said goodbye itself leaves nothing`() {
        val f = file()
        PlayerDeviceRecord(f).apply { registering("a"); deregistered("$base/a") }
        assertNull(PlayerDeviceRecord(f).registering("b"))
    }

    @Test fun `the goodbye is the page's deregistration without a state`() {
        val j = JSONObject(PlayerDeviceRecord.goodbye(3))
        assertEquals(3, j.getInt("seq_num"))
        assertEquals(JSONObject.NULL, j.get("state_ref"))
        assertEquals("deregister", j.getString("debug_source"))
        assertEquals("$base/x", PlayerDeviceRecord.deviceUrl("$base?product=0", "x"))
    }
}
