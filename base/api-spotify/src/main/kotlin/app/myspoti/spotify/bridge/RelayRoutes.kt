package app.myspoti.spotify.bridge

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Which way the host sends the page's relayed requests (see the host's ControlRelay).
 *
 * Some free accounts get every relayed command refused (402 COMMAND_FAILED) from the first one,
 * while the same account plays in Chrome. The lead kept (402-ANALYSIS, H2): for such accounts
 * Spotify checks the shape of a genuine browser's request, down to its network stack (the TLS
 * ClientHello, the HTTP/2 settings), which OkHttp does not have. A refused command is sent again
 * through a Chromium network stack first: the platform's own (android.net.http, Android 14+), or
 * Cronet from Google Play services on older versions. The other address family comes next (an
 * older lead: the WebView and the host's client on different families, 464XLAT). Whichever is
 * answered is kept for the rest of the session, for every relayed request; a session that was
 * never refused stays on OkHttp.
 */
enum class RelayRoute {
    DEFAULT, IPV4, IPV6, HTTP_ENGINE, CRONET;

    /** A Chromium network stack, not OkHttp. */
    val chromium: Boolean get() = this == HTTP_ENGINE || this == CRONET
}

enum class IpFamily {
    V4, V6;

    companion object {
        fun of(address: InetAddress?): IpFamily? = when (address) {
            is Inet6Address -> V6
            is Inet4Address -> V4
            else -> null
        }
    }
}

/**
 * [chromium]: the Chromium stack this phone offers now ([RelayRoute.HTTP_ENGINE] or
 * [RelayRoute.CRONET]), null when it has none. Asked at each refusal: Cronet is there only once
 * Google Play services has installed its provider.
 */
class RelayRoutes(private val chromium: () -> RelayRoute?) {
    constructor(engineAvailable: Boolean) : this({ if (engineAvailable) RelayRoute.HTTP_ENGINE else null })

    /** The Chromium stack this phone offers now, if any. */
    fun chromiumRoute(): RelayRoute? = chromium()

    /** The route every relayed request takes: [RelayRoute.DEFAULT] until another was taken after a refusal. */
    @Volatile var kept: RelayRoute = RelayRoute.DEFAULT
        private set

    /**
     * The route to try after a refusal on every route in [tried] (the first one is [kept]).
     * [used]: the family the refused request went out on (null: not known), so the retry takes
     * the other one. Null: nothing left, the refusal stands.
     */
    fun next(tried: List<RelayRoute>, used: IpFamily?): RelayRoute? {
        val other = when {
            used == IpFamily.V6 -> RelayRoute.IPV4
            used == IpFamily.V4 -> RelayRoute.IPV6
            RelayRoute.IPV4 in tried -> RelayRoute.IPV6
            else -> RelayRoute.IPV4
        }
        // The Chromium stack first, then the other family, each once.
        val familyRetried = tried.drop(1).any { it == RelayRoute.IPV4 || it == RelayRoute.IPV6 }
        val order = listOfNotNull(chromium(), other.takeIf { !familyRetried })
        return order.firstOrNull { it !in tried }
    }

    /** [route] was answered after the routes before it were refused: kept for the session. */
    fun won(route: RelayRoute) {
        kept = route
    }

    companion object {
        /** The addresses of [family] only; none is an unknown host (the network lacks that family). */
        fun only(family: IpFamily, host: String, all: List<InetAddress>): List<InetAddress> =
            all.filter { IpFamily.of(it) == family }.ifEmpty { throw UnknownHostException("$host has no ${family.name} address") }

        /**
         * An address for the logs: its family and the first part only, never the whole of it
         * ("v6 2a01:…", "v4 85.x.x.x"): enough to tell two paths apart, not to locate anyone.
         */
        fun mask(address: InetAddress?): String = when (address) {
            is Inet6Address -> "v6 " + (address.hostAddress ?: "").substringBefore(':') + ":…"
            is Inet4Address -> "v4 " + (address.hostAddress ?: "").substringBefore('.') + ".x.x.x"
            else -> "?"
        }
    }
}
