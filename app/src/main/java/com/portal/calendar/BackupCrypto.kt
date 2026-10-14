package com.portal.calendar

import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passphrase-encrypted backups: AES-256-GCM with a PBKDF2-HMAC-SHA256 key.
 * Layout (base64): version(1) | iterations(4, big-endian) | salt(16) | iv(12) | ciphertext+tag.
 * The first 21 bytes are also the GCM associated data, so editing the header breaks the tag.
 */
object BackupCrypto {
    private const val VERSION: Byte = 2
    const val DEFAULT_ITERATIONS = 210_000
    // A hostile backup must not be able to make us burn minutes of CPU on key derivation.
    private const val MAX_ITERATIONS = 1_000_000
    const val MIN_PASSPHRASE = 10
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val HEADER_LEN = 1 + 4 + SALT_LEN

    fun encrypt(plain: ByteArray, passphrase: String, iterations: Int = DEFAULT_ITERATIONS,
                random: SecureRandom = SecureRandom()): String {
        requireStrong(passphrase)
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val header = ByteBuffer.allocate(HEADER_LEN).put(VERSION).putInt(iterations).put(salt).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt, iterations), GCMParameterSpec(128, iv))
        cipher.updateAAD(header)
        return Base64.getEncoder().encodeToString(header + iv + cipher.doFinal(plain))
    }

    fun decrypt(token: String, passphrase: String): ByteArray {
        val raw = try { Base64.getDecoder().decode(token.trim()) }
            catch (e: IllegalArgumentException) { throw IllegalArgumentException("that backup is damaged") }
        if (raw.size < HEADER_LEN + IV_LEN + 16 || raw[0] != VERSION)
            throw IllegalArgumentException("that backup is damaged or from an unknown version")
        val header = raw.copyOfRange(0, HEADER_LEN)
        val buf = ByteBuffer.wrap(header)
        buf.get()
        val iterations = buf.int
        if (iterations !in 1000..MAX_ITERATIONS) throw IllegalArgumentException("that backup is damaged")
        val salt = ByteArray(SALT_LEN).also { buf.get(it) }
        val iv = raw.copyOfRange(HEADER_LEN, HEADER_LEN + IV_LEN)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt, iterations), GCMParameterSpec(128, iv))
        cipher.updateAAD(header)
        return try { cipher.doFinal(raw, HEADER_LEN + IV_LEN, raw.size - HEADER_LEN - IV_LEN) }
            catch (e: AEADBadTagException) { throw IllegalArgumentException("wrong passphrase, or the backup was changed") }
    }

    fun requireStrong(passphrase: String) {
        if (passphrase.length < MIN_PASSPHRASE)
            throw IllegalArgumentException("use a passphrase of at least $MIN_PASSPHRASE characters")
    }

    private fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, 256)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally { spec.clearPassword() }
    }
}
