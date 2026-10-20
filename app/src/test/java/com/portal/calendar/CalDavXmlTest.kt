package com.portal.calendar

import biweekly.Biweekly
import biweekly.ICalendar
import biweekly.util.Frequency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Pure-function tests for [CalDavXml] against real Radicale 3.8.3 responses
 * (src/test/resources/caldav/radicale — captured with curl from a local server) and
 * hand-written fixtures in the style of Nextcloud/sabre (escaped calendar-data, `cal:`/`d:`
 * prefixes), a CDATA/default-namespace server, and an iCloud-style calendar listing.
 * The Nextcloud and iCloud-style files are NOT captures from those products.
 */
class CalDavXmlTest {
    private fun fixture(path: String): String =
        javaClass.getResourceAsStream("/caldav/$path")!!.readBytes().toString(Charsets.UTF_8)

    private fun parse(ics: String): List<ICalendar> = Biweekly.parse(ics).all()

    // ------------------------------------------------------------ addresses

    @Test fun blankServerMeansIcloud() {
        assertEquals(CalDavXml.ICLOUD_ROOT, CalDavXml.normalizeServer(null))
        assertEquals(CalDavXml.ICLOUD_ROOT, CalDavXml.normalizeServer(""))
        assertEquals(CalDavXml.ICLOUD_ROOT, CalDavXml.normalizeServer("   "))
        assertEquals(CalDavXml.ICLOUD_ROOT, CalDavXml.normalizeServer("caldav.icloud.com"))
    }

    @Test fun serverGetsSchemeAndIsTrimmed() {
        assertEquals("https://cloud.example.com/remote.php/dav",
            CalDavXml.normalizeServer("  cloud.example.com/remote.php/dav "))
        assertEquals("https://cloud.example.com/remote.php/dav",
            CalDavXml.normalizeServer("https://cloud.example.com/remote.php/dav"))
        assertEquals("http://192.168.1.20:5232/", CalDavXml.normalizeServer("http://192.168.1.20:5232"))
        assertEquals("https://localhost:5232/", CalDavXml.normalizeServer("localhost:5232"))
        assertEquals("https://caldav.fastmail.com/", CalDavXml.normalizeServer("https://caldav.fastmail.com/"))
        assertEquals("https://example.com/dav.php", CalDavXml.normalizeServer("HTTPS://Example.COM/dav.php"))
    }

