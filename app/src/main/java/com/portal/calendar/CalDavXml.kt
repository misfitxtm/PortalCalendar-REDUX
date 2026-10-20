package com.portal.calendar

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI
import java.net.URLDecoder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** A calendar collection on a CalDAV account; [color] is "#rrggbb" when the server reports one. */
data class CalInfo(val name: String, val href: String, val color: String? = null)

/**
 * The pure half of the CalDAV client: request bodies, URL handling and WebDAV XML
 * extraction. Nothing here touches android.* (or the network), so it runs in plain
 * JVM unit tests against captured server responses.
 *
 * XML is read with a small tolerant tag scanner rather than a DOM parser: servers
 * disagree on namespace prefixes (`cal:`, `C:`, a default namespace…) and a few emit
 * slightly broken markup, and the scanner only ever needs element *local names*.
 * Entities and CDATA are decoded by hand ([text]).
 */
object CalDavXml {
    const val ICLOUD_ROOT = "https://caldav.icloud.com/"

    /** What a feed gets when a calendar has no events — valid, so it isn't treated as an error. */
    const val EMPTY_CALENDAR =
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Portal Family Calendar//EN\r\nEND:VCALENDAR\r\n"

    const val PRINCIPAL_BODY =
        """<d:propfind xmlns:d="DAV:"><d:prop><d:current-user-principal/></d:prop></d:propfind>"""
    const val HOME_SET_BODY =
        """<d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:prop><c:calendar-home-set/></d:prop></d:propfind>"""
    const val LIST_BODY =
        """<d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:a="http://apple.com/ns/ical/">""" +
        """<d:prop><d:displayname/><d:resourcetype/><c:supported-calendar-component-set/><a:calendar-color/></d:prop>""" +
        """</d:propfind>"""

    // ----------------------------------------------------------- addresses

    /** Server root for the stored `icloud_server` pref: blank = iCloud; a bare host gets https://. */
    fun normalizeServer(raw: String?): String {
        var s = raw?.trim().orEmpty()
        if (s.isEmpty()) return ICLOUD_ROOT
        if (!Regex("^[A-Za-z][A-Za-z0-9+.\\-]*://").containsMatchIn(s)) s = "https://$s"
        val url = s.toHttpUrlOrNull() // null for anything but a well-formed http(s) URL
            ?: throw IllegalArgumentException(
                "that doesn't look like a server address — try something like https://cloud.example.com/remote.php/dav")
        if (url.username.isNotEmpty() || url.password.isNotEmpty())
            throw IllegalArgumentException(
                "leave the username and password out of the server address — they have their own fields")
        return url.newBuilder().fragment(null).build().toString()
    }

    fun isIcloud(server: String): Boolean {
        val host = server.toHttpUrlOrNull()?.host ?: return false
        return host == "icloud.com" || host.endsWith(".icloud.com")
    }

    fun hostOf(server: String): String = server.toHttpUrlOrNull()?.host ?: server

