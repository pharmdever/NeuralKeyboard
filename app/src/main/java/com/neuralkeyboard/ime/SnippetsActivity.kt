package com.neuralkeyboard.ime

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.neuralkeyboard.core.SnippetStore

/** Add, edit and delete text snippets. */
class SnippetsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var store: SnippetStore
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        store = prefs.loadSnippets()
        title = getString(R.string.snippets_title)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(24))
        }
        column.addView(TextView(this).apply {
            setText(R.string.snippets_help)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, 0, 0, dp(12))
        })
        column.addView(Button(this).apply {
            setText(R.string.snippet_add)
            setOnClickListener { edit(null) }
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(list)
        setContentView(ScrollView(this).apply { addView(column) })
        render()
    }

    private fun render() {
        list.removeAllViews()
        for (s in store.snippets) {
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(12), 0, dp(12))
                isClickable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { edit(s) }
                addView(TextView(context).apply {
                    text = s.trigger
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(getColor(R.color.accent))
                })
                addView(TextView(context).apply {
                    text = s.text
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    maxLines = 3
                    ellipsize = TextUtils.TruncateAt.END
                })
            })
        }
    }

    private fun edit(existing: SnippetStore.Snippet?) {
        val trigger = EditText(this).apply {
            hint = getString(R.string.snippet_trigger)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
            setText(existing?.trigger ?: "")
        }
        val text = EditText(this).apply {
            hint = getString(R.string.snippet_text)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            gravity = android.view.Gravity.TOP
            setText(existing?.text ?: "")
        }
        // One-tap placeholders, inserted at the cursor of the text box.
        val placeholders = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (p in listOf("{cursor}", "{date}", "{time}", "{clipboard}")) {
            placeholders.addView(Button(this, null, android.R.attr.buttonStyleSmall).apply {
                this.text = p
                isAllCaps = false
                setOnClickListener {
                    val at = text.selectionStart.coerceAtLeast(0)
                    text.text.insert(at, p)
                }
            })
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(trigger)
            addView(text)
            addView(HorizontalScrollView(context).apply { addView(placeholders) })
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) getString(R.string.snippet_add).removePrefix("+ ") else existing.trigger)
            .setView(form)
            .setPositiveButton(R.string.snippet_save, null)
            .setNegativeButton(R.string.snippet_cancel, null)
            .apply {
                if (existing != null) setNeutralButton(R.string.snippet_delete) { _, _ ->
                    store.remove(existing.trigger)
                    save()
                }
            }
            .create()
        dialog.setOnShowListener {
            // Validate before closing.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newTrigger = trigger.text.toString()
                val ok = store.put(newTrigger, text.text.toString())
                if (!ok) {
                    Toast.makeText(this, R.string.snippet_invalid, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                // Renamed trigger: drop the old entry.
                if (existing != null && SnippetStore.normalizeTrigger(newTrigger) != existing.trigger) {
                    store.remove(existing.trigger)
                }
                save()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun save() {
        prefs.saveSnippets(store)
        render()
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()
}
