package com.portal.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the built-in catalogs against typos: a profile that names a missing chore, or an emoji the Portal can't draw. */
class ChoresDataTest {
    @Test fun defaultAgeProfilesOnlyNameRealStarterChores() {
        val starter = Chores.STARTER.map { it.title }.toSet()
        assertEquals(listOf("Ages 4–6", "Ages 7–9", "Ages 10–13"), Chores.DEFAULT_PROFILES.map { it.name })
        for (p in Chores.DEFAULT_PROFILES) {
            assertTrue("${p.name} is empty", p.titles.isNotEmpty())
            for (t in p.titles) assertTrue("${p.name}: $t is not a starter chore", t in starter)
            assertEquals("${p.name} repeats a chore", p.titles.size, p.titles.toSet().size)
        }
    }

    @Test fun starterChoresHaveSaneValues() {
        for (c in Chores.STARTER) {
            assertTrue(c.title, c.points in 1..10)
            assertTrue(c.title, c.days.isNotEmpty() && c.days.all { it in 1..7 })
        }
    }

    @Test fun rewardIdeasAreUniqueAffordableAndDrawable() {
        val names = Chores.REWARD_IDEAS.map { it.name.lowercase() }
        assertEquals("duplicate reward names", names.size, names.toSet().size)
        for (r in Chores.REWARD_IDEAS) assertTrue("${r.name} cost ${r.cost}", r.cost in 1..99)
    }

    @Test fun noCatalogIconNeedsTheOldFontFallback() {
        val icons = Chores.STARTER.map { it.icon } + Chores.REWARD_IDEAS.map { it.icon }
        for (i in icons) assertEquals("$i is too new for the Portal's emoji font", i, Rules.safeIcon(i))
    }
}
