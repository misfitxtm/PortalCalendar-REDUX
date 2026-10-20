package com.portal.calendar

import biweekly.Biweekly
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * End-to-end test of the real OkHttp code ([CalDavClient]) against a live CalDAV server.
 * Skipped unless CALDAV_TEST_SERVER is set (so a plain `./gradlew testDebugUnitTest` stays
 * offline). Written against Radicale 3.8.3 with a plaintext htpasswd user:
 *
 *   python3 -m radicale --server-hosts 127.0.0.1:5232 --auth-type htpasswd \
 *       --auth-htpasswd-filename users --auth-htpasswd-encryption plain --storage-filesystem-folder data
 *   CALDAV_TEST_SERVER=http://127.0.0.1:5232/ CALDAV_TEST_USER=alice CALDAV_TEST_PASSWORD=wonderland \
 *       ./gradlew testDebugUnitTest --no-daemon
 *
 * The test creates its own "portal-test-*" calendars under /<user>/ (from the seed files in
 * src/test/resources/caldav/radicale/seed) and deletes them afterwards. The seed events sit
 * in autumn 2026, so every read uses an explicit window instead of "now".
 */
class CalDavClientRadicaleTest {
    private val server: String? = System.getenv("CALDAV_TEST_SERVER")?.takeIf { it.isNotBlank() }
    private val user = System.getenv("CALDAV_TEST_USER") ?: "alice"
    private val password = System.getenv("CALDAV_TEST_PASSWORD") ?: "wonderland"

    private val http = OkHttpClient()
    private val home get() = server!!.trimEnd('/') + "/$user/"
    private val familyHref get() = home + "portal-test-family/"
    private val emptyHref get() = home + "portal-test-empty/"
    private val tasksHref get() = home + "portal-test-tasks/"

    private val winStart = Instant.parse("2026-09-15T00:00:00Z").toEpochMilli()
    private val winEnd = Instant.parse("2027-04-18T00:00:00Z").toEpochMilli()

    private fun client(pw: String = password, at: String = server!!) =
        CalDavClient(CalDavXml.normalizeServer(at), user, pw)

    private fun seed(name: String) =
        javaClass.getResourceAsStream("/caldav/radicale/seed/$name")!!.readBytes().toString(Charsets.UTF_8)

    private fun send(method: String, url: String, body: String? = null, type: String = "application/xml"): Int {
        val rb = body?.toRequestBody("$type; charset=utf-8".toMediaType())
        val req = Request.Builder().url(url).header("Authorization", Credentials.basic(user, password))
            .method(method, rb).build()
        return http.newCall(req).execute().use { it.code }
    }

    private fun mkcalendar(href: String, name: String, color: String?, comp: String) {
        val colorXml = if (color == null) "" else "<I:calendar-color>$color</I:calendar-color>"
        val code = send("MKCALENDAR", href, """<?xml version="1.0" encoding="UTF-8"?>
            <C:mkcalendar xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav" xmlns:I="http://apple.com/ns/ical/">
            <D:set><D:prop><D:displayname>$name</D:displayname>$colorXml
            <C:supported-calendar-component-set><C:comp name="$comp"/></C:supported-calendar-component-set>
            </D:prop></D:set></C:mkcalendar>""")
        assertEquals("MKCALENDAR $href", 201, code)
    }

    private fun put(href: String, file: String) =
        assertEquals("PUT $href", 201, send("PUT", href, seed(file), "text/calendar"))

    @Before fun setUp() {
        assumeTrue("set CALDAV_TEST_SERVER to run against a real server", server != null)
        cleanUp()
        mkcalendar(familyHref, "Portal Test Family &amp; Friends", "#4FA3E3FF", "VEVENT")
        mkcalendar(emptyHref, "Portal Test Empty", "#8E7CC3", "VEVENT")
        mkcalendar(tasksHref, "Portal Test Tasks", null, "VTODO")
        for (f in listOf("dentist", "cafe", "piano", "old", "far")) put(familyHref + "$f.ics", "$f.ics")
        put(tasksHref + "todo.ics", "todo.ics")
    }

    @After fun cleanUp() {
        if (server == null) return
        for (h in listOf(familyHref, emptyHref, tasksHref)) send("DELETE", h)
    }

    private fun ours(cals: List<CalInfo>) = cals.filter { it.href.contains("/portal-test-") }

    // ------------------------------------------------------------ discovery