    @Test fun badServerAddressesAreRejected() {
        for (bad in listOf("ftp://example.com", "https://", "https://user:secret@example.com/", "not a url at all"))
            try {
                CalDavXml.normalizeServer(bad)
                fail("accepted: $bad")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.isNotEmpty())
            }
    }

    @Test fun icloudDetectionAndHost() {
        assertTrue(CalDavXml.isIcloud("https://caldav.icloud.com/"))
        assertTrue(CalDavXml.isIcloud("https://p41-caldav.icloud.com:443/1234/calendars/"))
        assertFalse(CalDavXml.isIcloud("https://cloud.example.com/remote.php/dav"))
        assertFalse(CalDavXml.isIcloud("https://notricloud.com/"))
        assertEquals("cloud.example.com", CalDavXml.hostOf("https://cloud.example.com/remote.php/dav"))
    }

    @Test fun wellKnownKeepsSchemeHostAndPort() {
        assertEquals("https://cloud.example.com/.well-known/caldav",
            CalDavXml.wellKnownUrl("https://cloud.example.com/remote.php/dav"))
        assertEquals("http://192.168.1.20:5232/.well-known/caldav",
            CalDavXml.wellKnownUrl("http://192.168.1.20:5232/some/path/"))
    }

    @Test fun resolveHandlesAbsoluteRelativeAndUnencodedHrefs() {
        assertEquals("https://p41-caldav.icloud.com:443/1/calendars",
            CalDavXml.resolve("https://caldav.icloud.com/1/principal/", "https://p41-caldav.icloud.com:443/1/calendars"))
        assertEquals("https://caldav.icloud.com/1/principal/",
            CalDavXml.resolve("https://caldav.icloud.com/", "/1/principal/"))
        assertEquals("https://h.example/dav/cals/x/", CalDavXml.resolve("https://h.example/dav/cals/", "x/"))
        // a server that sends a raw space in an href must not blow up URI parsing
        assertEquals("https://h.example/dav/My%20Cal/", CalDavXml.resolve("https://h.example/dav/", "/dav/My Cal/"))
    }

    @Test fun calendarColorKeepsRgbDropsAlpha() {
        assertEquals("#4fa3e3", CalDavXml.parseColor("#4FA3E3FF"))
        assertEquals("#ff2968", CalDavXml.parseColor(" #FF2968 "))
        assertEquals("#0082c9", CalDavXml.parseColor("#0082c9"))
        assertNull(CalDavXml.parseColor("#abc"))
        assertNull(CalDavXml.parseColor("red"))
        assertNull(CalDavXml.parseColor("not-a-color"))
        assertNull(CalDavXml.parseColor(""))
        assertNull(CalDavXml.parseColor(null))
    }

    // ------------------------------------------------------------ request bodies

    private fun assertWellFormed(xml: String) {
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        f.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
    }

    @Test fun requestBodiesAreWellFormedXml() {
        assertWellFormed(CalDavXml.PRINCIPAL_BODY)
        assertWellFormed(CalDavXml.HOME_SET_BODY)
        assertWellFormed(CalDavXml.LIST_BODY)
        assertWellFormed(CalDavXml.calendarQueryBody(0, 1_000_000_000_000))
        assertTrue(CalDavXml.LIST_BODY.contains("calendar-color"))
        assertTrue(CalDavXml.LIST_BODY.contains("http://apple.com/ns/ical/"))
    }

    @Test fun calendarQueryAsksForTheUtcWindow() {
        val start = Instant.parse("2026-09-15T00:00:00Z").toEpochMilli()
        val end = Instant.parse("2027-04-18T00:00:00Z").toEpochMilli()
        val body = CalDavXml.calendarQueryBody(start, end)
        assertTrue(body, body.contains("""<c:time-range start="20260915T000000Z" end="20270418T000000Z"/>"""))
        assertTrue(body.contains("""<c:comp-filter name="VEVENT">"""))
        assertTrue(body.contains("<c:calendar-data/>"))
    }

    // ------------------------------------------------------------ principal / home-set

    @Test fun principalAndHomeFromRadicale() {
        assertEquals("/alice/", CalDavXml.principalHref(fixture("radicale/principal.xml")))
        assertEquals("/alice/", CalDavXml.calendarHomeHref(fixture("radicale/home-set.xml")))
    }

    @Test fun principalAndHomeFromNextcloudStyle() {
        assertEquals("/remote.php/dav/principals/users/alice/", CalDavXml.principalHref(fixture("nextcloud/principal.xml")))
        assertEquals("/remote.php/dav/calendars/alice", CalDavXml.calendarHomeHref(fixture("nextcloud/home-set.xml")))
    }

    @Test fun principalAndHomeFromIcloudStyle() {
        assertEquals("/1234567890/principal/", CalDavXml.principalHref(fixture("icloud-style/principal-and-home.xml")))
        assertEquals("https://p41-caldav.icloud.com:443/1234567890/calendars",
            CalDavXml.calendarHomeHref(fixture("icloud-style/home-set.xml")))
    }

    @Test fun missingOrUnauthenticatedPrincipalIsNull() {
        val unauth = """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/</d:href><d:propstat><d:prop>
            <d:current-user-principal><d:unauthenticated/></d:current-user-principal></d:prop>
            <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""
        assertNull(CalDavXml.principalHref(unauth))
        val notFound = """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/</d:href><d:propstat><d:prop>
            <d:current-user-principal/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response></d:multistatus>"""
        assertNull(CalDavXml.principalHref(notFound))
        assertNull(CalDavXml.principalHref("<html><body>Login</body></html>"))
        assertNull(CalDavXml.principalHref(""))
    }

    // ------------------------------------------------------------ calendar lists

    @Test fun radicaleCalendarList() {
        val cals = CalDavXml.calendars(fixture("radicale/calendar-list.xml"), "http://127.0.0.1:5232/alice/")
        // The home collection and the VTODO-only "Tasks" list are not event calendars.
        assertEquals(listOf(
            CalInfo("Empty Calendar", "http://127.0.0.1:5232/alice/empty/", "#8e7cc3"),
            CalInfo("Family & Friends", "http://127.0.0.1:5232/alice/family/", "#4fa3e3")), cals)
    }

    @Test fun nextcloudStyleCalendarList() {
        val cals = CalDavXml.calendars(fixture("nextcloud/calendar-list.xml"),
            "https://cloud.example.com/remote.php/dav/calendars/alice")
        val base = "https://cloud.example.com/remote.php/dav/calendars/alice/"
        assertEquals(listOf(
            CalInfo("Personal", base + "personal/", "#0082c9"),
            CalInfo("Kids & Sports \"Teams\"", base + "kids-sports-teams/", "#ff7a66"),
            CalInfo("No color", base + "no%20color%20here/", null),
            // blank display name -> last path segment; unparseable colour -> none
            CalInfo("unnamed-cal", base + "unnamed-cal/", null)), cals)
        // inbox, outbox (schedule-*), the collection itself and the VTODO-only list are excluded
        assertTrue(cals.none { it.name in setOf("Inbox", "Outbox", "My tasks") })
    }

    @Test fun icloudStyleCalendarListResolvesAgainstTheHome() {
        val cals = CalDavXml.calendars(fixture("icloud-style/calendar-list.xml"),
            "https://p41-caldav.icloud.com:443/1234567890/calendars")
        assertEquals(listOf(
            CalInfo("Home", "https://p41-caldav.icloud.com:443/1234567890/calendars/home/", "#1badf8"),
            CalInfo("Work", "https://p41-caldav.icloud.com:443/1234567890/calendars/2F6B1D0A-0000-4A4A-9999-ABCDEF012345/", "#ff2968")),
            cals) // inbox, reminders (VTODO) and notification are skipped
    }

    @Test fun calendarListFromGarbageIsEmpty() {
        assertTrue(CalDavXml.calendars("<html>nope</html>", "https://x.example/").isEmpty())
        assertTrue(CalDavXml.calendars("", "https://x.example/").isEmpty())
    }

    // ------------------------------------------------------------ multistatus -> ICS

    @Test fun radicaleReportBecomesAllItsCalendars() {
        val ics = CalDavXml.multistatusToIcs(fixture("radicale/report-family.xml"))
        val cals = parse(ics)
        assertEquals(3, cals.size)
        assertEquals(4, cals.sumOf { it.events.size }) // piano master + moved instance, cafe, dentist
        val byUid = cals.flatMap { it.events }.groupBy { it.uid.value }
        assertEquals(setOf("piano-weekly-001", "cafe-001", "dentist-001"), byUid.keys)

        val piano = byUid["piano-weekly-001"]!!
        assertEquals(2, piano.size)
        val master = piano.single { it.recurrenceId == null }
        assertEquals("Piano lesson", master.summary.value)
        assertEquals(Frequency.WEEKLY, master.recurrenceRule.value.frequency)
        assertEquals(1, master.exceptionDates.size)
        val moved = piano.single { it.recurrenceId != null }
        assertEquals("Piano lesson (moved)", moved.summary.value)

        // Out-of-window events were filtered by the server, and none leaked in.
        assertFalse(ics.contains("Ancient history"))
        assertFalse(ics.contains("Far future"))
    }

    @Test fun nonAsciiAndEscapedTextSurvives() {
        val cafe = parse(CalDavXml.multistatusToIcs(fixture("radicale/report-family.xml")))
            .flatMap { it.events }.single { it.uid.value == "cafe-001" }
        assertEquals("Café ☕ Müller, Zoë — 日本語 & <b>bold</b> \"quoted\" 'single'", cafe.summary.value)
        assertEquals("Zürich, Straße 5", cafe.location.value)
        assertTrue(cafe.description.value, cafe.description.value.startsWith("Line one\nLine two with ]]> inside"))
        assertTrue(cafe.description.value.endsWith("folded tail 😀"))
    }

    @Test fun emptyCalendarGivesAMinimalValidVcalendar() {
        val ics = CalDavXml.multistatusToIcs(fixture("radicale/report-empty.xml"))
        assertEquals(CalDavXml.EMPTY_CALENDAR, ics)
        assertTrue(ics.startsWith("BEGIN:VCALENDAR") && ics.trimEnd().endsWith("END:VCALENDAR"))
        val cals = parse(ics)
        assertEquals(1, cals.size)
        assertEquals(0, cals[0].events.size)
        // also an empty multistatus written with a prefix and as an open/close pair
        assertEquals(CalDavXml.EMPTY_CALENDAR, CalDavXml.multistatusToIcs("""<d:multistatus xmlns:d="DAV:"></d:multistatus>"""))
    }

    @Test fun nextcloudStyleEscapedCalendarData() {
        val ics = CalDavXml.multistatusToIcs(fixture("nextcloud/report.xml"))
        assertFalse(ics, Regex("&#?\\w+;").containsMatchIn(ics)) // every entity decoded
        assertTrue(ics.contains("\r\n")) // &#13; became a real CR
        val cals = parse(ics)
        assertEquals(2, cals.size) // the third response has no calendar-data (404 propstat)
        val soccer = cals.flatMap { it.events }.single { it.uid.value == "nc-soccer-001" }
        assertEquals("Soccer \"U10\" & Mom's run <field 3> 😀", soccer.summary.value)
        assertEquals("Café Field, Baltimore", soccer.location.value)
        val trash = cals.flatMap { it.events }.single { it.uid.value == "nc-trash-001" }
        assertEquals("Trash night", trash.summary.value)
        assertTrue(trash.recurrenceRule != null)
    }

    @Test fun cdataAndDefaultNamespace() {
        val ics = CalDavXml.multistatusToIcs(fixture("cdata/report-default-ns.xml"))
        val events = parse(ics).flatMap { it.events }
        assertEquals(2, events.size)
        val standup = events.single { it.uid.value == "cdata-standup-001" }
        // CDATA is literal: its markup characters and entity-looking text are not decoded again.
        assertEquals("Stand-up & <planning> \"daily\"", standup.summary.value)
        assertEquals("keep ]]> this intact &amp; do not decode &#13; here", standup.description.value)
        assertEquals("Offsite — Zürich", events.single { it.uid.value == "cdata-offsite-001" }.summary.value)
    }

    @Test fun mixedPrefixesAndEntityStylesInOneReply() {
        val xml = """<?xml version="1.0"?>
            <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
            <D:response><D:href>/a.ics</D:href><D:propstat><D:prop>
              <C:calendar-data>BEGIN:VCALENDAR&#10;VERSION:2.0&#10;BEGIN:VEVENT&#10;UID:a&#10;DTSTART:20261101T100000Z&#10;SUMMARY:one &amp;amp; two&#10;END:VEVENT&#10;END:VCALENDAR</C:calendar-data>
            </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
            <D:response><D:href>/b.ics</D:href><D:propstat><D:prop>
              <caldav:calendar-data xmlns:caldav="urn:ietf:params:xml:ns:caldav"><![CDATA[BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:b
DTSTART:20261102T100000Z
SUMMARY:two
END:VEVENT
END:VCALENDAR]]></caldav:calendar-data>
            </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
            </D:multistatus>"""
        val events = parse(CalDavXml.multistatusToIcs(xml)).flatMap { it.events }
        assertEquals(listOf("a", "b"), events.map { it.uid.value })
        assertEquals("one &amp; two", events[0].summary.value) // &amp;amp; -> literal "&amp;"
    }

    @Test fun nonMultistatusReplyIsAnError() {
        for (bad in listOf("<html><body>Please sign in</body></html>", "", "BEGIN:VCALENDAR\nEND:VCALENDAR"))
            try {
                CalDavXml.multistatusToIcs(bad)
                fail("accepted: $bad")
            } catch (e: IllegalArgumentException) { /* expected */ }
    }

    // ------------------------------------------------------------ delete-by-UID lookup

    @Test fun exactUidMatchPicksOnlyTheExactResource() {
        // Captured from Radicale: the text-match pre-filter for "evt-1" returned BOTH events,
        // evt-10 (a-ten.ics) first. Picking the first href would delete the wrong event.
        val xml = fixture("radicale/report-uid-substring.xml")
        assertEquals(listOf("/alice/uidtest/a-ten.ics", "/alice/uidtest/b-one.ics"),
            Regex("<href>([^<]+)</href>").findAll(xml).map { it.groupValues[1] }.toList()) // both came back
        assertEquals(listOf("/alice/uidtest/b-one.ics"), CalDavXml.hrefsWithExactUid(xml, "evt-1"))
        assertEquals(listOf("/alice/uidtest/a-ten.ics"), CalDavXml.hrefsWithExactUid(xml, "evt-10"))
        // prefixes, supersets and other-case variants of a UID are not that UID
        for (other in listOf("evt", "evt-", "evt-100", "EVT-1", "evt-1 ", "vt-1"))
            assertTrue(other, CalDavXml.hrefsWithExactUid(xml, other).isEmpty())
    }

    @Test fun uidEdgeCases() {
        val xml = fixture("nextcloud/report-uid-edge-cases.xml")
        val base = "/remote.php/dav/calendars/alice/personal/"
        fun find(uid: String) = CalDavXml.hrefsWithExactUid(xml, uid)

        // a folded UID line is matched as the whole UID, never as its first physical line
        assertEquals(listOf(base + "folded.ics"),
            find("folded-uid-with-a-long-name-that-the-server-wrapped-at-seventy-five-characters-0001"))
        assertTrue(find("folded-uid-with-a-long-name-that-the-server-wrapped-at-seventy-five-ch").isEmpty())

        // UIDs of a VALARM or a VTODO inside the resource are not event UIDs
        assertEquals(listOf(base + "alarm.ics"), find("alarm-owner"))
        assertTrue(find("alarm-inner-uid").isEmpty())
        assertTrue(find("todo-uid").isEmpty())

        // master + edited instance share a UID and one resource: one href
        assertEquals(listOf(base + "series.ics"), find("series-1"))

        // TEXT escapes and a quoted parameter containing ':' are handled
        assertEquals(listOf(base + "escaped.ics"), find("team, a;b"))
        assertEquals(listOf(base + "escaped.ics"), find("team\\, a\\;b"))

        assertEquals(listOf(base + "cdata.ics"), find("cdata-uid"))
        assertTrue(find("nodata").isEmpty()) // the 404 propstat resource never matches
    }

    @Test fun eventUidsReadsFoldedLinesWithAnyLineEnding() {
        val ics = "BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:abc\n def\n\tghi\nEND:VEVENT\r\nBEGIN:vevent\rUID:second\rEND:vevent\r"
        assertEquals(setOf("abcdefghi", "second"), CalDavXml.eventUids(ics))
        assertTrue(CalDavXml.eventUids("BEGIN:VCALENDAR\nEND:VCALENDAR").isEmpty())
        assertTrue(CalDavXml.eventUids("UID:outside-any-event").isEmpty())
        assertTrue(CalDavXml.eventUids("").isEmpty())
    }

    // ------------------------------------------------------------ which URLs get the credentials

    @Test fun credentialsGoOnlyToTheConnectedServerOrItsCalendarHosts() {
        val server = "https://cloud.example.com/remote.php/dav"
        val hrefs = listOf("https://cloud.example.com/remote.php/dav/calendars/alice/personal/")
        fun ok(url: String) = CalDavXml.isOnAccount(url, server, hrefs)

        assertTrue(ok("https://cloud.example.com/remote.php/dav/calendars/alice/personal/"))
        assertTrue(ok("https://cloud.example.com/some/other/path/")) // same origin, any path
        assertTrue(ok("HTTPS://CLOUD.EXAMPLE.COM:443/x/")) // case and the default port don't matter

        assertFalse(ok("https://evil.example.net/remote.php/dav/calendars/alice/personal/"))
        assertFalse(ok("https://cloud.example.com.evil.net/"))
        assertFalse(ok("https://evil.net/cloud.example.com/"))
        assertFalse(ok("https://cloud.example.com@evil.net/")) // userinfo trick: the host is evil.net
        assertFalse(ok("http://cloud.example.com/remote.php/dav/")) // a different scheme isn't the same origin
        assertFalse(ok("https://cloud.example.com:8443/remote.php/dav/")) // nor is a different port
        assertFalse(ok("https://example.com/"))
        for (junk in listOf("", "not a url", "/relative/path/", "ftp://cloud.example.com/", "https://"))
            assertFalse(junk, ok(junk))
    }

    @Test fun icloudShardHostsComeFromTheStoredCalendars() {
        val hrefs = listOf(
            "https://p41-caldav.icloud.com:443/1234567890/calendars/home/",
            "https://p41-caldav.icloud.com/1234567890/calendars/work/")
        fun ok(url: String) = CalDavXml.isOnAccount(url, CalDavXml.ICLOUD_ROOT, hrefs)
        assertTrue(ok("https://caldav.icloud.com/1234567890/calendars/home/"))
        assertTrue(ok("https://p41-caldav.icloud.com/1234567890/calendars/home/"))
        assertFalse(ok("https://p42-caldav.icloud.com/1234567890/calendars/home/")) // a shard this account never listed
        assertFalse(ok("https://icloud.com.evil.net/"))
        // with no calendars stored yet only the root itself qualifies
        assertTrue(CalDavXml.isOnAccount("https://caldav.icloud.com/x/", CalDavXml.ICLOUD_ROOT, emptyList()))
        assertFalse(CalDavXml.isOnAccount("https://p41-caldav.icloud.com/x/", CalDavXml.ICLOUD_ROOT, emptyList()))
        // a LAN server on a non-default port
        assertTrue(CalDavXml.isOnAccount("http://192.168.1.20:5232/alice/family/", "http://192.168.1.20:5232/", emptyList()))
        assertFalse(CalDavXml.isOnAccount("http://192.168.1.20:5233/alice/family/", "http://192.168.1.20:5232/", emptyList()))
    }

    // ------------------------------------------------------------ the tiny XML toolkit

    @Test fun elementsIgnoreMarkupInCommentsCdataAndQuotedAttributes() {
        val xml = """<a:root xmlns:a="x"><!-- <a:item>fake</a:item> --><a:item k="v>w">one</a:item>
            <![CDATA[ <a:item>fake2</a:item> ]]><b:item xmlns:b="y"><a:item>nested</a:item></b:item><item/><x:item q='1' /></a:root>"""
        val items = CalDavXml.elements(xml, "item")
        assertEquals(4, items.size)
        assertEquals("one", items[0].inner)
        assertEquals("v>w", CalDavXml.attr(items[0].attrs, "k"))
        assertEquals("<a:item>nested</a:item>", items[1].inner) // outermost only; nesting stays inside
        assertEquals("", items[2].inner)
        assertEquals("1", CalDavXml.attr(items[3].attrs, "q"))
    }

    @Test fun unescapeCoversPredefinedAndNumericEntities() {
        assertEquals("<>&\"'", CalDavXml.unescape("&lt;&gt;&amp;&quot;&apos;"))
        assertEquals("\r\n\r", CalDavXml.unescape("&#13;&#10;&#xD;"))
        assertEquals("é€😀", CalDavXml.unescape("&#233;&#x20AC;&#x1F600;"))
        assertEquals("&amp;", CalDavXml.unescape("&amp;amp;")) // decoded once, not twice
        // unknown / invalid references stay as written
        assertEquals("&nbsp; &#0; &#xD800; &#; & x;", CalDavXml.unescape("&nbsp; &#0; &#xD800; &#; & x;"))
    }

    @Test fun textMixesCdataAndEscapedParts() {
        assertEquals("a & b <c> d]]>e", CalDavXml.text("a &amp; b <![CDATA[<c>]]> d<![CDATA[]]]]><![CDATA[>]]>e"))
        assertEquals("plain", CalDavXml.text("plain"))
        assertEquals("kept", CalDavXml.text("<b>kept</b>"))
        assertEquals("<b>kept</b>", CalDavXml.text("<b>kept</b>", stripTags = false))
    }
}
