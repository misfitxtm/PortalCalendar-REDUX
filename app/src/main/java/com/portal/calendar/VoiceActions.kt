package com.portal.calendar

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Carries out a [VoiceCommands.Command] and returns a short sentence to show the user. */
object VoiceActions {
    fun apply(ctx: Context, c: VoiceCommands.Command): String = when (c) {
        is VoiceCommands.Command.Shopping -> {
            Shopping.mutate(ctx, JSONObject().put("action", "add").put("name", c.item))
            "Added “${c.item}” to the shopping list."
        }
        is VoiceCommands.Command.ToList -> {
            // Only existing lists: a misheard name must not create a junk list.
            val lists = JSONArray(FamilyLists.json(ctx))
            val hit = (0 until lists.length()).map { lists.getJSONObject(it) }.firstOrNull {
                MagicWords.fuzzyEquals(it.optString("name").lowercase(Locale.US), c.list)
            } ?: throw IllegalArgumentException("I couldn't find a list called “${c.list}”.")
            FamilyLists.mutate(ctx, JSONObject().put("action", "addItem")
                .put("listId", hit.optString("id")).put("text", c.item))
            "Added “${c.item}” to ${hit.optString("name")}."
        }
    }

    /** The family's own words (lists, items, names) so the recognizer can hear "add <your item> to groceries". */
    fun grammar(ctx: Context): String {
        val extra = ArrayList<String>()
        val lists = JSONArray(FamilyLists.json(ctx))
        for (i in 0 until lists.length()) {
            val l = lists.getJSONObject(i)
            extra.add(l.optString("name"))
            val items = l.optJSONArray("items") ?: continue
            for (j in 0 until items.length()) extra.add(items.getJSONObject(j).optString("text"))
        }
        val shop = JSONObject(Shopping.statusJson(ctx)).getJSONArray("items")
        for (i in 0 until shop.length()) extra.add(shop.getJSONObject(i).optString("name"))
        return VoiceCommands.grammar(extra)
    }

    /** Whether a Command can be understood at all, for tests and the settings page. */
    val EXAMPLES = listOf("add paper towels to the shopping list", "add eggs to groceries")
}
