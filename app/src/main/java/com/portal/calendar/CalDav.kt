package com.portal.calendar

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * The connected CalDAV account (one per Portal): iCloud — Apple ID + app-specific
 * password (account.apple.com → Sign-In and Security → App-Specific Passwords) — or
 * any CalDAV server (Nextcloud, Radicale, Baïkal, Fastmail, Synology…) with a
 * username + password. Stores the account in prefs and delegates the protocol work
 * to [CalDavClient]/[CalDavXml].
 *
 * It does two jobs: [addEventTo]/[deleteByUid] write events straight into the real
 * calendar (so they back-sync to every family device), and [fetchIcs] reads a
 * calendar for the board — feeds with source "caldav" use it instead of a published
 * ICS link.
 *
 * Google calendars can't be written this way (their CalDAV requires OAuth), which is
 * why Google has its own API client.
 *
 * Pref keys keep their original iCloud-era names so an existing connection keeps
 * working untouched; `icloud_server` is the only addition (absent/blank = iCloud).
 */
object CalDav {
    private const val TAG = "PortalCalDAV"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)

    fun isConnected(ctx: Context): Boolean = !prefs(ctx).getString("icloud_email", null).isNullOrEmpty()
    fun email(ctx: Context): String? = prefs(ctx).getString("icloud_email", null)

    /** Normalized server root; iCloud's when `icloud_server` is absent or blank. */
    fun server(ctx: Context): String = CalDavXml.normalizeServer(prefs(ctx).getString("icloud_server", null))

    fun isIcloud(ctx: Context): Boolean = CalDavXml.isIcloud(server(ctx))

    /** Display host of the connected server (e.g. "cloud.example.com"). */
    fun host(ctx: Context): String = CalDavXml.hostOf(server(ctx))

    /** Short tag after a calendar's name in pickers: "iCloud" or "CalDAV". */
    fun label(ctx: Context): String = if (runCatching { isIcloud(ctx) }.getOrDefault(false)) "iCloud" else "CalDAV"

    fun calendars(ctx: Context): List<CalInfo> {
        val arr = JSONArray(prefs(ctx).getString("icloud_cals", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            CalInfo(o.getString("name"), o.getString("href"),
                o.optString("color").takeIf { c -> c.isNotEmpty() })
        }
    }

    fun disconnect(ctx: Context) {
        prefs(ctx).edit()
            .remove("icloud_email").remove("icloud_password").remove("icloud_server")
            .remove("icloud_cals").remove("icloud_target")
            .apply()
    }

    /**
     * Signs in, discovers calendars, persists everything. Returns the calendars.
     * A blank [server] means iCloud; otherwise it's the CalDAV server's address
     * (https:// is assumed when no scheme is typed).
     */
    fun connect(ctx: Context, server: String?, user: String, password: String): List<CalInfo> {
        val root = CalDavXml.normalizeServer(server)
        val cals = CalDavClient(root, user, password).discoverCalendars()
        if (cals.isEmpty())
            throw IllegalArgumentException("signed in, but found no calendars that can hold events on this account")
        val arr = JSONArray()
        cals.forEach {
            val o = JSONObject().put("name", it.name).put("href", it.href)
            if (it.color != null) o.put("color", it.color)
            arr.put(o)
        }
        val e = prefs(ctx).edit()
            .putString("icloud_email", user)
            .putString("icloud_password", password)
            .putString("icloud_cals", arr.toString())
        if (root == CalDavXml.ICLOUD_ROOT) e.remove("icloud_server") else e.putString("icloud_server", root)
        e.apply()
        android.util.Log.i(TAG, "connected: ${cals.size} calendar(s), " +
            if (CalDavXml.isIcloud(root)) "iCloud" else "custom server")
        return cals
    }

    private fun client(ctx: Context): CalDavClient {
        val user = email(ctx)
        val password = prefs(ctx).getString("icloud_password", null)
        if (user.isNullOrEmpty() || password == null)
            throw IllegalArgumentException("CalDAV account not connected")
        return CalDavClient(server(ctx), user, password)
    }

    /**
     * The calendar at [calendarHref] (an absolute collection URL from [calendars])
     * as iCalendar text, for the next ~6 months and the last ~5 weeks — the same
     * window the board expands recurring events over.
     */
    fun fetchIcs(ctx: Context, calendarHref: String): String {
        val client = client(ctx)
        // A feed's address is user-supplied and /api/config is open to the LAN, so the stored
        // credentials only go to the connected server (or a host it handed us a calendar on).
        if (!CalDavXml.isOnAccount(calendarHref, server(ctx), calendars(ctx).map { it.href }))
            throw IllegalArgumentException("not on the connected CalDAV account")
        val now = System.currentTimeMillis()
        return client.fetchIcs(calendarHref,
            now - SyncManager.WINDOW_PAST_DAYS * SyncManager.DAY_MS,
            now + SyncManager.WINDOW_FUTURE_DAYS * SyncManager.DAY_MS)
    }

    /** Creates a VEVENT in the given calendar collection; returns its UID. */
    fun addEventTo(ctx: Context, target: String, title: String,
                   startMillis: Long, endMillis: Long, allDay: Boolean): String {
        return client(ctx).addEvent(target, title, startMillis, endMillis, allDay)
    }

    /**
     * Deletes the VEVENT with this UID from whichever connected calendar holds
     * it (located via a CalDAV calendar-query REPORT). Returns true if removed.
     */
    fun deleteByUid(ctx: Context, uid: String): Boolean {
        if (uid.isBlank()) return false
        val c = runCatching { client(ctx) }.getOrNull() ?: return false
        return c.deleteByUid(calendars(ctx).map { it.href }, uid)
    }

    /** Builds start/end millis from the config page's form fields (device tz). */
    fun eventWindow(date: String, time: String?, durationMins: Int, allDay: Boolean): Pair<Long, Long> {
        val parts = date.split("-").map { it.toInt() }
        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(parts[0], parts[1] - 1, parts[2])
        return if (allDay) {
            val start = cal.timeInMillis
            cal.add(Calendar.DAY_OF_MONTH, 1)
            start to cal.timeInMillis
        } else {
            val t = (time ?: "12:00").split(":").map { it.toInt() }
            cal.set(Calendar.HOUR_OF_DAY, t[0])
            cal.set(Calendar.MINUTE, t[1])
            val start = cal.timeInMillis
            start to start + durationMins * 60_000L
        }
    }
}
