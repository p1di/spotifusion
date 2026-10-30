package app.myspoti.spotify.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetAddress
import java.net.UnknownHostException

class RelayRoutesTest {
    private val v4 = InetAddress.getByAddress("spclient", byteArrayOf(85.toByte(), 1, 2, 3))
    private val v6 = InetAddress.getByAddress("spclient", ByteArray(16).also { it[0] = 0x2a; it[1] = 0x01 })

    @Test fun `a refusal goes to the platform's Chromium stack first, then the other family`() {
        val routes = RelayRoutes(engineAvailable = true)
        assertEquals(RelayRoute.HTTP_ENGINE, routes.next(listOf(RelayRoute.DEFAULT), IpFamily.V6))
        assertEquals(RelayRoute.IPV4, routes.next(listOf(RelayRoute.DEFAULT, RelayRoute.HTTP_ENGINE), null))
        assertNull(routes.next(listOf(RelayRoute.DEFAULT, RelayRoute.HTTP_ENGINE, RelayRoute.IPV4), IpFamily.V4))
    }

    @Test fun `before Android 14, Cronet is the first retry once it is there`() {
        var cronet: RelayRoute? = null
        val routes = RelayRoutes { cronet }
        assertEquals(RelayRoute.IPV4, routes.next(listOf(RelayRoute.DEFAULT), IpFamily.V6))
        cronet = RelayRoute.CRONET
        assertEquals(RelayRoute.CRONET, routes.next(listOf(RelayRoute.DEFAULT), IpFamily.V6))
        assertEquals(RelayRoute.IPV4, routes.next(listOf(RelayRoute.DEFAULT, RelayRoute.CRONET), null))
        assertTrue(RelayRoute.CRONET.chromium && RelayRoute.HTTP_ENGINE.chromium && !RelayRoute.IPV4.chromium)
    }

    @Test fun `a refusal over IPv4 is tried over IPv6, and nothing else without the platform stack`() {
        val routes = RelayRoutes(engineAvailable = false)
        assertEquals(RelayRoute.IPV6, routes.next(listOf(RelayRoute.DEFAULT), IpFamily.V4))
        assertNull(routes.next(listOf(RelayRoute.DEFAULT, RelayRoute.IPV6), IpFamily.V6))
    }

    @Test fun `the route that won is kept, and its refusal tries the others`() {
        val routes = RelayRoutes(engineAvailable = true)
        assertEquals(RelayRoute.DEFAULT, routes.kept)
        routes.won(RelayRoute.HTTP_ENGINE)
        assertEquals(RelayRoute.HTTP_ENGINE, routes.kept)
        assertEquals(RelayRoute.IPV4, routes.next(listOf(RelayRoute.HTTP_ENGINE), null))
        routes.won(RelayRoute.IPV4)
        assertEquals(RelayRoute.HTTP_ENGINE, routes.next(listOf(RelayRoute.IPV4), null))
        assertEquals(RelayRoute.IPV6, routes.next(listOf(RelayRoute.IPV4, RelayRoute.HTTP_ENGINE), null))
    }

    @Test fun `pinning keeps one family, and a network without it has no address`() {
        assertEquals(listOf(v4), RelayRoutes.only(IpFamily.V4, "spclient", listOf(v6, v4)))
        assertEquals(listOf(v6), RelayRoutes.only(IpFamily.V6, "spclient", listOf(v6, v4)))
        assertThrows<UnknownHostException> { RelayRoutes.only(IpFamily.V6, "spclient", listOf(v4)) }
    }

    @Test fun `addresses are masked in the logs`() {
        assertEquals("v4 85.x.x.x", RelayRoutes.mask(v4))
        assertEquals("v6 2a01:…", RelayRoutes.mask(v6))
        assertEquals(IpFamily.V6, IpFamily.of(v6))
    }
}
