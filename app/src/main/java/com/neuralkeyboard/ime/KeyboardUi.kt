package com.neuralkeyboard.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Builds the keyboard views in code: a suggestion strip on top of rows of keys.
 */
class KeyboardUi(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onKey(key: Key)
        fun onSuggestionPicked(index: Int)
    }

    var hapticsEnabled = true

    val root: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(color(R.color.kb_background))
        setPadding(dp(3), 0, dp(3), dp(4))
    }

    private val suggestionBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val suggestionViews = ArrayList<TextView>()
    private val keyRows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val handler = Handler(Looper.getMainLooper())

    init {
        repeat(SLOTS) { i ->
            if (i > 0) suggestionBar.addView(divider())
            val tv = TextView(context).apply {
                gravity = Gravity.CENTER
                setTextColor(color(R.color.suggestion_text))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                background = pressable(android.graphics.Color.TRANSPARENT, color(R.color.key_background_pressed))
                setOnClickListener {
                    haptic(this)
                    listener.onSuggestionPicked(i)
                }
            }
            suggestionViews += tv
            suggestionBar.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
        root.addView(suggestionBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))
        root.addView(keyRows)
    }

    /** Shows up to three suggestions; [highlight] marks the one space will insert. */
    fun setSuggestions(words: List<String>, highlight: Int) {
        suggestionViews.forEachIndexed { i, tv ->
            val w = words.getOrNull(i)
            tv.text = w ?: ""
            tv.isEnabled = w != null
            tv.setTypeface(null, if (i == highlight) Typeface.BOLD else Typeface.NORMAL)
            tv.setTextColor(color(if (i == highlight) R.color.accent else R.color.suggestion_text))
        }
    }

    fun setKeys(rows: List<List<Key>>, shift: ShiftState, enterLabel: String) {
        keyRows.removeAllViews()
        for (row in rows) {
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (key in row) line.addView(keyView(key, shift, enterLabel), keyParams(key))
            keyRows.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)))
        }
    }

    private fun keyParams(key: Key) = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, key.weight).apply {
        setMargins(dp(2), dp(4), dp(2), dp(4))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun keyView(key: Key, shift: ShiftState, enterLabel: String): View {
        if (key.type == Key.Type.SPACER) return View(context)
        val tv = TextView(context)
        tv.gravity = Gravity.CENTER
        tv.setTextColor(color(R.color.key_text))
        tv.text = when (key.type) {
            Key.Type.SHIFT -> if (shift == ShiftState.LOCKED) "⇪" else "⇧"
            Key.Type.DELETE -> "⌫"
            Key.Type.ENTER -> enterLabel
            else -> key.label
        }
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (key.type == Key.Type.CHAR) 22f else 16f)
        tv.contentDescription = when (key.type) {
            Key.Type.SHIFT -> "Shift"
            Key.Type.DELETE -> "Delete"
            Key.Type.ENTER -> "Enter"
            Key.Type.SPACE -> "Space"
            Key.Type.LANGUAGE -> "Switch language"
            else -> key.label
        }
        val special = key.isSpecial
        val active = key.type == Key.Type.SHIFT && shift != ShiftState.OFF
        tv.background = when {
            active -> pressable(color(R.color.accent), color(R.color.key_special_pressed))
            special -> pressable(color(R.color.key_special_background), color(R.color.key_special_pressed))
            else -> pressable(color(R.color.key_background), color(R.color.key_background_pressed))
        }
        if (active) tv.setTextColor(android.graphics.Color.WHITE)

        if (key.type == Key.Type.DELETE) {
            // Fire immediately, then auto-repeat while held.
            val repeater = object : Runnable {
                override fun run() {
                    listener.onKey(key)
                    handler.postDelayed(this, REPEAT_INTERVAL_MS)
                }
            }
            tv.setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        haptic(v)
                        listener.onKey(key)
                        handler.postDelayed(repeater, REPEAT_DELAY_MS)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        handler.removeCallbacks(repeater)
                    }
                }
                true
            }
        } else {
            tv.setOnClickListener {
                haptic(it)
                listener.onKey(key)
            }
        }
        return tv
    }

    private fun haptic(v: View) {
        if (hapticsEnabled) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun divider() = View(context).apply {
        setBackgroundColor(color(R.color.divider))
        layoutParams = LinearLayout.LayoutParams(dp(1), dp(22))
    }

    private fun pressable(normal: Int, pressed: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), rounded(pressed))
        addState(intArrayOf(), rounded(normal))
    }

    private fun rounded(c: Int) = GradientDrawable().apply {
        setColor(c)
        cornerRadius = dp(7).toFloat()
    }

    private fun color(id: Int) = context.getColor(id)

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics,
    ).toInt()

    companion object {
        const val SLOTS = 3
        private const val REPEAT_DELAY_MS = 400L
        private const val REPEAT_INTERVAL_MS = 50L
    }
}
