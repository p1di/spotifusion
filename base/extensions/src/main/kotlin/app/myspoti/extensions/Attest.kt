package app.myspoti.extensions

import kotlinx.serialization.Serializable

/**
 * The keys behind `myspoti.attest` ([Action.ATTEST]), one signing key per extension. The app
 * keeps them in the Android hardware keystore with a key attestation; the desktop runner
 * (`myspoti-ext`) keeps a software key for testing. The runtime has already checked the right and
 * the limits: an implementation only does the work, and throws with a plain reason.
 */
interface Attestation {
    /**
     * Creates the extension's EC P-256 signing key, or replaces it, with [challenge] as the
     * attestation challenge: the chain proves the key lives in hardware and names the app that made it.
     */
    fun enroll(extensionId: String, challenge: ByteArray): Enrollment

    /** The current key's public half, base64 SPKI, or null before [enroll]. */
    fun publicKey(extensionId: String): String?

    /** Base64 DER ECDSA-SHA256 signature of [data], made with the extension's key. Throws when it has none. */
    fun sign(extensionId: String, data: ByteArray): String

    /** Deletes the extension's key: nothing when it has none. Done when the extension is uninstalled. */
    fun reset(extensionId: String)

    companion object {
        /** The keystore alias of an extension's key: ids are unique, so no extension reaches another's key. */
        fun alias(extensionId: String) = "myspoti.ext.attest.$extensionId"

        /** The longest challenge `enroll` takes, in bytes (UTF-8). */
        const val MAX_CHALLENGE_BYTES = 128

        /** The longest text `sign` takes, in bytes (UTF-8). */
        const val MAX_SIGN_BYTES = 64 * 1024
    }
}

/** What `enroll` gives the script: the public key (base64 SPKI) and the attestation chain (base64 DER certificates, leaf first). */
@Serializable
data class Enrollment(val publicKey: String, val certChain: List<String>)
