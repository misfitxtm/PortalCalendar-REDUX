package com.portal.calendar

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Chores & star rewards. A chore belongs to a member and repeats on chosen
 * weekdays (1=Sun … 7=Sat, matching java.util.Calendar). Completions are an
 * append-only log of {choreId, date, memberId}; toggling today removes/adds
 * the entry. Points this week = each completion's stamped value (default 1)
 * plus manual parent adjustments (bonus / penalty), floored at 0 and compared
 * against a per-member weekly goal. The week runs Friday through Thursday
 * ([Rules.pointsWeekStart]). memberId and points are stamped at completion
 * time so a star survives its chore being pruned or deleted (older logs
 * without them fall back to looking the chore up / worth 1).
 */
object Chores {
    private const val FILE = "chores.json"
    private const val DONE = "chore_done.json"
    private const val GOALS = "star_goals.json"
    private const val HISTORY = "chore_history.json"
    private const val POINTS = "chore_points.json"
    private const val REWARDS = "chore_rewards.json"
    const val DEFAULT_GOAL = 10

    class Starter(val icon: String, val title: String, val points: Int, val days: List<Int>)

    // Calendar weekdays: 1=Sun … 7=Sat. Everyday tasks only; hygiene belongs in Routines.
    private val DAILY = (1..7).toList()
    private val SCHOOL_DAYS = (2..6).toList()
    val STARTER = listOf(
        Starter("🛏️", "Make bed", 1, DAILY),
        Starter("🧸", "Tidy up toys", 1, DAILY),
        Starter("🍽️", "Set the table", 1, DAILY),
        Starter("🥣", "Clear the table", 1, DAILY),
        Starter("🫧", "Load the dishwasher", 1, DAILY),
        Starter("🧽", "Wipe the counters", 1, DAILY),
        Starter("🐕", "Feed the pet", 1, DAILY),
        Starter("📖", "Read for 20 minutes", 1, DAILY),
        Starter("📚", "Do homework", 2, SCHOOL_DAYS),
        Starter("🗑️", "Take out the trash", 1, listOf(2, 5)),
        Starter("🪴", "Water the plants", 1, listOf(1, 4)),
        Starter("🧹", "Sweep the floor", 2, listOf(3, 6)),
        Starter("🧹", "Clean room", 3, listOf(7)),
        Starter("🧺", "Do laundry", 3, listOf(7)),
        Starter("👕", "Put clothes away", 1, listOf(1)),
        Starter("🌀", "Vacuum", 2, listOf(7)))

