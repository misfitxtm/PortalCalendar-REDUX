package com.portal.calendar

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals a string with a given AES key: "enc1:" + base64(iv | ciphertext+tag). Pure, so JUnit can test it. */
object SecretBox {
    const val PREFIX = "enc1:"
    private const val IV_LEN = 12

    fun isSealed(s: String?) = s != null && s.startsWith(PREFIX)

    fun seal(plain: String, key: SecretKey): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key) // the Keystore insists on choosing the IV itself
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getEncoder().encodeToString(c.iv + ct)
    }

    /** Null if the value can't be decrypted (lost or changed key); unsealed legacy values pass through. */
    fun open(stored: String?, key: SecretKey): String? {
        if (!isSealed(stored)) return stored
        return runCatching {
            val raw = Base64.getDecoder().decode(stored!!.removePrefix(PREFIX))
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, raw, 0, IV_LEN))
            String(c.doFinal(raw, IV_LEN, raw.size - IV_LEN), Charsets.UTF_8)
        }.getOrNull()
    }
}

/**
 * Account secrets (iCloud password, Google tokens, Gemini key) are stored sealed with a
 * non-exportable Android Keystore key, so a copy of the prefs file or an adb backup is useless.
 */
object Secrets {
    private const val ALIAS = "portalhub_secrets"
    /** Prefs holding secrets. The pairing token is deliberately not here: it is shown on screen in the QR code. */
    val KEYS = setOf("icloud_password", "g_client_secret", "g_refresh", "g_access", "ai_key")

    private val key: SecretKey by lazy {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build())
            }.generateKey()
    }

    fun seal(plain: String): String = SecretBox.seal(plain, key)

    /** Plain values pass through without touching the Keystore. */
    fun open(stored: String?): String? = if (SecretBox.isSealed(stored)) SecretBox.open(stored, key) else stored

    /** One-time upgrade: seal any secret still stored in plain text. */
    fun migrate(ctx: Context) {
        val prefs = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)
        val e = prefs.edit()
        var changed = false
        for (k in KEYS) {
            val v = prefs.getString(k, null)
            if (!v.isNullOrEmpty() && !SecretBox.isSealed(v)) { e.putString(k, seal(v)); changed = true }
        }
        if (changed) e.commit()
    }
}
