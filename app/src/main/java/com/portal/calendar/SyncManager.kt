package com.portal.calendar

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.concurrent.Executors

data class EventInstance(
    val start: Long,
    val end: Long,
    val allDay: Boolean,
    val title: String,
    val location: String?,
    val feedName: String,
    val color: Int,
    /** Source iCal UID — the handle used to delete-with-sync (empty if absent). */
    val uid: String = "",
    /** True for any occurrence of a repeating series (deleting removes all). */
    val recurring: Boolean = false
)

/**
 * Fetches each configured ICS feed, caches the raw text on disk (so the board
 * still shows events after a reboot with no network), parses with the Rust core and
 * expands recurrences into concrete instances inside a rolling window.
 */
class SyncManager(private val ctx: Context, private val store: ConfigStore) {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun requestSync(onDone: (events: List<EventInstance>, problems: List<String>) -> Unit) {
        executor.execute {
            val feeds = store.feeds()
            val all = ArrayList<EventInstance>()
            val problems = ArrayList<String>()
            for (feed in feeds) {
                val text = fetchWithCache(feed, problems) ?: continue
                try {
                    if (feed.kind == "inbox") processInbox(text)
                    else all.addAll(parseFeed(text, feed, problems))
                } catch (e: Exception) {
                    problems.add("${feed.name}: unreadable feed (${e.javaClass.simpleName})")
                }
            }
            all.sortBy { it.start }
            main.post { onDone(all, problems) }
        }
    }

    /** Network first; on failure falls back to the last good copy on disk. */
    private fun fetchWithCache(feed: FeedConfig, problems: MutableList<String>): String? {
        val cache = File(ctx.filesDir, "feed_" + md5(feed.url) + ".ics")
        try {
            val url = if (feed.url.startsWith("webcal://"))
                "https://" + feed.url.removePrefix("webcal://") else feed.url
            val conn = UrlGuard.open(url) {
                it.connectTimeout = 10_000
                it.readTimeout = 20_000
                it.setRequestProperty("User-Agent", "PortalFamilyCalendar/1.0")
            }
            try {
                if (conn.responseCode !in 200..299)
                    throw RuntimeException("HTTP ${conn.responseCode}")
                val text = UrlGuard.readCapped(conn.inputStream)
                if (!text.contains("BEGIN:VCALENDAR"))
                    throw RuntimeException("not an iCal feed")
                // Atomic: a crash mid-write must not corrupt the offline copy.
                val tmp = File(cache.path + ".tmp")
                tmp.writeText(text)
                tmp.renameTo(cache)
                return text
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            return if (cache.exists()) {
                problems.add("${feed.name}: offline, showing cached copy")
                cache.readText()
            } else {
                problems.add("${feed.name}: fetch failed (${e.message ?: e.javaClass.simpleName})")
                null
            }
        }
    }

    private fun parseFeed(text: String, feed: FeedConfig, problems: MutableList<String>? = null): List<EventInstance> {
        val out = ArrayList<EventInstance>()
        val now = System.currentTimeMillis()
        val windowStart = now - 35L * DAY_MS
        val windowEnd = now + 180L * DAY_MS
        // UIDs the user deleted from the board — keep them hidden until the
        // source feed catches up (Google's secret iCal can lag hours).
        val suppressed = DeletedEvents.activeUids(ctx)
        val parsed = IcsEvents.parse(text, windowStart, windowEnd)
        if (parsed.skipped > 0) problems?.add("${feed.name}: ${parsed.skipped} event(s) couldn't be read and were skipped")

        for (e in parsed.events) {
            if (e.cancelled) continue
            if (e.uid.isNotEmpty() && e.uid in suppressed) continue // deleted from the board

            // Magic-word events route to lists/chores and never render.
            val magic = MagicWords.match(e.summary)
            if (magic != null) {
                val uid = e.uid.ifEmpty { feed.url + e.summary }
                // Mark AFTER a successful execute so a failed command
                // retries next sync instead of being consumed silently.
                if (!MagicWords.isProcessed(ctx, uid)) {
                    runCatching { MagicWords.execute(ctx, magic, e.startMs) }
                        .onSuccess { MagicWords.markProcessed(ctx, uid) }
                }
                continue
            }

            val title = e.summary.trim().ifEmpty { "(untitled)" }
            val loc = e.location.trim().ifEmpty { null }
            when {
                // Edited single instance of a series, or a one-off event: shown if it overlaps the window.
                e.recurrenceIdMs != null || !e.recurring ->
                    if (e.inWindow) out.add(EventInstance(e.startMs, e.startMs + e.durationMs, e.allDay, title, loc,
                        feed.name, feed.color, e.uid, e.recurrenceIdMs != null))
                // Series master: the occurrences were expanded natively (RRULE + RDATE - EXDATE - edited instances).
                else -> for (t in e.instances)
                    out.add(EventInstance(t, t + e.durationMs, e.allDay, title, loc, feed.name, feed.color, e.uid, true))
            }
        }
        return out
    }

    /**
     * Test-fetches a feed URL (called from the config page when adding a
     * calendar, on the HTTP worker thread). Returns the event count; throws
     * IllegalArgumentException with a readable message if anything is wrong.
     */
    fun validateFeed(rawUrl: String): Int {
        val url = if (rawUrl.startsWith("webcal://"))
            "https://" + rawUrl.removePrefix("webcal://") else rawUrl
        try {
            val conn = UrlGuard.open(url) {
                it.connectTimeout = 10_000
                it.readTimeout = 20_000
                it.setRequestProperty("User-Agent", "PortalFamilyCalendar/1.0")
            }
            try {
                if (conn.responseCode !in 200..299)
                    throw IllegalArgumentException(
                        "the server said HTTP ${conn.responseCode} — check the link is the iCal/secret address")
                val text = UrlGuard.readCapped(conn.inputStream)
                if (!text.contains("BEGIN:VCALENDAR"))
                    throw IllegalArgumentException(
                        "that link isn't a calendar feed — it should end in .ics or start with webcal://")
                return IcsEvents.parse(text, 0, 0).events.size
            } finally {
                conn.disconnect()
            }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("couldn't reach that link (${e.message ?: e.javaClass.simpleName})")
        }
    }

    /** Inbox calendars: every event is a command, parsed loosely, never rendered. */
    private fun processInbox(text: String) {
        val now = System.currentTimeMillis()
        for (e in IcsEvents.parse(text, now - 35L * DAY_MS, now + 180L * DAY_MS).events) {
            if (e.cancelled) continue
            val title = e.summary.trim()
            if (title.isEmpty()) continue
            val uid = e.uid.ifEmpty { title }
            if (MagicWords.isProcessed(ctx, uid)) continue
            // Mark only after success — a failed command retries next sync.
            runCatching { MagicWords.execute(ctx, MagicWords.parseSmart(ctx, title), e.startMs) }
                .onSuccess { MagicWords.markProcessed(ctx, uid) }
        }
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val DAY_MS = 86_400_000L
    }
}
