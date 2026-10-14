package com.portal.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ResetsTest {
    @Test fun acceptsKnownScopes() {
        assertEquals(setOf("points", "potty"), Resets.parse(listOf("points", "potty", "points")))
    }

    @Test fun refusesNothingAndUnknownNames() {
        for (bad in listOf(emptyList(), listOf("points", "everything"), listOf("")))
            try { Resets.parse(bad); fail("accepted $bad") } catch (e: IllegalArgumentException) { /* expected */ }
    }
}
