package com.portal.calendar

import kotlin.math.pow

/**
 * The celebration jingle: a fast rising chiptune arpeggio in three steps, like a video-game power-up.
 * Synthesized (an original square-wave run, not a recording), so no audio file ships.
 */
object PowerUpSound {
    const val RATE = 22_050
    private const val NOTE_SECONDS = 0.06
    private const val LOUDNESS = 0.2 // half the first version: it was too loud on the Portal

    // Each step is the same run of semitones above a root, and the root climbs: G3, then a half step, then a whole step up.
    private val RUN = listOf(0, 5, 9, 12, 17, 21, 24, 21)
    private val ROOTS = listOf(55, 56, 58) // MIDI note numbers

    private fun hz(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)

    fun samples(): ShortArray {
        val per = (NOTE_SECONDS * RATE).toInt()
        val out = ShortArray(per * RUN.size * ROOTS.size + (0.25 * RATE).toInt()) // quiet tail so the last note rings out
        var at = 0
        for (root in ROOTS) for (step in RUN) {
            val f = hz(root + step)
            for (i in 0 until per) {
                val square = if ((i * f / RATE) % 1.0 < 0.5) 1.0 else -1.0
                val env = minOf(1.0, i / (0.004 * RATE)) * (1.0 - 0.35 * i / per)
                out[at++] = (square * LOUDNESS * env * Short.MAX_VALUE).toInt().toShort()
            }
        }
        // The last note holds and fades into the tail.
        val last = per - 1
        val f = hz(ROOTS.last() + RUN.last())
        for (i in 0 until (0.25 * RATE).toInt()) {
            val square = if (((last + i) * f / RATE) % 1.0 < 0.5) 1.0 else -1.0
            out[at++] = (square * LOUDNESS * 0.6 * (1.0 - i / (0.25 * RATE)) * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    fun play() = Sfx.play(samples(), RATE)

    fun stop() = Sfx.stop()
}
