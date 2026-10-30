package app.myspoti.extensions.cli

import app.myspoti.extensions.ActionRequest
import app.myspoti.extensions.Attestation
import app.myspoti.extensions.ExtensionRuntime
import app.myspoti.extensions.Hook
import app.myspoti.extensions.Host
import app.myspoti.extensions.Manifest
import app.myspoti.extensions.MemoryStorage
import app.myspoti.extensions.Storage
import app.myspoti.extensions.TrackInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** The desktop runner's software keys: sign/verify round trips, one key per extension, and `myspoti.attest` through the sandbox. */
class SoftwareAttestationTest {
    @get:Rule val tmp = TemporaryFolder()

    private val track = TrackInfo("abc", "spotify:track:abc", "Song", "Artist", listOf("Artist"), "Album", 180_000)
    private fun keys(dir: File = File(tmp.root, "attest")) = SoftwareAttestation(dir)

    private fun publicKey(spki: String): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(spki)))

    private fun verifies(spki: String, text: String, signature: String): Boolean =
        Signature.getInstance("SHA256withECDSA").apply { initVerify(publicKey(spki)); update(text.toByteArray()) }.verify(Base64.getDecoder().decode(signature))

    private fun certificate(der: String) = CertificateFactory.getInstance("X.509").generateCertificate(Base64.getDecoder().decode(der).inputStream()) as X509Certificate

    @Test fun signaturesVerifyWithTheEnrolledPublicKeyAndOnlyForTheSameText() {
        val k = keys()
        val enrollment = k.enroll("io.github.test.a", "challenge".toByteArray())
        assertEquals(enrollment.publicKey, k.publicKey("io.github.test.a"))
        val text = "POST\n/v1/sync\n1700000000\nnonce-1\nabc123"
        val signature = k.sign("io.github.test.a", text.toByteArray())
        assertTrue(verifies(enrollment.publicKey, text, signature))
        assertFalse(verifies(enrollment.publicKey, text + "x", signature))
        // The public key is an EC P-256 SPKI.
        assertEquals("EC", publicKey(enrollment.publicKey).algorithm)
        assertEquals(91, Base64.getDecoder().decode(enrollment.publicKey).size)
    }

    @Test fun theChainIsOneSelfSignedCertificateMarkedTestOnly() {
        val enrollment = keys().enroll("io.github.test.a", "server-challenge".toByteArray())
        assertEquals(1, enrollment.certChain.size)
        val cert = certificate(enrollment.certChain[0])
        assertEquals(enrollment.publicKey, Base64.getEncoder().encodeToString(cert.publicKey.encoded))
        cert.verify(cert.publicKey)
        cert.checkValidity()
        assertTrue(cert.subjectX500Principal.name, cert.subjectX500Principal.name.contains("TEST-ONLY"))
        assertEquals(cert.subjectX500Principal, cert.issuerX500Principal)
        // Not Google's attestation extension: a real verifier finds none and refuses the chain.
        assertNull(cert.getExtensionValue("1.3.6.1.4.1.11129.2.1.17"))
        val marker = String(cert.getExtensionValue(SoftwareAttestation.MARKER_OID)!!)
        assertTrue(marker.contains("myspoti-software-test-only;challenge=" + Base64.getEncoder().encodeToString("server-challenge".toByteArray())))
    }

    @Test fun theKeySurvivesBetweenRunsAndEnrollingAgainReplacesIt() {
        val first = keys().enroll("io.github.test.a", "c1".toByteArray())
        val again = keys()
        assertEquals(first.publicKey, again.publicKey("io.github.test.a"))
        assertTrue(verifies(first.publicKey, "x", again.sign("io.github.test.a", "x".toByteArray())))
        val second = again.enroll("io.github.test.a", "c2".toByteArray())
        assertNotEquals(first.publicKey, second.publicKey)
        val signature = keys().sign("io.github.test.a", "x".toByteArray())
        assertTrue(verifies(second.publicKey, "x", signature))
        assertFalse(verifies(first.publicKey, "x", signature))
    }

    @Test fun noKeyExistsBeforeEnrollAndSignThrows() {
        val k = keys()
        assertNull(k.publicKey("io.github.test.a"))
        try { k.sign("io.github.test.a", "x".toByteArray()); fail("signed without a key") } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("not enrolled")) }
    }

    @Test fun resetDeletesTheKeyOfThatExtensionOnly() {
        val k = keys()
        k.enroll("io.github.test.a", "c".toByteArray())
        val b = k.enroll("io.github.test.b", "c".toByteArray())
        k.reset("io.github.test.a")
        k.reset("io.github.test.never")
        assertNull(k.publicKey("io.github.test.a"))
        assertEquals(b.publicKey, k.publicKey("io.github.test.b"))
        assertTrue(verifies(b.publicKey, "x", k.sign("io.github.test.b", "x".toByteArray())))
    }

    @Test fun twoExtensionsNeverShareAKey() {
        val k = keys()
        val a = k.enroll("io.github.test.a", "c".toByteArray())
        assertNull(k.publicKey("io.github.test.b"))
        try { k.sign("io.github.test.b", "x".toByteArray()); fail("b signed with a's key") } catch (_: IllegalStateException) {}
        val b = k.enroll("io.github.test.b", "c".toByteArray())
        assertNotEquals(a.publicKey, b.publicKey)
        val bySigner = k.sign("io.github.test.a", "x".toByteArray())
        assertTrue(verifies(a.publicKey, "x", bySigner))
        assertFalse(verifies(b.publicKey, "x", bySigner))
        // An id that is the start of another's does not reach its key file either.
        assertNull(k.publicKey("io.github.test"))
        assertNotEquals(Attestation.alias("io.github.test.a"), Attestation.alias("io.github.test.a.b"))
    }

    // ── Through the sandbox, as an extension uses it ──

    private class DesktopLikeHost(val keys: Attestation) : Host {
        override fun log(extensionId: String, message: String) {}
        override fun storage(extensionId: String): Storage = MemoryStorage()
        override fun setting(extensionId: String, key: String): String? = null
        override fun attestation(): Attestation = keys
    }

    private fun run(id: String, host: Host, script: String, vararg actions: String): String? {
        val m = Manifest(id, "Attest", "1.0", author = "me", actions = (listOf("lyrics.provide") + actions).map { ActionRequest(it, "Proves this is a real install") })
        val js = "myspoti.provide('lyrics', function () { try { return { v: (function () { $script })() }; } catch (e) { return { v: 'error: ' + e.message }; } });"
        return ExtensionRuntime(m, js, host, m.asks().map { it.key }.toSet()).use { it.start(); it.call(Hook.LYRICS, track.toJson(), 3_000) }
    }

    private fun value(json: String?) = kotlinx.serialization.json.Json.parseToJsonElement(json!!).let { (it as kotlinx.serialization.json.JsonObject)["v"]!! }

    @Test fun anExtensionSignsAndItsServerVerifiesWithTheKeyFromEnroll() {
        val host = DesktopLikeHost(keys())
        val out = run("io.github.test.a", host, """
            var e = myspoti.attest.enroll('nonce-from-server');
            var text = ['POST', '/v1/events', '1700000000', 'n-1', myspoti.util.sha256('{"a":1}')].join('\n');
            return { publicKey: e.publicKey, same: myspoti.attest.publicKey() === e.publicKey, chain: e.certChain.length, text: text, signature: myspoti.attest.sign(text) };
        """, "attest")
        val o = value(out) as kotlinx.serialization.json.JsonObject
        fun s(k: String) = (o[k] as kotlinx.serialization.json.JsonPrimitive).content
        assertEquals("true", s("same"))
        assertEquals("1", s("chain"))
        assertTrue(verifies(s("publicKey"), s("text"), s("signature")))
        assertFalse(verifies(s("publicKey"), s("text") + "!", s("signature")))
    }

    @Test fun anExtensionCannotSignWithAnotherExtensionsKey() {
        val host = DesktopLikeHost(keys())
        val a = run("io.github.test.a", host, "return myspoti.attest.enroll('c').publicKey;", "attest")
        // b has the right but never enrolled: a's key is not reachable from it, by any call.
        assertEquals("null", value(run("io.github.test.b", host, "return myspoti.attest.publicKey();", "attest")).toString())
        assertTrue(value(run("io.github.test.b", host, "return myspoti.attest.sign('x');", "attest")).toString().contains("not enrolled"))
        val b = run("io.github.test.b", host, "return myspoti.attest.enroll('c').publicKey;", "attest")
        assertNotEquals(a, b)
        val signed = value(run("io.github.test.b", host, "return myspoti.attest.sign('x');", "attest")).toString().trim('"')
        assertTrue(verifies(value(b).toString().trim('"'), "x", signed))
        assertFalse(verifies(value(a).toString().trim('"'), "x", signed))
        // Resetting b leaves a's key.
        run("io.github.test.b", host, "myspoti.attest.reset(); return 1;", "attest")
        assertEquals("null", value(run("io.github.test.b", host, "return myspoti.attest.publicKey();", "attest")).toString())
        assertEquals(value(a), value(run("io.github.test.a", host, "return myspoti.attest.publicKey();", "attest")))
    }

    @Test fun withoutTheActionTheSandboxRefusesBeforeTheKeysAreTouched() {
        val host = DesktopLikeHost(keys())
        val out = run("io.github.test.a", host, "return myspoti.attest.enroll('c');")
        assertTrue(out, out!!.contains("attest is not in this extension's manifest"))
        assertNull(host.keys.publicKey("io.github.test.a"))
        assertFalse(File(tmp.root, "attest").exists())
    }
}
