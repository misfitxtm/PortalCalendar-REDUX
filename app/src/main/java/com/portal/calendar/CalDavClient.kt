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
        val code = execute(req) { it.code }
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
     * (located via a CalDAV calendar-query REPORT). Returns true if removed.
     */
    fun deleteByUid(calendarHrefs: List<String>, uid: String): Boolean {
        if (uid.isBlank()) return false
        for (calHref in calendarHrefs) {
            // Fast path: events we (and Apple's own clients) create live at
            // {collection}/{uid}.ics. Try a direct DELETE first; a 404 just
            // means "not at the convention URL" → fall through to the lookup.
            // Encode the UID as a path segment — a feed UID can contain /, #, ?
            // which would otherwise re-target the request at the wrong resource.
            val direct = calHref.trimEnd('/') + "/" + encodeSegment(uid) + ".ics"
            if (tryDelete(direct, treat404AsGone = false)) return true
            // General path: locate the resource by UID (other clients name it
            // differently), then DELETE it.
            val href = queryHrefByUid(calHref, uid) ?: continue
            if (tryDelete(CalDavXml.resolve(calHref, href), treat404AsGone = true)) return true
        }
        return false
    }

    private fun tryDelete(url: String, treat404AsGone: Boolean): Boolean = try {
        val req = Request.Builder().url(url)
            .header("Authorization", auth).delete().build()
        execute(req) { it.code in 200..299 || it.code == 410 || (treat404AsGone && it.code == 404) }
    } catch (e: Exception) { false }

    /** First resource href matching UID in a calendar collection, or null. */
    private fun queryHrefByUid(calUrl: String, uid: String): String? {
        val body = """<?xml version="1.0" encoding="utf-8"?>
            <c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
            <d:prop><d:getetag/></d:prop>
            <c:filter><c:comp-filter name="VCALENDAR"><c:comp-filter name="VEVENT">
            <c:prop-filter name="UID"><c:text-match collation="i;octet">${xmlEscape(uid)}</c:text-match></c:prop-filter>
            </c:comp-filter></c:comp-filter></c:filter></c:calendar-query>""".trimIndent()
        val req = Request.Builder().url(calUrl)
            .header("Authorization", auth)
            .header("Depth", "1")
            .method("REPORT", body.toRequestBody(XML))
            .build()
        val xml = http.newCall(req).execute().use { r ->
            if (r.code !in 200..299 && r.code != 207) return null
            r.body?.string() ?: return null
        }
        return Regex("<(?:\\w+:)?href[^>]*>([^<]+\\.ics)</", RegexOption.IGNORE_CASE)
            .find(xml)?.groupValues?.get(1)?.trim()
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

    /** One authenticated WebDAV request. Redirects are followed (OkHttp re-sends PROPFIND with its body). */
    private fun request(method: String, url: String, depth: Int, body: String): Reply {
        val req = Request.Builder().url(url)
            .header("Authorization", auth)
            .header("Depth", depth.toString())
            .method(method, body.toRequestBody(XML))
            .build()
        return execute(req) { Reply(it.code, it.body?.string() ?: "", it.request.url.toString()) }
    }

    private fun <T> execute(req: Request, read: (okhttp3.Response) -> T): T = try {
        http.newCall(req).execute().use(read)
    } catch (e: IOException) {
        throw IllegalArgumentException("couldn't reach ${req.url.host} (${e.message ?: e.javaClass.simpleName})")
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

        private val XML = "application/xml; charset=utf-8".toMediaType()
        private val ICS = "text/calendar; charset=utf-8".toMediaType()
    }
}
