package com.portal.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCodeTest {
    @Test fun codeIsSixDigits() {
        repeat(200) { assertTrue(Regex("\\d{6}").matches(PairingCode.issue(now = 0))) }
    }

    @Test fun rightCodeWorksOnceAndSpacesAreIgnored() {
        val c = PairingCode.issue(now = 1_000)
        assertTrue(PairingCode.redeem(c.substring(0, 3) + " " + c.substring(3), now = 2_000))
        assertFalse(PairingCode.redeem(c, now = 2_001)) // single use
    }

    @Test fun expiresAfterFiveMinutes() {
        val c = PairingCode.issue(now = 0)
        assertFalse(PairingCode.redeem(c, now = PairingCode.LIFETIME_MS))
        val d = PairingCode.issue(now = 0)
        assertTrue(PairingCode.redeem(d, now = PairingCode.LIFETIME_MS - 1))
    }

    @Test fun fiveWrongTriesBurnTheCode() {
        val c = PairingCode.issue(now = 0)
        val wrong = if (c == "000000") "111111" else "000000"
        repeat(PairingCode.MAX_WRONG) { assertFalse(PairingCode.redeem(wrong, now = 1)) }
        assertFalse(PairingCode.redeem(c, now = 2)) // even the right code is dead now
    }

    @Test fun newCodeReplacesTheOldAndResetsTries() {
        val a = PairingCode.issue(now = 0)
        repeat(PairingCode.MAX_WRONG - 1) { PairingCode.redeem("abc", now = 1) }
        val b = PairingCode.issue(now = 2)
        if (a != b) assertFalse(PairingCode.redeem(a, now = 3))
        assertTrue(PairingCode.redeem(b, now = 3))
    }

    @Test fun nothingWorksBeforeACodeIsIssued() {
        val c = PairingCode.issue(now = 0)
        PairingCode.redeem(c, now = 1) // consume
        assertEquals(false, PairingCode.redeem("", now = 2))
        assertEquals(false, PairingCode.redeem("000000", now = 2))
    }
}
