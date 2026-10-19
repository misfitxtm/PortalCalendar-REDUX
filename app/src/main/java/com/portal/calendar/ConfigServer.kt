package com.portal.calendar

import android.content.Context

/**
 * Phone-facing config API. The Rust core (core/) owns the socket and the auth gate;
 * only requests that passed it reach [handle].
 */
class ConfigServer(
    private val ctx: Context,
    private val store: ConfigStore,
    private val onConfigChanged: () -> Unit
) : NativeHandler {

    private class Req(val method: String, val uri: String, val query: String, val body: ByteArray) {
        fun param(name: String): String? = query.split('&').firstNotNullOfOrNull {
            val (k, v) = it.split('=', limit = 2).let { p -> p[0] to p.getOrElse(1) { "" } }
            if (k == name) java.net.URLDecoder.decode(v, "UTF-8") else null
        }
    }

    private object Method { const val GET = "GET"; const val POST = "POST" }

    override fun handle(method: String, path: String, query: String, body: ByteArray): NativeResponse = try {
        route(Req(method, path, query, body))
    } catch (e: IllegalArgumentException) {
        resp(400, "application/json", "{\"error\":${jsonStr(e.message ?: "invalid input")}}")
    } catch (e: Exception) {
        resp(500, "application/json", "{\"error\":${jsonStr(e.message ?: e.javaClass.simpleName)}}")
    }

    private fun route(s: Req): NativeResponse = when {
        s.uri == "/" || s.uri == "/index.html" -> {
            val html = ctx.assets.open("config.html").bufferedReader().readText()
            resp(200, "text/html", html)
        }
        s.uri == "/api/config" && s.method == Method.GET ->
            json(store.feedsJson())
        s.uri == "/api/config" && s.method == Method.POST -> {
            store.saveJson(readBody(s))
            onConfigChanged()
            json("{\"ok\":true}")
        }
        s.uri == "/api/sync" && s.method == Method.POST -> {
            readBody(s) // drain — an unread body corrupts the next keep-alive request
            onConfigChanged()
            json("{\"ok\":true}")
        }
        s.uri == "/manifest.json" && s.method == Method.GET ->
            resp(200, "application/json", """
                {"name":"PortalHub REDUX","short_name":"REDUX",
                 "start_url":"/","display":"standalone",
                 "background_color":"#f4f1ea","theme_color":"#f0584c",
                 "icons":[{"src":"/icon.svg","sizes":"any","type":"image/svg+xml"}]}
            """.trimIndent())
        s.uri == "/icon.svg" && s.method == Method.GET ->
            resp(200, "image/svg+xml",
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 100\">" +
                "<rect width=\"100\" height=\"100\" rx=\"24\" fill=\"#F0584C\"/>" +
                "<rect x=\"16\" y=\"28\" width=\"68\" height=\"56\" rx=\"10\" fill=\"white\"/>" +
                "<rect x=\"24\" y=\"42\" width=\"32\" height=\"9\" rx=\"4.5\" fill=\"#2BB3A3\"/>" +
                "<rect x=\"24\" y=\"56\" width=\"44\" height=\"9\" rx=\"4.5\" fill=\"#F2A93B\"/>" +
                "<rect x=\"24\" y=\"70\" width=\"24\" height=\"9\" rx=\"4.5\" fill=\"#4FA3E3\"/></svg>")
        s.uri == "/api/members" && s.method == Method.GET ->
            json(Members.publicJson(ctx)) // PINs never go over HTTP
        s.uri == "/api/members" && s.method == Method.POST -> {
            Members.save(ctx, readBody(s))
            json(Members.publicJson(ctx))
        }
        s.uri == "/api/lists" && s.method == Method.GET ->
            json(FamilyLists.json(ctx))
        s.uri == "/api/lists" && s.method == Method.POST ->
            json(FamilyLists.mutate(ctx, org.json.JSONObject(readBody(s))))
        s.uri == "/api/gtasks/link" && s.method == Method.POST -> {
            GoogleTasks.link(ctx, org.json.JSONObject(readBody(s)).getString("listId"))
            json(FamilyLists.json(ctx))
        }
        s.uri == "/api/gtasks/unlink" && s.method == Method.POST -> {
            GoogleTasks.unlink(ctx, org.json.JSONObject(readBody(s)).getString("listId"))
            json(FamilyLists.json(ctx))
        }
        s.uri == "/api/chores" && s.method == Method.GET ->
            json(Chores.statusJson(ctx))
        s.uri == "/api/chores" && s.method == Method.POST ->
            json(Chores.mutate(ctx, org.json.JSONObject(readBody(s))))
        s.uri == "/api/reset" && s.method == Method.POST -> {
            // Wipes everything. The caller has to type the word, so a stray request can't do it.
            if (org.json.JSONObject(readBody(s)).optString("confirm") != "RESET") throw IllegalArgumentException("type RESET to confirm")
            ConfigBundle.resetAll(ctx)
            App.instance.lastEvents = emptyList()
            App.instance.notifyDataChanged()
            onConfigChanged()
            json("{\"ok\":true}")
        }
        s.uri == "/api/reset/data" && s.method == Method.POST -> {
            // Wipes part of the hub. Same typed-word rule as the full reset.
            val o = org.json.JSONObject(readBody(s))
            if (o.optString("confirm") != "RESET") throw IllegalArgumentException("type RESET to confirm")
            val a = o.optJSONArray("scopes") ?: org.json.JSONArray()
            Resets.run(ctx, Resets.parse((0 until a.length()).map { a.getString(it) }))
            json("{\"ok\":true}")
        }
        s.uri == "/api/voice" && s.method == Method.GET ->
            json(voiceJson())
        s.uri == "/api/voice" && s.method == Method.POST -> {
            val o = org.json.JSONObject(readBody(s))
            if (o.has("engine")) store.setVoiceEngine(o.getString("engine"))
            if (o.has("enabled")) store.setVoiceEnabled(o.getBoolean("enabled"))
            if (!store.voiceEnabled()) VoskTranscriber.close() // free the model the moment voice is switched off
            App.instance.notifyDataChanged()
            json(voiceJson())
        }
        // A computer typing the 6-digit pairing code. The gate lets this POST through unauthenticated, so the code is the check.
        s.uri == "/pair" && s.method == Method.POST ->
            if (PairingCode.redeem(org.json.JSONObject(String(s.body, Charsets.UTF_8)).optString("code")))
                json(org.json.JSONObject().put("token", store.serverToken()).toString())
            else resp(401, "application/json", "{\"error\":\"wrong or expired code. Get a new one if this keeps happening.\"}")
        s.uri == "/api/pair/new" && s.method == Method.POST ->
            json(org.json.JSONObject().put("code", PairingCode.issue()).put("seconds", PairingCode.LIFETIME_MS / 1000).toString())
        // Opened by the shopping-list QR. The gate lets /dl/ through, so the short-lived code is the only check here.
        s.uri == "/dl/shopping-list.md" && s.method == Method.GET ->
            if (!DownloadLink.valid(s.param("c"))) resp(401, "text/plain", "this link has expired — scan the QR code again")
            else resp(200, "text/markdown", Shopping.markdown(
                Data.readArray(ctx, "shopping.json").let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o -> o.optString("name") to o.optBoolean("done") } } },
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())))
        // One photo per request (raw bytes), so a phone can upload a whole batch one by one with progress.
        s.uri == "/api/photos" && s.method == Method.GET ->
            json(org.json.JSONObject().put("count", Photos.count()).toString())
        s.uri == "/api/photos" && s.method == Method.POST ->
            json(org.json.JSONObject().put("saved", Photos.save(ctx, s.param("name") ?: "", s.body)).put("count", Photos.count()).toString())
        s.uri == "/api/shopping" && s.method == Method.GET ->
            json(Shopping.statusJson(ctx))
        s.uri == "/api/shopping" && s.method == Method.POST ->
            json(Shopping.mutate(ctx, org.json.JSONObject(readBody(s))))
        s.uri == "/api/potty" && s.method == Method.GET ->
            json(Potty.statusJson(ctx))
        s.uri == "/api/potty" && s.method == Method.POST ->
            json(Potty.mutate(ctx, org.json.JSONObject(readBody(s))))
        s.uri == "/api/routines" && s.method == Method.GET ->
            json(Routines.statusJson(ctx))
        s.uri == "/api/routines" && s.method == Method.POST ->
            json(Routines.mutate(ctx, org.json.JSONObject(readBody(s))))
        s.uri == "/api/meals" && s.method == Method.GET ->
            json(Meals.statusJson(ctx))
        s.uri == "/api/meals" && s.method == Method.POST ->
            json(Meals.mutate(ctx, org.json.JSONObject(readBody(s))))
        s.uri == "/api/ai" && s.method == Method.GET ->
            json(Gemini.statusJson(ctx))
        s.uri == "/api/ai" && s.method == Method.POST -> {
            val o = org.json.JSONObject(readBody(s))
            json(Gemini.setConfig(ctx,
                if (o.has("enabled")) o.getBoolean("enabled") else null,
                if (o.has("key")) o.getString("key") else null,
                if (o.has("model")) o.getString("model") else null))
        }
        s.uri == "/api/ai/import" && s.method == Method.POST -> {
            val o = org.json.JSONObject(readBody(s))
            json(Gemini.smartImport(ctx,
                o.optString("text").takeIf { it.isNotEmpty() },
                o.optString("image").takeIf { it.isNotEmpty() },
                o.optString("mime").takeIf { it.isNotEmpty() }))
        }
        s.uri == "/api/ai/apply" && s.method == Method.POST ->
            json(Gemini.applyProposals(ctx, org.json.JSONObject(readBody(s))))
        s.uri == "/api/ai/recipe" && s.method == Method.POST ->
            json(Gemini.recipe(ctx, org.json.JSONObject(readBody(s)).getString("dish")))
        s.uri == "/api/ai/meal" && s.method == Method.POST -> {
            val o = org.json.JSONObject(readBody(s))
            json(Gemini.planMeal(ctx,
                o.getString("dish"), o.getString("date"), o.getString("slot"),
                o.optBoolean("groceries", true)))
        }
        s.uri == "/api/setup" && s.method == Method.GET ->
            json("{\"fresh\":${store.feeds().isEmpty() && !store.wizardDone()}}")
        s.uri == "/api/setup" && s.method == Method.POST -> {
            readBody(s) // drain — an unread body corrupts the next keep-alive request
            store.setWizardDone()
            json("{\"fresh\":false}")
        }
        s.uri == "/api/features" && s.method == Method.GET ->
            json(featuresJson())
        s.uri == "/api/features" && s.method == Method.POST -> {
            val o = org.json.JSONObject(readBody(s))
            // Validate first so a bad request changes nothing.
            val proposed = Rules.TAB_KEYS.map { if (o.has(it)) o.getBoolean(it) else store.featureEnabled(it, default = it in DEFAULT_ON) }
            if (proposed.filterIndexed { i, on -> on && i != Rules.SHOPPING_TAB }.isEmpty())
                throw IllegalArgumentException("keep at least one tab other than Shopping turned on")
            if (o.has("defaultTab")) store.setDefaultTab(o.getString("defaultTab"))
            for (key in Rules.TAB_KEYS) if (o.has(key)) store.setFeature(key, o.getBoolean(key))
            App.instance.notifyDataChanged()
            json(featuresJson())
        }
        s.uri == "/api/pin" && s.method == Method.GET ->
            json("{\"enabled\":${store.pin().isNotEmpty()}}")
        s.uri == "/api/pin" && s.method == Method.POST -> {
            store.setPin(org.json.JSONObject(readBody(s)).optString("pin"))
            json("{\"enabled\":${store.pin().isNotEmpty()}}")
        }
        s.uri == "/api/weather" && s.method == Method.GET ->
            json(Weather.statusJson(ctx))
        s.uri == "/api/weather/search" && s.method == Method.POST ->
            json(Weather.search(org.json.JSONObject(readBody(s)).getString("q")).toString())
        s.uri == "/api/weather/set" && s.method == Method.POST -> {
            val o = org.json.JSONObject(readBody(s))
            Weather.set(ctx,
                if (o.has("lat")) o.getDouble("lat") else null,
                if (o.has("lon")) o.getDouble("lon") else null,
                if (o.has("label")) o.getString("label") else null,
                if (o.has("unit")) o.getString("unit") else null)
            Weather.maybeRefresh(ctx, force = true) // worker thread; fine
            json(Weather.statusJson(ctx))
        }
        s.uri == "/api/weather/clear" && s.method == Method.POST -> {
            readBody(s) // drain
            Weather.clear(ctx)
            App.instance.notifyDataChanged()
            json(Weather.statusJson(ctx))
        }
        s.uri == "/api/writers" && s.method == Method.GET ->
            json(Writers.statusJson(ctx))
        s.uri == "/api/target" && s.method == Method.POST -> {
            val o = org.json.JSONObject(readBody(s))
            Writers.setTarget(ctx, o.getString("kind"), o.getString("id"))
            json(Writers.statusJson(ctx))
        }
        s.uri == "/api/icloud/connect" && s.method == Method.POST -> {
            val o = org.json.JSONObject(readBody(s))
            CalDav.connect(ctx, o.getString("email").trim(), o.getString("password").trim())
            Writers.ensureDefault(ctx)
            json(Writers.statusJson(ctx))
        }
        s.uri == "/api/icloud/disconnect" && s.method == Method.POST -> {
            readBody(s) // drain
            CalDav.disconnect(ctx)
            Writers.ensureDefault(ctx)
            json(Writers.statusJson(ctx))
        }
        s.uri == "/api/google/begin" && s.method == Method.POST -> {
            val o = org.json.JSONObject(readBody(s))
            val url = GoogleCal.begin(ctx, o.getString("clientId").trim(), o.getString("clientSecret").trim())
            json(org.json.JSONObject().put("authUrl", url).toString())
        }
        s.uri == "/api/google/code" && s.method == Method.POST -> {
            GoogleCal.finish(ctx, org.json.JSONObject(readBody(s)).getString("code"))
            Writers.ensureDefault(ctx)
            json(Writers.statusJson(ctx))
        }
        s.uri == "/api/google/disconnect" && s.method == Method.POST -> {
            readBody(s) // drain
            GoogleCal.disconnect(ctx)
            Writers.ensureDefault(ctx)
            json(Writers.statusJson(ctx))
        }
        // Landing spot if the user swaps localhost→portal-ip on the Google
        // redirect URL: completes the connection right here.
        s.uri == "/oauth" && s.method == Method.GET -> {
            val code = s.param("code")
            val msg = try {
                if (code.isNullOrEmpty()) throw IllegalArgumentException("no code in the URL")
                GoogleCal.finish(ctx, code)
                Writers.ensureDefault(ctx)
                "✓ Google Calendar connected — you can close this tab and go back to the setup page."
            } catch (e: Exception) {
                "Couldn't finish the Google sign-in: ${e.message}"
            }
            resp(200, "text/html",
                "<html><body style=\"font-family:sans-serif;background:#f4f1ea;color:#333a45;padding:40px;\"><h2>${android.text.TextUtils.htmlEncode(msg)}</h2></body></html>")
        }
        s.uri == "/api/event" && s.method == Method.POST -> {
            val o = org.json.JSONObject(readBody(s))
            val title = o.getString("title").trim()
            if (title.isEmpty()) throw IllegalArgumentException("the event needs a title")
            val allDay = o.optBoolean("allDay", false)
            // optString turns a JSON null into the string "null" — normalize.
            val time = o.optString("time").takeIf { it.isNotEmpty() && it != "null" }
            val (start, end) = CalDav.eventWindow(
                o.getString("date"), time,
                o.optInt("durationMins", 60), allDay)
            Writers.addEvent(ctx, title, start, end, allDay)
            onConfigChanged() // pull feeds so the new event shows up ASAP
            json("{\"ok\":true}")
        }
        s.uri == "/api/validate" && s.method == Method.POST -> {
            val url = org.json.JSONObject(readBody(s)).getString("url")
            val count = App.instance.sync.validateFeed(url)
            json("{\"ok\":true,\"events\":$count}")
        }
        s.uri == "/api/export" && s.method == Method.POST ->
            resp(200, "text/plain", ConfigBundle.export(ctx, org.json.JSONObject(readBody(s)).getString("passphrase")))
        s.uri == "/api/import" && s.method == Method.POST -> {
            val o = org.json.JSONObject(readBody(s))
            ConfigBundle.import(ctx, o.getString("code"), o.optString("passphrase").ifEmpty { null })
            App.instance.notifyDataChanged() // refresh chores/lists/meals tabs
            onConfigChanged() // rebuilds the board (layout/feeds/creds all may differ)
            json("{\"ok\":true}")
        }
        s.uri == "/api/layout" && s.method == Method.GET ->
            json(org.json.JSONObject()
                .put("weekStart", store.weekStart())
                .put("defaultView", store.defaultView())
                .put("orientation", store.orientation())
                .put("theme", store.theme())
                .put("sidebar", !store.sidebarHidden())
                .put("sounds", store.soundEffects())
                .put("tabOrder", org.json.JSONArray(store.tabOrder())).toString())
        s.uri == "/api/layout" && s.method == Method.POST -> {
            val o = org.json.JSONObject(readBody(s))
            if (o.has("weekStart")) store.setWeekStart(o.getInt("weekStart"))
            if (o.has("defaultView")) store.setDefaultView(o.getInt("defaultView"))
            if (o.has("orientation")) store.setOrientation(o.getString("orientation"))
            if (o.has("theme")) store.setTheme(o.getString("theme"))
            if (o.has("sidebar")) store.setSidebarHidden(!o.getBoolean("sidebar"))
            if (o.has("sounds")) store.setSoundEffects(o.getBoolean("sounds"))
            o.optJSONArray("tabOrder")?.let { a -> store.setTabOrder((0 until a.length()).map { a.getString(it) }) }
            onConfigChanged() // re-render / rebuild (week start, theme, orientation)
            json(org.json.JSONObject()
                .put("weekStart", store.weekStart())
                .put("defaultView", store.defaultView())
                .put("orientation", store.orientation())
                .put("theme", store.theme())
                .put("sidebar", !store.sidebarHidden())
                .put("sounds", store.soundEffects())
                .put("tabOrder", org.json.JSONArray(store.tabOrder())).toString())
        }
        s.uri == "/api/scale" && s.method == Method.GET ->
            json("{\"scale\":${store.uiScale()}}")
        s.uri == "/api/scale" && s.method == Method.POST -> {
            store.setUiScale(org.json.JSONObject(readBody(s)).getDouble("scale").toFloat())
            onConfigChanged() // board notices the change and rebuilds itself
            json("{\"scale\":${store.uiScale()}}")
        }
        // Live zoom while the page's slider is being dragged — GPU transform
        // only; the drag-end POST to /api/scale does the real re-layout.
        s.uri == "/api/scale/preview" && s.method == Method.POST -> {
            val v = org.json.JSONObject(readBody(s)).getDouble("scale").toFloat()
            App.instance.onMain { App.instance.activeBoard?.previewScale(v) }
            json("{\"ok\":true}")
        }
        // ---- Hub-and-spoke family sync ----
        s.uri == "/api/familysync" && s.method == Method.GET ->
            json(FamilySync.statusJson(ctx))
        s.uri == "/api/familysync" && s.method == Method.POST -> {
            val o = org.json.JSONObject(readBody(s))
            FamilySync.setConfig(ctx,
                if (o.has("role")) o.getString("role") else null,
                if (o.has("manualHub")) o.getString("manualHub") else null,
                if (o.has("mirrorAll")) o.getBoolean("mirrorAll") else null,
                if (o.has("hubToken")) o.getString("hubToken") else null)
            json(FamilySync.statusJson(ctx))
        }
        // Served by a hub to its spokes: raw shared family files (no secrets).
        s.uri == "/api/family" && s.method == Method.GET ->
            json(FamilySync.snapshotJson(ctx))
        // Mirror-all: the hub's whole config bundle (includes feeds + credentials).
        s.uri == "/api/family/full" && s.method == Method.GET ->
            resp(200, "text/plain", ConfigBundle.exportPlain(ctx))
        s.uri == "/api/effects" && s.method == Method.GET -> json(effectsJson())
        s.uri == "/api/effects" && s.method == Method.POST -> {
            val o = org.json.JSONObject(readBody(s))
            if (o.has("enabled")) store.setChoreEffects(o.getBoolean("enabled"))
            for (k in CELEBRATE_KINDS) if (o.has(k)) store.setCelebrate(k, o.getBoolean(k))
            o.optString("preview").takeIf { it in CELEBRATE_KINDS }?.let { App.instance.previewCelebration(it) }
            json(effectsJson())
        }
        s.uri == "/api/screensaver" && s.method == Method.GET ->
            json(Screensaver.statusJson(ctx))
        s.uri == "/api/screensaver" && s.method == Method.POST -> {
            val o = org.json.JSONObject(readBody(s))
            if (o.has("mode")) Screensaver.setMode(ctx, o.getString("mode"))
            if (o.has("yieldMinutes")) Screensaver.setYieldMinutes(ctx, o.getInt("yieldMinutes"))
            json(Screensaver.statusJson(ctx))
        }
        else -> resp(404, "text/plain", "not found")
    }

    private fun readBody(s: Req) = String(s.body, Charsets.UTF_8)

    // Opt-in tabs (routines, potty, shopping) default off; the rest default on.
    private fun featuresJson(): String = org.json.JSONObject().also { o ->
        Rules.TAB_KEYS.forEach { o.put(it, store.featureEnabled(it, default = it in DEFAULT_ON)) }
        o.put("defaultTab", store.defaultTab())
    }.toString()

    private fun effectsJson(): String = org.json.JSONObject().put("enabled", store.choreEffects())
        .apply { for (k in CELEBRATE_KINDS) put(k, store.celebrate(k)) }.toString()

    private fun voiceJson(): String = org.json.JSONObject()
        .put("enabled", store.voiceEnabled())
        .put("engine", store.voiceEngine())
        .put("geminiReady", Gemini.isReady(ctx))
        .put("examples", org.json.JSONArray(VoiceActions.EXAMPLES))
        .toString()

    private fun resp(status: Int, mime: String, body: String) =
        NativeResponse(status, "$mime; charset=utf-8", body.toByteArray(Charsets.UTF_8))

    private fun json(body: String) = resp(200, "application/json", body)

    // org.json handles control characters too (raw \n in an error message
    // would otherwise produce an unparsable error response).
    private fun jsonStr(s: String): String = org.json.JSONObject.quote(s)

    companion object {
        // portal-remote already owns 8080 on this device.
        const val PORT = 8090
        private val DEFAULT_ON = setOf("calendar", "chores", "lists", "meals")
    }
}