    @Test fun discoversEventCalendarsWithColours() {
        val cals = ours(client().discoverCalendars())
        // Tasks (VTODO-only) is left out; the colour's alpha byte is dropped.
        assertEquals(listOf(
            CalInfo("Portal Test Empty", emptyHref, "#8e7cc3"),
            CalInfo("Portal Test Family & Friends", familyHref, "#4fa3e3")), cals.sortedBy { it.name })
    }

    @Test fun serverAddressVariantsAllDiscoverTheSameCalendars() {
        val base = server!!.trimEnd('/')
        val expected = ours(client().discoverCalendars())
        assertEquals(2, expected.size)
        for (addr in listOf(
            base,                    // no trailing slash
            "$base/",
            home,                    // pointing straight at the user's collection
            "$base/nonexistent/",    // Radicale answers 403 here: must fall back to /.well-known/caldav
        )) assertEquals(addr, expected, ours(client(at = addr).discoverCalendars()))
    }

    @Test fun wrongPasswordSaysSignInFailed() {
        try {
            client(pw = "definitely-wrong").discoverCalendars()
            fail("expected a sign-in failure")
        } catch (e: IllegalArgumentException) {
            assertEquals("CalDAV sign-in failed (401) — check the username and password (Nextcloud and Fastmail need an app password)",
                e.message)
        }
    }

