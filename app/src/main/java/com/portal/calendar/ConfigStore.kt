package com.portal.calendar

import android.content.Context
import java.time.DayOfWeek
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

data class FeedConfig(
    val name: String,
    val color: Int,
    val url: String,
    /** "calendar" renders on the board; "inbox" = every event is a command. */
    val kind: String = "calendar")

/** A new random sign-in token: 32 bytes as 64 hex characters. */
internal fun newServerToken(): String =
    ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

val CELEBRATE_KINDS = setOf("potty", "routines", "penalty")

/** Feed list persisted as a JSON array in SharedPreferences. */
class ConfigStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)

    init { applyWeek() }

    fun feedsJson(): String = prefs.getString(KEY, "[]")!!

    fun feeds(): List<FeedConfig> {
        val arr = JSONArray(feedsJson())
        val out = ArrayList<FeedConfig>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val color = try {
                Color.parseColor(o.optString("color", "#58A6FF"))
            } catch (e: IllegalArgumentException) {
                Color.parseColor("#58A6FF")
            }
            out.add(FeedConfig(o.optString("name", "Calendar"), color, o.optString("url", ""),
                if (o.optString("kind") == "inbox") "inbox" else "calendar"))
        }
        return out
    }

    /** Validates and saves; throws on malformed input (surfaced to the config page). */
    fun saveJson(json: String) {
        val arr = JSONArray(json)
        val clean = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val name = o.optString("name").trim()
            var url = o.optString("url").trim()
            val color = o.optString("color", "#58A6FF").trim()
            if (name.isEmpty()) throw IllegalArgumentException("calendar #${i + 1} has no name")
            if (url.startsWith("webcal://")) url = "https://" + url.removePrefix("webcal://")
            if (!url.startsWith("http://") && !url.startsWith("https://"))
                throw IllegalArgumentException("\"$name\" has an invalid URL")
            Color.parseColor(color) // throws if bad
            clean.put(JSONObject().put("name", name).put("color", color).put("url", url)
                .put("kind", if (o.optString("kind") == "inbox") "inbox" else "calendar"))
        }
        prefs.edit().putString(KEY, clean.toString()).commit() // server thread: durable before the 200 reply
    }

    /** Set once the setup wizard finishes or is skipped — never auto-launch again. */
    fun wizardDone(): Boolean = prefs.getBoolean("wizard_done", false)

    fun setWizardDone() {
        prefs.edit().putBoolean("wizard_done", true).commit()
    }

    /**
     * Board tab visibility: "chores" / "lists" / "meals" default on; "routines"
     * and "potty" are opt-in (default false) so they stay hidden until a family turns them on.
     */
    fun featureEnabled(key: String, default: Boolean = true): Boolean =
        prefs.getBoolean("feature_$key", default)

    /** First day of the points week, 1=Monday … 7=Sunday (java.time); Friday by default. */
    fun pointsWeekStart(): Int = prefs.getInt("points_week_start", DayOfWeek.FRIDAY.value).coerceIn(1, 7)

    /** Reward Shop open days (1=Monday … 7=Sunday); empty means the last day of the week. */
    fun shopDays(): Set<Int> = prefs.getString("shop_days", "")!!.split(',').mapNotNull { it.toIntOrNull() }.filter { it in 1..7 }.toSet()

    fun setPointsWeek(start: Int, shop: Set<Int>) {
        require(start in 1..7 && shop.all { it in 1..7 }) { "pick days between Monday and Sunday" }
        prefs.edit().putInt("points_week_start", start).putString("shop_days", shop.sorted().joinToString(",")).commit()
        applyWeek()
    }

    private fun applyWeek() {
        Rules.weekStartDay = DayOfWeek.of(pointsWeekStart())
        Rules.shopDays = shopDays().map { DayOfWeek.of(it) }.toSet()
    }

    fun setFeature(key: String, on: Boolean) {
        prefs.edit().putBoolean("feature_$key", on).commit()
    }

    /** Raw on/off for every tab in [Rules.TAB_KEYS] order; opt-in tabs default off. */
    fun tabFlags(): List<Boolean> = Rules.TAB_KEYS.map {
        featureEnabled(it, default = it in setOf("calendar", "chores", "lists", "meals"))
    }

    /** The points-week start whose recap card was dismissed, so it doesn't reappear that week. */
    fun recapDismissed(): String? = prefs.getString("recap_dismissed", null)

    fun setRecapDismissed(weekStart: String) {
        prefs.edit().putString("recap_dismissed", weekStart).commit()
    }

    /** The left panel (clock, weather, today's events) can be tucked away so the main area gets the whole screen. */
    fun sidebarHidden(): Boolean = prefs.getBoolean("sidebar_hidden", false)

    fun setSidebarHidden(hidden: Boolean) {
        prefs.edit().putBoolean("sidebar_hidden", hidden).commit()
    }

    /** The order of the tab cards on the board, as tab keys; always every tab exactly once. */
    fun tabOrder(): List<String> = Rules.normalizeTabOrder(
        runCatching { JSONArray(prefs.getString("tab_order", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } } }
            .getOrDefault(emptyList()))

    fun setTabOrder(order: List<String>) {
        prefs.edit().putString("tab_order", JSONArray(Rules.normalizeTabOrder(order)).toString()).commit()
    }

    /** Sound effects (the accident reaction's siren and "womps"). On by default; the volume is the Portal's own. */
    fun soundEffects(): Boolean = prefs.getBoolean("sound_effects", true)

    fun setSoundEffects(on: Boolean) {
        prefs.edit().putBoolean("sound_effects", on).commit()
    }

    /** Voice commands are completely off (no mic button, no recording, no model) until a parent turns them on. */
    fun voiceEnabled(): Boolean = prefs.getBoolean("voice_enabled", false)

    fun setVoiceEnabled(on: Boolean) {
        prefs.edit().putBoolean("voice_enabled", on).commit()
    }

    /** "offline" (Vosk, on-device, default) or "gemini" (sends audio to Google, needs a key). */
    fun voiceEngine(): String = prefs.getString("voice_engine", "offline") ?: "offline"

    fun setVoiceEngine(engine: String) {
        if (engine != "offline" && engine != "gemini") throw IllegalArgumentException("engine must be offline or gemini")
        prefs.edit().putString("voice_engine", engine).commit()
    }

    /** Tab the board opens on (and returns to after idle). Never "shopping": it is PIN-locked. */
    fun defaultTab(): String = prefs.getString("default_tab", "calendar") ?: "calendar"

    fun setDefaultTab(key: String) {
        if (key !in Rules.TAB_KEYS || key == "shopping")
            throw IllegalArgumentException("pick one of the open tabs")
        prefs.edit().putString("default_tab", key).commit()
    }

    /** Members who use the potty tracker. Nobody until a parent picks them. */
    fun pottyKids(): Set<String> = prefs.getString("potty_kids", null)?.let { raw ->
        val a = JSONArray(raw)
        (0 until a.length()).map { a.getString(it) }.toSet()
    } ?: emptySet()

    fun setPottyKids(ids: Collection<String>) {
        prefs.edit().putString("potty_kids", JSONArray(ids.toList()).toString()).commit()
    }

    /** Hours between "bathroom time" chimes on the board (8am-7pm); 0 = off. */
    fun pottyChimeHours(): Int = prefs.getInt("potty_chime_hours", 0).takeIf { it in setOf(0, 2, 3, 4) } ?: 0

    fun setPottyChimeHours(hours: Int) {
        require(hours in setOf(0, 2, 3, 4)) { "chime every 2, 3 or 4 hours, or off" }
        prefs.edit().putInt("potty_chime_hours", hours).commit()
    }

    /** Celebration animations when a chore is checked off (on by default). */
    fun choreEffects(): Boolean = prefs.getBoolean("chore_effects", true)

    fun setChoreEffects(on: Boolean) {
        prefs.edit().putBoolean("chore_effects", on).commit()
    }

    /** Full-screen reactions: celebrations for the Potty Tracker ("potty") and Get Ready ("routines"), and the "penalty" screen when points are deducted; all on by default. */
    fun celebrate(kind: String): Boolean = prefs.getBoolean("celebrate_$kind", true)

    fun setCelebrate(kind: String, on: Boolean) {
        require(kind in CELEBRATE_KINDS) { "unknown celebration" }
        prefs.edit().putBoolean("celebrate_$kind", on).commit()
    }

    /** Secret the phone presents to the config server; created once, never exported. */
    @Synchronized
    fun serverToken(): String = prefs.getString("server_token", null) ?: resetServerToken()

    /** Replaces the token, which signs out every browser and phone that used the old one. */
    fun resetServerToken(): String = newServerToken().also { prefs.edit().putString("server_token", it).commit() }

    /** Kid-lock PIN; empty = lock disabled. Gates edits on the board only. */
    fun pin(): String = prefs.getString("kid_pin", "") ?: ""

    fun setPin(v: String) {
        if (v.isNotEmpty() && !v.matches(Regex("\\d{4}")))
            throw IllegalArgumentException("the PIN must be exactly 4 digits")
        prefs.edit().putString("kid_pin", v).commit()
    }

    /** Chore-approval PIN; empty = use the kid-lock PIN. */
    fun approvalPin(): String = prefs.getString("approval_pin", "") ?: ""

    fun setApprovalPin(v: String) {
        if (v.isNotEmpty() && !v.matches(Regex("\\d{4}")))
            throw IllegalArgumentException("the PIN must be exactly 4 digits")
        prefs.edit().putString("approval_pin", v).commit()
    }

    /** "pin" = a PIN popup on each check-off; "queue" = kids request, a parent approves. */
    fun approvalMode(): String = prefs.getString("approval_mode", "pin").takeIf { it == "queue" } ?: "pin"

    fun setApprovalMode(v: String) {
        if (v != "pin" && v != "queue") throw IllegalArgumentException("mode must be pin or queue")
        prefs.edit().putString("approval_mode", v).commit()
    }

    /** Global UI zoom (1.0 = designed-for-Portal+ size; 10″ Portals want ~1.1–1.25). */
    fun uiScale(): Float = prefs.getFloat("ui_scale", 1f)

    fun setUiScale(v: Float) {
        prefs.edit().putFloat("ui_scale", v.coerceIn(0.7f, 1.6f)).commit()
    }

    /**
     * First day of the week as a java.util.Calendar day constant (1=Sun … 7=Sat),
     * or 0 = follow the device locale. Affects every week/month layout + the
     * chore/meal week boundaries.
     */
    fun weekStart(): Int = prefs.getInt("week_start", 0)

    fun weekStartResolved(): Int {
        val v = weekStart()
        return if (v in 1..7) v else java.util.Calendar.getInstance().firstDayOfWeek
    }

    fun setWeekStart(day: Int) {
        prefs.edit().putInt("week_start", if (day in 1..7) day else 0).commit()
    }

    /** Which calendar view the board opens on. 0=Day 1=Week 2=Month 3=Plan. */
    fun defaultView(): Int = prefs.getInt("default_view", 1).coerceIn(0, 3)

    fun setDefaultView(v: Int) {
        prefs.edit().putInt("default_view", v.coerceIn(0, 3)).commit()
    }

    /** "landscape" (default), "portrait", or "auto" (sensor). */
    fun orientation(): String = prefs.getString("orientation", "landscape") ?: "landscape"

    fun setOrientation(v: String) {
        val clean = if (v in listOf("landscape", "portrait", "auto")) v else "landscape"
        prefs.edit().putString("orientation", clean).commit()
    }

    /** Theme: "light" / "dark" (default, also after a reset) / "auto" (night by clock) / "system". */
    fun theme(): String = prefs.getString("theme", "dark") ?: "dark"

    fun setTheme(v: String) {
        val clean = if (v in listOf("light", "dark", "auto", "system")) v else "dark"
        prefs.edit().putString("theme", clean).commit()
    }

    /** Resolves the theme to dark-on/off right now (for auto/system). */
    fun isDark(ctx: Context): Boolean = when (theme()) {
        "dark" -> true
        "light" -> false
        "auto" -> { // night by the clock — dark 7pm–7am
            val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            h >= 19 || h < 7
        }
        "system" -> (ctx.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        else -> false
    }

    companion object {
        private const val KEY = "feeds"
    }
}
