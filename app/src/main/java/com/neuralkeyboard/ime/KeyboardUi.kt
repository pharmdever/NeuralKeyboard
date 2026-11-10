package com.neuralkeyboard.ime

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.BaseAdapter
import android.widget.ListView
import android.view.ViewGroup
import android.widget.TextView
import com.neuralkeyboard.core.ClipboardHistory
import com.neuralkeyboard.core.SnippetStore

/**
 * Builds the keyboard views in code: a suggestion strip (with clipboard and snippet
 * buttons) on top of either the keys or a panel (clipboard history / snippets), plus
 * a transparent overlay that draws the key-press popup above everything.
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
        fun onSnippetButton()
        fun onSnippetPicked(trigger: String)
        fun onEditSnippets()
    }

    enum class Panel { CLIPBOARD, SNIPPETS }

    /** What the first suggestion slot is showing, if it is a special chip. */
    enum class Chip { CLIPBOARD, SNIPPET }

    // Declared first: the buttons created by the property initialisers below use it.
    private val iconCache = HashMap<Pair<Int, Int>, Drawable>()

    var hapticsEnabled = true
        set(value) {
            field = value
            keyboardView.hapticsEnabled = value
        }

    var openPanel: Panel? = null
        private set

    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(color(R.color.kb_background))
        setPadding(dp(3), 0, dp(3), dp(4))
    }
    private val preview = KeyPreviewOverlay(context)

    /** The view handed to the input method framework. */
    val root: FrameLayout = FrameLayout(context).apply {
        addView(content)
        addView(preview, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private val suggestionBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val suggestionViews = ArrayList<TextView>()
    private var lastSuggestions: Triple<List<String>, Int, Chip?>? = null
    private val keyboardView = KeyboardView(context) { listener.onKey(it) }
    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
    }
    private val clipButton = toolbarButton(R.drawable.ic_clipboard, R.string.clipboard_title) { listener.onClipboardButton() }
    private val snippetButton = toolbarButton(R.drawable.ic_snippet, R.string.snippets_title) { listener.onSnippetButton() }

    init {
        keyboardView.previewListener = { label, rect ->
            if (label == null || rect == null) preview.hide() else preview.show(label, rect, keyboardView)
        }
        suggestionBar.addView(clipButton, LinearLayout.LayoutParams(dp(42), LinearLayout.LayoutParams.MATCH_PARENT))
        suggestionBar.addView(snippetButton, LinearLayout.LayoutParams(dp(42), LinearLayout.LayoutParams.MATCH_PARENT))
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
        content.addView(suggestionBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))
        val body = FrameLayout(context)
        body.addView(keyboardView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        // The panel's height is set to the keys' height when it opens (see showPanel).
        body.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 0))
        content.addView(body)
    }

    private val debuggable = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0

    init {
        if (debuggable) {
            // Debug builds only: report sizes and button positions for tools/ui_test.py.
            root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                root.post {
                    if (!root.isShown) return@post
                    val at = IntArray(2)
                    root.getLocationOnScreen(at)
                    android.util.Log.i("NKTEST", "root top=${at[1]} height=${root.height}")
                    for ((name, v) in listOf("clipboard" to clipButton, "snippet" to snippetButton)) {
                        v.getLocationOnScreen(at)
                        android.util.Log.i("NKTEST", "button $name ${at[0] + v.width / 2},${at[1] + v.height / 2}")
                    }
                }
            }
        }
    }

    /** Key-press effects (A: popup, B: press animation). */
    fun setEffects(popup: Boolean, pressAnimation: Boolean) {
        keyboardView.showPreview = popup
        keyboardView.animatePress = pressAnimation
        if (!popup) preview.hide()
    }

    // ------------------------------------------------------------------ panels

    fun showClipboard(items: List<ClipboardHistory.Item>, enabled: Boolean) {
        val empty = when {
            !enabled -> R.string.clipboard_off
            items.isEmpty() -> R.string.clipboard_empty
            else -> 0
        }
        val clear = if (items.any { !it.pinned }) {
            smallButton(context.getString(R.string.clipboard_clear), null) { listener.onClipClear() }
        } else null
        showPanel(Panel.CLIPBOARD, R.string.clipboard_title, clear, empty, if (enabled) items.size else 0) { clipRow(items[it]) }
    }

    fun showSnippets(snippets: List<SnippetStore.Snippet>) {
        val edit = smallButton(context.getString(R.string.snippets_edit), R.drawable.ic_edit) { listener.onEditSnippets() }
        showPanel(Panel.SNIPPETS, R.string.snippets_title, edit, if (snippets.isEmpty()) R.string.snippets_empty else 0, snippets.size) {
            snippetRow(snippets[it])
        }
    }

    /**
     * Shows a panel in place of the keys (same height, so nothing jumps). Rows are
     * created only as they scroll into view, so a long history opens instantly.
     */
    private fun showPanel(which: Panel, title: Int, action: View?, emptyText: Int, count: Int, row: (Int) -> View) {
        panel.removeAllViews()
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(smallButton(context.getString(R.string.keyboard_back), R.drawable.ic_back) { hidePanel() })
        header.addView(TextView(context).apply {
            setText(title)
            setTextColor(color(R.color.key_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        action?.let(header::addView)
        panel.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)))

        val body: View = if (emptyText != 0) hint(emptyText) else ListView(context).apply {
            divider = null
            selector = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            adapter = object : BaseAdapter() {
                override fun getCount() = count
                override fun getItem(position: Int): Any = position
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View = row(position)
            }
        }
        panel.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Exactly the keys' height, so opening a panel never resizes the keyboard.
        if (panel.layoutParams.height != keyboardView.height) {
            panel.layoutParams = panel.layoutParams.apply { height = keyboardView.height }
        }
        keyboardView.visibility = View.INVISIBLE
        panel.visibility = View.VISIBLE
        openPanel = which
        preview.hide()
        refreshToolbar()
    }

    fun hidePanel() {
        panel.visibility = View.GONE
        keyboardView.visibility = View.VISIBLE
        panel.removeAllViews()
        openPanel = null
        refreshToolbar()
    }

    private fun refreshToolbar() {
        clipButton.setImageDrawable(icon(R.drawable.ic_clipboard,
            if (openPanel == Panel.CLIPBOARD) R.color.accent else R.color.suggestion_text))
        snippetButton.setImageDrawable(icon(R.drawable.ic_snippet,
            if (openPanel == Panel.SNIPPETS) R.color.accent else R.color.suggestion_text))
    }

    private fun clipRow(item: ClipboardHistory.Item): View {
        val row = listRow { listener.onClipPaste(item.text) }
        row.addView(rowText(item.text.trim(), maxLines = 2), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(iconButton(if (item.pinned) R.drawable.ic_pin_on else R.drawable.ic_pin,
            if (item.pinned) R.string.clipboard_unpin else R.string.clipboard_pin, active = item.pinned) {
            listener.onClipPin(item.text, !item.pinned)
        })
        row.addView(iconButton(R.drawable.ic_close, R.string.clipboard_delete, active = false) { listener.onClipDelete(item.text) })
        return wrapRow(row)
    }

    private fun snippetRow(snippet: SnippetStore.Snippet): View {
        val row = listRow { listener.onSnippetPicked(snippet.trigger) }
        row.setPadding(dp(10), dp(9), dp(10), dp(9))
        row.addView(TextView(context).apply {
            text = snippet.trigger
            setTextColor(color(R.color.accent))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            minWidth = dp(64)
        })
        row.addView(rowText(snippet.text.replace('\n', ' ').trim(), maxLines = 2),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return wrapRow(row)
    }

    private fun listRow(onClick: () -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = pressable(color(R.color.key_background), color(R.color.key_background_pressed))
        setPadding(dp(10), dp(6), dp(4), dp(6))
        setOnClickListener {
            haptic(this)
            onClick()
        }
    }

    /** ListView ignores margins, so each row sits in a padded wrapper. */
    private fun wrapRow(row: View) = FrameLayout(context).apply {
        setPadding(dp(4), dp(3), dp(4), dp(3))
        addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun rowText(text: String, maxLines: Int) = TextView(context).apply {
        this.text = text
        setTextColor(color(R.color.key_text))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        this.maxLines = maxLines
        ellipsize = TextUtils.TruncateAt.END
    }

    // ------------------------------------------------------------------ suggestions & keys

    /** Shows up to three suggestions; [highlight] marks the one space will insert. */
    fun setSuggestions(words: List<String>, highlight: Int, chip: Chip? = null) {
        val shown = Triple(words.take(SLOTS), highlight, chip)
        if (shown == lastSuggestions) return // nothing to redraw or re-layout
        lastSuggestions = shown
        suggestionViews.forEachIndexed { i, tv ->
            val w = words.getOrNull(i)
            tv.text = w ?: ""
            tv.isEnabled = w != null
            val isChip = chip != null && i == 0 && w != null
            tv.setTypeface(null, if (i == highlight || isChip) Typeface.BOLD else Typeface.NORMAL)
            tv.setTextColor(color(if (i == highlight || isChip) R.color.accent else R.color.suggestion_text))
            val chipIcon = when (chip) {
                Chip.CLIPBOARD -> R.drawable.ic_clipboard
                Chip.SNIPPET -> R.drawable.ic_snippet
                null -> 0
            }
            tv.setCompoundDrawablesRelative(if (isChip) icon(chipIcon, R.color.accent, 18) else null, null, null, null)
            tv.compoundDrawablePadding = if (isChip) dp(6) else 0
            tv.setPadding(if (isChip) dp(10) else 0, 0, if (isChip) dp(6) else 0, 0)
        }
    }

    fun setKeys(rows: List<KeyRow>, shift: ShiftState, enterLabel: String) {
        keyboardView.setKeys(rows, shift, enterLabel)
    }

    // ------------------------------------------------------------------ helpers

    private fun toolbarButton(iconRes: Int, description: Int, onClick: () -> Unit) = ImageView(context).apply {
        setImageDrawable(icon(iconRes, R.color.suggestion_text))
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = context.getString(description)
        background = pressable(android.graphics.Color.TRANSPARENT, color(R.color.key_background_pressed))
        setOnClickListener {
            haptic(this)
            onClick()
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
    private fun icon(res: Int, colorRes: Int, sizeDp: Int = 24): Drawable {
        // Inflate and tint each icon once; later uses are cheap copies.
        val base = iconCache.getOrPut(res to colorRes) {
            context.getDrawable(res)!!.mutate().apply { setTint(color(colorRes)) }
        }
        val copy = base.constantState?.newDrawable(context.resources) ?: base
        copy.setBounds(0, 0, dp(sizeDp), dp(sizeDp))
        return copy
    }


    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics,
    ).toInt()

    companion object {
        const val SLOTS = 3
    }
}
