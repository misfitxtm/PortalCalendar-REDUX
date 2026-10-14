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
    private const val BONUS = "potty_bonus.json" // which 7-day bonuses were already given, so undoing one by hand isn't re-awarded
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

    /** Erases every mark, accident and bonus record; which kids use the tracker stays. */
    fun clearAll(ctx: Context) {
        Data.writeArray(ctx, FILE, JSONArray())
        Data.writeArray(ctx, BONUS, JSONArray())
    }

    fun statusJson(ctx: Context): String {
        val today = LocalDate.now()
        val log = Data.readArray(ctx, FILE)
        val awake = HashMap<String, MutableList<String>>() // memberId -> dates of daytime accidents
        for (i in 0 until log.length()) log.getJSONObject(i).let {
            if (it.optString("kind") == "day") awake.getOrPut(it.optString("memberId")) { ArrayList() }.add(it.optString("date"))
        }
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
                .put("awake", JSONArray(awake[mem.id].orEmpty().filter { runCatching { LocalDate.parse(it) }.getOrNull()?.isAfter(today.minusDays(SHOW_DAYS)) == true }))
                .put("best", Rules.pottyBest(marks))
                .put("done", streak >= Rules.POTTY_GOAL_DAYS)
                .put("days", days))
        }
        return JSONObject().put("today", today.toString())
            .put("goal", Rules.POTTY_GOAL_DAYS).put("members", members)
            .put("everyone", everyone).toString()
    }

    /**
     * Gives +5 stars for every full block of 7 straight dry days, and takes a bonus back if the marks that earned it change
     * (a day is cleared or marked wet). Derived from the marks, so backfilling and fixing mistakes both stay correct.
     */
    private fun syncDryBonuses(ctx: Context, mid: String) {
        val marks = marksFor(Data.readArray(ctx, FILE), mid)
        val qualifying = Rules.dryBonusDates(marks).map { it.toString() }.toSet()
        val given = Data.readArray(ctx, BONUS).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            .filter { it.optString("memberId") == mid }.map { it.optString("date") }.toSet()
        fun key(d: String) = "dry7:$mid:$d"
        for (d in given - qualifying) {
            Chores.mutate(ctx, JSONObject().put("action", "revokeBonus").put("key", key(d)))
            Data.mutate(ctx, BONUS) { arr ->
                for (i in arr.length() - 1 downTo 0)
                    if (arr.getJSONObject(i).optString("memberId") == mid && arr.getJSONObject(i).optString("date") == d) arr.remove(i)
            }
        }
        val fresh = qualifying - given
        for (d in fresh.sorted()) {
            Chores.mutate(ctx, JSONObject().put("action", "awardBonus").put("memberId", mid).put("key", key(d))
                .put("delta", Rules.DRY_BONUS_STARS).put("reason", "${Rules.DRY_BONUS_DAYS} dry days in a row: +${Rules.DRY_BONUS_STARS} ⭐"))
            Data.mutate(ctx, BONUS) { arr -> arr.put(JSONObject().put("memberId", mid).put("date", d)) }
        }
        if (fresh.isNotEmpty()) App.instance.notifyBonus(Members.byId(ctx, mid)?.name.orEmpty(), fresh.size * Rules.DRY_BONUS_STARS)
    }

    /**
     * Wetting during the day, while awake: the whole week starts over. The dry-night streak goes to 0, every other mark
     * this week is cleared, and that person's chore points for the week go to 0. Then the board plays the "oh no" animation.
     */
    private fun awakeAccident(ctx: Context, mid: String, date: LocalDate, today: LocalDate): String {
        if (!Rules.inPointsWeek(date, today)) throw IllegalArgumentException("a daytime accident resets this week, so it has to be dated this week")
        Data.mutate(ctx, FILE) { arr ->
            for (i in arr.length() - 1 downTo 0) {
                val e = arr.getJSONObject(i)
                val old = runCatching { LocalDate.parse(e.optString("date")) }.getOrNull()
                if (old == null || old.isBefore(today.minusDays(KEEP_DAYS))
                    || (e.optString("memberId") == mid && Rules.inPointsWeek(old, today))) arr.remove(i)
            }
            arr.put(JSONObject().put("memberId", mid).put("date", date.toString()).put("dry", false).put("kind", "day"))
        }
        val name = Members.byId(ctx, mid)?.name.orEmpty()
        syncDryBonuses(ctx, mid) // marks were cleared: take back any bonus they earned *before* the points are zeroed
        Chores.mutate(ctx, JSONObject().put("action", "resetWeekPoints").put("memberId", mid)
            .put("reason", "Accident while awake: week reset"))
        FamilySync.pushIfSpoke(ctx, "potty", JSONObject().put("action", "mark").put("memberId", mid)
            .put("date", date.toString()).put("state", "awake").toString())
        App.instance.notifyAccident(name)
        App.instance.notifyDataChanged()
        return statusJson(ctx)
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
                if (state !in listOf("dry", "wet", "clear", "awake")) throw IllegalArgumentException("state must be dry, wet, awake or clear")
                if (state == "awake") return awakeAccident(ctx, mid, date, today)
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
                syncDryBonuses(ctx, mid)
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
