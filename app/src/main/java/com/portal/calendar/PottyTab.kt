package com.portal.calendar

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Optional board tab: a dry-night streak toward 3 weeks plus a month calendar.
 * Marking is behind the parent PIN ([onGate]); a wet day resets the streak.
 */
class PottyTab(
    private val ctx: Context,
    private val onGate: (action: () -> Unit) -> Unit
) {
    private var selected = ""
    private var month: YearMonth = YearMonth.now()
    val view: LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(18), dp(22), dp(16))
    }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).roundToInt()

    fun render() {
        view.removeAllViews()
        val status = JSONObject(Potty.statusJson(ctx))
        val members = status.getJSONArray("members")
        val goal = status.getInt("goal")
        val today = LocalDate.parse(status.getString("today"))

        view.addView(TextView(ctx).apply {
            text = "POTTY TRACKER"
            textSize = 13f
            setTextColor(ACCENT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.14f
        }, lp(bottom = dp(10)))

        if (members.length() == 0) {
            view.addView(note("Nobody is set up for the potty tracker.\nPick who uses it on your phone: Settings → Board tabs."))
            return
        }
        val list = (0 until members.length()).map { members.getJSONObject(it) }
        val me = list.find { it.getString("id") == selected } ?: list.first().also { selected = it.getString("id") }
        val color = Members.parse(me.getString("color"))

        if (list.size > 1) {
            val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (m in list) {
                val on = m.getString("id") == selected
                chips.addView(TextView(ctx).apply {
                    text = m.getString("name")
                    textSize = 15f
                    setTextColor(if (on) Color.WHITE else INK)
                    background = rounded(if (on) Members.parse(m.getString("color")) else PILL, 18)
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                    setOnClickListener { selected = m.getString("id"); render() }
                }, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) })
            }
            view.addView(chips, lp(bottom = dp(10)))
        }

        val portrait = ctx.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val body = LinearLayout(ctx).apply {
            orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }
        view.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))
        body.addView(streakCard(me, color, goal, today),
            if (portrait) LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) }
            else LinearLayout.LayoutParams(0, MATCH, 1f).apply { rightMargin = dp(10) })
        body.addView(calendarCard(me, today),
            if (portrait) LinearLayout.LayoutParams(MATCH, 0, 1f)
            else LinearLayout.LayoutParams(0, MATCH, 2f))
    }

    private fun streakCard(me: JSONObject, color: Int, goal: Int, today: LocalDate): LinearLayout {
        val streak = me.getInt("streak")
        val card = card()
        card.addView(TextView(ctx).apply {
            text = "🔥 $streak"
            textSize = 54f
            setTextColor(INK)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }, lp())
        card.addView(TextView(ctx).apply {
            text = if (streak == 1) "dry day in a row" else "dry days in a row"
            textSize = 15f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
        }, lp(bottom = dp(14)))

        val track = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; background = rounded(PILL, 6) }
        val frac = min(1f, streak.toFloat() / goal)
        if (frac > 0f) track.addView(android.view.View(ctx).apply { background = rounded(color, 6) },
            LinearLayout.LayoutParams(0, dp(14), frac))
        track.addView(android.view.View(ctx), LinearLayout.LayoutParams(0, dp(14), 1f - frac))
        card.addView(track, lp(bottom = dp(8)))
        card.addView(TextView(ctx).apply {
            text = if (me.getBoolean("done")) "🎉 3 weeks dry — amazing!" else "${goal - streak} more days to $goal (3 weeks)"
            textSize = 15f
            setTextColor(INK)
            gravity = Gravity.CENTER
        }, lp(bottom = dp(6)))
        card.addView(TextView(ctx).apply {
            text = "Best streak: ${me.getInt("best")}"
            textSize = 13f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
        }, lp(bottom = dp(16)))

        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(action("☀️ Dry today", DRY) { mark(me, today, "dry") },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(6) })
        row.addView(action("💧 Wet today", WET) { mark(me, today, "wet") },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(6) })
        card.addView(row, lp())
        return card
    }

    private fun calendarCard(me: JSONObject, today: LocalDate): LinearLayout {
        val days = me.getJSONObject("days")
        val card = card()
        val nav = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun arrow(t: String, delta: Long) = TextView(ctx).apply {
            text = t
            textSize = 22f
            setTextColor(INK)
            gravity = Gravity.CENTER
            minimumWidth = dp(48); minimumHeight = dp(44)
            setOnClickListener { month = month.plusMonths(delta); render() }
        }
        nav.addView(arrow("◀", -1))
        nav.addView(TextView(ctx).apply {
            text = "${month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${month.year}"
            textSize = 19f
            setTextColor(INK)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        nav.addView(arrow("▶", 1))
        card.addView(nav, lp(bottom = dp(6)))

        val wk = ConfigStore(ctx).weekStartResolved() // Calendar constant: 1 = Sunday
        val firstDow = DayOfWeek.of(if (wk == 1) 7 else wk - 1)
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 0 until 7) head.addView(TextView(ctx).apply {
            text = firstDow.plus(i.toLong()).getDisplayName(TextStyle.SHORT, Locale.getDefault())
            textSize = 12f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        card.addView(head, lp(bottom = dp(4)))

        val lead = (month.atDay(1).dayOfWeek.value - firstDow.value + 7) % 7
        val rows = (lead + month.lengthOfMonth() + 6) / 7
        for (r in 0 until rows) {
            val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until 7) {
                val dayNum = r * 7 + c - lead + 1
                val cell = TextView(ctx).apply { gravity = Gravity.CENTER }
                if (dayNum in 1..month.lengthOfMonth()) {
                    val date = month.atDay(dayNum)
                    val state = if (days.has(date.toString())) days.getBoolean(date.toString()) else null
                    val future = date.isAfter(today)
                    cell.text = when (state) { true -> "✓"; false -> "💧"; null -> "$dayNum" }
                    cell.textSize = 17f
                    cell.setTextColor(if (state != null) Color.WHITE else if (future) MUTED else INK)
                    cell.background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(when (state) { true -> DRY; false -> WET; null -> if (future) Color.TRANSPARENT else PILL })
                        if (date == today) setStroke(dp(2), ACCENT)
                    }
                    if (!future) cell.setOnClickListener {
                        // none → dry → wet → none
                        mark(me, date, when (state) { null -> "dry"; true -> "wet"; false -> "clear" })
                    }
                }
                line.addView(cell, LinearLayout.LayoutParams(0, MATCH, 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            }
            card.addView(line, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        card.addView(TextView(ctx).apply {
            text = "Tap a day to mark it: dry ✓ → wet 💧 → clear"
            textSize = 12f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
        }, lp(top = dp(6)))
        return card
    }

    private fun mark(me: JSONObject, date: LocalDate, state: String) = onGate {
        runCatching {
            Potty.mutate(ctx, JSONObject().put("action", "mark").put("memberId", me.getString("id"))
                .put("date", date.toString()).put("state", state))
        }
    }

    // ------------------------------------------------------------ helpers

    private fun card() = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(CARD, 16)
        elevation = dp(2).toFloat()
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    private fun action(label: String, color: Int, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = 16f
        setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        background = rounded(color, 18)
        minimumHeight = dp(52)
        setPadding(dp(8), dp(10), dp(8), dp(10))
        setOnClickListener { onClick() }
    }

    private fun note(t: String) = TextView(ctx).apply { text = t; textSize = 17f; setTextColor(MUTED); gravity = Gravity.CENTER }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun lp(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = top; bottomMargin = bottom }

    companion object {
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        private val DRY = Color.parseColor("#2BB3A3")
        private val WET = Color.parseColor("#4FA3E3")
        private val CARD get() = Palette.CARD
        private val PILL get() = Palette.PILL
        private val INK get() = Palette.INK
        private val MUTED get() = Palette.MUTED
        private val ACCENT get() = Palette.ACCENT
    }
}
