package com.portal.calendar

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccidentSoundTest {
    private val rate = AccidentSound.RATE

    /** Pitch of a short window, from positive-going zero crossings. */
    private fun hz(s: ShortArray, startSec: Double, lenSec: Double = 0.06): Double {
        val a = (startSec * rate).toInt()
        val b = ((startSec + lenSec) * rate).toInt()
        var crossings = 0
        for (i in a + 1 until b) if (s[i - 1] <= 0 && s[i] > 0) crossings++
        return crossings / lenSec
    }

    @Test fun lastsAboutFourAndAHalfSecondsAndIsNotClipped() {
        val s = AccidentSound.samples()
        val seconds = s.size.toDouble() / rate
        assertTrue("length $seconds s", seconds in 3.8..4.8)
        val peak = s.maxOf { abs(it.toInt()) }
        assertTrue("peak $peak", peak in 8_000..30_000) // audible, and below full scale so it can't clip
    }

    @Test fun sirenSweepsHighAndLow() {
        val s = AccidentSound.siren()
        val low = hz(s, 0.10)   // near the bottom of the first swing
        val high = hz(s, 0.50)  // top of the swing
        assertTrue("low $low", low in 500.0..900.0)
        assertTrue("high $high", high in 1000.0..1500.0)
        assertTrue(high > low * 1.4)
    }

    @Test fun threeWompsStepDownInPitch() {
        val w = AccidentSound.womps()
        val first = hz(w, 0.10)
        val second = hz(w, 0.42 + 0.08 + 0.10)
        val third = hz(w, 0.42 + 0.08 + 0.42 + 0.08 + 0.30)
        assertTrue("$first > $second > $third", first > second && second > third)
        assertTrue("first note near 450 Hz: $first", first in 380.0..520.0)
    }

    @Test fun silenceBetweenSirenAndWomps() {
        val s = AccidentSound.samples()
        val gapStart = (2.05 * rate).toInt()
        val gapEnd = (2.20 * rate).toInt()
        assertEquals(0, (gapStart until gapEnd).maxOf { abs(s[it].toInt()) })
    }
}
