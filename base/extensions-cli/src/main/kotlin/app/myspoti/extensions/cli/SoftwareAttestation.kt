package app.myspoti.extensions.cli

import app.myspoti.extensions.Attestation
import app.myspoti.extensions.Enrollment
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * `myspoti.attest` for the desktop runner: a software EC P-256 key per extension, kept in [dir]
 * so it survives between runs, with a self-signed one-certificate "chain". TEST ONLY. Nothing here
 * is hardware-backed and nothing names Myspoti's signing certificate: a server that verifies the
 * chain as it must (to Google's attestation roots, section "Prove it is a genuine Myspoti" of
 * docs/extensions/API.md) rejects it, which is what lets an author test both the happy path of the
 * signatures and the refusal of this key.
 *
 * The certificate carries the challenge and a marker in an extension of a private OID
 * ([MARKER_OID]), not in Google's attestation extension, so nothing can mistake it for the real one.
 */
class SoftwareAttestation(private val dir: File, private val log: (extensionId: String, message: String) -> Unit = { _, _ -> }) : Attestation {
    private data class Stored(val key: PrivateKey, val cert: ByteArray)

    override fun enroll(extensionId: String, challenge: ByteArray): Enrollment {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val cert = selfSigned(extensionId, pair, challenge)
        dir.mkdirs()
        val file = file(extensionId)
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(b64(pair.private.encoded) + "\n" + b64(cert) + "\n")
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        log(extensionId, "attest: made a SOFTWARE key for the desktop runner: test only, not hardware-backed, a real server must refuse it")
        return Enrollment(b64(pair.public.encoded), listOf(b64(cert)))
    }

    override fun publicKey(extensionId: String): String? = load(extensionId)?.let { b64(publicOf(it.cert).encoded) }

    override fun sign(extensionId: String, data: ByteArray): String {
        val stored = load(extensionId) ?: throw IllegalStateException("not enrolled: call myspoti.attest.enroll(challenge) first")
        return b64(Signature.getInstance("SHA256withECDSA").apply { initSign(stored.key); update(data) }.sign())
    }

    override fun reset(extensionId: String) { file(extensionId).delete() }

    /** The extension's key file: the id is a safe file name (lower case, digits, `_` and `.`), and one id never gives another's name. */
    private fun file(extensionId: String) = File(dir, "attest-$extensionId.key")

    private fun load(extensionId: String): Stored? {
        val lines = file(extensionId).takeIf { it.isFile }?.readLines().orEmpty()
        if (lines.size < 2) return null
        val cert = Base64.getDecoder().decode(lines[1])
        val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(lines[0])))
        return Stored(key, cert)
    }

    private fun publicOf(cert: ByteArray): PublicKey =
        java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(cert.inputStream()).publicKey

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    companion object {
        /** A private-use OID: the certificate's marker and challenge, apart from Google's attestation extension. */
        const val MARKER_OID = "1.3.6.1.4.1.99999.1"
        private const val ECDSA_SHA256 = "1.2.840.10045.4.3.2"
        private const val COMMON_NAME = "2.5.4.3"

        /** A self-signed X.509 v3 certificate over [pair]'s public key, valid for ten years, signed with its private key. */
        internal fun selfSigned(extensionId: String, pair: KeyPair, challenge: ByteArray): ByteArray {
            val name = seq(set(seq(oid(COMMON_NAME), tlv(0x0C, "Myspoti desktop TEST-ONLY software key ($extensionId)".toByteArray()))))
            val now = System.currentTimeMillis()
            val validity = seq(utcTime(now - 60_000), utcTime(now + 10L * 365 * 24 * 3_600_000))
            val marker = ("myspoti-software-test-only;challenge=" + Base64.getEncoder().encodeToString(challenge)).toByteArray()
            val extensions = tlv(0xA3, seq(seq(oid(MARKER_OID), tlv(0x04, marker))))
            val serial = BigInteger(64, java.security.SecureRandom()).abs().add(BigInteger.ONE)
            val algorithm = seq(oid(ECDSA_SHA256))
            val tbs = seq(
                tlv(0xA0, tlv(0x02, byteArrayOf(2))),
                tlv(0x02, serial.toByteArray()),
                algorithm,
                name,
                validity,
                name,
                pair.public.encoded,
                extensions,
            )
            val signature = Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private); update(tbs) }.sign()
            return seq(tbs, algorithm, tlv(0x03, byteArrayOf(0) + signature))
        }

        // ── DER, just what a certificate needs ──

        private fun tlv(tag: Int, content: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(tag)
            when {
                content.size < 0x80 -> out.write(content.size)
                content.size < 0x100 -> { out.write(0x81); out.write(content.size) }
                else -> { out.write(0x82); out.write(content.size shr 8); out.write(content.size and 0xFF) }
            }
            out.write(content)
            return out.toByteArray()
        }

        private fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })
        private fun set(vararg parts: ByteArray) = tlv(0x31, parts.fold(ByteArray(0)) { a, b -> a + b })

        private fun oid(dotted: String): ByteArray {
            val n = dotted.split('.').map { it.toLong() }
            val out = ByteArrayOutputStream()
            out.write((n[0] * 40 + n[1]).toInt())
            for (v in n.drop(2)) {
                val groups = generateSequence(v) { it shr 7 }.takeWhile { it > 0 }.toList().ifEmpty { listOf(0L) }.reversed()
                groups.forEachIndexed { i, g -> out.write(((g and 0x7F) or if (i < groups.lastIndex) 0x80L else 0L).toInt()) }
            }
            return tlv(0x06, out.toByteArray())
        }

        private fun utcTime(millis: Long): ByteArray {
            val f = java.text.SimpleDateFormat("yyMMddHHmmss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            return tlv(0x17, f.format(java.util.Date(millis)).toByteArray())
        }
    }
}
