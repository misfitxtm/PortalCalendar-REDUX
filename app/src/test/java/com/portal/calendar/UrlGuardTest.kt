package com.portal.calendar

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class UrlGuardTest {
    private fun blocked(ip: String) = UrlGuard.isBlocked(InetAddress.getByName(ip))

    @Test fun blocksSelfAndMetadataAddresses() {
        for (ip in listOf("127.0.0.1", "::1", "0.0.0.0", "169.254.169.254", "fe80::1", "224.0.0.1", "::ffff:127.0.0.1"))
            assertEquals(ip, true, blocked(ip))
    }

    @Test fun allowsPublicAndLanFeeds() {
        for (ip in listOf("8.8.8.8", "192.168.1.20", "10.0.0.5", "2606:4700::1111"))
            assertEquals(ip, false, blocked(ip))
    }

    @Test fun readCappedAllowsSmallRefusesBig() {
        assertEquals("héllo", UrlGuard.readCapped("héllo".byteInputStream(), max = 16))
        try {
            UrlGuard.readCapped(ByteArray(20_000).inputStream(), max = 10_000)
            fail("expected refusal")
        } catch (e: IllegalArgumentException) {
            assertEquals("that feed is too large", e.message)
        }
    }
}
