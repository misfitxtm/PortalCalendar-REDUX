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
    private const val POOP = "potty_poop.json" // days a kid went poop; kept apart from dry/wet so streaks are untouched
    private const val EFFORT = "potty_effort.json" // habits ticked per day (one entry per member, date and item)
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
        Data.writeArray(ctx, POOP, JSONArray())
        Data.writeArray(ctx, EFFORT, JSONArray())
    }

    /** One bonus star for the day when enough habits are ticked; taken back if one is unticked. Keyed, so repeats change nothing. */
    private fun syncEffortBonus(ctx: Context, mid: String, date: String) {
        val done = Data.readArray(ctx, EFFORT).let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.count { it.optString("memberId") == mid && it.optString("date") == date }
        }
        val key = "effort:$mid:$date"
        if (Rules.effortBonus(done)) Chores.mutate(ctx, JSONObject().put("action", "awardBonus").put("memberId", mid).put("key", key)
            .put("delta", 1).put("reason", "Bathroom habits: +1 ⭐"))
        else Chores.mutate(ctx, JSONObject().put("action", "revokeBonus").put("key", key))
    }

    /** The last [days] days for the parent: nights, accidents, poop pattern and habits. */
    private fun summary(mid: String, today: LocalDate, days: Long, marks: Map<LocalDate, Boolean>, awake: List<String>,
                        poops: List<JSONObject>, effort: List<JSONObject>): JSONObject {
        val from = today.minusDays(days - 1)
        fun inWin(s: String) = runCatching { LocalDate.parse(s) }.getOrNull()?.let { !it.isBefore(from) && !it.isAfter(today) } == true
        val nights = marks.filterKeys { !it.isBefore(from) && !it.isAfter(today) }
        val p = poops.filter { inWin(it.optString("date")) }
        val stools = p.map { it.optInt("stool") }.filter { it in 1..7 }
        val habitDays = effort.filter { inWin(it.optString("date")) }.groupingBy { it.optString("date") }.eachCount()
        return JSONObject().put("days", days)
            .put("nightsDry", nights.count { it.value }).put("nightsWet", nights.count { !it.value })
            .put("dayAccidents", awake.count { inWin(it) })
            .put("poopDays", p.map { it.optString("date") }.toSet().size)
            .put("avgStool", if (stools.isEmpty()) JSONObject.NULL else Math.round(stools.average() * 10) / 10.0)
            .put("hard", stools.count { it <= 2 }).put("loose", stools.count { it >= 6 })
            .put("painful", p.count { it.optBoolean("pain") }).put("leaks", p.count { it.optBoolean("leak") })
            .put("soiled", p.count { it.optBoolean("soiled") })
            .put("longestNoPoop", Rules.longestPoopGap(p.mapNotNull { runCatching { LocalDate.parse(it.optString("date")) }.getOrNull() }, from, today))
            .put("habitDays", habitDays.count { Rules.effortBonus(it.value) })
    }

    fun statusJson(ctx: Context): String {
        val today = LocalDate.now()
        val log = Data.readArray(ctx, FILE)
        val awake = HashMap<String, MutableList<String>>() // memberId -> dates of daytime accidents
        for (i in 0 until log.length()) log.getJSONObject(i).let {
            if (it.optString("kind") == "day") awake.getOrPut(it.optString("memberId")) { ArrayList() }.add(it.optString("date"))
        }
        val poop = HashMap<String, MutableList<JSONObject>>() // memberId -> their poop entries
        Data.readArray(ctx, POOP).let { a ->
            for (i in 0 until a.length()) a.getJSONObject(i).let { poop.getOrPut(it.optString("memberId")) { ArrayList() }.add(it) }
        }
        val effort = HashMap<String, MutableList<JSONObject>>()
        Data.readArray(ctx, EFFORT).let { a ->
            for (i in 0 until a.length()) a.getJSONObject(i).let { effort.getOrPut(it.optString("memberId")) { ArrayList() }.add(it) }
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
                .put("poop", JSONArray(poop[mem.id].orEmpty().filter { runCatching { LocalDate.parse(it.optString("date")) }.getOrNull()?.isAfter(today.minusDays(SHOW_DAYS)) == true }))
                .put("daysSincePoop", Rules.daysSincePoop(poop[mem.id].orEmpty().mapNotNull { runCatching { LocalDate.parse(it.optString("date")) }.getOrNull() }, today) ?: JSONObject.NULL)
                .put("effort", JSONArray(effort[mem.id].orEmpty().filter { it.optString("date") == today.toString() }.map { it.optString("item") }))
                .put("summary", summary(mem.id, today, 28, marks, awake[mem.id].orEmpty(), poop[mem.id].orEmpty(), effort[mem.id].orEmpty()))
                .put("best", Rules.pottyBest(marks))
                .put("done", streak >= Rules.POTTY_GOAL_DAYS)
                .put("days", days))
        }
        return JSONObject().put("today", today.toString())
            .put("goal", Rules.POTTY_GOAL_DAYS).put("members", members)
            .put("everyone", everyone)
            .put("settings", JSONObject().put("chimeHours", ConfigStore(ctx).pottyChimeHours()))
            .toString()
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
            "poop" -> {
                val mid = action.getString("memberId")
                if (Members.byId(ctx, mid) == null) throw IllegalArgumentException("unknown family member")
                val today = LocalDate.now()
                val date = action.optString("date").ifEmpty { today.toString() }.let {
                    runCatching { LocalDate.parse(it) }.getOrNull() ?: throw IllegalArgumentException("that date isn't valid")
                }
                if (date.isAfter(today)) throw IllegalArgumentException("can't mark a day that hasn't happened")
                if (date.isBefore(today.minusDays(KEEP_DAYS))) throw IllegalArgumentException("that date is too old")
                val yes = when (action.getString("state")) { "yes" -> true; "clear" -> false
                    else -> throw IllegalArgumentException("state must be yes or clear") }
                Data.mutate(ctx, POOP) { arr ->
                    for (i in arr.length() - 1 downTo 0) {
                        val e = arr.getJSONObject(i)
                        val old = runCatching { LocalDate.parse(e.optString("date")) }.getOrNull()
                        if (old == null || old.isBefore(today.minusDays(KEEP_DAYS)) || (e.optString("memberId") == mid && old == date)) arr.remove(i)
                    }
                    if (yes) arr.put(JSONObject().put("memberId", mid).put("date", date.toString())
                        .apply { action.optInt("stool").takeIf { it in 1..7 }?.let { put("stool", it) } }
                        .put("pain", action.optBoolean("pain")).put("leak", action.optBoolean("leak")).put("soiled", action.optBoolean("soiled")))
                }
            }
            "effort" -> {
                val mid = action.getString("memberId")
                if (Members.byId(ctx, mid) == null) throw IllegalArgumentException("unknown family member")
                val item = action.getString("item")
                if (item !in Rules.EFFORT_ITEMS) throw IllegalArgumentException("unknown habit")
                val yes = when (action.getString("state")) { "yes" -> true; "clear" -> false
                    else -> throw IllegalArgumentException("state must be yes or clear") }
                val today = LocalDate.now().toString() // habits are for today only, so a bonus always lands in the current week
                Data.mutate(ctx, EFFORT) { arr ->
                    for (i in arr.length() - 1 downTo 0) {
                        val e = arr.getJSONObject(i)
                        val old = runCatching { LocalDate.parse(e.optString("date")) }.getOrNull()
                        if (old == null || old.isBefore(LocalDate.now().minusDays(KEEP_DAYS))
                            || (e.optString("memberId") == mid && e.optString("date") == today && e.optString("item") == item)) arr.remove(i)
                    }
                    if (yes) arr.put(JSONObject().put("memberId", mid).put("date", today).put("item", item))
                }
                syncEffortBonus(ctx, mid, today)
            }
            "setOptions" -> ConfigStore(ctx).setPottyChimeHours(action.getInt("chimeHours"))
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
