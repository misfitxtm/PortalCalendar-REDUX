package com.portal.calendar

import kotlin.math.pow

/**
 * The points-deducted sound: a little hop up, a pause, then a sad stepping-down run that ends in a long falling slide,
 * in the style of a video-game "you died" jingle. Synthesized (an original square-wave tune, not a recording).
 */
object GameOverSound {
    const val RATE = 22_050
    private const val LOUDNESS = 0.2 // same as the power-up jingle: it was too loud at 0.4

    /** One piece of the tune: slide from [from] to [to] Hz over [seconds]; from == 0 is silence. */
    private class Seg(val from: Double, val to: Double, val seconds: Double)

    private fun hz(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)
    private fun note(midi: Int, seconds: Double) = Seg(hz(midi), hz(midi), seconds)

    private val TUNE = listOf(
        note(71, 0.08), note(77, 0.08), note(77, 0.18), // the hop
        Seg(0.0, 0.0, 0.30),
        note(77, 0.13), note(76, 0.13), note(75, 0.13), note(74, 0.13), // stepping down
        note(73, 0.13), note(72, 0.13), note(71, 0.13),
        Seg(hz(70), hz(46), 0.75)) // the long fall

    fun samples(): ShortArray {
        val out = ArrayList<Short>()
        for (seg in TUNE) {
            val n = (seg.seconds * RATE).toInt()
            var phase = 0.0
            for (i in 0 until n) {
                if (seg.from == 0.0) { out.add(0); continue }
                val t = i.toDouble() / n
                phase += (seg.from + (seg.to - seg.from) * t) / RATE
                val square = if (phase % 1.0 < 0.5) 1.0 else -1.0
                val env = minOf(1.0, i / (0.004 * RATE)) * (1.0 - 0.3 * t) * minOf(1.0, (n - i) / (0.01 * RATE))
                out.add((square * LOUDNESS * env * Short.MAX_VALUE).toInt().toShort())
            }
        }
        return out.toShortArray()
    }

    fun play() = Sfx.play(samples(), RATE)

    fun stop() = Sfx.stop()
}
