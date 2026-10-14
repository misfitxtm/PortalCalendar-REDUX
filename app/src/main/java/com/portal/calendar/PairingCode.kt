package com.portal.calendar

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * A 6-digit code that signs a computer in without scanning a QR. It works once, expires after 5 minutes, and is
 * burned after 5 wrong tries, so guessing it over the Wi-Fi (about 1 chance in 200,000 per code) is not practical.
 * A new code replaces the old one.
 */
object PairingCode {
    const val LIFETIME_MS = 5 * 60 * 1000L
    const val MAX_WRONG = 5

    private var code = ""
    private var expires = 0L
    private var wrong = 0

    @Synchronized
    fun issue(now: Long = System.currentTimeMillis()): String {
        code = "%06d".format(SecureRandom().nextInt(1_000_000))
        expires = now + LIFETIME_MS
        wrong = 0
        return code
    }

    /** True once for the right code; every other call counts as a wrong try. */
    @Synchronized
    fun redeem(typed: String, now: Long = System.currentTimeMillis()): Boolean {
        if (code.isEmpty() || now >= expires) return false
        if (MessageDigest.isEqual(code.toByteArray(), typed.replace(" ", "").toByteArray())) {
            code = ""
            return true
        }
        if (++wrong >= MAX_WRONG) code = ""
        return false
    }
}
