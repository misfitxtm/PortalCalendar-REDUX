package com.portal.calendar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Original cartoon pictures for the seven Bristol stool types, drawn in code so no artwork is bundled. */
class StoolIcon(ctx: Context, private val type: Int) : View(ctx) {
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8D5A2B") }
    private val light = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#B07A44") }
    private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4E2F14"); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }

    // Everything is laid out on a 100 x 60 canvas and scaled to the view.
    override fun onDraw(c: Canvas) {
        val s = minOf(width / 100f, height / 60f)
        c.save()
        c.translate((width - 100 * s) / 2f, (height - 60 * s) / 2f)
        c.scale(s, s)
        dark.strokeWidth = 2.5f
        when (type) {
            1 -> for ((x, y, r) in listOf(Triple(18f, 30f, 8f), Triple(36f, 18f, 7f), Triple(40f, 42f, 8f), Triple(62f, 28f, 8f), Triple(82f, 16f, 7f), Triple(80f, 44f, 8f)))
                { c.drawCircle(x, y, r, body); c.drawCircle(x - r / 3, y - r / 3, r / 3, light) } // separate hard pellets
            2 -> for ((x, r) in listOf(14f to 12f, 30f to 15f, 48f to 13f, 66f to 16f, 84f to 12f)) c.drawCircle(x, 30f, r, body) // lumpy log
            3 -> { c.drawRoundRect(RectF(8f, 14f, 92f, 46f), 16f, 16f, body)
                for (x in listOf(28f, 48f, 66f)) c.drawLine(x, 16f, x + 4f, 30f, dark) } // log with cracks
            4 -> { c.drawRoundRect(RectF(8f, 16f, 92f, 44f), 14f, 14f, body)
                c.drawRoundRect(RectF(18f, 21f, 80f, 27f), 3f, 3f, light) } // smooth and shiny
            5 -> for ((x, y, w, h) in listOf(listOf(8f, 20f, 36f, 40f), listOf(36f, 10f, 66f, 40f), listOf(62f, 26f, 94f, 52f), listOf(30f, 32f, 56f, 54f)))
                c.drawOval(RectF(x, y, w, h), body) // soft blobs with clear edges
            6 -> { for ((x, y) in listOf(22f to 36f, 38f to 30f, 54f to 33f, 70f to 36f, 32f to 44f, 50f to 44f, 62f to 26f)) c.drawCircle(x, y, 11f, body)
                c.drawRoundRect(RectF(14f, 38f, 82f, 52f), 6f, 6f, body) } // mushy, ragged edges
            else -> { c.drawOval(RectF(6f, 36f, 94f, 54f), body)
                c.drawCircle(30f, 24f, 4f, body); c.drawCircle(56f, 18f, 3.5f, body); c.drawCircle(76f, 26f, 4f, body) } // runny puddle with drops
        }
        c.restore()
    }
}
