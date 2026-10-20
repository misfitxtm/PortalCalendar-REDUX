package com.portal.calendar

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The server couldn't be reached at all (DNS, connect, TLS or read timeout) — as opposed to
 * answering with an HTTP error. A refresh uses the type to stop trying the same dead host
 * for every remaining calendar. The message is fit to show to the user.
 */
class CalDavUnreachable(val host: String, message: String) : IllegalArgumentException(message)

/**
 * CalDAV over HTTP for one account on one server ([server] is the normalized root,
 * see [CalDavXml.normalizeServer]): calendar discovery, reading a calendar as ICS,
 * creating and deleting events. HTTP Basic auth only.
 *
 * Deliberately free of android.* — [CalDav] holds the stored account and calls
 * this — so the real requests can be exercised from a JVM test against a local
 * server (Radicale). All failures surface as IllegalArgumentException with a message
 * fit to show on the config page.
 */
class CalDavClient(private val server: String, private val user: String, password: String) {
    private val auth = Credentials.basic(user, password, Charsets.UTF_8)
    private val icloud = CalDavXml.isIcloud(server)

    private class Reply(val code: Int, val body: String, val url: String) {
        val ok get() = code in 200..299 || code == 207
        val redirected get() = isRedirect(code)
    }

    // ------------------------------------------------------------ discovery

    /**
     * Signs in and lists the account's event calendars: principal → calendar home →
     * calendars. The principal is looked up at [server] first and, if that fails,
     * at `/.well-known/caldav` on the same host (RFC 6764).
     */
    fun discoverCalendars(): List<CalInfo> {
        val principalUrl = findPrincipal()
        val homeReply = propfind(principalUrl, 0, CalDavXml.HOME_SET_BODY)
        checkAuth(homeReply)
        val home = (if (homeReply.ok) CalDavXml.calendarHomeHref(homeReply.body) else null)
            ?: throw IllegalArgumentException("signed in, but couldn't locate the calendar home" +
                if (homeReply.ok) "" else " (HTTP ${homeReply.code})")
        val homeUrl = CalDavXml.resolve(homeReply.url, home)
        val listReply = propfind(homeUrl, 1, CalDavXml.LIST_BODY)
        checkAuth(listReply)
        if (!listReply.ok) throw IllegalArgumentException("${serverName()} error HTTP ${listReply.code}")
        return CalDavXml.calendars(listReply.body, listReply.url)
    }

    private fun findPrincipal(): String {
        val tries = listOf(server, CalDavXml.wellKnownUrl(server)).distinct()
        var unauthorized = false
        var detail: String? = null
        for (url in tries) {
            val r = propfind(url, 0, CalDavXml.PRINCIPAL_BODY)
            if (r.code == 401) unauthorized = true
            if (r.ok) {
                val href = CalDavXml.principalHref(r.body)
                if (href != null) return CalDavXml.resolve(r.url, href)
                if (detail == null) detail = "no CalDAV account found there"
            } else if (detail == null) {
                detail = "HTTP ${r.code}"
            }
        }
        if (unauthorized) throw IllegalArgumentException(signInFailed(401))
        throw IllegalArgumentException(
            if (icloud) "iCloud sign-in failed — check the Apple ID, and use an app-specific password (not the normal account password)"
            else "couldn't find a CalDAV account at $server ($detail) — check the server address and username")
    }

    // ------------------------------------------------------------ reading

    /**
     * The calendar's events overlapping [startMillis]..[endMillis] as iCalendar text
     * (several VCALENDARs concatenated — biweekly reads those). Recurring series
     * come back as their master, not expanded.
     */
    fun fetchIcs(calendarHref: String, startMillis: Long, endMillis: Long): String {
        val r = request("REPORT", calendarHref, 1, CalDavXml.calendarQueryBody(startMillis, endMillis))
        checkAuth(r)
        if (r.redirected) throw IllegalArgumentException(REDIRECTED)
        if (r.code == 404 || r.code == 410)
            throw IllegalArgumentException("that calendar wasn't found on the server (HTTP ${r.code}) — it may have been renamed or deleted")
        if (!r.ok) throw IllegalArgumentException("${serverName()} error HTTP ${r.code}")
        return CalDavXml.multistatusToIcs(r.body)
    }

