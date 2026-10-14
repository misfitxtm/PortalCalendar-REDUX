package com.portal.calendar

import android.content.Context
import kotlin.math.PI
import kotlin.math.sin

/**
 * The accident reaction's sound: a siren, then three falling "womp" notes (a sad trombone). Synthesized here, so there are no
 * audio files to ship. [samples] is pure so tests can check its shape; [play] hands it to the speaker.
 */
object AccidentSound {
    const val RATE = 22_050
    private const val SIREN_SECONDS = 2.0
    private const val GAP_SECONDS = 0.25

    // Start and end pitch of each "womp" (Hz) and its length: three notes stepping down, the last one long.
    private val WOMPS = listOf(Triple(466.0, 440.0, 0.42), Triple(440.0, 415.0, 0.42), Triple(415.0, 349.0, 1.0))

    private const val LOUDNESS = 0.55 // of full scale: loud enough to get attention, not to hurt

    private fun tone(phase: Double) = sin(phase) + 0.30 * sin(2 * phase) + 0.12 * sin(3 * phase)

    /** Up-and-down wail between ~650 and ~1250 Hz, two full swings. */
    fun siren(): ShortArray {
        val n = (SIREN_SECONDS * RATE).toInt()
        val out = ShortArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / RATE
            val sweep = 0.5 - 0.5 * kotlin.math.cos(2 * PI * t / 1.0) // 0 -> 1 -> 0 each second
            val hz = 650.0 + 600.0 * sweep
            phase += 2 * PI * hz / RATE
            val fade = minOf(1.0, t / 0.05) * minOf(1.0, (SIREN_SECONDS - t) / 0.08)
            out[i] = (tone(phase) / 1.4 * LOUDNESS * fade * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    /** "womp womp womp": each note slides down a little, with a quick attack and a decaying body; the last one wobbles. */
    fun womps(): ShortArray {
        val out = ArrayList<Short>()
        for ((index, w) in WOMPS.withIndex()) {
            val (from, to, seconds) = w
            val n = (seconds * RATE).toInt()
            var phase = 0.0
            for (i in 0 until n) {
                val t = i.toDouble() / n
                val vibrato = if (index == WOMPS.lastIndex) 1.0 + 0.025 * sin(2 * PI * 6.0 * i / RATE) else 1.0
                val hz = (from + (to - from) * t) * vibrato
                phase += 2 * PI * hz / RATE
                val env = minOf(1.0, i / (0.012 * RATE)) * (1.0 - 0.55 * t) * minOf(1.0, (n - i) / (0.03 * RATE))
                out.add((tone(phase) / 1.4 * LOUDNESS * env * Short.MAX_VALUE).toInt().toShort())
            }
            repeat((0.08 * RATE).toInt()) { out.add(0) }
        }
        return out.toShortArray()
    }

    fun samples(): ShortArray = siren() + ShortArray((GAP_SECONDS * RATE).toInt()) + womps()

    fun play(@Suppress("UNUSED_PARAMETER") ctx: Context) = Sfx.play(samples(), RATE)

    fun stop() = Sfx.stop()
}
