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

    @Test fun bonusDatesAreEverySeventhStraightDryDay() {
        val seven = (0 until 7).associate { d("2026-10-01").plusDays(it.toLong()) to true }
        assertEquals(listOf(d("2026-10-07")), Rules.dryBonusDates(seven))
        val fifteen = (0 until 15).associate { d("2026-10-01").plusDays(it.toLong()) to true }
        assertEquals(listOf(d("2026-10-07"), d("2026-10-14")), Rules.dryBonusDates(fifteen))
        assertEquals(emptyList<LocalDate>(), Rules.dryBonusDates((0 until 6).associate { d("2026-10-01").plusDays(it.toLong()) to true }))
    }

    @Test fun aWetDayOrAGapRestartsTheCountForTheBonus() {
        val wet = (0 until 10).associate { d("2026-10-01").plusDays(it.toLong()) to (it != 4) } // wet on the 5th
        assertEquals(emptyList<LocalDate>(), Rules.dryBonusDates(wet)) // 4 dry, then 5 dry: never 7 in a row
        val gap = (0 until 8).filter { it != 3 }.associate { d("2026-10-01").plusDays(it.toLong()) to true } // a missing day
        assertEquals(emptyList<LocalDate>(), Rules.dryBonusDates(gap))
        val twoRuns = (0 until 7).associate { d("2026-10-01").plusDays(it.toLong()) to true } +
            (0 until 7).associate { d("2026-10-20").plusDays(it.toLong()) to true }
        assertEquals(listOf(d("2026-10-07"), d("2026-10-26")), Rules.dryBonusDates(twoRuns))
        assertEquals(true, Rules.isDryBonusStreak(14)); assertEquals(false, Rules.isDryBonusStreak(0)); assertEquals(false, Rules.isDryBonusStreak(8))
    }

    @Test fun sameTaskIgnoresFillerWordsAndSmallTypos() {
        assertEquals(true, Rules.sameTask("Make bed", "Make the bed"))
        assertEquals(true, Rules.sameTask("Pack backpack", "Pack the backpack"))
        assertEquals(true, Rules.sameTask("Clean room", "clean your room"))
        assertEquals(true, Rules.sameTask("Feed the dog", "Feed the dogg"))
        assertEquals(false, Rules.sameTask("Brush teeth", "Do homework"))
        assertEquals(false, Rules.sameTask("Make bed", "Make dinner"))
        assertEquals(false, Rules.sameTask("", "Make bed"))
    }

    @Test fun savedTabOrderIsMadeSafe() {
        assertEquals(Rules.TAB_KEYS, Rules.normalizeTabOrder(emptyList()))
        assertEquals(listOf("rewards", "chores") + Rules.TAB_KEYS.filter { it != "rewards" && it != "chores" },
            Rules.normalizeTabOrder(listOf("rewards", "bogus", "chores", "rewards")))
        assertEquals(Rules.TAB_KEYS.size, Rules.normalizeTabOrder(listOf("potty", "potty", "x")).size)
    }

    @Test fun everyTabHasAnEmojiLabel() {
        assertEquals(Rules.TAB_KEYS.size, Rules.TAB_LABELS.size)
        assertEquals("💰 Reward Shop 💰", Rules.TAB_LABELS[Rules.TAB_KEYS.indexOf("rewards")])
        assertEquals("🚽 Pee Tracker", Rules.TAB_LABELS[Rules.TAB_KEYS.indexOf("potty")])
    }

    @Test fun startTabFollowsTheChosenOrder() {
        // chores (1) and potty (5) are on; the user put potty first
        assertEquals(5, Rules.startTab(tabs(1, 5), wanted = 0, order = listOf(5, 1, 0, 2, 3, 4, 6, 7)))
        assertEquals(1, Rules.startTab(tabs(1, 5), wanted = 0))
    }

    @Test fun getReadyFollowsTheClock() {
        assertEquals("morning", Rules.sectionForHour(0))
        assertEquals("morning", Rules.sectionForHour(11))
        assertEquals("afternoon", Rules.sectionForHour(12))
        assertEquals("afternoon", Rules.sectionForHour(16))
        assertEquals("evening", Rules.sectionForHour(17))
        assertEquals("evening", Rules.sectionForHour(23))
    }

    @Test fun pointsWeekRunsFridayThroughThursday() {
        val thu = d("2026-10-15") // last day of the week that began Fri Oct 9
        assertEquals(true, Rules.inPointsWeek(d("2026-10-09"), thu))
        assertEquals(true, Rules.inPointsWeek(d("2026-10-15"), thu))
        assertEquals(false, Rules.inPointsWeek(d("2026-10-08"), thu)) // the week before
        assertEquals(false, Rules.inPointsWeek(d("2026-10-16"), thu)) // the future
        assertEquals(true, Rules.inPointsWeek(d("2026-10-16"), d("2026-10-16"))) // Friday starts a new week
        assertEquals(false, Rules.inPointsWeek(d("2026-10-15"), d("2026-10-16")))
    }

    @Test fun recapShowsFridayAndSaturdayOnlyUntilDismissed() {
        assertEquals(true, Rules.recapDue(d("2026-10-16"), null))   // Friday: new week
        assertEquals(true, Rules.recapDue(d("2026-10-17"), null))   // Saturday
        assertEquals(false, Rules.recapDue(d("2026-10-18"), null))  // Sunday: too late
        assertEquals(false, Rules.recapDue(d("2026-10-14"), null))  // Wednesday
        assertEquals(false, Rules.recapDue(d("2026-10-16"), "2026-10-16")) // dismissed this week
        assertEquals(true, Rules.recapDue(d("2026-10-23"), "2026-10-16"))  // last week's dismissal doesn't carry over
    }

    @Test fun newEmojiAreSwappedForOnesTheOldFontCanDraw() {
        assertEquals("🧼", Rules.safeIcon("🫧"))
        assertEquals("🌱", Rules.safeIcon("🪴"))
        assertEquals("🛏️", Rules.safeIcon("🛏️")) // anything else is left alone
    }

    @Test fun milestonesAreCelebratedOnceWhenCrossed() {
        assertEquals(3, Rules.pottyMilestoneReached(2, 3))
        assertEquals(7, Rules.pottyMilestoneReached(6, 7))
        assertEquals(21, Rules.pottyMilestoneReached(20, 21))
        assertEquals(7, Rules.pottyMilestoneReached(2, 8)) // backfilling several days: the biggest one crossed
        assertEquals(null, Rules.pottyMilestoneReached(3, 3)) // already there
        assertEquals(null, Rules.pottyMilestoneReached(10, 0)) // a wet day
        assertEquals(null, Rules.pottyMilestoneReached(0, 0))
    }

    @Test fun nextMilestoneAdvancesAndEnds() {
        assertEquals(3, Rules.nextPottyMilestone(0))
        assertEquals(14, Rules.nextPottyMilestone(7))
        assertEquals(null, Rules.nextPottyMilestone(21))
    }

    private fun tabs(vararg on: Int) = List(Rules.TAB_KEYS.size) { it in on }

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
