package com.portal.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShoppingExportTest {
    @Test fun listsItemsToBuyFirstThenCheckedOff() {
        val md = Shopping.markdown(listOf("Milk" to false, "Eggs" to true, "Bread" to false), "2026-10-14")
        assertEquals("# 🛒 Shopping list\n\n_Saved 2026-10-14 · 2 to buy, 1 checked off_\n\n- [ ] Milk\n- [ ] Bread\n- [x] Eggs\n", md)
    }

    @Test fun emptyListSaysSo() {
        assertTrue(Shopping.markdown(emptyList(), "2026-10-14").contains("Nothing on the list."))
    }

    @Test fun anItemCannotStartANewLine() {
        val md = Shopping.markdown(listOf("Milk\n# Injected heading" to false), "2026-10-14")
        assertFalse(md.lines().any { it.startsWith("# Injected") })
    }
}

class DownloadLinkTest {
    @Test fun codeWorksUntilItExpiresAndOnlyTheNewestWorks() {
        val a = DownloadLink.issue(now = 1_000)
        assertTrue(DownloadLink.valid(a, now = 1_000 + DownloadLink.LIFETIME_MS - 1))
        assertFalse(DownloadLink.valid(a, now = 1_000 + DownloadLink.LIFETIME_MS))
        val b = DownloadLink.issue(now = 5_000)
        assertFalse(DownloadLink.valid(a, now = 5_001))
        assertTrue(DownloadLink.valid(b, now = 5_001))
    }

    @Test fun missingOrWrongCodeIsRefused() {
        DownloadLink.issue(now = 1_000)
        assertFalse(DownloadLink.valid(null, now = 1_001))
        assertFalse(DownloadLink.valid("", now = 1_001))
        assertFalse(DownloadLink.valid("nope", now = 1_001))
    }
}
