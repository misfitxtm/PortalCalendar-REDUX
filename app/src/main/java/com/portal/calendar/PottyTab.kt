package com.portal.calendar

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
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
    /** Fired when a recent day is marked dry; [milestone] when that reached 3, 7, 14 or 21 days. */
    /** Shows a view over the whole screen; null hides it again. */
    private val onOverlay: (android.view.View?) -> Unit = {},
    private val onCelebrate: (name: String, streak: Int, milestone: Boolean) -> Unit = { _, _, _ -> },
    /** Asks "are you sure?" (and the parent PIN) before something drastic; runs [action] if confirmed. */
    private val onConfirm: (title: String, message: String, button: String, action: () -> Unit) -> Unit = { _, _, _, a -> a() },
    private val onGate: (action: () -> Unit) -> Unit
) {
    private var selected = ""
    private var month: YearMonth = YearMonth.now()
    private var showSummary = false
    private var poopOpen = false
    private var pStool = 0
    private var pPain = false
    private var pLeak = false
    private var pSoiled = false
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

        val titleRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(ctx).apply {
            text = "🚽 POTTY TRACKER"
            textSize = 13f
            setTextColor(ACCENT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.14f
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        titleRow.addView(TextView(ctx).apply {
            text = if (showSummary) "📅 Calendar" else "📊 Summary"
            textSize = 14f
            setTextColor(INK)
            background = rounded(PILL, 16)
            setPadding(dp(14), dp(7), dp(14), dp(7))
            setOnClickListener { showSummary = !showSummary; render() }
        })
        view.addView(titleRow, lp(bottom = dp(10)))

        if (members.length() == 0) {
            view.addView(note("Nobody is set up for the potty tracker.\nPick who uses it on your phone: Settings → Board tabs."))
            return
        }
        val list = (0 until members.length()).map { members.getJSONObject(it) }.filter { Members.onSchedule(ctx, it.getString("id")) }
        if (list.isEmpty()) {
            view.addView(note("Nobody on the potty tracker is at this house this week."))
            return
        }
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
        val left = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        left.addView(todayCard(me, today), lp(bottom = dp(10))) // the picture card first: a child can reach it without scrolling
        left.addView(streakCard(me, color, goal, today), lp())
        body.addView(ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(left) },
            if (portrait) LinearLayout.LayoutParams(MATCH, 0, 1f).apply { bottomMargin = dp(10) }
            else LinearLayout.LayoutParams(0, MATCH, 1f).apply { rightMargin = dp(10) })
        body.addView(if (showSummary) summaryCard(me) else calendarCard(me, today),
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
        // Milestone chips: filled once earned.
        val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        for (m in Rules.POTTY_MILESTONES) chips.addView(TextView(ctx).apply {
            val earned = streak >= m
            text = if (earned) "⭐ $m" else "$m"
            textSize = 13f
            setTextColor(if (earned) Color.WHITE else MUTED)
            background = rounded(if (earned) color else PILL, 14)
            setPadding(dp(10), dp(5), dp(10), dp(5))
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(dp(3), 0, dp(3), 0) })
        card.addView(chips, lp(bottom = dp(4)))
        card.addView(TextView(ctx).apply {
            val toGo = Rules.DRY_BONUS_DAYS - streak % Rules.DRY_BONUS_DAYS
            text = "⭐ +${Rules.DRY_BONUS_STARS} bonus stars every ${Rules.DRY_BONUS_DAYS} dry days" + " · $toGo to go"
            textSize = 12f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
        }, lp(bottom = dp(8)))
        card.addView(TextView(ctx).apply {
            val next = Rules.nextPottyMilestone(streak)
            text = if (next == null) "🎉 3 weeks dry — amazing!"
                   else "Next: $next days · ${next - streak} to go" + if (next == goal) " (3 weeks)" else ""
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
        row.addView(action("🌙 Dry night", DRY) { mark(me, today, "dry") },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(6) })
        row.addView(action("💧 Wet night", WET) { mark(me, today, "wet") },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(6) })
        card.addView(row, lp())
        // Drastic, so it asks first: resets the streak, this week's marks and this week's points.
        card.addView(TextView(ctx).apply {
            text = "😠 Accident while awake"
            textSize = 15f
            setTextColor(OOPS)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setStroke(dp(2), OOPS) }
            minimumHeight = dp(48)
            setPadding(dp(8), dp(10), dp(8), dp(10))
            setOnClickListener {
                val n = me.getString("name")
                onConfirm("Accident while awake?",
                    "This starts $n's whole week over: the dry-night streak goes to 0, this week's marks are cleared, and this week's chore points go to 0.",
                    "Reset the week") { markNow(me, today, "awake") }
            }
        }, lp(top = dp(10)))
        return card
    }

    private fun calendarCard(me: JSONObject, today: LocalDate): LinearLayout {
        val days = me.getJSONObject("days")
        val poopDays = me.getJSONArray("poop").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("date") }.toSet() }
        val awakeDays = me.getJSONArray("awake").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
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
                    val oops = awakeDays.contains(date.toString())
                    val future = date.isAfter(today)
                    cell.text = (if (oops) "😠" else when (state) { true -> "✓"; false -> "💧"; null -> "$dayNum" }) +
                        if (date.toString() in poopDays) "\n💩" else ""
                    cell.textSize = if (date.toString() in poopDays) 13f else 17f
                    cell.setTextColor(if (state != null) Color.WHITE else if (future) MUTED else INK)
                    cell.background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(if (oops) OOPS else when (state) { true -> DRY; false -> WET; null -> if (future) Color.TRANSPARENT else PILL })
                        if (date == today) setStroke(dp(2), ACCENT)
                    }
                    if (!future) cell.setOnClickListener {
                        // none → dry → wet → none (an awake accident is cleared with one tap)
                        mark(me, date, if (oops) "clear" else when (state) { null -> "dry"; true -> "wet"; false -> "clear" })
                    }
                }
                line.addView(cell, LinearLayout.LayoutParams(0, MATCH, 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            }
            card.addView(line, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        card.addView(TextView(ctx).apply {
            text = "Tap a day to mark it: dry ✓ → wet 💧 → clear.  😠 = accident while awake  💩 = went poop"
            textSize = 12f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
        }, lp(top = dp(6)))
        return card
    }

    private fun mark(me: JSONObject, date: LocalDate, state: String) = onGate { markNow(me, date, state) }

    /** Applies a mark; the caller has already cleared the PIN (and any confirmation). */
    private fun poopSave(me: JSONObject, date: LocalDate, state: String) {
        runCatching {
            Potty.mutate(ctx, JSONObject().put("action", "poop").put("memberId", me.getString("id"))
                .put("date", date.toString()).put("state", state).put("stool", pStool)
                .put("pain", pPain).put("leak", pLeak).put("soiled", pSoiled))
        }
    }

    private fun poop(me: JSONObject, date: LocalDate, state: String) = onGate {
        poopSave(me, date, state)
        poopOpen = false
        render()
    }

    /** First tap of the day: the poop is marked at once, then a full-screen chart asks what it looked like. */
    private fun markPoopAndAsk(me: JSONObject, today: LocalDate) = onGate {
        pStool = 0; pPain = false; pLeak = false; pSoiled = false
        poopSave(me, today, "yes")
        render()
        showChart(me, today)
    }

    private fun showChart(me: JSONObject, today: LocalDate) {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(28), dp(18), dp(28), dp(18)) }
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(ctx).apply { text = "💩 ➜ 🤔"; textSize = 38f; gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(TextView(ctx).apply {
            text = "✖"
            textSize = 26f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            minimumWidth = dp(56); minimumHeight = dp(56)
            setOnClickListener { onOverlay(null) } // closes without details; the poop stays marked
        })
        col.addView(top, lp(bottom = dp(8)))
        fun margins() = LinearLayout.LayoutParams(0, MATCH, 1f).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) }
        for ((range, weight) in listOf(0..3 to 3f, 4..6 to 3f)) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in range) row.addView(tile(null, Rules.STOOL_NAMES[i], pStool == i + 1,
                when (i) { 0, 1 -> HARD; 2, 3 -> DRY; else -> WET }, StoolIcon(ctx, i + 1), scale = 1.9f) { pStool = i + 1; showChart(me, today) }, margins())
            col.addView(row, LinearLayout.LayoutParams(MATCH, 0, weight))
        }
        val flags = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        flags.addView(tile("😣", "Ouch", pPain, OOPS, scale = 1.4f) { pPain = !pPain; showChart(me, today) }, margins())
        flags.addView(tile("💦", "Leak", pLeak, OOPS, scale = 1.4f) { pLeak = !pLeak; showChart(me, today) }, margins())
        flags.addView(tile("👖", "In pants", pSoiled, OOPS, scale = 1.4f) { pSoiled = !pSoiled; showChart(me, today) }, margins())
        col.addView(flags, LinearLayout.LayoutParams(MATCH, 0, 2f))
        col.addView(action("✅ Done", DRY) {
            poopSave(me, today, "yes")
            onOverlay(null)
            render()
        }.apply { textSize = 24f; minimumHeight = dp(64) }, lp(top = dp(8)))
        onOverlay(col)
    }

    private fun effort(me: JSONObject, item: String, on: Boolean) = onGate {
        runCatching {
            Potty.mutate(ctx, JSONObject().put("action", "effort").put("memberId", me.getString("id"))
                .put("item", item).put("state", if (on) "yes" else "clear"))
        }
        render()
    }

    private fun todayCard(me: JSONObject, today: LocalDate): LinearLayout {
        val card = card()
        card.addView(TextView(ctx).apply {
            text = "⭐ Today"
            textSize = 20f
            setTextColor(INK)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, lp())
        val done = me.getJSONArray("effort").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        card.addView(TextView(ctx).apply {
            text = "⭐".repeat(minOf(done.size, Rules.EFFORT_BONUS_NEEDED)) + "☆".repeat(maxOf(Rules.EFFORT_BONUS_NEEDED - done.size, 0)) +
                "   (${Rules.EFFORT_BONUS_NEEDED} habits = ⭐ +1)"
            textSize = 20f
            setTextColor(MUTED)
        }, lp(bottom = dp(8)))
        // Big picture tiles, two across: a 6-year-old can tap them without reading.
        for (rowStart in Rules.EFFORT_ITEMS.indices step 2) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in rowStart until minOf(rowStart + 2, Rules.EFFORT_ITEMS.size)) {
                val key = Rules.EFFORT_ITEMS[i]
                val on = key in done
                row.addView(tile(Rules.EFFORT_PICTURES[i], Rules.EFFORT_SHORT[i], on, DRY) { effort(me, key, !on) },
                    LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            }
            card.addView(row, lp())
        }

        // Poop: a heads-up when it has been a while, then today's entry.
        val since = if (me.isNull("daysSincePoop")) null else me.getInt("daysSincePoop")
        val entry = me.getJSONArray("poop").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.getString("date") == today.toString() } }
        if (since != null && since >= Rules.NO_POOP_ALERT_DAYS) card.addView(TextView(ctx).apply {
            text = "⚠️ No poop in $since days. Water, fiber and a sit after meals help; call the doctor if it hurts or goes on."
            textSize = 13f
            setTextColor(Color.WHITE)
            background = rounded(OOPS, 12)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }, lp(top = dp(6), bottom = dp(6)))
        else if (since != null) card.addView(TextView(ctx).apply {
            text = "Last poop: " + when (since) { 0 -> "today"; 1 -> "yesterday"; else -> "$since days ago" }
            textSize = 12f
            setTextColor(MUTED)
        }, lp(top = dp(6), bottom = dp(2)))
        card.addView(action(if (entry != null) "💩 Pooped today ✓" else "💩 Pooped today?", POOP) {
            if (entry == null) markPoopAndAsk(me, today)
            else {
                poopOpen = !poopOpen
                if (poopOpen) {
                    pStool = entry.optInt("stool"); pPain = entry.optBoolean("pain")
                    pLeak = entry.optBoolean("leak"); pSoiled = entry.optBoolean("soiled")
                }
                render()
            }
        }, lp(top = dp(4)))
        if (poopOpen) {
            card.addView(TextView(ctx).apply { text = "💩 ➜ 🤔"; textSize = 26f; gravity = Gravity.CENTER }, lp(top = dp(8), bottom = dp(2)))
            for (rowStart in listOf(0, 4)) {
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                for (i in rowStart until minOf(rowStart + 4, 7)) {
                    // Orange = hard, green = just right (🍌), blue = soft or runny.
                    val tone = when (i) { 0, 1 -> HARD; 2, 3 -> DRY; else -> WET }
                    row.addView(tile(null, Rules.STOOL_NAMES[i], pStool == i + 1, tone, StoolIcon(ctx, i + 1)) { pStool = i + 1; render() },
                        LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
                }
                card.addView(row, lp())
            }
            val flags = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            flags.addView(tile("😣", "Ouch", pPain, OOPS) { pPain = !pPain; render() }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            flags.addView(tile("💦", "Leak", pLeak, OOPS) { pLeak = !pLeak; render() }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            flags.addView(tile("👖", "In pants", pSoiled, OOPS) { pSoiled = !pSoiled; render() }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            card.addView(flags, lp())
            val btns = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            btns.addView(action("✅ Done", DRY) { poop(me, today, "yes") }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(6) })
            if (entry != null) btns.addView(action("✖️ Undo", WET) { poop(me, today, "clear") }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(6) })
            card.addView(btns, lp(top = dp(8)))
        }
        return card
    }

    /** A big tappable picture with a tiny caption; filled with [tone] when chosen. */
    private fun tile(picture: String?, caption: String, on: Boolean, tone: Int, icon: android.view.View? = null, scale: Float = 1f, onClick: () -> Unit) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = if (on) rounded(tone, 16) else GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(PILL); setStroke(dp(2), tone) }
        minimumHeight = dp((78 * scale).toInt())
        setPadding(dp(4), dp(8), dp(4), dp(6))
        if (icon != null) addView(icon, LinearLayout.LayoutParams(dp((64 * scale).toInt()), dp((40 * scale).toInt())))
        else addView(TextView(ctx).apply { text = picture + if (on) "✔" else ""; textSize = 30f * scale; gravity = Gravity.CENTER })
        addView(TextView(ctx).apply { text = caption; textSize = 12f * scale; setTextColor(if (on) Color.WHITE else INK); gravity = Gravity.CENTER; maxLines = 1 })
        setOnClickListener { onClick() }
    }

    private fun summaryCard(me: JSONObject): LinearLayout {
        val s = me.getJSONObject("summary")
        val card = card()
        card.addView(TextView(ctx).apply {
            text = "📊 Last ${s.getInt("days")} days · ${me.getString("name")}"
            textSize = 19f
            setTextColor(INK)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, lp(bottom = dp(10)))
        val avg = if (s.isNull("avgStool")) "none logged" else "${s.getDouble("avgStool")} (hard ${s.getInt("hard")} · loose ${s.getInt("loose")})"
        val lines = listOf(
            "🌙 Nights: ${s.getInt("nightsDry")} dry · ${s.getInt("nightsWet")} wet",
            "😠 Accidents while awake: ${s.getInt("dayAccidents")}",
            "💩 Days with a poop: ${s.getInt("poopDays")} · longest gap ${s.getInt("longestNoPoop")} days",
            "Stool type (1-7, 4 is ideal): $avg",
            "😣 Painful: ${s.getInt("painful")} · 💦 Leaks: ${s.getInt("leaks")} · 🩲 Soiled: ${s.getInt("soiled")}",
            "✅ Days with ${Rules.EFFORT_BONUS_NEEDED}+ habits ticked: ${s.getInt("habitDays")}")
        for (l in lines) card.addView(TextView(ctx).apply { text = l; textSize = 16f; setTextColor(INK) }, lp(bottom = dp(8)))
        card.addView(TextView(ctx).apply {
            text = "Only what has been logged. Bring this to the pediatrician; the setup page can print it."
            textSize = 12f
            setTextColor(MUTED)
        }, lp(top = dp(4)))
        return card
    }

    private fun markNow(me: JSONObject, date: LocalDate, state: String) {
        val before = me.getInt("streak")
        runCatching {
            Potty.mutate(ctx, JSONObject().put("action", "mark").put("memberId", me.getString("id"))
                .put("date", date.toString()).put("state", state))
        }.onSuccess { status ->
            val after = JSONObject(status).getJSONArray("members").let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.getString("id") == me.getString("id") }
            }?.getInt("streak") ?: return@onSuccess
            val recent = !date.isBefore(LocalDate.now().minusDays(1)) // back-filling an old day isn't worth a party
            if (state == "dry" && recent) onCelebrate(me.optString("name"), after, Rules.pottyMilestoneReached(before, after) != null)
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
        private val OOPS = Color.parseColor("#D9453D")
        private val POOP = Color.parseColor("#8D6E63")
        private val HARD = Color.parseColor("#E8943A")
        private val CARD get() = Palette.CARD
        private val PILL get() = Palette.PILL
        private val INK get() = Palette.INK
        private val MUTED get() = Palette.MUTED
        private val ACCENT get() = Palette.ACCENT
    }
}
