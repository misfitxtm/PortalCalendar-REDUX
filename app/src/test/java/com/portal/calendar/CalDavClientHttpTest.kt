package com.portal.calendar

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections

/**
 * [CalDavClient] against a throw-away in-process HTTP server (JDK's com.sun.net.httpserver),
 * for behaviour a well-behaved CalDAV server like Radicale never shows: redirects, error
 * statuses and an address nobody listens on. Needs no network and no environment variables.
 *
 * The redirect tests guard a real trap: OkHttp turns a redirected PUT/DELETE/REPORT into a
 * body-less GET, which a server happily answers 200 — so a "write" would look successful
 * while nothing was stored (or deleted).
 */
class CalDavClientHttpTest {
    private class Seen(val method: String, val path: String, val body: String)

    private lateinit var http: HttpServer
    private val seen: MutableList<Seen> = Collections.synchronizedList(ArrayList())
    private val base get() = "http://127.0.0.1:${http.address.port}"

    private fun fixture(path: String): String =
        javaClass.getResourceAsStream("/caldav/$path")!!.readBytes().toString(Charsets.UTF_8)

    private fun client(root: String = "$base/") = CalDavClient(root, "alice", "secret")

    private fun HttpExchange.reply(code: Int, body: String = "", type: String = "text/xml; charset=utf-8") {
        if (body.isEmpty()) { sendResponseHeaders(code, -1); close(); return }
        val bytes = body.toByteArray(Charsets.UTF_8)
        responseHeaders.add("Content-Type", type)
        sendResponseHeaders(code, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun HttpExchange.redirect(code: Int, to: String) {
        responseHeaders.add("Location", to)
        reply(code)
    }

    /**
     * Everything under /old/ answers every method with a 302 to the same path under /new/, where
     * GET works (200) like a real server's moved collection; /moved/ does the same with a 307;
     * /dav/ and /alice/ serve the discovery fixtures; /boom/ is a 500 and /locked/ a 401.
     */
    @Before fun startServer() {
        http = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        http.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val path = ex.requestURI.path
            seen.add(Seen(ex.requestMethod, path, body))
            when {
                path.startsWith("/old/") -> ex.redirect(302, "/new/" + path.removePrefix("/old/"))
                path.startsWith("/moved/") -> ex.redirect(307, "/new/" + path.removePrefix("/moved/"))
                path.startsWith("/new/") -> when (ex.requestMethod) {
                    "GET" -> ex.reply(200, "<html>a perfectly happy page</html>", "text/html")
                    "PUT" -> ex.reply(201)
                    "DELETE" -> ex.reply(204)
                    else -> ex.reply(405)
                }
                path.startsWith("/good/") -> when (ex.requestMethod) {
                    "DELETE" -> ex.reply(204)
                    else -> ex.reply(404)
                }
                path.startsWith("/boom/") -> ex.reply(500, "oops", "text/plain")
                path.startsWith("/locked/") -> ex.reply(401, "no", "text/plain")
                path == "/.well-known/caldav" -> ex.redirect(301, "/dav/")
                path == "/dav/" && ex.requestMethod == "PROPFIND" ->
                    if (body.contains("current-user-principal")) ex.reply(207, fixture("radicale/principal.xml"))
                    else ex.reply(400, "the PROPFIND body did not survive the redirect", "text/plain")
                path == "/alice/" && ex.requestMethod == "PROPFIND" -> when {
                    body.contains("calendar-home-set") -> ex.reply(207, fixture("radicale/home-set.xml"))
                    body.contains("displayname") -> ex.reply(207, fixture("radicale/calendar-list.xml"))
                    else -> ex.reply(400, "unexpected PROPFIND body", "text/plain")
                }
                else -> ex.reply(404)
            }
        }
        http.start()
    }

    @After fun stopServer() = http.stop(0)

    private fun assertRedirectMessage(e: IllegalArgumentException) =
        assertEquals("the server redirected the request — reconnect using its final address", e.message)

    private fun assertNeverFollowed() =
        assertTrue("a request reached the redirect target: " + seen.map { "${it.method} ${it.path}" },
            seen.none { it.path.startsWith("/new/") })

    // ------------------------------------------------------------ redirected writes

    @Test fun aRedirectedPutIsAFailureNotAStoredEvent() {
        try {
            client().addEvent("$base/old/cal/", "Dentist", 1_800_000_000_000, 1_800_003_600_000, false)
            fail("addEvent reported success although the server only redirected the PUT")
        } catch (e: IllegalArgumentException) {
            assertRedirectMessage(e)
        }
        assertEquals(listOf("PUT"), seen.map { it.method }) // one PUT, and nothing replayed as a GET
        assertNeverFollowed()
    }

    @Test fun aRedirectedPutIsAFailureForAllDayAnd307Too() {
        for (root in listOf("$base/old/cal/", "$base/moved/cal/")) {
            seen.clear()
            try {
                client().addEvent(root, "Day off", 1_800_000_000_000, 1_800_086_400_000, true)
                fail("success for $root")
            } catch (e: IllegalArgumentException) {
                assertRedirectMessage(e)
            }
            assertNeverFollowed()
        }
    }

    @Test fun aRedirectedDeleteIsAFailureNotARemoval() {
        try {
            client().deleteByUid(listOf("$base/old/cal/"), "uid-1")
            fail("deleteByUid reported 'not found' although the server redirected the DELETE")
        } catch (e: IllegalArgumentException) {
            assertRedirectMessage(e)
        }
        assertNeverFollowed()
        // The direct DELETE and the lookup REPORT were both tried, and both left alone at the redirect.
        assertEquals(listOf("DELETE", "REPORT"), seen.map { it.method })
    }

    @Test fun aRedirectingCalendarDoesNotBlockADeleteOnTheNextOne() {
        assertTrue(client().deleteByUid(listOf("$base/old/cal/", "$base/good/cal/"), "uid-1"))
        assertNeverFollowed()
        assertEquals("DELETE", seen.last().method)
        assertEquals("/good/cal/uid-1.ics", seen.last().path)
    }

    @Test fun anUnknownEventIsStillJustNotFound() {
        // No redirect involved: a 404 everywhere is a plain "nothing to delete", not an error.
        assertFalse(client().deleteByUid(listOf("$base/nothing/cal/"), "uid-1"))
    }

    @Test fun aRedirectedReadIsReportedNotParsedAsACalendar() {
        try {
            client().fetchIcs("$base/old/cal/", 0, 1_000_000_000_000)
            fail("expected an error")
        } catch (e: IllegalArgumentException) {
            assertRedirectMessage(e)
        }
        assertNeverFollowed()
    }

    // ------------------------------------------------------------ discovery still follows redirects

    @Test fun discoveryFollowsWellKnownAndKeepsThePropfindBody() {
        val cals = client().discoverCalendars()
        assertEquals(listOf("Empty Calendar", "Family & Friends"), cals.map { it.name })
        assertEquals("$base/alice/family/", cals.last().href) // resolved against the redirect's final host

        val principalAsk = seen.first { it.path == "/dav/" }
        assertEquals("PROPFIND", principalAsk.method) // not downgraded to GET by the 301
        assertTrue(principalAsk.body, principalAsk.body.contains("current-user-principal"))
        assertEquals(listOf("/", "/.well-known/caldav", "/dav/", "/alice/", "/alice/"), seen.map { it.path })
    }

    // ------------------------------------------------------------ unreachable vs. error status

    @Test fun nobodyListeningIsReportedAsUnreachableWithItsHost() {
        for (call in listOf<(CalDavClient) -> Any?>(
            { it.fetchIcs("http://127.0.0.1:1/cal/", 0, 1) },
            { it.addEvent("http://127.0.0.1:1/cal/", "x", 0, 1, false) },
            { it.discoverCalendars() },
        )) try {
            call(client("http://127.0.0.1:1/"))
            fail("expected failure")
        } catch (e: CalDavUnreachable) {
            assertEquals("127.0.0.1", e.host)
            assertTrue(e.message!!, e.message!!.startsWith("couldn't reach 127.0.0.1 ("))
        }
    }

    @Test fun httpErrorStatusesAreNotUnreachable() {
        // A 500 or a 401 means the server answered: another calendar on it is still worth trying.
        for (path in listOf("boom", "locked", "missing")) try {
            client().fetchIcs("$base/$path/cal/", 0, 1)
            fail("expected failure for $path")
        } catch (e: CalDavUnreachable) {
            fail("$path: an HTTP error status was classified as unreachable")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!, e.message!!.contains("500") || e.message!!.contains("401") || e.message!!.contains("404"))
        }
    }
}
