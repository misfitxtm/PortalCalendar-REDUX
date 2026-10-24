package com.portal.calendar

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerTokenTest {
    @Test fun tokenIs64HexCharactersAndLongEnoughForTheGate() {
        repeat(100) { assertTrue(Regex("[0-9a-f]{64}").matches(newServerToken())) }
    }

    @Test fun everyTokenIsDifferent() {
        assertNotEquals(newServerToken(), newServerToken())
    }
}
