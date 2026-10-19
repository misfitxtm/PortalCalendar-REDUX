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

    /** Is [date] in the same points week as [today], and not in the future? */
    fun inPointsWeek(date: LocalDate, today: LocalDate): Boolean =
        !date.isBefore(pointsWeekStart(today)) && !date.isAfter(today)

    /** The Reward Shop is open only on its chosen days (by default the last day of the week), so penalties land before spending. */
    fun shopOpen(day: LocalDate) = day.dayOfWeek in openDays()

    /** Which alternating week [day] falls in: "A" or "B", flipping when each week starts. */
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

    /** Daily bathroom habits a kid can tick; effort, not outcomes. */
    val EFFORT_ITEMS = listOf("morning", "meal", "bedtime", "water", "told")
    val EFFORT_LABELS = listOf("🌅 Morning sit", "🍽️ Sat after a meal", "🌙 Peed before bed", "💧 Drank water", "📣 Told a grown-up early")

    /** Picture-first versions for a child who can't read yet: a big picture and one or two short words. */
    val EFFORT_PICTURES = listOf("🌅🚽", "🍽️🚽", "🌙🚽", "🥛", "🙋")
    val EFFORT_SHORT = listOf("Morning", "After meal", "Bed time", "Water", "I told")

    /** Kid-chart names under the drawn pictures ([StoolIcon]): 4 is the ideal. */
    val STOOL_NAMES = listOf("1 Pellets", "2 Lumpy", "3 Cracked", "4 Smooth", "5 Blobs", "6 Mushy", "7 Runny")

    /** This many habits ticked in a day earns one bonus star. */
    const val EFFORT_BONUS_NEEDED = 3

    fun effortBonus(done: Int) = done >= EFFORT_BONUS_NEEDED

    /** A parent heads-up once nobody has pooped for this many days. */
    const val NO_POOP_ALERT_DAYS = 3

    /** Days since the last poop, or null when none is recorded. */
    fun daysSincePoop(dates: Collection<LocalDate>, today: LocalDate): Int? =
        dates.maxOrNull()?.let { java.time.temporal.ChronoUnit.DAYS.between(it, today).toInt().coerceAtLeast(0) }

    /** Longest run of days with no poop between [from] and [to]. */
    fun longestPoopGap(dates: Collection<LocalDate>, from: LocalDate, to: LocalDate): Int {
        val pts = listOf(from.minusDays(1)) + dates.filter { !it.isBefore(from) && !it.isAfter(to) }.sorted() + listOf(to.plusDays(1))
        return pts.zipWithNext { a, b -> (java.time.temporal.ChronoUnit.DAYS.between(a, b) - 1).toInt() }.max()
    }

    /** Bristol stool type 1-7 in words: 1-2 hard, 3-4 normal, 5-7 soft to watery. */
    val STOOL_LABELS = listOf("1 Hard lumps", "2 Lumpy log", "3 Cracked log", "4 Smooth log", "5 Soft blobs", "6 Mushy", "7 Watery")

    // The Portal runs Android 9/10, whose emoji font predates these; they draw as an empty box.
    private val ICON_FALLBACK = mapOf("🫧" to "🧼", "🪴" to "🌱")

    /** An icon the Portal can draw: newer emoji are swapped for an older look-alike. */
    fun safeIcon(icon: String): String = ICON_FALLBACK[icon] ?: icon

    /** First day of the points week (Friday unless the family changed it). Set from [ConfigStore]. */
    @Volatile var weekStartDay: DayOfWeek = DayOfWeek.FRIDAY

    /** Days the Reward Shop is open; empty means the last day of the week. Set from [ConfigStore]. */
    @Volatile var shopDays: Set<DayOfWeek> = emptySet()

    /** Chore points reset when each week starts. */
    fun pointsWeekStart(day: LocalDate): LocalDate =
        day.with(TemporalAdjusters.previousOrSame(weekStartDay))

    fun dayName(d: DayOfWeek): String = d.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)

    private fun openDays(): Set<DayOfWeek> = shopDays.ifEmpty { setOf(weekStartDay.minus(1)) }

    /** "Thursday", "Monday and Friday", or "every day". */
    fun shopDaysText(): String = openDays().let { d ->
        if (d.size == 7) "every day" else d.sorted().joinToString(" and ") { dayName(it) }
    }

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
    val TAB_LABELS = listOf("📅 Calendar", "✅ Chores", "📝 Lists", "🍽️ Meals", "⏰ Get Ready", "🚽 Potty Tracker", "🛒 Shopping", "💰 Reward Shop 💰")

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
