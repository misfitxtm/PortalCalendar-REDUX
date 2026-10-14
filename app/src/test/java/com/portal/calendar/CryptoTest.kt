package com.portal.calendar

import java.util.Base64
import javax.crypto.KeyGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CryptoTest {
    private val pass = "correct horse battery"
    private val data = "secret bundle ✓".toByteArray()
    private fun enc(p: String = pass) = BackupCrypto.encrypt(data, p, iterations = 1000)

    private fun refused(block: () -> Unit): String {
        try { block() } catch (e: IllegalArgumentException) { return e.message ?: "" }
        fail("expected IllegalArgumentException"); return ""
    }

    @Test fun roundTrip() = assertArrayEquals(data, BackupCrypto.decrypt(enc(), pass))

    @Test fun wrongPassphraseIsRefused() {
        assertTrue(refused { BackupCrypto.decrypt(enc(), "not the passphrase") }.contains("wrong passphrase"))
    }

    @Test fun shortPassphraseIsRefused() {
        assertTrue(refused { BackupCrypto.encrypt(data, "short", iterations = 1000) }.contains("at least"))
    }

    @Test fun sameInputEncryptsDifferentlyEachTime() = assertNotEquals(enc(), enc())

    private fun flip(token: String, index: Int): String {
        val raw = Base64.getDecoder().decode(token)
        raw[index] = (raw[index].toInt() xor 1).toByte()
        return Base64.getEncoder().encodeToString(raw)
    }

    @Test fun tamperedCiphertextIsRefused() {
        val t = enc()
        val last = Base64.getDecoder().decode(t).size - 1
        assertTrue(refused { BackupCrypto.decrypt(flip(t, last), pass) }.contains("changed"))
    }

    @Test fun tamperedHeaderIsRefused() { // salt byte is authenticated data, so editing it fails the tag
        assertTrue(refused { BackupCrypto.decrypt(flip(enc(), 10), pass) }.isNotEmpty())
    }

    @Test fun hostileIterationCountIsRefusedBeforeDerivingAKey() {
        val raw = Base64.getDecoder().decode(enc())
        raw[1] = 0x7f // iterations becomes huge
        val started = System.nanoTime()
        refused { BackupCrypto.decrypt(Base64.getEncoder().encodeToString(raw), pass) }
        assertTrue("should fail fast", System.nanoTime() - started < 500_000_000L)
    }

    @Test fun garbageIsRefused() {
        refused { BackupCrypto.decrypt("not base64 !!!", pass) }
        refused { BackupCrypto.decrypt(Base64.getEncoder().encodeToString(ByteArray(10)), pass) }
    }

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun secretBoxRoundTripAndLegacyPassThrough() {
        val s = SecretBox.seal("hunter2", key)
        assertTrue(SecretBox.isSealed(s))
        assertEquals("hunter2", SecretBox.open(s, key))
        assertEquals("plain-old-value", SecretBox.open("plain-old-value", key))
        assertNull(SecretBox.open(null, key))
    }

    @Test fun secretBoxWithWrongKeyOrTamperingYieldsNull() {
        val s = SecretBox.seal("hunter2", key)
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertNull(SecretBox.open(s, other))
        assertNull(SecretBox.open(s.dropLast(4) + "AAAA", key))
    }
}
