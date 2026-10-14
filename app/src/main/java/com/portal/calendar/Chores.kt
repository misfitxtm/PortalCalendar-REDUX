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
    private const val PROFILES = "chore_profiles.json"
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
        Starter("🧼", "Load the dishwasher", 1, DAILY),
        Starter("🧽", "Wipe the counters", 1, DAILY),
        Starter("🐕", "Feed the pet", 1, DAILY),
        Starter("📖", "Read for 20 minutes", 1, DAILY),
        Starter("📚", "Do homework", 2, SCHOOL_DAYS),
        Starter("🗑️", "Take out the trash", 1, listOf(2, 5)),
        Starter("🌱", "Water the plants", 1, listOf(1, 4)),
        Starter("🧹", "Sweep the floor", 2, listOf(3, 6)),
        Starter("🧹", "Clean room", 3, listOf(7)),
        Starter("🧺", "Do laundry", 3, listOf(7)),
        Starter("👕", "Put clothes away", 1, listOf(1)),
        Starter("🌀", "Vacuum", 2, listOf(7)))

    /** One-time upgrade for chores saved with emoji this device can't draw; keeps ids and order. */
    fun fixIcons(ctx: Context) {
        val needs = Data.readArray(ctx, FILE).let { a ->
            (0 until a.length()).any { Rules.safeIcon(a.getJSONObject(it).optString("icon")) != a.getJSONObject(it).optString("icon") }
        }
        if (needs) Data.mutate(ctx, FILE) { arr ->
            for (i in 0 until arr.length()) arr.getJSONObject(i).let { it.put("icon", Rules.safeIcon(it.optString("icon"))) }
        }
    }

    /** Titles of one person's repeating or one-time chores, for linking Get Ready items to them. */
    fun choreTitlesFor(ctx: Context, memberId: String): List<String> =
        Data.readArray(ctx, FILE).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            .filter { it.optString("memberId") == memberId }.map { it.optString("title") }.distinct()

    /**
     * Keeps a chore in step with the Get Ready item linked to it: ticking the item checks that person's matching
     * chore off for today (and its points count), un-ticking undoes it. A chore that isn't due today is left alone.
     */
    fun syncLinked(ctx: Context, memberId: String, link: String, done: Boolean) {
        val today = dayFmt().format(Date())
        val dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        val chore = Data.readArray(ctx, FILE).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            .firstOrNull { it.optString("memberId") == memberId && Rules.sameTask(it.optString("title"), link) && isDueOn(it, today, dow) }
            ?: return
        val id = chore.optString("id")
        val isDone = Data.readArray(ctx, DONE).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            .any { it.optString("choreId") == id && it.optString("date") == today }
        if (isDone != done) mutate(ctx, JSONObject().put("action", "toggle").put("choreId", id))
    }

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
            .put("rewardIdeas", JSONArray().also { out ->
                REWARD_IDEAS.forEach { out.put(JSONObject().put("icon", it.icon).put("name", it.name)
                    .put("cost", it.cost).put("category", it.category)) }
            })
            .put("profiles", profilesJson(ctx))
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

    // ---- Reward ideas: a catalog a parent can add from; kids then pick from what was added. ---------
    // Emoji are limited to ones the Portal's Android 10 font can draw (see Rules.safeIcon).

    class RewardIdea(val icon: String, val name: String, val cost: Int, val category: String)

    val REWARD_IDEAS = listOf(
        RewardIdea("📺", "30 extra minutes of TV", 8, "Screen time"),
        RewardIdea("🎮", "30 minutes of video games", 8, "Screen time"),
        RewardIdea("📱", "15 extra minutes on a tablet", 5, "Screen time"),
        RewardIdea("🎬", "Pick the family movie", 10, "Screen time"),
        RewardIdea("🍦", "Ice cream treat", 6, "Treats"),
        RewardIdea("🍫", "Chocolate or candy treat", 4, "Treats"),
        RewardIdea("🍕", "Pick dinner (pizza night)", 15, "Treats"),
        RewardIdea("🥞", "Pancakes for breakfast", 8, "Treats"),
        RewardIdea("🍿", "Popcorn and a movie", 6, "Treats"),
        RewardIdea("🧁", "Bake cupcakes together", 10, "Treats"),
        RewardIdea("🎲", "Pick the family game night game", 8, "Activities"),
        RewardIdea("🏞️", "Trip to the park", 10, "Activities"),
        RewardIdea("🏊", "Swim trip", 15, "Activities"),
        RewardIdea("🚲", "Bike ride with a parent", 6, "Activities"),
        RewardIdea("🎳", "Bowling or arcade outing", 25, "Activities"),
        RewardIdea("🛍️", "Shopping trip for a small treat", 20, "Activities"),
        RewardIdea("⛺", "Camp out in the living room", 12, "Activities"),
        RewardIdea("🌙", "Stay up 15 minutes late", 5, "Privileges"),
        RewardIdea("🎧", "Choose the music in the car", 4, "Privileges"),
        RewardIdea("👑", "Boss of the day", 12, "Privileges"),
        RewardIdea("🚫", "Skip one chore", 10, "Privileges"),
        RewardIdea("🧸", "Small toy", 15, "Prizes"),
        RewardIdea("📚", "New book", 15, "Prizes"),
        RewardIdea("💵", "$1 bonus", 10, "Prizes"),
        RewardIdea("🎁", "Pick from the prize box", 8, "Prizes"),
        RewardIdea("⭐", "Sticker pack", 3, "Prizes"))

    private const val MAX_REWARDS = 60

    // ---- Profiles: a named set of repeating chores that can be applied to anyone. -----------------

    class Item(val title: String, val icon: String, val points: Int, val days: List<Int>)
    class DefaultProfile(val name: String, val titles: List<String>)

    // Written into the profile list the first time it is read; after that they are ordinary profiles you can edit or delete.
    // Each names starter chores, so the icons, point values and days stay in one place.
    val DEFAULT_PROFILES = listOf(
        DefaultProfile("Ages 4–6",
            listOf("Make bed", "Tidy up toys", "Set the table", "Feed the pet", "Water the plants", "Put clothes away")),
        DefaultProfile("Ages 7–9",
            listOf("Make bed", "Do homework", "Read for 20 minutes", "Clear the table", "Feed the pet", "Take out the trash", "Clean room")),
        DefaultProfile("Ages 10–13",
            listOf("Make bed", "Do homework", "Clean room", "Do laundry", "Load the dishwasher", "Wipe the counters",
                "Sweep the floor", "Take out the trash", "Vacuum")))

    private fun defaultItems(p: DefaultProfile): List<Item> =
        p.titles.mapNotNull { t -> STARTER.firstOrNull { it.title == t } }.map { Item(it.title, it.icon, it.points, it.days) }

    /** Adds any default profile whose name isn't on the list yet. */
    private fun addDefaultProfiles(ctx: Context) {
        Data.mutate(ctx, PROFILES) { arr ->
            val have = (0 until arr.length()).map { arr.getJSONObject(it).optString("name").lowercase() }.toSet()
            for (d in DEFAULT_PROFILES) if (d.name.lowercase() !in have)
                arr.put(JSONObject().put("id", UUID.randomUUID().toString()).put("name", d.name)
                    .put("items", JSONArray(defaultItems(d).map(::itemJson))))
        }
    }

    private fun customProfiles(ctx: Context): List<JSONObject> {
        // First use (no profile file yet): start with the age profiles. Deleting them all later leaves the file, so they stay gone.
        if (!java.io.File(ctx.filesDir, PROFILES).exists()) addDefaultProfiles(ctx)
        return Data.readArray(ctx, PROFILES).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    }

    private fun itemsOf(o: JSONObject): List<Item> = o.optJSONArray("items")?.let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map { i ->
            Item(i.optString("title"), i.optString("icon").ifEmpty { "⭐" }, i.optInt("points", 1).coerceIn(1, 10),
                i.optJSONArray("days")?.let { d -> (0 until d.length()).map { d.getInt(it) } } ?: (1..7).toList())
        }
    } ?: emptyList()

    private fun itemJson(i: Item) = JSONObject().put("title", i.title).put("icon", i.icon)
        .put("points", i.points).put("days", JSONArray(i.days))

    private fun profilesJson(ctx: Context): JSONArray = JSONArray().also { out ->
        for (c in customProfiles(ctx)) out.put(JSONObject().put("id", c.optString("id")).put("name", c.optString("name"))
            .put("builtin", false).put("items", c.optJSONArray("items") ?: JSONArray()))
    }

    /** Adds [items] to each person, skipping chores that person already has. */
    private fun addItemsTo(ctx: Context, targets: List<String>, items: List<Item>) {
        Data.mutate(ctx, FILE) { arr ->
            val have = (0 until arr.length()).map { arr.getJSONObject(it) }
                .map { it.optString("memberId") to it.optString("title").lowercase() }.toSet()
            for (mid in targets) for (it in items) {
                if ((mid to it.title.lowercase()) in have) continue // already on their list
                arr.put(JSONObject().put("id", UUID.randomUUID().toString())
                    .put("title", it.title).put("memberId", mid).put("icon", it.icon)
                    .put("points", it.points).put("days", JSONArray(it.days)))
            }
        }
    }

    private fun memberTargets(ctx: Context, ids: JSONArray): List<String> =
        (0 until ids.length()).map { ids.optString(it) }.filter { Members.byId(ctx, it) != null }
            .ifEmpty { throw IllegalArgumentException("pick at least one family member") }

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
                val reason = action.optString("reason").trim().take(60)
                Data.mutate(ctx, POINTS) { arr ->
                    arr.put(JSONObject()
                        .put("id", UUID.randomUUID().toString())
                        .put("memberId", mid)
                        .put("delta", delta)
                        .put("reason", reason)
                        .put("date", dayFmt().format(Date())))
                    prune(arr)
                }
                if (delta < 0) App.instance.notifyDeduction(Members.byId(ctx, mid)?.name ?: "", delta, reason)
            }
            "addReward" -> {
                val name = action.getString("name").trim().take(40)
                if (name.isEmpty()) throw IllegalArgumentException("the reward needs a name")
                val cost = action.getInt("cost")
                if (cost !in 1..99) throw IllegalArgumentException("a reward costs 1 to 99 points")
                Data.mutate(ctx, REWARDS) { arr ->
                    if (arr.length() >= MAX_REWARDS) throw IllegalArgumentException("that's plenty of rewards — remove one first")
                    arr.put(JSONObject().put("id", UUID.randomUUID().toString()).put("name", name)
                        .put("cost", cost).put("icon", action.optString("icon").ifEmpty { "🎁" }.take(4)))
                }
            }
            "addRewardPack" -> {
                // Adds the chosen ideas (all of them when none are named), skipping names already on the list.
                val wanted = action.optJSONArray("names")?.let { n -> (0 until n.length()).map { n.getString(it).lowercase() }.toSet() }
                Data.mutate(ctx, REWARDS) { arr ->
                    val have = (0 until arr.length()).map { arr.getJSONObject(it).optString("name").lowercase() }.toMutableSet()
                    for (r in REWARD_IDEAS) {
                        if (wanted != null && r.name.lowercase() !in wanted) continue
                        if (!have.add(r.name.lowercase())) continue
                        if (arr.length() >= MAX_REWARDS) throw IllegalArgumentException("that's plenty of rewards — remove some first")
                        arr.put(JSONObject().put("id", UUID.randomUUID().toString()).put("name", r.name)
                            .put("cost", r.cost).put("icon", r.icon))
                    }
                }
            }
            "updateReward" -> {
                // Name and icon are optional so the old "change the cost" call still works.
                val id = action.getString("id")
                val cost = action.getInt("cost")
                if (cost !in 1..99) throw IllegalArgumentException("a reward costs 1 to 99 points")
                val name = if (action.has("name")) action.getString("name").trim().take(40) else null
                if (name != null && name.isEmpty()) throw IllegalArgumentException("the reward needs a name")
                Data.mutate(ctx, REWARDS) { arr ->
                    val r = (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { it.optString("id") == id }
                        ?: throw IllegalArgumentException("that reward no longer exists")
                    r.put("cost", cost)
                    if (name != null) r.put("name", name)
                    if (action.has("icon")) r.put("icon", Rules.safeIcon(action.getString("icon").trim().take(4)).ifEmpty { "🎁" })
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
            "awardBonus" -> {
                // A bonus is an adjustment with a key, so asking twice for the same bonus changes nothing.
                val mid = action.getString("memberId")
                val key = action.getString("key")
                val delta = action.getInt("delta")
                if (Members.byId(ctx, mid) == null) throw IllegalArgumentException("unknown family member")
                if (delta !in 1..50) throw IllegalArgumentException("a bonus is 1 to 50 points")
                Data.mutate(ctx, POINTS) { arr ->
                    if ((0 until arr.length()).none { arr.getJSONObject(it).optString("key") == key }) {
                        arr.put(JSONObject().put("id", UUID.randomUUID().toString()).put("memberId", mid).put("delta", delta)
                            .put("reason", action.optString("reason").trim().take(60)).put("key", key)
                            .put("date", dayFmt().format(Date())))
                        prune(arr)
                    }
                }
            }
            "revokeBonus" -> {
                val key = action.getString("key")
                Data.mutate(ctx, POINTS) { arr ->
                    for (i in arr.length() - 1 downTo 0) if (arr.getJSONObject(i).optString("key") == key) arr.remove(i)
                }
            }
            "resetWeekPoints" -> {
                // Zeroes this week's earned points for one person by logging a matching negative adjustment,
                // so it shows in the history and can be undone. Anything already spent stays spent.
                val mid = action.getString("memberId")
                if (Members.byId(ctx, mid) == null) throw IllegalArgumentException("unknown family member")
                val earned = weekTotals(ctx, Data.readArray(ctx, FILE), Data.readArray(ctx, DONE), weekStartStr()).first.optInt(mid)
                if (earned > 0) Data.mutate(ctx, POINTS) { arr ->
                    arr.put(JSONObject().put("id", UUID.randomUUID().toString()).put("memberId", mid).put("delta", -earned)
                        .put("reason", action.optString("reason").trim().take(60).ifEmpty { "Week reset" })
                        .put("date", dayFmt().format(Date())))
                    prune(arr)
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
                val targets = memberTargets(ctx, action.getJSONArray("memberIds"))
                val wanted = action.optJSONArray("titles")?.let { t -> (0 until t.length()).map { t.getString(it) }.toSet() }
                addItemsTo(ctx, targets, STARTER.filter { wanted == null || it.title in wanted }
                    .map { Item(it.title, it.icon, it.points, it.days) })
            }
            "applyProfile" -> {
                val targets = memberTargets(ctx, action.getJSONArray("memberIds"))
                val pid = action.getString("profileId")
                val items = customProfiles(ctx).firstOrNull { it.optString("id") == pid }?.let(::itemsOf)
                    ?: throw IllegalArgumentException("that profile no longer exists")
                addItemsTo(ctx, targets, items)
            }
            "saveProfile" -> {
                val name = action.getString("name").trim().take(40)
                if (name.isEmpty()) throw IllegalArgumentException("give the profile a name")
                val mid = action.getString("memberId")
                if (Members.byId(ctx, mid) == null) throw IllegalArgumentException("unknown family member")
                val chores = Data.readArray(ctx, FILE).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
                    .filter { it.optString("memberId") == mid && !it.optBoolean("oneTime") }
                if (chores.isEmpty()) throw IllegalArgumentException("that person has no repeating chores to save")
                val items = JSONArray(chores.take(30).map { c ->
                    itemJson(Item(c.optString("title"), c.optString("icon").ifEmpty { "⭐" },
                        c.optInt("points", 1).coerceIn(1, 10),
                        c.optJSONArray("days")?.let { d -> (0 until d.length()).map { d.getInt(it) } } ?: (1..7).toList()))
                })
                Data.mutate(ctx, PROFILES) { arr ->
                    // Same name replaces, so re-saving updates a profile instead of piling up copies.
                    for (i in arr.length() - 1 downTo 0)
                        if (arr.getJSONObject(i).optString("name").equals(name, ignoreCase = true)) arr.remove(i)
                    if (arr.length() >= 20) throw IllegalArgumentException("that's plenty of profiles — delete one first")
                    arr.put(JSONObject().put("id", UUID.randomUUID().toString()).put("name", name).put("items", items))
                }
            }
            "saveProfileFull" -> {
                // The profile editor: create (no id) or replace (id) a profile with the items as edited.
                val name = action.getString("name").trim().take(40)
                if (name.isEmpty()) throw IllegalArgumentException("give the profile a name")
                val raw = action.getJSONArray("items")
                if (raw.length() == 0) throw IllegalArgumentException("add at least one chore to the profile")
                if (raw.length() > 30) throw IllegalArgumentException("a profile can hold up to 30 chores")
                val items = JSONArray((0 until raw.length()).map { raw.getJSONObject(it) }.map { i ->
                    val title = i.getString("title").trim().take(40)
                    if (title.isEmpty()) throw IllegalArgumentException("every chore needs a name")
                    val days = i.optJSONArray("days")?.let { d -> (0 until d.length()).map { d.getInt(it) }.filter { it in 1..7 }.distinct().sorted() }
                        ?: (1..7).toList()
                    if (days.isEmpty()) throw IllegalArgumentException("“$title” needs at least one day")
                    itemJson(Item(title, i.optString("icon").trim().take(4).ifEmpty { "⭐" }, i.optInt("points", 1).coerceIn(1, 10), days))
                })
                val id = action.optString("id")
                customProfiles(ctx) // make sure the defaults exist before editing
                Data.mutate(ctx, PROFILES) { arr ->
                    val clash = (0 until arr.length()).map { arr.getJSONObject(it) }
                        .any { it.optString("name").equals(name, ignoreCase = true) && it.optString("id") != id }
                    if (clash) throw IllegalArgumentException("there is already a profile called “$name”")
                    val existing = (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { it.optString("id") == id }
                    if (existing != null) { existing.put("name", name); existing.put("items", items) }
                    else {
                        if (arr.length() >= 20) throw IllegalArgumentException("that's plenty of profiles — delete one first")
                        arr.put(JSONObject().put("id", UUID.randomUUID().toString()).put("name", name).put("items", items))
                    }
                }
            }
            "restoreDefaultProfiles" -> {
                customProfiles(ctx)
                addDefaultProfiles(ctx)
            }
            "deleteProfile" -> {
                val id = action.getString("id")
                Data.mutate(ctx, PROFILES) { arr ->
                    for (i in arr.length() - 1 downTo 0) if (arr.getJSONObject(i).optString("id") == id) arr.remove(i)
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