    // ------------------------------------------------------------ writing

    /** Creates a VEVENT in the given calendar collection; returns its UID. */
    fun addEvent(target: String, title: String,
                 startMillis: Long, endMillis: Long, allDay: Boolean): String {
        val uid = UUID.randomUUID().toString().uppercase(Locale.US)
        val utc = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
        val dateOnly = SimpleDateFormat("yyyyMMdd", Locale.US)
        val body = buildString {
            append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Portal Family Calendar//EN\r\n")
            append("BEGIN:VEVENT\r\nUID:").append(uid).append("\r\n")
            append("DTSTAMP:").append(utc.format(Date())).append("\r\n")
            append("SUMMARY:").append(escape(title)).append("\r\n")
            if (allDay) {
                append("DTSTART;VALUE=DATE:").append(dateOnly.format(Date(startMillis))).append("\r\n")
                append("DTEND;VALUE=DATE:").append(dateOnly.format(Date(endMillis))).append("\r\n")
            } else {
                append("DTSTART:").append(utc.format(Date(startMillis))).append("\r\n")
                append("DTEND:").append(utc.format(Date(endMillis))).append("\r\n")
            }
            append("END:VEVENT\r\nEND:VCALENDAR\r\n")
        }
        val url = CalDavXml.resolve(server, target).trimEnd('/') + "/" + uid + ".ics"
        val req = Request.Builder().url(url)
            .header("Authorization", auth)
            .header("If-None-Match", "*")
            .put(body.toRequestBody(ICS))
            .build()
        // Redirects are NOT followed for a write: OkHttp would replay a redirected PUT as a body-less
        // GET, which succeeds and makes it look as if the event was stored.
        val code = execute(req, follow = false) { it.code }
        if (isRedirect(code)) throw IllegalArgumentException(REDIRECTED)
        if (code == 401)
            throw IllegalArgumentException(
                if (icloud) "iCloud rejected the sign-in — reconnect with a fresh app-specific password"
                else "the CalDAV server rejected the sign-in — reconnect with the current password")
        if (code !in 200..299)
            throw IllegalArgumentException("${if (icloud) "iCloud" else "the CalDAV server"} refused the event (HTTP $code)")
        return uid
    }

    /**
     * Deletes the VEVENT with this UID from whichever of [calendarHrefs] holds it
     * (located via a CalDAV calendar-query REPORT). Returns true if removed, false if no
     * calendar had it. Throws if nothing was removed and some request was answered with a
     * redirect: a redirected DELETE has not deleted anything, so "not found" would be wrong.
     */
    fun deleteByUid(calendarHrefs: List<String>, uid: String): Boolean {
        if (uid.isBlank()) return false
        var redirected = false
        for (calHref in calendarHrefs) {
            // Fast path: events we (and Apple's own clients) create live at
            // {collection}/{uid}.ics. Try a direct DELETE first; a 404 just
            // means "not at the convention URL" → fall through to the lookup.
            // Encode the UID as a path segment — a feed UID can contain /, #, ?
            // which would otherwise re-target the request at the wrong resource.
            val direct = calHref.trimEnd('/') + "/" + encodeSegment(uid) + ".ics"
            when (tryDelete(direct, treat404AsGone = false)) {
                Deleted.YES -> return true
                Deleted.REDIRECTED -> redirected = true
                Deleted.NO -> {}
            }
            // General path: locate the resource by UID (other clients name it
            // differently), then DELETE it. Only resources whose UID matches exactly.
            val hrefs = try {
                hrefsByUid(calHref, uid)
            } catch (e: RedirectedException) {
                redirected = true
                emptyList()
            }
            for (href in hrefs) {
                when (tryDelete(CalDavXml.resolve(calHref, href), treat404AsGone = true)) {
                    Deleted.YES -> return true
                    Deleted.REDIRECTED -> redirected = true
                    Deleted.NO -> {}
                }
            }
        }
        if (redirected) throw IllegalArgumentException(REDIRECTED)
        return false
    }

    private enum class Deleted { YES, NO, REDIRECTED }

    private class RedirectedException : Exception()

