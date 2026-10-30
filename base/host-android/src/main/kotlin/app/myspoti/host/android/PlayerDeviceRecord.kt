package app.myspoti.host.android

import org.json.JSONObject
import java.io.File

/**
 * The playback device the last page registered, kept across app starts so the next page can
 * take it off the account.
 *
 * The Web Player creates its harmony player with `randomizeId: true`: every page load registers
 * a brand-new device id (the `_spharmony_device_id` key in localStorage is skipped in that case),
 * and only says goodbye (`DELETE /track-playback/v1/devices/{id}`) from its unload handler. An
 * app swiped away or killed never runs it, so the old device stays in the account's cluster for
 * the registration's keep-alive (6 h), an idle COMPUTER next to the new one. On some free
 * accounts Spotify then refuses the commands with 402.
 *
 * When a new page registers, the previous id gets the same DELETE the page would have sent,
 * with the next sequence number of that device and no playback state.
 */
internal class PlayerDeviceRecord(private val file: File) {
    @Volatile private var id: String? = null
    @Volatile private var seq: Long = 0
    private var loaded = false

    @Synchronized private fun load() {
        if (loaded) return
        loaded = true
        runCatching { JSONObject(file.readText()) }.getOrNull()?.let {
            id = it.optString("id").takeIf { s -> s.isNotEmpty() }
            seq = it.optLong("seq", 0)
        }
    }

    @Synchronized private fun save() = runCatching {
        val current = id
        if (current == null) file.delete()
        else {
            file.parentFile?.mkdirs()
            file.writeText(JSONObject().put("id", current).put("seq", seq).toString())
        }
    }

    /**
     * A new page registers [newId]: returns the id of the device the previous page left, if any,
     * with the sequence number its goodbye carries, and keeps the new one.
     */
    @Synchronized fun registering(newId: String): Pair<String, Long>? {
        load()
        val previous = id?.takeIf { it != newId }?.let { it to seq + 1 }
        if (previous != null || id == null) {
            id = newId
            seq = 0
            save()
        }
        return previous
    }

    /** The registration's answer: the device's sequence numbers start at [initialSeq]. */
    @Synchronized fun answered(initialSeq: Long) {
        load()
        if (id != null && initialSeq > seq) {
            seq = initialSeq
            save()
        }
    }

    /** A request the page sent for its device: its sequence numbers move on. */
    @Synchronized fun sent(url: String, body: String?) {
        load()
        val current = id ?: return
        if (body == null || !url.substringBefore('?').contains("/track-playback/v1/devices/$current")) return
        val highest = highestSeq(body) ?: return
        if (highest > seq) {
            seq = highest
            save()
        }
    }

    /** The page took its own device off (its unload handler ran): nothing left to clean. */
    @Synchronized fun deregistered(url: String) {
        load()
        val current = id ?: return
        if (url.substringBefore('?').endsWith("/track-playback/v1/devices/$current")) {
            id = null
            save()
        }
    }

    companion object {
        internal fun highestSeq(body: String): Long? = runCatching {
            val j = JSONObject(body)
            val nums = j.optJSONArray("seq_nums")
            val all = listOfNotNull(j.opt("seq_num") as? Number) +
                (0 until (nums?.length() ?: 0)).mapNotNull { nums?.opt(it) as? Number }
            all.maxOfOrNull { it.toLong() }
        }.getOrNull()

        /** The goodbye the page sends on unload, without a playback state (the old page is gone). */
        internal fun goodbye(seq: Long): String = JSONObject()
            .put("seq_num", seq)
            .put("state_ref", JSONObject.NULL)
            .put("sub_state", JSONObject().put("playback_speed", 0).put("position", 0).put("duration", 0))
            .put("previous_position", 0)
            .put("debug_source", "deregister")
            .toString()

        /** `https://host/track-playback/v1/devices` + `/id`. */
        internal fun deviceUrl(registrationUrl: String, id: String) = registrationUrl.substringBefore('?').trimEnd('/') + "/" + id
    }
}
