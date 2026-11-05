package com.neuralkeyboard.ime

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.graphics.drawable.Drawable
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.neuralkeyboard.core.ClipboardHistory

/**
 * Builds the keyboard views in code: a suggestion strip (with a clipboard button)
 * on top of either the rows of keys or the clipboard history panel.
 */
class KeyboardUi(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onKey(key: Key)
        fun onSuggestionPicked(index: Int)
        fun onClipboardButton()
        fun onClipPaste(text: String)
        fun onClipPin(text: String, pinned: Boolean)
        fun onClipDelete(text: String)
        fun onClipClear()
    }

    var hapticsEnabled = true
        set(value) {
            field = value
            keyboardView.hapticsEnabled = value
        }

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
    private val keyboardView = KeyboardView(context) { listener.onKey(it) }
    private val keyRows: View get() = keyboardView
    private val clipPanel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
    }
    private val clipButton = ImageView(context)

    val isClipboardOpen: Boolean get() = clipPanel.visibility == View.VISIBLE

    init {
        clipButton.apply {
            setImageDrawable(icon(R.drawable.ic_clipboard, R.color.suggestion_text))
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = context.getString(R.string.clipboard_title)
            background = pressable(android.graphics.Color.TRANSPARENT, color(R.color.key_background_pressed))
            setOnClickListener {
                haptic(this)
                listener.onClipboardButton()
            }
        }
        suggestionBar.addView(clipButton, LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.MATCH_PARENT))
        suggestionBar.addView(divider())
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
        val body = FrameLayout(context)
        body.addView(keyboardView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        body.addView(clipPanel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(body)
    }

    /** Shows the clipboard panel in place of the keys (same height, so nothing jumps). */
    fun showClipboard(items: List<ClipboardHistory.Item>, enabled: Boolean) {
        clipPanel.removeAllViews()

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(smallButton(context.getString(R.string.keyboard_back), R.drawable.ic_back) { listener.onClipboardButton() })
        header.addView(TextView(context).apply {
            text = context.getString(R.string.clipboard_title)
            setTextColor(color(R.color.key_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (items.any { !it.pinned }) {
            header.addView(smallButton(context.getString(R.string.clipboard_clear), null) { listener.onClipClear() })
        }
        clipPanel.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)))

        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        when {
            !enabled -> list.addView(hint(R.string.clipboard_off))
            items.isEmpty() -> list.addView(hint(R.string.clipboard_empty))
            else -> items.forEach { list.addView(clipRow(it)) }
        }
        clipPanel.addView(ScrollView(context).apply { addView(list) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Keep the keyboard height: the panel covers the key rows exactly.
        keyRows.visibility = View.INVISIBLE
        clipPanel.visibility = View.VISIBLE
        clipButton.setImageDrawable(icon(R.drawable.ic_clipboard, R.color.accent))
    }

    fun hideClipboard() {
        clipPanel.visibility = View.GONE
        keyRows.visibility = View.VISIBLE
        clipPanel.removeAllViews()
        clipButton.setImageDrawable(icon(R.drawable.ic_clipboard, R.color.suggestion_text))
    }

    private fun clipRow(item: ClipboardHistory.Item): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pressable(color(R.color.key_background), color(R.color.key_background_pressed))
            setPadding(dp(10), dp(6), dp(4), dp(6))
            setOnClickListener {
                haptic(this)
                listener.onClipPaste(item.text)
            }
        }
        row.addView(TextView(context).apply {
            text = item.text.trim()
            setTextColor(color(R.color.key_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(iconButton(if (item.pinned) R.drawable.ic_pin_on else R.drawable.ic_pin,
            if (item.pinned) R.string.clipboard_unpin else R.string.clipboard_pin, active = item.pinned) {
            listener.onClipPin(item.text, !item.pinned)
        })
        row.addView(iconButton(R.drawable.ic_close, R.string.clipboard_delete, active = false) { listener.onClipDelete(item.text) })
        return row.also {
            it.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(dp(4), dp(3), dp(4), dp(3)) }
        }
    }

    private fun iconButton(iconRes: Int, description: Int, active: Boolean, onClick: () -> Unit) = ImageView(context).apply {
        setImageDrawable(icon(iconRes, if (active) R.color.accent else R.color.suggestion_text))
        scaleType = ImageView.ScaleType.CENTER
        if (iconRes == R.drawable.ic_pin) alpha = 0.55f
        contentDescription = context.getString(description)
        background = pressable(android.graphics.Color.TRANSPARENT, color(R.color.key_background_pressed))
        layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
        setOnClickListener {
            haptic(this)
            onClick()
        }
    }

    private fun smallButton(label: String, iconRes: Int?, onClick: () -> Unit) = TextView(context).apply {
        text = label
        if (iconRes != null) {
            setCompoundDrawablesRelative(icon(iconRes, R.color.accent, 18), null, null, null)
            compoundDrawablePadding = dp(6)
        }
        gravity = Gravity.CENTER
        setTextColor(color(R.color.accent))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dp(12), 0, dp(12), 0)
        background = pressable(android.graphics.Color.TRANSPARENT, color(R.color.key_background_pressed))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT)
        setOnClickListener {
            haptic(this)
            onClick()
        }
    }

    private fun hint(res: Int) = TextView(context).apply {
        setText(res)
        gravity = Gravity.CENTER
        setTextColor(color(R.color.suggestion_text))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dp(16), dp(32), dp(16), dp(16))
    }

    /** Shows up to three suggestions; [highlight] marks the one space will insert. */
    fun setSuggestions(words: List<String>, highlight: Int, clipChip: Boolean = false) {
        if (!isClipboardOpen) clipButton.setImageDrawable(icon(R.drawable.ic_clipboard, R.color.suggestion_text))
        suggestionViews.forEachIndexed { i, tv ->
            val w = words.getOrNull(i)
            tv.text = w ?: ""
            tv.isEnabled = w != null
            tv.setTypeface(null, if (i == highlight) Typeface.BOLD else Typeface.NORMAL)
            tv.setTextColor(color(if (i == highlight) R.color.accent else R.color.suggestion_text))
            // A just-copied text is shown with a clipboard icon in the first slot.
            val chip = clipChip && i == 0 && w != null
            tv.setCompoundDrawablesRelative(if (chip) icon(R.drawable.ic_clipboard, R.color.accent, 18) else null, null, null, null)
            tv.compoundDrawablePadding = if (chip) dp(6) else 0
            tv.setPadding(if (chip) dp(10) else 0, 0, if (chip) dp(6) else 0, 0)
        }
    }

    fun setKeys(rows: List<KeyRow>, shift: ShiftState, enterLabel: String) {
        keyboardView.setKeys(rows, shift, enterLabel)
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

    /** A generated vector icon (see tools/icons), tinted for the current theme. */
    private fun icon(res: Int, colorRes: Int, sizeDp: Int = 24): Drawable = context.getDrawable(res)!!.mutate().apply {
        setTint(color(colorRes))
        setBounds(0, 0, dp(sizeDp), dp(sizeDp))
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics,
    ).toInt()

    companion object {
        const val SLOTS = 3
    }
}