    private fun tryDelete(url: String, treat404AsGone: Boolean): Deleted = try {
        val req = Request.Builder().url(url)
            .header("Authorization", auth).delete().build()
        // Not followed: a redirected DELETE would be replayed as a GET, which "succeeds".
        execute(req, follow = false) {
            when {
                isRedirect(it.code) -> Deleted.REDIRECTED
                it.code in 200..299 || it.code == 410 || (treat404AsGone && it.code == 404) -> Deleted.YES
                else -> Deleted.NO
            }
        }
    } catch (e: Exception) { Deleted.NO }

    /**
     * Hrefs of the resources in a calendar collection whose VEVENT has exactly this UID.
     * The REPORT's `text-match` is a *substring* match (UID `evt-1` also finds `evt-10`),
     * so it only narrows the search: calendar-data is requested too and each candidate's
     * UID is compared exactly, to never delete a different event.
     */
    private fun hrefsByUid(calUrl: String, uid: String): List<String> {
        val body = """<?xml version="1.0" encoding="utf-8"?>
            <c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
            <d:prop><d:getetag/><c:calendar-data/></d:prop>
            <c:filter><c:comp-filter name="VCALENDAR"><c:comp-filter name="VEVENT">
            <c:prop-filter name="UID"><c:text-match collation="i;octet">${xmlEscape(uid)}</c:text-match></c:prop-filter>
            </c:comp-filter></c:comp-filter></c:filter></c:calendar-query>""".trimIndent()
        val r = request("REPORT", calUrl, 1, body)
        if (r.redirected) throw RedirectedException()
        if (!r.ok) return emptyList()
        return CalDavXml.hrefsWithExactUid(r.body, uid)
    }

    // ------------------------------------------------------------ plumbing

    private fun serverName() = if (icloud) "iCloud" else "CalDAV server"

    private fun signInFailed(code: Int) =
        if (icloud) "iCloud sign-in failed ($code) — use an app-specific password from account.apple.com, not the normal password"
        else "CalDAV sign-in failed ($code) — check the username and password (Nextcloud and Fastmail need an app password)"

    private fun checkAuth(r: Reply) {
        if (r.code == 401) throw IllegalArgumentException(signInFailed(401))
    }

    private fun propfind(url: String, depth: Int, body: String) = request("PROPFIND", url, depth, body)

    /**
     * One authenticated WebDAV request. Only PROPFIND (discovery, where `/.well-known/caldav`
     * is a redirect) follows redirects — OkHttp re-sends it with its body. Any other method
     * would be replayed as a body-less GET, so a 3xx comes back as-is for the caller to report.
     */
    private fun request(method: String, url: String, depth: Int, body: String): Reply {
        val req = Request.Builder().url(url)
            .header("Authorization", auth)
            .header("Depth", depth.toString())
            .method(method, body.toRequestBody(XML))
            .build()
        return execute(req, follow = method == "PROPFIND") {
            Reply(it.code, it.body?.string() ?: "", it.request.url.toString())
        }
    }

    private fun <T> execute(req: Request, follow: Boolean = true, read: (okhttp3.Response) -> T): T = try {
        (if (follow) http else httpNoRedirect).newCall(req).execute().use(read)
    } catch (e: IOException) {
        throw CalDavUnreachable(req.url.host,
            "couldn't reach ${req.url.host} (${e.message ?: e.javaClass.simpleName})")
    }

    private fun xmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun escape(s: String): String = s
        .replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")

    /** Same encoding as android.net.Uri.encode: keep A-Za-z0-9 and _-!.~'()*, percent-encode the rest as UTF-8. */
    private fun encodeSegment(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            if (c < 0x80 && (c.toChar().isLetterOrDigit() || c.toChar() in "_-!.~'()*")) sb.append(c.toChar())
            else sb.append('%').append("%02X".format(c))
        }
        return sb.toString()
    }

    companion object {
        private val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        /** For everything but discovery: a redirected write must fail, not be replayed as a GET. */
        private val httpNoRedirect = http.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        const val REDIRECTED = "the server redirected the request — reconnect using its final address"

        private fun isRedirect(code: Int) = code in 300..399 && code != 304

        private val XML = "application/xml; charset=utf-8".toMediaType()
        private val ICS = "text/calendar; charset=utf-8".toMediaType()
    }
}
