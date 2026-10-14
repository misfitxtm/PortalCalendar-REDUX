package com.portal.calendar

import org.json.JSONObject
import java.util.TimeZone

/** One VEVENT as the Rust parser reports it. [instances] are the occurrences inside the window (recurring masters only). */
data class RawEvent(
    val uid: String,
    val summary: String,
    val location: String,
    val cancelled: Boolean,
    val startMs: Long,
    val allDay: Boolean,
    val durationMs: Long,
    val recurrenceIdMs: Long?,
    val recurring: Boolean,
    val instances: List<Long>,
    val inWindow: Boolean
)

data class IcsResult(val events: List<RawEvent>, val skipped: Int, val tzFallbacks: Int)

/** Feed parsing runs in the Rust core: the download is untrusted, and the parser has hard caps. */
object IcsEvents {
    fun parse(text: String, windowStartMs: Long, windowEndMs: Long): IcsResult {
        val root = JSONObject(PortalCore.parseIcs(text, windowStartMs, windowEndMs, TimeZone.getDefault().id))
        root.optString("error").takeIf { it.isNotEmpty() }?.let { throw IllegalStateException(it) }
        val arr = root.getJSONArray("events")
        val events = ArrayList<RawEvent>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val inst = o.getJSONArray("instances")
            events.add(RawEvent(
                o.getString("uid"), o.getString("summary"), o.getString("location"), o.getBoolean("cancelled"),
                o.getLong("startMs"), o.getBoolean("allDay"), o.getLong("durationMs"),
                if (o.isNull("recurrenceIdMs")) null else o.getLong("recurrenceIdMs"),
                o.getBoolean("recurring"), List(inst.length()) { inst.getLong(it) }, o.getBoolean("inWindow")))
        }
        return IcsResult(events, root.optInt("skipped"), root.optInt("tzFallbacks"))
    }
}
