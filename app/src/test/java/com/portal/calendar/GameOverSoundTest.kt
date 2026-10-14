package com.portal.calendar

import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

class GameOverSoundTest {
    private val rate = GameOverSound.RATE

    private fun hz(s: ShortArray, startSec: Double, lenSec: Double = 0.05): Double {
        val a = (startSec * rate).toInt()
        val b = ((startSec + lenSec) * rate).toInt()
        var crossings = 0
        for (i in a + 1 until b) if (s[i - 1] <= 0 && s[i] > 0) crossings++
        return crossings / lenSec
    }

    @Test fun isShortQuietEnoughAndNotClipped() {
        val s = GameOverSound.samples()
        val seconds = s.size.toDouble() / rate
        assertTrue("length $seconds s", seconds in 1.5..3.0)
        val peak = s.maxOf { abs(it.toInt()) }
        assertTrue("peak $peak", peak in 3_000..8_000) // at most ~25% of full scale
    }

    @Test fun hopsUpThenFallsToALowNote() {
        val s = GameOverSound.samples()
        assertTrue(hz(s, 0.09) > hz(s, 0.005)) // the hop goes up
        val end = s.size.toDouble() / rate
        assertTrue(hz(s, end - 0.09, 0.08) < hz(s, 0.70) / 2) // the last slide ends far below the stepping notes
    }
}
