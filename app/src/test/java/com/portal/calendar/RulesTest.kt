package com.portal.calendar

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class RulesTest {
    private fun d(s: String) = LocalDate.parse(s)

    @Test fun pointsWeekStartsOnFriday() {
        assertEquals(d("2026-10-09"), Rules.pointsWeekStart(d("2026-10-09"))) // Fri
        assertEquals(d("2026-10-09"), Rules.pointsWeekStart(d("2026-10-15"))) // Thu, last day of the week
        assertEquals(d("2026-10-16"), Rules.pointsWeekStart(d("2026-10-16"))) // next Fri resets
        assertEquals(d("2026-10-09"), Rules.pointsWeekStart(d("2026-10-11"))) // Sun
    }

    private fun run(vararg dry: Boolean, start: String = "2026-10-01") =
        dry.mapIndexed { i, v -> d(start).plusDays(i.toLong()) to v }.toMap()

    @Test fun streakCountsDryDaysUpToToday() {
        val m = run(true, true, true) // Oct 1-3
        assertEquals(3, Rules.pottyStreak(m, d("2026-10-03")))
    }

    @Test fun unmarkedTodayDoesNotBreakStreak() {
        val m = run(true, true, true)
        assertEquals(3, Rules.pottyStreak(m, d("2026-10-04")))
    }

    @Test fun wetDayResetsTheClock() {
        val m = run(true, true, false, true, true) // wet on Oct 3
        assertEquals(2, Rules.pottyStreak(m, d("2026-10-05")))
        assertEquals(0, Rules.pottyStreak(run(true, true, false), d("2026-10-03")))
    }

    @Test fun unmarkedEarlierDayBreaksStreak() {
        val m = mapOf(d("2026-10-01") to true, d("2026-10-03") to true) // Oct 2 missing
        assertEquals(1, Rules.pottyStreak(m, d("2026-10-03")))
    }

    @Test fun twentyOneDaysReachesTheGoal() {
        val m = (0 until Rules.POTTY_GOAL_DAYS).associate { d("2026-10-01").plusDays(it.toLong()) to true }
        assertEquals(21, Rules.pottyStreak(m, d("2026-10-21")))
    }

    @Test fun bestStreakSurvivesAReset() {
        val m = run(true, true, true, true, false, true, true)
        assertEquals(4, Rules.pottyBest(m))
        assertEquals(2, Rules.pottyStreak(m, d("2026-10-07")))
    }

    private fun tabs(vararg on: Int) = List(7) { it in on }

    @Test fun allTabsOffFallsBackToCalendar() {
        assertEquals(tabs(0), Rules.effectiveTabs(tabs()))
        assertEquals(tabs(1, 5), Rules.effectiveTabs(tabs(1, 5)))
    }

    @Test fun shoppingAloneIsNotEnough() {
        assertEquals(tabs(0, 6), Rules.effectiveTabs(tabs(6))) // calendar added, shopping kept
    }

    @Test fun startTabUsesWantedWhenOn() {
        assertEquals(1, Rules.startTab(tabs(0, 1, 2), wanted = 1))
    }

    @Test fun startTabFallsBackWhenWantedIsOff() {
        assertEquals(1, Rules.startTab(tabs(1, 2), wanted = 0)) // calendar off -> first on
        assertEquals(0, Rules.startTab(tabs(0, 1), wanted = 99))
    }

    @Test fun shoppingIsNeverTheStartTab() {
        assertEquals(1, Rules.startTab(tabs(1, 6), wanted = 6))
        assertEquals(0, Rules.startTab(Rules.effectiveTabs(tabs(6)), wanted = 6))
    }
}
