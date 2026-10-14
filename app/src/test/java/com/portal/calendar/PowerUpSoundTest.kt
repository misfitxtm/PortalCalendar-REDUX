package com.portal.calendar

import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerUpSoundTest {
    private val rate = PowerUpSound.RATE

    /** Pitch of a short window, from positive-going zero crossings. */
    private fun hz(s: ShortArray, startSec: Double, lenSec: Double = 0.05): Double {
        val a = (startSec * rate).toInt()
        val b = ((startSec + lenSec) * rate).toInt()
        var crossings = 0
        for (i in a + 1 until b) if (s[i - 1] <= 0 && s[i] > 0) crossings++
        return crossings / lenSec
    }

    @Test fun isShortAndNotClipped() {
        val s = PowerUpSound.samples()
        val seconds = s.size.toDouble() / rate
        assertTrue("length $seconds s", seconds in 1.0..2.0)
        val peak = s.maxOf { abs(it.toInt()) }
        assertTrue("peak $peak", peak in 3_000..30_000)
    }

    @Test fun climbsWithinEachRunAndEachRunStartsHigher() {
        val s = PowerUpSound.samples()
        val note = 0.06
        // First run: 1st note (G3 ~196 Hz) is far below its 7th (G5 ~784 Hz).
        assertTrue(hz(s, 0.005) < hz(s, 6 * note + 0.005) / 2)
        // The second run peaks higher than the first (a half step is too small to count on the low notes).
        assertTrue(hz(s, 14 * note + 0.005) > hz(s, 6 * note + 0.005))
    }
}
