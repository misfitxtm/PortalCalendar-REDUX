package com.portal.calendar

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * The board's Reward Shop: pick a kid, see what they can spend this week, and tap a reward.
 * Spending takes a parent's approval, so the tap goes through [onPick] (the board asks for the PIN).
 */
class RewardsTab(
    private val ctx: Context,
    private val onPick: (anchor: View, memberId: String, memberName: String, rewardId: String, rewardName: String) -> Unit
) {
    private var selected = ""
    val view: LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(18), dp(22), dp(16))
    }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).roundToInt()

    fun render() {
        view.removeAllViews()
        val st = JSONObject(Chores.statusJson(ctx))
        val members = st.getJSONArray("members")
        val rewards = st.getJSONArray("rewards")
        val spendable = st.getJSONObject("spendable")

        // A gold marquee, so the shop feels like a treat rather than a list.
        view.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = goldBar(20)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            addView(TextView(ctx).apply {
                text = "🎁  💰  REWARD SHOP  💰  🎁"
                textSize = 24f
                setTextColor(GOLD_INK)
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
            addView(TextView(ctx).apply {
                text = "Spend your stars on something awesome!"
                textSize = 14f
                setTextColor(GOLD_INK)
                gravity = Gravity.CENTER
            })
        }, lp(bottom = dp(10)))

        if (members.length() == 0) { view.addView(note("Add family members first — set them up from your phone.")); return }
        val kids = (0 until members.length()).map { members.getJSONObject(it) }
        val me = kids.firstOrNull { it.getString("id") == selected } ?: kids.first().also { selected = it.getString("id") }
        val left = spendable.optInt(me.getString("id"))

        // Who is shopping: chips with each kid's points to spend.
        val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for (m in kids) {
            val on = m.getString("id") == selected
            chips.addView(TextView(ctx).apply {
                text = "${m.getString("name")}  ⭐ ${spendable.optInt(m.getString("id"))}"
                textSize = 16f
                setTextColor(if (on) Color.WHITE else INK)
                background = rounded(if (on) Members.parse(m.getString("color")) else PILL, 18)
                setPadding(dp(18), dp(10), dp(18), dp(10))
                setOnClickListener { selected = m.getString("id"); render() }
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) })
        }
        view.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, lp(bottom = dp(6)))
        view.addView(TextView(ctx).apply {
            text = "💰 ${me.getString("name")} has ⭐ $left to spend this week · stars reset every Friday"
            textSize = 15f
            setTextColor(INK)
        }, lp(bottom = dp(12)))

        if (rewards.length() == 0) {
            view.addView(note("No rewards yet.\nAdd some from your phone: Chores → Rewards."))
            return
        }
        val portrait = ctx.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val cols = if (portrait) 2 else 3
        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        var row: LinearLayout? = null
        for (i in 0 until rewards.length()) {
            val r = rewards.getJSONObject(i)
            if (i % cols == 0) row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val cost = r.optInt("cost")
            val ok = left >= cost
            row!!.addView(card(r, cost, ok, cost - left) { anchor ->
                onPick(anchor, me.getString("id"), me.getString("name"), r.getString("id"), r.getString("name"))
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) })
            if (i % cols == cols - 1 || i == rewards.length() - 1) {
                for (pad in (i % cols) + 1 until cols) row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
                grid.addView(row, lp())
            }
        }
        view.addView(ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(grid, FrameLayout.LayoutParams(MATCH, WRAP))
        }, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }

    private fun card(r: JSONObject, cost: Int, affordable: Boolean, short: Int, onTap: (View) -> Unit) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = if (affordable) stroked(CARD, GOLD) else rounded(CARD, 16)
        alpha = if (affordable) 1f else 0.55f
        elevation = if (affordable) dp(4).toFloat() else 0f
        setPadding(dp(12), dp(14), dp(12), dp(14))
        // The prize sits in a soft gold bubble, with a sparkle on the ones you can afford.
        addView(TextView(ctx).apply {
            text = (r.optString("icon").ifEmpty { "🎁" }) + if (affordable) "✨" else ""
            textSize = 40f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x33FFC107) }
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }, LinearLayout.LayoutParams(WRAP, WRAP))
        addView(TextView(ctx).apply {
            text = r.optString("name")
            textSize = 16f
            setTextColor(INK)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            minLines = 2; maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, lp(top = dp(6)))
        addView(TextView(ctx).apply {
            text = if (affordable) "💰 $cost · 🎁 Get it!" else "🔒 $cost · needs $short more"
            textSize = 15f
            setTextColor(if (affordable) GOLD_INK else MUTED)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            background = if (affordable) goldBar(16) else rounded(PILL, 16)
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(8) })
        if (affordable) setOnClickListener { onTap(this) }
    }

    private fun note(t: String) = TextView(ctx).apply { text = t; textSize = 17f; setTextColor(MUTED); gravity = Gravity.CENTER }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    private fun stroked(fill: Int, stroke: Int) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(16).toFloat(); setStroke(dp(3), stroke)
    }

    private fun goldBar(radius: Int) = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(GOLD_LIGHT, GOLD)).apply {
        cornerRadius = dp(radius).toFloat()
    }

    private fun lp(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = top; bottomMargin = bottom }

    companion object {
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        private val CARD get() = Palette.CARD
        private val PILL get() = Palette.PILL
        private val INK get() = Palette.INK
        private val MUTED get() = Palette.MUTED
        private const val GOLD = 0xFFFFB300.toInt()
        private const val GOLD_LIGHT = 0xFFFFE082.toInt()
        private const val GOLD_INK = 0xFF4A2800.toInt() // dark brown: readable on gold in light and dark themes
    }
}