    @Test fun unreachableServerIsReadable() {
        try {
            client(at = "http://127.0.0.1:1/").discoverCalendars()
            fail("expected a connection failure")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!, e.message!!.startsWith("couldn't reach 127.0.0.1 ("))
        }
    }

    // ------------------------------------------------------------ reading

    @Test fun fetchesTheWindowAsConcatenatedCalendars() {
        val ics = client().fetchIcs(familyHref, winStart, winEnd)
        val cals = Biweekly.parse(ics).all()
        assertEquals(3, cals.size)
        val events = cals.flatMap { it.events }
        assertEquals(setOf("piano-weekly-001", "cafe-001", "dentist-001"), events.map { it.uid.value }.toSet())
        assertEquals(4, events.size) // the piano series is a master + one moved instance in one resource
        val master = events.single { it.uid.value == "piano-weekly-001" && it.recurrenceId == null }
        assertTrue(master.recurrenceRule != null) // returned as a master (not expanded), though it began before the window
        assertEquals("Café ☕ Müller, Zoë — 日本語 & <b>bold</b> \"quoted\" 'single'",
            events.single { it.uid.value == "cafe-001" }.summary.value)
        assertFalse(ics.contains("Ancient history"))
        assertFalse(ics.contains("Far future"))
    }

    @Test fun theWindowIsHonouredByTheServer() {
        val jan2025 = client().fetchIcs(familyHref,
            Instant.parse("2024-12-25T00:00:00Z").toEpochMilli(), Instant.parse("2025-01-10T00:00:00Z").toEpochMilli())
        assertEquals(listOf("old-001"), Biweekly.parse(jan2025).all().flatMap { it.events }.map { it.uid.value })
    }

    @Test fun emptyCalendarIsAValidEmptyFeed() {
        val ics = client().fetchIcs(emptyHref, winStart, winEnd)
        assertEquals(CalDavXml.EMPTY_CALENDAR, ics)
        assertEquals(0, Biweekly.parse(ics).all().sumOf { it.events.size })
    }

    @Test fun aMissingCalendarIsAReadableError() {
        try {
            client().fetchIcs(home + "no-such-calendar/", winStart, winEnd)
            fail("expected an error")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!, e.message!!.contains("wasn't found") || e.message!!.contains("error HTTP"))
        }
    }

    @Test fun wrongPasswordOnReadSaysSignInFailed() {
        try {
            client(pw = "nope").fetchIcs(familyHref, winStart, winEnd)
            fail("expected a sign-in failure")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!, e.message!!.startsWith("CalDAV sign-in failed (401)"))
        }
    }

    // ------------------------------------------------------------ writing

    @Test fun addThenReadThenDeleteRoundTrip() {
        val c = client()
        val start = Instant.parse("2026-11-15T15:00:00Z").toEpochMilli()
        val uid = c.addEvent(familyHref, "Round trip; with, punctuation ☕", start, start + 3_600_000, false)
        val after = Biweekly.parse(c.fetchIcs(familyHref, winStart, winEnd)).all().flatMap { it.events }
        assertEquals("Round trip; with, punctuation ☕", after.single { it.uid.value == uid }.summary.value)

        assertTrue(c.deleteByUid(listOf(emptyHref, familyHref), uid)) // fast path: {collection}/{uid}.ics
        val gone = Biweekly.parse(c.fetchIcs(familyHref, winStart, winEnd)).all().flatMap { it.events }
        assertTrue(gone.none { it.uid.value == uid })
    }

    @Test fun anAllDayEventCanBeAdded() {
        val c = client()
        val day = Instant.parse("2026-11-20T00:00:00Z").toEpochMilli()
        val uid = c.addEvent(familyHref, "Day off", day, day + 86_400_000, true)
        val ev = Biweekly.parse(c.fetchIcs(familyHref, winStart, winEnd)).all().flatMap { it.events }
            .single { it.uid.value == uid }
        assertFalse(ev.dateStart.value.hasTime())
    }

    private fun putEvent(href: String, uid: String, summary: String) {
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Portal Test//EN\r\nBEGIN:VEVENT\r\nUID:$uid\r\n" +
            "DTSTAMP:20261001T120000Z\r\nDTSTART:20261201T150000Z\r\nDTEND:20261201T160000Z\r\n" +
            "SUMMARY:$summary\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        assertEquals("PUT $href", 201, send("PUT", href, ics, "text/calendar"))
    }

    private fun uidsOnServer(): Set<String> =
        Biweekly.parse(client().fetchIcs(familyHref, winStart, winEnd)).all().flatMap { it.events }
            .map { it.uid.value }.toSet()

    @Test fun deleteNeverTouchesAnEventWhoseUidOnlyContainsTheTarget() {
        // Neither resource is named after its UID, so deletes go through the UID lookup — whose
        // text-match is a substring match. a-ten.ics (evt-10) sorts before b-one.ics (evt-1).
        putEvent(familyHref + "a-ten.ics", "evt-10", "Ten")
        putEvent(familyHref + "b-one.ics", "evt-1", "One")

        // Precondition that makes this test meaningful: the server's own filter returns both.
        val raw = http.newCall(Request.Builder().url(familyHref)
            .header("Authorization", Credentials.basic(user, password)).header("Depth", "1")
            .method("REPORT", """<c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                <d:prop><d:getetag/></d:prop><c:filter><c:comp-filter name="VCALENDAR"><c:comp-filter name="VEVENT">
                <c:prop-filter name="UID"><c:text-match collation="i;octet">evt-1</c:text-match></c:prop-filter>
                </c:comp-filter></c:comp-filter></c:filter></c:calendar-query>""".toRequestBody("application/xml".toMediaType()))
            .build()).execute().use { it.body!!.string() }
        assertTrue(raw, raw.contains("a-ten.ics") && raw.contains("b-one.ics"))

        val c = client()
        assertFalse(c.deleteByUid(listOf(familyHref), "evt")) // matches both by substring, is neither
        assertTrue(uidsOnServer().containsAll(listOf("evt-1", "evt-10")))

        assertTrue(c.deleteByUid(listOf(familyHref), "evt-1"))
        val afterOne = uidsOnServer()
        assertFalse("evt-1 should be gone", "evt-1" in afterOne)
        assertTrue("evt-10 must survive deleting evt-1", "evt-10" in afterOne)

        assertFalse(c.deleteByUid(listOf(familyHref), "evt-1")) // already gone: must not fall through to evt-10
        assertTrue("evt-10" in uidsOnServer())

        assertTrue(c.deleteByUid(listOf(familyHref), "evt-10"))
        assertTrue(uidsOnServer().none { it.startsWith("evt-") })
    }

    @Test fun deleteFindsEventsThatAreNotAtTheUidUrl() {
        // dentist.ics holds UID dentist-001, so {collection}/dentist-001.ics is a 404:
        // the delete must fall through to the calendar-query lookup.
        val c = client()
        assertTrue(c.deleteByUid(listOf(familyHref), "dentist-001"))
        val left = Biweekly.parse(c.fetchIcs(familyHref, winStart, winEnd)).all().flatMap { it.events }
        assertTrue(left.none { it.uid.value == "dentist-001" })
        assertFalse(c.deleteByUid(listOf(familyHref), "dentist-001")) // already gone
        assertFalse(c.deleteByUid(listOf(familyHref), ""))
    }
}