    /**
     * `scheme://host:port` of a URL (default ports spelled out, host lower-cased), or null if
     * it isn't a valid http(s) URL. Two calendars on the same server share it.
     */
    fun originOf(url: String): String? =
        url.trim().toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}:${it.port}" }

    /** `<scheme>://<host>[:port]/.well-known/caldav` (RFC 6764) for a server address. */
    fun wellKnownUrl(server: String): String {
        val url = server.toHttpUrlOrNull() ?: throw IllegalArgumentException("bad server address: $server")
        return url.newBuilder().encodedPath("/.well-known/caldav").query(null).fragment(null).build().toString()
    }

    /** Absolute hrefs are returned as-is; relative ones are resolved against [base]. */
    fun resolve(base: String, href: String): String {
        if (href.startsWith("http://") || href.startsWith("https://")) return href
        return try {
            URI(base).resolve(href).toString()
        } catch (e: Exception) {
            // Servers sometimes send unencoded spaces/unicode in hrefs, which URI rejects.
            base.toHttpUrlOrNull()?.resolve(href)?.toString()
                ?: throw IllegalArgumentException("the server sent an address that can't be used: $href")
        }
    }

    /**
     * `apple:calendar-color` is `#RRGGBB` or `#RRGGBBAA` — keep the colour, drop the
     * alpha, lower-case it. Anything else → null.
     */
    fun parseColor(raw: String?): String? {
        val m = Regex("^#([0-9a-fA-F]{6})(?:[0-9a-fA-F]{2})?$").find(raw?.trim().orEmpty()) ?: return null
        return "#" + m.groupValues[1].lowercase()
    }

    // ----------------------------------------------------------- request bodies

    private val CALDAV_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    /** calendar-query REPORT: every VEVENT resource with an occurrence in the window (UTC). */
    fun calendarQueryBody(startMillis: Long, endMillis: Long): String {
        val start = CALDAV_TIME.format(Instant.ofEpochMilli(startMillis))
        val end = CALDAV_TIME.format(Instant.ofEpochMilli(endMillis))
        return """<?xml version="1.0" encoding="utf-8"?>
<c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
<d:prop><c:calendar-data/></d:prop>
<c:filter><c:comp-filter name="VCALENDAR"><c:comp-filter name="VEVENT">
<c:time-range start="$start" end="$end"/>
</c:comp-filter></c:comp-filter></c:filter></c:calendar-query>"""
    }

    // ----------------------------------------------------------- responses

    /** `current-user-principal` href from a PROPFIND multistatus, or null. */
    fun principalHref(xml: String): String? = hrefInProp(xml, "current-user-principal")

    /** First `calendar-home-set` href from a PROPFIND multistatus, or null. */
    fun calendarHomeHref(xml: String): String? = hrefInProp(xml, "calendar-home-set")

    private fun hrefInProp(xml: String, prop: String): String? {
        for (resp in elements(xml, "response")) {
            val el = elements(okProps(resp.inner), prop).firstOrNull() ?: continue
            for (h in elements(el.inner, "href")) {
                val v = text(h.inner).trim()
                if (v.isNotEmpty()) return v
            }
        }
        return null
    }

    /**
     * The event calendars in a Depth:1 PROPFIND of the calendar home. Inbox/outbox/
     * notification collections (not `calendar` resources) and task/journal-only lists
     * are skipped. A calendar with no display name falls back to its last path segment.
     */
    fun calendars(xml: String, baseUrl: String): List<CalInfo> {
        val out = ArrayList<CalInfo>()
        for (resp in elements(xml, "response")) {
            val href = elements(resp.inner, "href").firstOrNull()?.let { text(it.inner).trim() }
                ?.takeIf { it.isNotEmpty() } ?: continue
            val props = okProps(resp.inner)
            // A real calendar: resourcetype holds a CalDAV <calendar/> element, whatever
            // its prefix (iCloud writes it with an inline xmlns).
            val rt = elements(props, "resourcetype").firstOrNull() ?: continue
            if (elements(rt.inner, "calendar").isEmpty()) continue
            val set = elements(props, "supported-calendar-component-set").firstOrNull()
            if (set != null) {
                val comps = elements(set.inner, "comp").mapNotNull { attr(it.attrs, "name")?.uppercase() }
                if (comps.isNotEmpty() && "VEVENT" !in comps) continue
            }
            val url = resolve(baseUrl, href)
            if (out.any { it.href == url }) continue
            val name = elements(props, "displayname").firstOrNull()?.let { text(it.inner).trim() }
                .takeUnless { it.isNullOrEmpty() } ?: lastSegment(url)
            val color = elements(props, "calendar-color").firstOrNull()?.let { parseColor(text(it.inner)) }
            out.add(CalInfo(name, url, color))
        }
        return out
    }

    /**
     * Concatenates every VCALENDAR in a calendar-query REPORT multistatus (each
     * resource's `calendar-data`, CDATA or entity-escaped) into one string that
     * biweekly parses as several calendars. No events → [EMPTY_CALENDAR]. Throws if
     * the reply isn't a multistatus at all (e.g. an HTML login page).
     */
    fun multistatusToIcs(xml: String): String {
        if (!Regex("<(?:[A-Za-z_][\\w.\\-]*:)?multistatus[\\s/>]").containsMatchIn(xml))
            throw IllegalArgumentException("the server's reply wasn't a CalDAV calendar listing")
        val parts = ArrayList<String>()
        for (resp in elements(xml, "response")) {
            for (data in elements(okProps(resp.inner), "calendar-data")) {
                val ics = text(data.inner, stripTags = false).trim()
                if (ics.contains("BEGIN:VCALENDAR")) parts.add(ics)
            }
        }
        return if (parts.isEmpty()) EMPTY_CALENDAR else parts.joinToString("\r\n", postfix = "\r\n")
    }

    /**
     * Hrefs of the resources in a calendar-query multistatus whose VEVENT has UID exactly
     * [uid]. The query's UID `text-match` is only a pre-filter — CalDAV defines it as a
     * substring match, so asking for `evt-1` also returns `evt-10` — which is why the
     * reply must include `calendar-data` and every candidate is checked here.
     */
    fun hrefsWithExactUid(xml: String, uid: String): List<String> {
        val out = ArrayList<String>()
        for (resp in elements(xml, "response")) {
            val href = elements(resp.inner, "href").firstOrNull()?.let { text(it.inner).trim() }
                ?.takeIf { it.isNotEmpty() } ?: continue
            val has = elements(okProps(resp.inner), "calendar-data")
                .any { uid in eventUids(text(it.inner, stripTags = false)) }
            if (has && href !in out) out.add(href)
        }
        return out
    }

    /**
     * The UID of every VEVENT in iCalendar text, after unfolding continuation lines. Only
     * the VEVENT's own UID counts (not a nested VALARM's). Each UID is listed as written
     * and with TEXT escapes (`\,` `\;` `\\` `\n`) resolved, so it matches however the
     * caller learned the UID.
     */
    fun eventUids(ics: String): Set<String> {
        val uids = LinkedHashSet<String>()
        val stack = ArrayList<String>()
        for (line in ics.replace(Regex("\r?\n[ \t]|\r[ \t]"), "").split(Regex("\r\n|\n|\r"))) {
            val colon = valueColon(line)
            if (colon < 0) continue
            val name = line.substring(0, colon).substringBefore(';').trim().uppercase()
            val value = line.substring(colon + 1)
            when {
                name == "BEGIN" -> stack.add(value.trim().uppercase())
                name == "END" -> {
                    val at = stack.lastIndexOf(value.trim().uppercase())
                    if (at >= 0) while (stack.size > at) stack.removeAt(stack.size - 1)
                }
                name == "UID" && stack.lastOrNull() == "VEVENT" -> {
                    uids.add(value)
                    uids.add(unescapeIcalText(value))
                }
            }
        }
        return uids
    }

    /** Index of the `:` that ends a content line's name and parameters (ignoring colons inside quoted parameter values). */
    private fun valueColon(line: String): Int {
        var quoted = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') quoted = !quoted
            else if (c == ':' && !quoted) return i
        }
        return -1
    }

    private fun unescapeIcalText(s: String): String {
        if (s.indexOf('\\') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n', 'N' -> sb.append('\n')
                    ',', ';', '\\' -> sb.append(n)
                    else -> sb.append(c).append(n)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /**
     * Whether [url] is on the connected account: same scheme, host and port as the server
     * root [server], or as one of the account's calendar hrefs [calendarHrefs] (iCloud
     * keeps calendars on per-account shard hosts such as p41-caldav.icloud.com). Used to
     * keep the stored credentials from being sent to a feed-supplied address anywhere else.
     */
    fun isOnAccount(url: String, server: String, calendarHrefs: List<String>): Boolean {
        val target = url.trim().toHttpUrlOrNull() ?: return false
        return (listOf(server) + calendarHrefs).any { known ->
            val k = known.toHttpUrlOrNull()
            k != null && k.scheme == target.scheme && k.host == target.host && k.port == target.port
        }
    }

    private fun lastSegment(url: String): String {
        val seg = url.substringBefore('?').trimEnd('/').substringAfterLast('/')
        val decoded = try { URLDecoder.decode(seg.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { seg }
        return decoded.ifEmpty { "Calendar" }
    }

    /** The `prop` bodies of a response's successful (2xx) propstats, joined. */
    private fun okProps(responseInner: String): String {
        val sb = StringBuilder()
        for (ps in elements(responseInner, "propstat")) {
            val status = elements(ps.inner, "status").firstOrNull()?.let { text(it.inner) }.orEmpty()
            if (status.isNotBlank() && !Regex("\\s2\\d\\d(\\s|$)").containsMatchIn(status)) continue
            for (p in elements(ps.inner, "prop")) sb.append(p.inner)
        }
        return sb.toString()
    }

    // ----------------------------------------------------------- tiny XML toolkit

    /** An element found by [elements]: qualified [name], raw attribute text, raw inner markup. */
    class El(val name: String, val attrs: String, val inner: String)

    /**
     * Every element whose *local* name (prefix ignored) is [local], outermost first, in
     * document order. A same-named element nested inside one stays in its parent's
     * [El.inner]. Comments, CDATA, PIs and DOCTYPE are skipped, so markup-looking text
     * inside them can't be mistaken for tags.
     */
    fun elements(xml: String, local: String): List<El> {
        val out = ArrayList<El>()
        val n = xml.length
        var i = 0
        var depth = 0
        var capName = ""
        var capAttrs = ""
        var capStart = 0
        while (i < n) {
            val lt = xml.indexOf('<', i)
            if (lt < 0) break
            if (xml.startsWith("<!--", lt)) { i = skipTo(xml, "-->", lt + 4); continue }
            if (xml.startsWith("<![CDATA[", lt)) { i = skipTo(xml, "]]>", lt + 9); continue }
            if (xml.startsWith("<?", lt)) { i = skipTo(xml, "?>", lt + 2); continue }
            if (xml.startsWith("<!", lt)) { i = skipTo(xml, ">", lt + 2); continue }
            val gt = tagEnd(xml, lt + 1)
            if (gt < 0) break
            i = gt + 1
            val closing = xml[lt + 1] == '/'
            var p = lt + (if (closing) 2 else 1)
            val nameStart = p
            while (p < gt && !xml[p].isWhitespace() && xml[p] != '/') p++
            val qname = xml.substring(nameStart, p)
            if (qname.substringAfter(':') != local) continue
            if (closing) {
                if (depth > 0 && --depth == 0) out.add(El(capName, capAttrs, xml.substring(capStart, lt)))
            } else {
                val selfClosing = xml[gt - 1] == '/'
                if (depth > 0) {
                    if (!selfClosing) depth++
                } else if (selfClosing) {
                    out.add(El(qname, xml.substring(p, gt - 1), ""))
                } else {
                    depth = 1; capName = qname; capAttrs = xml.substring(p, gt); capStart = gt + 1
                }
            }
        }
        return out
    }

    private fun skipTo(xml: String, end: String, from: Int): Int {
        val e = xml.indexOf(end, from)
        return if (e < 0) xml.length else e + end.length
    }

    /** Index of the `>` closing the tag whose body starts at [from], ignoring `>` inside quoted attribute values. */
    private fun tagEnd(xml: String, from: Int): Int {
        var quote = '\u0000'
        var i = from
        while (i < xml.length) {
            val c = xml[i]
            if (quote != '\u0000') { if (c == quote) quote = '\u0000' }
            else if (c == '"' || c == '\'') quote = c
            else if (c == '>') return i
            i++
        }
        return -1
    }

    /** Value of attribute [name] in the raw attribute text of a tag, entity-decoded. */
    fun attr(attrs: String, name: String): String? {
        val m = Regex("(?:^|\\s)" + Regex.escape(name) + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')").find(attrs) ?: return null
        return unescape(m.groups[1]?.value ?: m.groups[2]?.value ?: "")
    }

    /**
     * Text content of an element's [inner] markup: CDATA sections verbatim, everything
     * else with the five predefined entities and numeric references (`&#13;`, `&#xD;`,
     * `&#x1F600;`…) decoded. Child tags are dropped unless [stripTags] is false (used
     * for calendar-data, where a stray `<` from a sloppy server is iCalendar text).
     */
    fun text(inner: String, stripTags: Boolean = true): String {
        if (inner.indexOf('<') < 0 && inner.indexOf('&') < 0) return inner
        val sb = StringBuilder(inner.length)
        var i = 0
        while (i < inner.length) {
            val cd = inner.indexOf("<![CDATA[", i)
            val plainEnd = if (cd < 0) inner.length else cd
            if (plainEnd > i) {
                val seg = inner.substring(i, plainEnd)
                sb.append(unescape(if (stripTags) seg.replace(Regex("<[^>]*>"), "") else seg))
            }
            if (cd < 0) break
            val end = inner.indexOf("]]>", cd + 9)
            if (end < 0) { sb.append(inner, cd + 9, inner.length); break }
            sb.append(inner, cd + 9, end)
            i = end + 3
        }
        return sb.toString()
    }

    /** Decodes `&lt; &gt; &amp; &quot; &apos;` and decimal/hex character references; unknown entities stay literal. */
    fun unescape(s: String): String {
        if (s.indexOf('&') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val semi = s.indexOf(';', i + 1)
                if (semi >= i + 3 && semi <= i + 10) {
                    val ent = s.substring(i + 1, semi)
                    val rep: String? = when {
                        ent == "lt" -> "<"
                        ent == "gt" -> ">"
                        ent == "amp" -> "&"
                        ent == "quot" -> "\""
                        ent == "apos" -> "'"
                        ent.startsWith("#x") || ent.startsWith("#X") -> codePoint(ent.substring(2).toIntOrNull(16))
                        ent.startsWith("#") -> codePoint(ent.substring(1).toIntOrNull())
                        else -> null
                    }
                    if (rep != null) { sb.append(rep); i = semi + 1; continue }
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun codePoint(cp: Int?): String? =
        if (cp != null && cp in 1..0x10FFFF && cp !in 0xD800..0xDFFF) String(Character.toChars(cp)) else null
}