    private fun dayFmt() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** [includePins] only for the board's own PIN pad — never over HTTP. */
    fun statusJson(ctx: Context, includePins: Boolean = false): String {
        var chores = Data.readArray(ctx, FILE)
        // One-time chores quietly retire a few days after their date.
        val cutoff = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -3) }
        val cut = dayFmt().format(cutoff.time)
        val needsPrune = (0 until chores.length()).any {
            val c = chores.getJSONObject(it)
            c.optBoolean("oneTime") && c.optString("date") < cut
        }
        if (needsPrune) chores = Data.mutate(ctx, FILE) { arr ->
            for (i in arr.length() - 1 downTo 0) {
                val c = arr.getJSONObject(i)
                if (c.optBoolean("oneTime") && c.optString("date") < cut) arr.remove(i)
            }
            arr
        }
        val done = Data.readArray(ctx, DONE)
        val today = dayFmt().format(Date())

        val doneToday = JSONArray()
        for (i in 0 until done.length()) {
            val d = done.getJSONObject(i)
            if (d.optString("date") == today) doneToday.put(d.optString("choreId"))
        }

        val weekStart = weekStartStr()
        val (stars, spent, adjustments) = weekTotals(ctx, chores, done, weekStart)
        val prevStart = LocalDate.parse(weekStart).minusDays(7).toString()
        val lastWeek = lastWeekJson(ctx, chores, done, prevStart, weekStart)
        val spendable = JSONObject()
        for (k in stars.keys()) spendable.put(k, (stars.optInt(k) - spent.optInt(k)).coerceAtLeast(0))

        val goals = JSONObject()
        val g = Data.readArray(ctx, GOALS)
        for (i in 0 until g.length()) {
            val o = g.getJSONObject(i)
            goals.put(o.optString("memberId"), o.optInt("goal", DEFAULT_GOAL))
        }

        return JSONObject()
            .put("chores", chores)
            .put("doneToday", doneToday)
            .put("stars", stars)
            .put("goals", goals)
            .put("adjustments", adjustments)
            .put("spendable", spendable)
            .put("rewards", Data.readArray(ctx, REWARDS))
            .put("weekStart", weekStart)
            .put("lastWeek", lastWeek)
            .put("recapDue", lastWeek.getJSONArray("results").let { r ->
                (0 until r.length()).any { r.getJSONObject(it).getInt("earned") > 0 }
            } && Rules.recapDue(LocalDate.now(), ConfigStore(ctx).recapDismissed()))
            .put("starter", JSONArray().also { out ->
                STARTER.forEach { out.put(JSONObject().put("icon", it.icon).put("title", it.title)
                    .put("points", it.points).put("days", JSONArray(it.days))) }
            })
            .put("members", JSONArray(
                if (includePins) Members.json(ctx) else Members.publicJson(ctx)))
            .put("suggestions", suggestions(ctx, chores))
            .toString()
    }

    /**
     * This week's points per member: earned (completions + bonus/penalty, floored at 0), spent on
     * rewards, and the adjustment log newest-first. Redemptions are log entries with kind "redeem".
     */
    private fun weekTotals(ctx: Context, chores: JSONArray, done: JSONArray, weekStart: String, weekEnd: String? = null):
            Triple<JSONObject, JSONObject, JSONArray> {
        fun inWeek(date: String) = date >= weekStart && (weekEnd == null || date < weekEnd)
        val choreMember = HashMap<String, String>()
        for (i in 0 until chores.length()) {
            val c = chores.getJSONObject(i)
            choreMember[c.optString("id")] = c.optString("memberId")
        }
        val stars = JSONObject()
        val spent = JSONObject()
        fun add(o: JSONObject, m: String, n: Int) { if (m.isNotEmpty()) o.put(m, o.optInt(m, 0) + n) }
        for (i in 0 until done.length()) {
            val d = done.getJSONObject(i)
            if (inWeek(d.optString("date"))) {
                // Stamped memberId first (survives chore prune/delete);
                // fall back to the chore lookup for pre-stamp log entries.
                val m = d.optString("memberId")
                    .ifEmpty { choreMember[d.optString("choreId")] ?: "" }
                add(stars, m, d.optInt("points", 1))
            }
        }
        // Newest first; the log is append-only, so walk it backwards.
        val adjustments = JSONArray()
        val adj = Data.readArray(ctx, POINTS)
        for (i in adj.length() - 1 downTo 0) {
            val a = adj.getJSONObject(i)
            if (!inWeek(a.optString("date"))) continue
            if (a.optString("kind") == "redeem") add(spent, a.optString("memberId"), a.optInt("cost"))
            else add(stars, a.optString("memberId"), a.optInt("delta"))
            adjustments.put(a)
        }
        for (k in stars.keys().asSequence().toList()) stars.put(k, stars.optInt(k).coerceAtLeast(0))
        return Triple(stars, spent, adjustments)
    }

    /** Last week's earned points per member, best first, and whether each hit their goal. */
    private fun lastWeekJson(ctx: Context, chores: JSONArray, done: JSONArray, from: String, to: String): JSONObject {
        val (stars, _, _) = weekTotals(ctx, chores, done, from, to)
        val goals = HashMap<String, Int>()
        Data.readArray(ctx, GOALS).let { g ->
            for (i in 0 until g.length()) goals[g.getJSONObject(i).optString("memberId")] = g.getJSONObject(i).optInt("goal", DEFAULT_GOAL)
        }
        val rows = Members.all(ctx).map { m ->
            val earned = stars.optInt(m.id)
            val goal = goals[m.id] ?: DEFAULT_GOAL
            JSONObject().put("memberId", m.id).put("name", m.name).put("earned", earned)
                .put("goal", goal).put("hitGoal", earned >= goal)
        }.sortedByDescending { it.getInt("earned") }
        return JSONObject().put("start", from).put("end", to).put("results", JSONArray(rows))
    }

    /** The family's frequently re-added chores, fuzzy-grouped, active ones excluded. */
    private fun suggestions(ctx: Context, active: JSONArray): JSONArray {
        val hist = Data.readArray(ctx, HISTORY)
        data class Group(val title: String, val icon: String, var count: Int)
        val groups = ArrayList<Group>()
        for (i in 0 until hist.length()) {
            val h = hist.getJSONObject(i)
            val t = h.optString("title")
            if (t.isEmpty()) continue
            val g = groups.find { MagicWords.fuzzyEquals(it.title.lowercase(), t.lowercase()) }
            if (g != null) g.count++
            else groups.add(Group(t, h.optString("icon").ifEmpty { "⭐" }, 1))
        }
        val activeTitles = (0 until active.length()).map {
            active.getJSONObject(it).optString("title").lowercase()
        }
        val out = JSONArray()
        groups.filter { g -> g.count >= 2 &&
                activeTitles.none { MagicWords.fuzzyEquals(it, g.title.lowercase()) } }
            .sortedByDescending { it.count }
            .take(8)
            .forEach { out.put(JSONObject().put("title", it.title).put("icon", it.icon)) }
        return out
    }

    fun mutate(ctx: Context, action: JSONObject): String {
        when (action.getString("action")) {
            "addChore" -> {
                val title = action.getString("title").trim()
                if (title.isEmpty()) throw IllegalArgumentException("the chore needs a name")
                // One chore per assignee — "memberIds" assigns several kids at once.
                val ids = action.optJSONArray("memberIds")
                val targets = if (ids != null && ids.length() > 0)
                    (0 until ids.length()).map { ids.optString(it) }
                else listOf(action.optString("memberId"))
                Data.mutate(ctx, FILE) { arr ->
                    for (mid in targets) {
                        val chore = JSONObject()
                            .put("id", UUID.randomUUID().toString())
                            .put("title", title)
                            .put("memberId", mid)
                            .put("icon", action.optString("icon").ifEmpty { "⭐" })
                            .put("points", action.optInt("points", 1).coerceIn(1, 10))
                        if (action.optBoolean("oneTime", false)) {
                            chore.put("oneTime", true)
                            chore.put("date", action.optString("date").ifEmpty { dayFmt().format(Date()) })
                        } else {
                            chore.put("days", action.optJSONArray("days")
                                ?: JSONArray(listOf(1, 2, 3, 4, 5, 6, 7)))
                        }
                        arr.put(chore)
                    }
                }
                // Remember what gets added — the composer learns the family's
                // common chores and offers them as personalized quick-picks.
                Data.mutate(ctx, HISTORY) { hist ->
                    hist.put(JSONObject()
                        .put("title", title)
                        .put("icon", action.optString("icon").ifEmpty { "⭐" }))
                    while (hist.length() > 200) hist.remove(0)
                }
            }
            "deleteChore" -> {
                val id = action.getString("choreId")
                Data.mutate(ctx, FILE) { arr ->
                    for (i in arr.length() - 1 downTo 0)
                        if (arr.getJSONObject(i).optString("id") == id) arr.remove(i)
                }
                // Drop only completions that can't stand on their own (no
                // stamped memberId) — earned stars survive the deletion.
                Data.mutate(ctx, DONE) { done ->
                    for (i in done.length() - 1 downTo 0) {
                        val d = done.getJSONObject(i)
                        if (d.optString("choreId") == id && d.optString("memberId").isEmpty())
                            done.remove(i)
                    }
                }
            }
            "toggle" -> {
                val id = action.getString("choreId")
                val today = dayFmt().format(Date())
                // Resolve assignee and value now so the star outlives the chore.
                val chore = Data.readArray(ctx, FILE).let { arr ->
                    (0 until arr.length()).map { arr.getJSONObject(it) }
                        .firstOrNull { it.optString("id") == id }
                } ?: throw IllegalArgumentException("that chore no longer exists")
                val memberId = chore.optString("memberId")
                val points = chore.optInt("points", 1)
                Data.mutate(ctx, DONE) { done ->
                    var removed = false
                    for (i in done.length() - 1 downTo 0) {
                        val d = done.getJSONObject(i)
                        if (d.optString("choreId") == id && d.optString("date") == today) {
                            done.remove(i); removed = true
                        }
                    }
                    if (!removed) done.put(JSONObject()
                        .put("choreId", id).put("date", today).put("memberId", memberId)
                        .put("points", points))
                    prune(done)
                }
            }
            "adjust" -> {
                val mid = action.getString("memberId")
                if (Members.byId(ctx, mid) == null) throw IllegalArgumentException("unknown family member")
                val delta = action.getInt("delta")
                if (delta == 0 || delta !in -50..50)
                    throw IllegalArgumentException("points must be between -50 and 50, and not 0")
                Data.mutate(ctx, POINTS) { arr ->
                    arr.put(JSONObject()
                        .put("id", UUID.randomUUID().toString())
                        .put("memberId", mid)
                        .put("delta", delta)
                        .put("reason", action.optString("reason").trim().take(60))
                        .put("date", dayFmt().format(Date())))
                    prune(arr)
                }
            }
            "addReward" -> {
                val name = action.getString("name").trim().take(40)
                if (name.isEmpty()) throw IllegalArgumentException("the reward needs a name")
                val cost = action.getInt("cost")
                if (cost !in 1..99) throw IllegalArgumentException("a reward costs 1 to 99 points")
                Data.mutate(ctx, REWARDS) { arr ->
                    if (arr.length() >= 30) throw IllegalArgumentException("that's plenty of rewards — remove one first")
                    arr.put(JSONObject().put("id", UUID.randomUUID().toString()).put("name", name)
                        .put("cost", cost).put("icon", action.optString("icon").ifEmpty { "🎁" }.take(4)))
                }
            }
            "deleteReward" -> {
                val id = action.getString("id")
                Data.mutate(ctx, REWARDS) { arr ->
                    for (i in arr.length() - 1 downTo 0) if (arr.getJSONObject(i).optString("id") == id) arr.remove(i)
                }
            }
            "redeem" -> {
                val mid = action.getString("memberId")
                if (Members.byId(ctx, mid) == null) throw IllegalArgumentException("unknown family member")
                val reward = Data.readArray(ctx, REWARDS).let { arr ->
                    (0 until arr.length()).map { arr.getJSONObject(it) }
                        .firstOrNull { it.optString("id") == action.getString("rewardId") }
                } ?: throw IllegalArgumentException("that reward no longer exists")
                val cost = reward.optInt("cost")
                // Check and spend under one lock so two taps can't overdraw.
                synchronized(this) {
                    val (stars, spent, _) = weekTotals(ctx, Data.readArray(ctx, FILE), Data.readArray(ctx, DONE), weekStartStr())
                    val left = stars.optInt(mid) - spent.optInt(mid)
                    if (left < cost) throw IllegalArgumentException("not enough points: ${left.coerceAtLeast(0)} to spend, needs $cost")
                    Data.mutate(ctx, POINTS) { arr ->
                        arr.put(JSONObject()
                            .put("id", UUID.randomUUID().toString()).put("kind", "redeem")
                            .put("memberId", mid).put("cost", cost)
                            .put("reason", reward.optString("icon") + " " + reward.optString("name"))
                            .put("date", dayFmt().format(Date())))
                        prune(arr)
                    }
                }
            }
            "undoAdjust" -> {
                val id = action.getString("id")
                Data.mutate(ctx, POINTS) { arr ->
                    for (i in arr.length() - 1 downTo 0)
                        if (arr.getJSONObject(i).optString("id") == id) arr.remove(i)
                }
            }
            "addStarterPack" -> {
                val ids = action.getJSONArray("memberIds")
                val targets = (0 until ids.length()).map { ids.optString(it) }
                    .filter { Members.byId(ctx, it) != null }
                if (targets.isEmpty()) throw IllegalArgumentException("pick at least one family member")
                val wanted = action.optJSONArray("titles")?.let { t -> (0 until t.length()).map { t.getString(it) }.toSet() }
                Data.mutate(ctx, FILE) { arr ->
                    val have = (0 until arr.length()).map { arr.getJSONObject(it) }
                        .map { it.optString("memberId") to it.optString("title").lowercase() }.toSet()
                    for (mid in targets) for (st in STARTER) {
                        if (wanted != null && st.title !in wanted) continue
                        if ((mid to st.title.lowercase()) in have) continue // already on their list
                        arr.put(JSONObject()
                            .put("id", UUID.randomUUID().toString())
                            .put("title", st.title).put("memberId", mid)
                            .put("icon", st.icon).put("points", st.points)
                            .put("days", JSONArray(st.days)))
                    }
                }
            }
            "dismissRecap" -> {
                // Local to this device, so it is not forwarded to a hub.
                ConfigStore(ctx).setRecapDismissed(weekStartStr())
                App.instance.notifyDataChanged()
                return statusJson(ctx)
            }
            "setGoal" -> {
                val mid = action.getString("memberId")
                val goal = action.getInt("goal").coerceIn(1, 99)
                Data.mutate(ctx, GOALS) { arr ->
                    var found = false
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        if (o.optString("memberId") == mid) { o.put("goal", goal); found = true }
                    }
                    if (!found) arr.put(JSONObject().put("memberId", mid).put("goal", goal))
                }
            }
            else -> throw IllegalArgumentException("unknown action")
        }
        FamilySync.pushIfSpoke(ctx, "chores", action.toString())
        App.instance.notifyDataChanged()
        return statusJson(ctx)
    }

    /** Due on a given yyyy-MM-dd date (+ its Calendar.DAY_OF_WEEK). */
    fun isDueOn(chore: JSONObject, date: String, dayOfWeek: Int): Boolean {
        if (chore.optBoolean("oneTime")) return chore.optString("date") == date
        val days = chore.optJSONArray("days") ?: return true
        for (i in 0 until days.length()) if (days.optInt(i) == dayOfWeek) return true
        return false
    }

    private fun prune(done: JSONArray) {
        val cutoff = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -60) }
        val cut = dayFmt().format(cutoff.time)
        for (i in done.length() - 1 downTo 0)
            if (done.getJSONObject(i).optString("date") < cut) done.remove(i)
    }

    private fun weekStartStr(): String = Rules.pointsWeekStart(LocalDate.now()).toString()
}
