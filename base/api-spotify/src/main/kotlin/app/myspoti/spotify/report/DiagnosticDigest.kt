package app.myspoti.spotify.report

/**
 * The log a tester sends, made readable and small (a paste on Discord): what the 402 and ad
 * investigations had to dig out of 1 500 lines of polling and font warnings comes first, the
 * rest is cut down.
 *
 * - Noise is left out: the page's font warnings, the 15 s state/queue polling, library and
 *   device look-ups (their calls and their answers), the page's own network notes unless one failed.
 * - The same line in a row is kept once with its count ("(×12)").
 * - Each kind of line is kept at most [perKind] times; what was cut is counted at the end.
 * - Tokens, account ids, e-mails and addresses are masked ([mask]).
 * - The whole stays under [maxBytes]: the oldest lines go first.
 *
 * Lines are "HH:mm:ss.SSS text", as the host's Logbook keeps them.
 */
object DiagnosticDigest {
    private val TIME = Regex("""^(\d\d:\d\d:\d\d\.\d{3}) (.*)$""", RegexOption.DOT_MATCHES_ALL)
    private val CALL = Regex("""^call #(\d+) (\S+)""")
    /** Look-ups and polling: their calls and their answers ("call #12 … ok in 8 ms") go. */
    private val QUIET_CALLS = setOf("library.contains", "player.state", "player.queue", "volume.get", "token.get", "media.state")
    private val QUIET_SERVICES = Regex(""""service":"(ConnectDevicesAPI|RootlistAPI|UserAPI|PlaylistPermissionsAPI|LibraryAPI)"""")
    private val NOISE = listOf(
        Regex("""^console WARNING: The resource .* was preloaded"""),
        Regex("""^relay PUT /track-playback/v1/devices/\S+/volume -> 2\d\d"""),
    )
    private val PAGE_NET = Regex("""^page net: """)
    private val PAGE_NET_FAILED = Regex(""""status":(0|[45]\d\d)""")

    /** Up to this many lines of one kind are kept (a long session's state PUTs, its page states). */
    const val PER_KIND = 80
    const val MAX_BYTES = 190_000
    private const val MAX_LINE = 400

    fun digest(lines: List<String>, perKind: Int = PER_KIND, maxBytes: Int = MAX_BYTES): String {
        val quietIds = HashSet<String>()
        val kept = ArrayList<String>()
        val counts = HashMap<String, Int>()
        val dropped = LinkedHashMap<String, Int>()
        var lastText: String? = null
        var lastStamp = ""
        var repeats = 0
        fun flush() {
            val text = lastText ?: return
            kept += "$lastStamp ${if (repeats > 1) "$text (×$repeats)" else text}"
        }
        for (raw in lines) {
            val m = TIME.find(raw)
            val stamp = m?.groupValues?.get(1) ?: ""
            val text = mask(m?.groupValues?.get(2) ?: raw).let { if (it.length > MAX_LINE) it.take(MAX_LINE) + "…" else it }
            if (isNoise(text, quietIds)) continue
            if (text == lastText) { repeats++; continue }
            val kind = kindOf(text)
            val n = (counts[kind] ?: 0) + 1
            counts[kind] = n
            if (n > perKind) { dropped[kind] = (dropped[kind] ?: 0) + 1; continue }
            flush()
            lastText = text
            lastStamp = stamp
            repeats = 1
        }
        flush()
        val tail = if (dropped.isEmpty()) "" else buildString {
            appendLine()
            appendLine("--- cut (more than $perKind lines of one kind) ---")
            dropped.entries.sortedByDescending { it.value }.forEach { (k, v) -> appendLine("$v × $k") }
        }
        // The newest lines matter most: the oldest go until the whole fits.
        var size = tail.length + kept.sumOf { it.length + 1 }
        var first = 0
        while (size > maxBytes && first < kept.size) { size -= kept[first].length + 1; first++ }
        return buildString {
            if (first > 0) appendLine("($first older lines left out to keep the report small)")
            kept.subList(first, kept.size).forEach { appendLine(it) }
            append(tail)
        }
    }

    private fun isNoise(text: String, quietIds: MutableSet<String>): Boolean {
        if (NOISE.any { it.containsMatchIn(text) }) return true
        if (PAGE_NET.containsMatchIn(text)) return !PAGE_NET_FAILED.containsMatchIn(text)
        val call = CALL.find(text) ?: return false
        val id = call.groupValues[1]
        // A failed call is never noise.
        if (" FAILED" in text) return false
        if (id in quietIds) { if (" ok in " in text) quietIds.remove(id); return true }
        val method = call.groupValues[2]
        if (method in QUIET_CALLS || (method == "service.call" && QUIET_SERVICES.containsMatchIn(text))) {
            if (" ok in " !in text) quietIds += id
            return true
        }
        return false
    }

    /** A line's kind: its words with numbers, quoted values and ids taken out. */
    internal fun kindOf(text: String): String =
        text.replace(Regex("\"[^\"]*\""), "\"…\"")
            .replace(Regex("#\\d+"), "#")
            .replace(Regex("\\d+"), "N")
            .split(' ').take(6).joinToString(" ")

    private val BEARER = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+""")
    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+""")
    private val USER_URI = Regex("""spotify:user:[^\s",:}]+""")
    private val PERSONAL = Regex(""""(username|displayName|email|accountId|userId|birthdate|id_token|access_token|accessToken|clientToken|token)"\s*:\s*"[^"]*"""")
    private val URL_TOKEN = Regex("""(__token__|token|hmac|sig|signature)=[^&\s"]+""")
    /** Long opaque strings: tokens, device ids, hashes. */
    private val OPAQUE = Regex("""\b[A-Za-z0-9_-]{32,}\b""")
    private val IPV4 = Regex("""(?<![\w./\\-])(\d{1,3})\.\d{1,3}\.\d{1,3}\.\d{1,3}(?![\w.])""")
    private val IPV6 = Regex("""(?<![\w:])([0-9a-fA-F]{1,4}):(?:[0-9a-fA-F]{0,4}:){3,6}[0-9a-fA-F]{0,4}(?![\w:])""")

    /** [text] without what identifies a person or opens an account; enough left to read the log. */
    fun mask(text: String): String = text
        .replace(BEARER, "Bearer ***")
        .replace(EMAIL, "***@***")
        .replace(USER_URI, "spotify:user:***")
        .replace(PERSONAL) { "\"${it.groupValues[1]}\":\"***\"" }
        .replace(URL_TOKEN) { "${it.groupValues[1]}=***" }
        .replace(OPAQUE) { it.value.take(4) + "…" }
        .replace(IPV4) { "${it.groupValues[1]}.x.x.x" }
        .replace(IPV6) { "${it.groupValues[1]}:…" }
}
