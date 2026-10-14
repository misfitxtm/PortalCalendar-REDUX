package com.portal.calendar

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * Optional dry-night tracker. One mark per member per day (dry or wet); the
 * streak rules live in [Rules] so they are unit-tested off-device.
 */
object Potty {
    private const val FILE = "potty_log.json"
    private const val KEEP_DAYS = 400L
    private const val SHOW_DAYS = 120L

    private fun marksFor(log: JSONArray, memberId: String): Map<LocalDate, Boolean> {
        val m = HashMap<LocalDate, Boolean>()
        for (i in 0 until log.length()) {
            val e = log.getJSONObject(i)
            if (e.optString("memberId") != memberId) continue
            runCatching { LocalDate.parse(e.optString("date")) }.getOrNull()
                ?.let { m[it] = e.optBoolean("dry") }
        }
        return m
    }

    fun statusJson(ctx: Context): String {
        val today = LocalDate.now()
        val log = Data.readArray(ctx, FILE)
        val members = JSONArray()
        val everyone = JSONArray()
        val kids = ConfigStore(ctx).pottyKids()
        for (mem in Members.all(ctx)) {
            val on = mem.id in kids
            everyone.put(JSONObject().put("id", mem.id).put("name", mem.name).put("enabled", on))
            if (!on) continue
            val marks = marksFor(log, mem.id)
            val days = JSONObject()
            marks.filterKeys { it.isAfter(today.minusDays(SHOW_DAYS)) }
                .forEach { (d, dry) -> days.put(d.toString(), dry) }
            val streak = Rules.pottyStreak(marks, today)
            members.put(JSONObject()
                .put("id", mem.id).put("name", mem.name)
                .put("color", String.format("#%06X", 0xFFFFFF and mem.color))
                .put("streak", streak)
                .put("best", Rules.pottyBest(marks))
                .put("done", streak >= Rules.POTTY_GOAL_DAYS)
                .put("days", days))
        }
        return JSONObject().put("today", today.toString())
            .put("goal", Rules.POTTY_GOAL_DAYS).put("members", members)
            .put("everyone", everyone).toString()
    }

    /** action "mark": memberId, optional date (default today), state = dry | wet | clear. */
    fun mutate(ctx: Context, action: JSONObject): String {
        when (action.getString("action")) {
            "mark" -> {
                val mid = action.getString("memberId")
                if (Members.byId(ctx, mid) == null) throw IllegalArgumentException("unknown family member")
                val today = LocalDate.now()
                val date = action.optString("date").ifEmpty { today.toString() }.let {
                    runCatching { LocalDate.parse(it) }.getOrNull()
                        ?: throw IllegalArgumentException("that date isn't valid")
                }
                if (date.isAfter(today)) throw IllegalArgumentException("can't mark a day that hasn't happened")
                if (date.isBefore(today.minusDays(KEEP_DAYS))) throw IllegalArgumentException("that date is too old")
                val state = action.getString("state")
                if (state !in listOf("dry", "wet", "clear")) throw IllegalArgumentException("state must be dry, wet or clear")
                Data.mutate(ctx, FILE) { arr ->
                    for (i in arr.length() - 1 downTo 0) {
                        val e = arr.getJSONObject(i)
                        val old = runCatching { LocalDate.parse(e.optString("date")) }.getOrNull()
                        if (old == null || old.isBefore(today.minusDays(KEEP_DAYS))
                            || (e.optString("memberId") == mid && old == date)) arr.remove(i)
                    }
                    if (state != "clear") arr.put(JSONObject()
                        .put("memberId", mid).put("date", date.toString()).put("dry", state == "dry"))
                }
            }
            "setKids" -> {
                val ids = action.getJSONArray("memberIds")
                val known = Members.all(ctx).map { it.id }.toSet()
                // Unknown ids are dropped; an empty list is valid (nobody uses it).
                ConfigStore(ctx).setPottyKids((0 until ids.length()).map { ids.getString(it) }.filter { it in known })
            }
            else -> throw IllegalArgumentException("unknown action")
        }
        FamilySync.pushIfSpoke(ctx, "potty", action.toString())
        App.instance.notifyDataChanged()
        return statusJson(ctx)
    }
}
