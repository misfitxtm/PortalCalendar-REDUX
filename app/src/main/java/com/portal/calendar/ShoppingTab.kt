package com.portal.calendar

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Parent-locked shopping list: tap an item to check it off, quick-add household
 * items from presets, or type one. The PIN gate is applied by the board when the
 * tab is opened (see BoardController.setTabLocked), not here.
 */
class ShoppingTab(private val ctx: Context) {
    private var category = ""
    private lateinit var itemsBox: LinearLayout
    private lateinit var catRow: LinearLayout
    private lateinit var presetBox: LinearLayout
    private lateinit var clearBtn: TextView
    private lateinit var input: EditText

    val view: LinearLayout = build()

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).roundToInt()

    private fun build(): LinearLayout {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(16))
        }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(ctx).apply {
            text = "SHOPPING LIST 🔒"
            textSize = 13f
            setTextColor(ACCENT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.14f
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        clearBtn = TextView(ctx).apply {
            text = "Clear checked"
            textSize = 14f
            setTextColor(INK)
            background = rounded(PILL, 18)
            setPadding(dp(16), dp(7), dp(16), dp(7))
            setOnClickListener { act(JSONObject().put("action", "clearDone")) }
        }
        head.addView(clearBtn)
        root.addView(head, lp(bottom = dp(12)))

        val portrait = ctx.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val body = LinearLayout(ctx).apply { orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
        root.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))

        itemsBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val itemsCard = card().apply {
            addView(ScrollView(ctx).apply {
                isVerticalScrollBarEnabled = false
                addView(itemsBox, FrameLayout.LayoutParams(MATCH, WRAP))
            }, LinearLayout.LayoutParams(MATCH, MATCH))
        }
        body.addView(itemsCard, if (portrait) LinearLayout.LayoutParams(MATCH, 0, 1f).apply { bottomMargin = dp(10) }
                                else LinearLayout.LayoutParams(0, MATCH, 1f).apply { rightMargin = dp(10) })

        val right = card()
        right.addView(TextView(ctx).apply {
            text = "Quick add"
            textSize = 15f
            setTextColor(INK)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, lp(bottom = dp(8)))
        catRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        right.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(catRow)
        }, lp(bottom = dp(8)))
        presetBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        right.addView(ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(presetBox, FrameLayout.LayoutParams(MATCH, WRAP))
        }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        input = EditText(ctx).apply {
            hint = "+ Type an item"
            textSize = 16f
            setTextColor(INK)
            setHintTextColor(FAINT)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = rounded(PILL, 12)
            setPadding(dp(14), dp(11), dp(14), dp(11))
            setOnEditorActionListener { v, _, _ ->
                val name = text.toString().trim()
                if (name.isNotEmpty()) {
                    act(JSONObject().put("action", "add").put("name", name))
                    setText("")
                    (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .hideSoftInputFromWindow(v.windowToken, 0)
                }
                true
            }
        }
        right.addView(input, lp(top = dp(10)))
        body.addView(right, if (portrait) LinearLayout.LayoutParams(MATCH, 0, 1f)
                            else LinearLayout.LayoutParams(0, MATCH, 1f))
        return root
    }

    fun render() {
        val st = JSONObject(Shopping.statusJson(ctx))
        val items = st.getJSONArray("items")
        val list = (0 until items.length()).map { items.getJSONObject(it) }.sortedBy { it.optBoolean("done") }
        val open = list.filter { !it.optBoolean("done") }.map { it.optString("name").lowercase() }.toSet()
        clearBtn.visibility = if (list.any { it.optBoolean("done") }) View.VISIBLE else View.GONE

        itemsBox.removeAllViews()
        if (list.isEmpty()) itemsBox.addView(TextView(ctx).apply {
            text = "Nothing to buy 🎉\nTap a quick-add item or type one."
            textSize = 16f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            setPadding(0, dp(30), 0, 0)
        }, lp())
        for (item in list) {
            val done = item.optBoolean("done")
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(if (done) PILL else CARD_SOFT, 12)
                setPadding(dp(14), dp(12), dp(8), dp(12))
                setOnClickListener { act(JSONObject().put("action", "toggle").put("id", item.optString("id"))) }
            }
            row.addView(TextView(ctx).apply {
                text = if (done) "☑" else "☐"
                textSize = 22f
                setTextColor(if (done) MUTED else ACCENT)
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(12) })
            row.addView(TextView(ctx).apply {
                text = item.optString("name")
                textSize = 17f
                setTextColor(if (done) MUTED else INK)
                if (done) paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(TextView(ctx).apply {
                text = "✕"
                textSize = 18f
                setTextColor(MUTED)
                gravity = Gravity.CENTER
                minimumWidth = dp(44); minimumHeight = dp(40)
                setOnClickListener { _ -> act(JSONObject().put("action", "delete").put("id", item.optString("id"))) }
            })
            itemsBox.addView(row, lp(bottom = dp(8)))
        }

        val presets = st.getJSONObject("presets")
        val cats = presets.keys().asSequence().toList()
        if (category !in cats) category = cats.firstOrNull() ?: ""
        catRow.removeAllViews()
        for (c in cats) catRow.addView(TextView(ctx).apply {
            text = c
            textSize = 14f
            setTextColor(if (c == category) Color.WHITE else INK)
            background = rounded(if (c == category) ACCENT else PILL, 16)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { category = c; render() }
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) })

        presetBox.removeAllViews()
        val names = presets.optJSONArray(category)
        if (names != null) {
            var row: LinearLayout? = null
            for (i in 0 until names.length()) {
                if (i % 2 == 0) row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                val name = names.getString(i)
                val onList = name.lowercase() in open
                row!!.addView(TextView(ctx).apply {
                    text = if (onList) "✓ $name" else name
                    textSize = 15f
                    setTextColor(if (onList) Color.WHITE else INK)
                    background = rounded(if (onList) ACCENT else CARD_SOFT, 12)
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    setOnClickListener { act(JSONObject().put("action", "add").put("name", name)) }
                }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
                if (i % 2 == 1 || i == names.length() - 1) {
                    if (i % 2 == 0) row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
                    presetBox.addView(row, lp())
                }
            }
        }
    }

    private fun act(a: JSONObject) { runCatching { Shopping.mutate(ctx, a) } }

    // ------------------------------------------------------------ helpers

    private fun card() = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(CARD, 16)
        elevation = dp(2).toFloat()
        setPadding(dp(14), dp(14), dp(14), dp(14))
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun lp(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = top; bottomMargin = bottom }

    companion object {
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        private val CARD get() = Palette.CARD
        private val CARD_SOFT get() = Palette.CARD_SOFT
        private val PILL get() = Palette.PILL
        private val INK get() = Palette.INK
        private val MUTED get() = Palette.MUTED
        private val FAINT get() = Palette.FAINT
        private val ACCENT get() = Palette.ACCENT
    }
}
