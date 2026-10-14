package com.portal.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Pure date rules, kept free of Android types so plain JUnit can test them. */
object Rules {
    /** A potty streak of this many days (3 weeks) completes the goal. */
    const val POTTY_GOAL_DAYS = 21

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
    val TAB_KEYS = listOf("calendar", "chores", "lists", "meals", "routines", "potty", "shopping")
    const val SHOPPING_TAB = 6

    /**
     * Keep at least one unlocked tab on: if nothing but (or not even) Shopping is on,
     * Calendar stays on, so the board can never open straight onto the PIN-locked tab.
     */
    fun effectiveTabs(on: List<Boolean>): List<Boolean> =
        if (on.indices.any { it != SHOPPING_TAB && on[it] }) on
        else on.mapIndexed { i, v -> v && i == SHOPPING_TAB || i == 0 }

    /** The tab to open on: the wanted one if it is on and open, else the first open one. */
    fun startTab(visible: List<Boolean>, wanted: Int): Int =
        if (wanted in visible.indices && visible[wanted] && wanted != SHOPPING_TAB) wanted
        else visible.indices.firstOrNull { visible[it] && it != SHOPPING_TAB } ?: 0
}
