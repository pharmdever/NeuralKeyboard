package com.neuralkeyboard.ime

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Launcher screen: walks the user through enabling the keyboard, exposes the
 * preferences and offers a text box to try it out.
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var enableStatus: TextView
    private lateinit var selectStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        title = getString(R.string.setup_title)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }

        column.addView(text(R.string.setup_intro, 16f))

        column.addView(heading(R.string.step_enable))
        column.addView(button(R.string.step_enable) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })
        enableStatus = text(R.string.status_enabled, 14f).also(column::addView)

        column.addView(heading(R.string.step_choose))
        column.addView(button(R.string.step_choose) {
            getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        })
        selectStatus = text(R.string.status_selected, 14f).also(column::addView)

        column.addView(EditText(this).apply {
            hint = getString(R.string.try_it)
            minLines = 3
        })

        column.addView(heading(R.string.prefs_title))
        column.addView(toggle(R.string.pref_autocorrect, prefs.autocorrect) { prefs.autocorrect = it })
        column.addView(toggle(R.string.pref_auto_cap, prefs.autoCapitalize) { prefs.autoCapitalize = it })
        column.addView(toggle(R.string.pref_double_space, prefs.doubleSpacePeriod) { prefs.doubleSpacePeriod = it })
        column.addView(toggle(R.string.pref_haptics, prefs.haptics) { prefs.haptics = it })
        column.addView(toggle(R.string.pref_learning, prefs.learning) { prefs.learning = it })
        column.addView(button(R.string.clear_learned) {
            prefs.clearLearnedWords()
            Toast.makeText(this, R.string.cleared, Toast.LENGTH_SHORT).show()
        })

        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshStatus() // the IME picker dialog closing returns focus here
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val imm = getSystemService(InputMethodManager::class.java)
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val selected = current?.startsWith("$packageName/") == true
        enableStatus.visibility = if (enabled) TextView.VISIBLE else TextView.GONE
        selectStatus.visibility = if (selected) TextView.VISIBLE else TextView.GONE
    }

    private fun text(res: Int, size: Float) = TextView(this).apply {
        setText(res)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setPadding(0, dp(4), 0, dp(8))
    }

    private fun heading(res: Int) = TextView(this).apply {
        setText(res)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(6))
    }

    private fun button(res: Int, onClick: () -> Unit) = Button(this).apply {
        setText(res)
        setOnClickListener { onClick() }
    }

    @Suppress("DEPRECATION") // android.widget.Switch keeps us free of AndroidX dependencies
    private fun toggle(res: Int, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        setText(res)
        isChecked = checked
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(0, dp(10), 0, dp(10))
        setOnCheckedChangeListener { _, value -> onChange(value) }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()
}
