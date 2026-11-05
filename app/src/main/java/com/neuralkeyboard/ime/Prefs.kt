package com.neuralkeyboard.ime

import android.content.Context
import android.content.SharedPreferences
import com.neuralkeyboard.core.Language
import java.io.File

/** Typed access to the keyboard's settings, shared by the service and the settings screen. */
class Prefs(context: Context) {

    private val app = context.applicationContext
    val raw: SharedPreferences = app.getSharedPreferences("neuralkeyboard", Context.MODE_PRIVATE)

    var autocorrect by flag("autocorrect", true)
    var autoCapitalize by flag("auto_cap", true)
    var doubleSpacePeriod by flag("double_space", true)
    var haptics by flag("haptics", true)
    var learning by flag("learning", true)
    var clipboardHistory by flag("clipboard_history", true)
    var learnFromClipboard by flag("learn_from_clipboard", true)

    /** How long unpinned clipboard items are kept, in hours; 0 = until deleted. */
    var clipboardRetentionHours: Int
        get() = raw.getInt("clipboard_retention_hours", 0)
        set(value) = raw.edit().putInt("clipboard_retention_hours", value).apply()

    val clipboardTtlMillis: Long?
        get() = clipboardRetentionHours.takeIf { it > 0 }?.let { it * 3_600_000L }

    /** Drug-name vocabulary; changing it makes a running keyboard reload its dictionaries. */
    var medicalTerms: Boolean
        get() = raw.getBoolean("medical_terms", true)
        set(value) {
            raw.edit().putBoolean("medical_terms", value).putInt("reset_token", resetToken + 1).apply()
        }

    var language: Language
        get() = Language.fromCode(raw.getString("language", null))
        set(value) = raw.edit().putString("language", value.code).apply()

    /** Bumped when dictionaries must be reloaded (learned data wiped, vocabulary changed). */
    val resetToken: Int get() = raw.getInt("reset_token", 0)

    fun userDataFile(language: Language) = File(app.filesDir, "user_${language.code}.tsv")

    /** Clipboard history, kept in the app's private storage. */
    val clipboardFile: File get() = File(app.filesDir, "clipboard_history.tsv")

    /** Older versions stored only pinned items here; read once, then deleted. */
    val legacyPinnedClipsFile: File get() = File(app.filesDir, "clipboard_pinned.tsv")

    fun clearLearnedWords() {
        Language.entries.forEach { userDataFile(it).delete() }
        raw.edit().putInt("reset_token", resetToken + 1).apply()
    }

    private fun flag(key: String, default: Boolean) = object : kotlin.properties.ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = raw.getBoolean(key, default)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Boolean) {
            raw.edit().putBoolean(key, value).apply()
        }
    }
}
