package com.portal.calendar

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Household shopping list: quick-add presets or typed items. The board tab is parent-PIN locked. */
object Shopping {
    private const val FILE = "shopping.json"
    private const val MAX_ITEMS = 200
    private const val MAX_NAME = 60

    val PRESETS: Map<String, List<String>> = linkedMapOf(
        "Cleaning" to listOf("Paper towels", "Dish soap", "Dishwasher pods", "Laundry detergent",
            "Fabric softener", "Trash bags", "Sponges", "All-purpose cleaner", "Glass cleaner",
            "Disinfecting wipes"),
        "Bathroom" to listOf("Toilet paper", "Hand soap", "Body wash", "Shampoo", "Conditioner",
            "Toothpaste", "Toothbrushes", "Deodorant", "Tissues"),
        "Kids & pets" to listOf("Diapers", "Baby wipes", "Pull-ups", "Bandages", "Pet food", "Cat litter"),
        "Kitchen & home" to listOf("Aluminum foil", "Plastic wrap", "Zip bags", "Napkins",
            "Coffee filters", "Light bulbs", "Batteries"))

    /** The list as a Markdown checklist (to buy first, then checked off), for saving to a phone. Items are (name, done). */
    fun markdown(items: List<Pair<String, Boolean>>, today: String): String {
        val (done, todo) = items.partition { it.second }
        fun line(i: Pair<String, Boolean>, box: String) = "- [$box] " + i.first.replace(Regex("\\s+"), " ").trim()
        return buildString {
            append("# 🛒 Shopping list\n\n_Saved $today · ${todo.size} to buy, ${done.size} checked off_\n\n")
            todo.forEach { append(line(it, " ")).append('\n') }
            done.forEach { append(line(it, "x")).append('\n') }
            if (items.isEmpty()) append("Nothing on the list.\n")
        }
    }

    fun statusJson(ctx: Context): String = JSONObject()
        .put("items", Data.readArray(ctx, FILE))
        .put("presets", JSONObject().also { o -> PRESETS.forEach { (k, v) -> o.put(k, JSONArray(v)) } })
        .toString()

    fun mutate(ctx: Context, action: JSONObject): String {
        when (action.getString("action")) {
            "add" -> {
                val name = action.getString("name").trim().take(MAX_NAME)
                if (name.isEmpty()) throw IllegalArgumentException("the item needs a name")
                Data.mutate(ctx, FILE) { arr ->
                    val same = (0 until arr.length()).map { arr.getJSONObject(it) }
                        .firstOrNull { it.optString("name").equals(name, ignoreCase = true) }
                    when {
                        // Already on the list: bring a checked-off one back, otherwise nothing to do.
                        same != null -> same.put("done", false)
                        arr.length() >= MAX_ITEMS -> throw IllegalArgumentException("the list is full — clear some checked items")
                        else -> arr.put(JSONObject()
                            .put("id", UUID.randomUUID().toString()).put("name", name).put("done", false)
                            .put("added", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())))
                    }
                }
            }
            "toggle" -> {
                val id = action.getString("id")
                Data.mutate(ctx, FILE) { arr ->
                    (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { it.optString("id") == id }
                        ?.let { it.put("done", !it.optBoolean("done")) }
                        ?: throw IllegalArgumentException("that item is gone")
                }
            }
            "delete" -> {
                val id = action.getString("id")
                Data.mutate(ctx, FILE) { arr ->
                    for (i in arr.length() - 1 downTo 0) if (arr.getJSONObject(i).optString("id") == id) arr.remove(i)
                }
            }
            "clearDone" -> Data.mutate(ctx, FILE) { arr ->
                for (i in arr.length() - 1 downTo 0) if (arr.getJSONObject(i).optBoolean("done")) arr.remove(i)
            }
            else -> throw IllegalArgumentException("unknown action")
        }
        FamilySync.pushIfSpoke(ctx, "shopping", action.toString())
        App.instance.notifyDataChanged()
        return statusJson(ctx)
    }
}
