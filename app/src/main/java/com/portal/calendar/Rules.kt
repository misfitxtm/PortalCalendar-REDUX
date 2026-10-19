package com.portal.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Pure date rules, kept free of Android types so plain JUnit can test them. */
object Rules {
    /** A potty streak of this many days (3 weeks) completes the goal. */
    const val POTTY_GOAL_DAYS = 21

    /** The recap of last week's points shows on the first two days of a new points week, until dismissed. */
    fun recapDue(today: LocalDate, dismissedWeekStart: String?): Boolean {
        val start = pointsWeekStart(today)
        return java.time.temporal.ChronoUnit.DAYS.between(start, today) <= 1 && dismissedWeekStart != start.toString()
    }

    /** Every this-many dry days in a row earns a bonus. */
    const val DRY_BONUS_DAYS = 7
    const val DRY_BONUS_STARS = 5

    /** Is a streak of [n] dry days exactly a bonus streak (7, 14, 21, ...)? */
    fun isDryBonusStreak(n: Int) = n > 0 && n % DRY_BONUS_DAYS == 0

    /**
     * The days on which a full block of 7 straight dry days is completed (the 7th, 14th, 21st ... day of a run).
     * Marks: true = dry, false = wet; a wet day or a gap ends the run.
     */
    fun dryBonusDates(marks: Map<LocalDate, Boolean>): List<LocalDate> {
        val out = ArrayList<LocalDate>()
        var run = 0
        var prev: LocalDate? = null
        for ((d, dry) in marks.toSortedMap()) {
            run = if (!dry) 0 else if (prev != null && d == prev.plusDays(1) && run > 0) run + 1 else 1
            if (dry) prev = d else prev = null
            if (isDryBonusStreak(run)) out.add(d)
        }
        return out
    }

    private val FILLER_WORDS = setOf("the", "a", "an", "my", "your", "our", "up")

    private fun words(s: String): String = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.isNotEmpty() && it !in FILLER_WORDS }.joinToString(" ")

    private fun edits(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1).also { it[0] = i }
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    /** Do two task names mean the same thing? ("Make bed" and "Make the bed"; small typos allowed.) */
    fun sameTask(a: String, b: String): Boolean {
        val x = words(a)
        val y = words(b)
        if (x.isEmpty() || y.isEmpty()) return false
        return x == y || edits(x, y) <= if (maxOf(x.length, y.length) <= 8) 1 else 2
    }

    /** Get Ready follows the clock: morning until noon, afternoon until 5 pm, then evening. */
    fun sectionForHour(hour: Int): String = when {
        hour < 12 -> "morning"
        hour < 17 -> "afternoon"
        else -> "evening"
    }

    /** Is [date] in the same Friday-to-Thursday week as [today], and not in the future? */
    fun inPointsWeek(date: LocalDate, today: LocalDate): Boolean =
        !date.isBefore(pointsWeekStart(today)) && !date.isAfter(today)

    /** The Reward Shop opens only on Thursday, the last day of the Friday-to-Thursday week, so penalties land before spending. */
    fun shopOpen(day: LocalDate) = day.dayOfWeek == DayOfWeek.THURSDAY

    /** Which alternating week [day] falls in: "A" or "B", flipping every Friday. */
    fun weekSlot(day: LocalDate): String =
        if (java.time.temporal.ChronoUnit.WEEKS.between(LocalDate.of(2026, 1, 2), pointsWeekStart(day)) % 2 == 0L) "A" else "B"

    /** Is a person on the board on [day]? [schedule] is "" (every week), "A" or "B". */
    fun onSchedule(schedule: String, day: LocalDate) = schedule.isEmpty() || schedule == weekSlot(day)

    /** Small wins on the way to the 3-week goal; the last one is the goal itself. */
    val POTTY_MILESTONES = listOf(3, 7, 14, POTTY_GOAL_DAYS)

    /** The biggest milestone crossed by going from [before] to [after] dry days, if any. */
    fun pottyMilestoneReached(before: Int, after: Int): Int? =
        POTTY_MILESTONES.lastOrNull { before < it && after >= it }

    fun nextPottyMilestone(streak: Int): Int? = POTTY_MILESTONES.firstOrNull { it > streak }

    // The Portal runs Android 9/10, whose emoji font predates these; they draw as an empty box.
    private val ICON_FALLBACK = mapOf("🫧" to "🧼", "🪴" to "🌱")

    /** An icon the Portal can draw: newer emoji are swapped for an older look-alike. */
    fun safeIcon(icon: String): String = ICON_FALLBACK[icon] ?: icon

    /** Chore points reset every Friday: the week runs Friday through Thursday. */
    fun pointsWeekStart(day: LocalDate): LocalDate =
        day.with(TemporalAdjusters.previousOrSame(DayOfWeek.FRIDAY))

    /**
     * Consecutive dry days ending today. A not-yet-marked today doesn't break the
     * streak, but a wet day or an unmarked earlier day does. Marks: true = dry, false = wet.
     */
    fun pottyStreak(marks: Map<LocalDate, Boolean>, today: LocalDate): Int {
        var d = if (marks[today] == null) today.minusDays(1) else today
        var n = 0
        while (marks[d] == true) { n++; d = d.minusDays(1) }
        return n
    }

    /** Longest run of consecutive dry days ever recorded. */
    fun pottyBest(marks: Map<LocalDate, Boolean>): Int {
        var best = 0
        var run = 0
        var prev: LocalDate? = null
        for ((d, dry) in marks.toSortedMap()) {
            run = if (dry) (if (prev != null && d == prev.plusDays(1) && run > 0) run + 1 else 1) else 0
            if (dry) prev = d
            best = maxOf(best, run)
        }
        return best
    }

    /** Board tabs in display order; the index is the tab number. */
    val TAB_KEYS = listOf("calendar", "chores", "lists", "meals", "routines", "potty", "shopping", "rewards")
    const val SHOPPING_TAB = 6
    const val REWARDS_TAB = 7

    /** What each tab card says, in [TAB_KEYS] order. */
    val TAB_LABELS = listOf("📅 Calendar", "✅ Chores", "📝 Lists", "🍽️ Meals", "⏰ Get Ready", "🚽 Pee Tracker", "🛒 Shopping", "💰 Reward Shop 💰")

    /** A saved tab order made safe: unknown names dropped, repeats removed, anything missing added at the end. */
    fun normalizeTabOrder(saved: List<String>): List<String> {
        val kept = saved.filter { it in TAB_KEYS }.distinct()
        return kept + TAB_KEYS.filter { it !in kept }
    }

    /**
     * Keep at least one unlocked tab on: if nothing but (or not even) Shopping is on,
     * Calendar stays on, so the board can never open straight onto the PIN-locked tab.
     */
    fun effectiveTabs(on: List<Boolean>): List<Boolean> =
        if (on.indices.any { it != SHOPPING_TAB && on[it] }) on
        else on.mapIndexed { i, v -> v && i == SHOPPING_TAB || i == 0 }

    /** The tab to open on: the wanted one if it is on and open, else the first open one. */
    fun startTab(visible: List<Boolean>, wanted: Int, order: List<Int> = visible.indices.toList()): Int =
        if (wanted in visible.indices && visible[wanted] && wanted != SHOPPING_TAB) wanted
        else order.firstOrNull { it in visible.indices && visible[it] && it != SHOPPING_TAB } ?: 0
}
