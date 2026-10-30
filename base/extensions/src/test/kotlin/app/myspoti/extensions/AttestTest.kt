package app.myspoti.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `myspoti.attest`: the right it needs, what it checks before the host's keys are asked, and its limits. */
class AttestTest {
    private val track = TrackInfo("abc", "spotify:track:abc", "Song", "Artist", listOf("Artist"), "Album", 180_000)

    /** Keys that are only a record of what was asked: the host's side is tested with the desktop runner's real ones. */
    private class FakeKeys : Attestation {
        val enrolled = mutableListOf<Pair<String, String>>()
        val keys = mutableSetOf<String>()
        val reset = mutableListOf<String>()
        override fun enroll(extensionId: String, challenge: ByteArray): Enrollment {
            enrolled += extensionId to String(challenge)
            keys += extensionId
            return Enrollment("spki-$extensionId", listOf("leaf", "root"))
        }
        override fun publicKey(extensionId: String): String? = if (extensionId in keys) "spki-$extensionId" else null
        override fun sign(extensionId: String, data: ByteArray): String {
            check(extensionId in keys) { "not enrolled" }
            return "sig:${String(data)}"
        }
        override fun reset(extensionId: String) { keys -= extensionId; reset += extensionId }
    }

    private class Host(val keys: Attestation?) : app.myspoti.extensions.Host {
        override fun log(extensionId: String, message: String) {}
        override fun storage(extensionId: String): Storage = MemoryStorage()
        override fun setting(extensionId: String, key: String): String? = null
        override fun attestation(): Attestation? = keys
    }

    private fun manifest(vararg names: String) = Manifest("io.github.test.attest", "Attest", "1.0", author = "me",
        actions = (listOf("lyrics.provide") + names).map { ActionRequest(it, "Proves this is a real install") })

    /** The value of [body] (or its error message) as the script sees it. */
    private fun eval(body: String, host: app.myspoti.extensions.Host, declared: Array<String> = arrayOf("attest"), granted: Set<String> = declared.toSet() + "lyrics.provide", limits: ExtensionRuntime.Limits = ExtensionRuntime.Limits()): String? =
        ExtensionRuntime(manifest(*declared), "myspoti.provide('lyrics', function () { try { return { v: $body }; } catch (e) { return { v: 'error: ' + e.message }; } });", host, granted, limits)
            .use { it.start(); it.call(Hook.LYRICS, track.toJson(), 3_000) }

    @Test fun theActionIsNormalAndNeedsNoAreaLikeTheOtherSupportActions() {
        assertEquals(Level.NORMAL, Action.ATTEST.level)
        assertEquals(null, Action.ATTEST.area)
        assertEquals(Action.ATTEST, Action.of("attest"))
        assertEquals(emptyList<String>(), manifest("attest").problems())
        assertTrue(manifest("attest").asks().any { it.key == "attest" && it.why.isNotBlank() })
    }

    @Test fun withoutTheActionEveryCallThrowsAndNoKeyIsTouched() {
        val keys = FakeKeys()
        for (call in listOf("myspoti.attest.enroll('c')", "myspoti.attest.publicKey()", "myspoti.attest.sign('x')", "myspoti.attest.reset()")) {
            val out = eval(call, Host(keys), declared = emptyArray())!!
            assertTrue("$call: $out", out.contains("attest is not in this extension's manifest"))
        }
        assertTrue(keys.enrolled.isEmpty() && keys.reset.isEmpty())
    }

    @Test fun declaredButNotGrantedIsRefusedToo() {
        val keys = FakeKeys()
        val out = eval("myspoti.attest.enroll('c')", Host(keys), granted = setOf("lyrics.provide"))!!
        assertTrue(out, out.contains("the user did not grant attest"))
        assertTrue(keys.enrolled.isEmpty())
    }

