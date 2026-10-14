package com.portal.calendar

import android.content.Context

/** Start-fresh resets for one part of the hub at a time (the full reset is [ConfigBundle.resetAll]). */
object Resets {
    val SCOPES = listOf("points", "chores", "potty", "routines")

    /** Validates the names a caller sent; throws IllegalArgumentException if none or any are unknown. */
    fun parse(names: List<String>): Set<String> {
        require(names.isNotEmpty()) { "choose what to reset" }
        names.firstOrNull { it !in SCOPES }?.let { throw IllegalArgumentException("unknown reset: $it") }
        return names.toSet()
    }

    fun run(ctx: Context, scopes: Set<String>) {
        // Points first: when the chores go too there is no chore credit left to cancel out.
        if ("points" in scopes) Chores.resetWeekPointsAll(ctx, choresAlsoCleared = "chores" in scopes)
        if ("chores" in scopes) Chores.clearChores(ctx)
        if ("potty" in scopes) Potty.clearAll(ctx)
        if ("routines" in scopes) Routines.clearAll(ctx)
        App.instance.notifyDataChanged()
    }
}
