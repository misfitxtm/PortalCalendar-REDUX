package com.portal.calendar

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * A short-lived code for one-off file links (the shopping list QR). It opens a single file, never the setup page,
 * so a code in a QR on the Portal's screen can't be used to sign in. A new code replaces the old one.
 */
object DownloadLink {
    const val LIFETIME_MS = 10 * 60 * 1000L

    private var code = ""
    private var expires = 0L

    @Synchronized
    fun issue(now: Long = System.currentTimeMillis()): String {
        code = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        expires = now + LIFETIME_MS
        return code
    }

    @Synchronized
    fun valid(presented: String?, now: Long = System.currentTimeMillis()): Boolean =
        presented != null && code.isNotEmpty() && now < expires &&
            MessageDigest.isEqual(code.toByteArray(), presented.toByteArray())
}