    @Test fun enrollGivesTheKeyAndTheChainAndPassesTheChallenge() {
        val keys = FakeKeys()
        assertEquals("""{"v":null}""", eval("myspoti.attest.publicKey()", Host(keys)))
        assertEquals("""{"v":{"publicKey":"spki-io.github.test.attest","certChain":["leaf","root"]}}""", eval("myspoti.attest.enroll('server-challenge-1')", Host(keys)))
        assertEquals(listOf("io.github.test.attest" to "server-challenge-1"), keys.enrolled)
        assertEquals("""{"v":"spki-io.github.test.attest"}""", eval("myspoti.attest.publicKey()", Host(keys)))
        assertEquals("""{"v":"sig:GET /x"}""", eval("myspoti.attest.sign('GET /x')", Host(keys)))
        eval("myspoti.attest.reset()", Host(keys))
        assertEquals(listOf("io.github.test.attest"), keys.reset)
        assertEquals("""{"v":null}""", eval("myspoti.attest.publicKey()", Host(keys)))
    }

    @Test fun signBeforeEnrollThrowsAClearError() {
        val out = eval("myspoti.attest.sign('x')", Host(FakeKeys()))!!
        assertTrue(out, out.contains("attest.sign: not enrolled"))
    }

    @Test fun theChallengeIsOneToOneHundredTwentyEightBytes() {
        val keys = FakeKeys()
        for (bad in listOf("''", "'a'.repeat(129)", "'é'.repeat(65)")) {
            val out = eval("myspoti.attest.enroll($bad)", Host(keys))!!
            assertTrue("$bad: $out", out.contains("the challenge is 1 to 128 bytes"))
        }
        assertTrue(keys.enrolled.isEmpty())
        assertTrue(eval("myspoti.attest.enroll('a'.repeat(128)).publicKey", Host(keys))!!.startsWith("""{"v":"spki-"""))
        assertTrue(eval("myspoti.attest.enroll('é'.repeat(64)).publicKey", Host(keys))!!.startsWith("""{"v":"spki-"""))
    }

    @Test fun aHostWithoutKeysSaysSo() {
        val out = eval("myspoti.attest.enroll('c')", Host(null))!!
        assertTrue(out, out.contains("cannot make hardware-backed keys"))
    }

    @Test fun enrollIsLimitedPerHourAndSignPerMinute() {
        val keys = FakeKeys()
        val limits = ExtensionRuntime.Limits(attestSignsPerMinute = 3, attestEnrollsPerHour = 2)
        assertEquals("""{"v":"ok,ok,attest.enroll: at most 2 an hour"}""", eval(
            "(function () { var r = []; for (var i = 0; i < 3; i++) { try { myspoti.attest.enroll('c'); r.push('ok'); } catch (e) { r.push(e.message); } } return r.join(','); })()", Host(keys), limits = limits))
        // The window is the runtime's: a new one starts with its own count.
        assertEquals("""{"v":"s,s,s,more than 3 attest.sign calls in a minute"}""", eval(
            "(function () { myspoti.attest.enroll('c'); var r = []; for (var i = 0; i < 4; i++) { try { myspoti.attest.sign('x'); r.push('s'); } catch (e) { r.push(e.message); } } return r.join(','); })()", Host(keys), limits = limits))
        // Mind the defaults the docs promise.
        assertEquals(120, ExtensionRuntime.Limits().attestSignsPerMinute)
        assertEquals(5, ExtensionRuntime.Limits().attestEnrollsPerHour)
    }

    @Test fun signTakesAtMost64KB() {
        val keys = FakeKeys()
        val out = eval("(function () { myspoti.attest.enroll('c'); try { myspoti.attest.sign('a'.repeat(65 * 1024)); return 'signed'; } catch (e) { return e.message; } })()", Host(keys))!!
        assertTrue(out, out.contains("attest.sign: text over 64 KB"))
    }

    @Test fun featuresListAttestOnlyWhenGranted() {
        val js = "myspoti.features.indexOf('attest') >= 0"
        assertEquals("""{"v":true}""", eval(js, Host(FakeKeys())))
        assertEquals("""{"v":false}""", eval(js, Host(FakeKeys()), declared = emptyArray()))
        assertFalse(eval(js, Host(FakeKeys()), granted = setOf("lyrics.provide"))!!.contains("true"))
    }

    @Test fun keyAliasesNeverCollideAcrossExtensionIds() {
        val ids = listOf("io.github.a.x", "io.github.a.x.y", "io.github.a", "io.github.a_x")
        assertEquals(ids.size, ids.map(Attestation::alias).toSet().size)
        assertEquals("myspoti.ext.attest.io.github.a.x", Attestation.alias("io.github.a.x"))
    }
}
