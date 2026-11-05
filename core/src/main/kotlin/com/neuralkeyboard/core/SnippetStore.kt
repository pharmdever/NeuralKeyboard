package com.neuralkeyboard.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Text snippets: a short trigger ("soap") that expands to longer text.
 *
 * Placeholders in the text:
 *   {date}       today, dd/MM/yyyy
 *   {time}       now, HH:mm
 *   {clipboard}  the most recently copied text
 *   {cursor}     where the cursor goes after inserting (first occurrence)
 */
class SnippetStore {

    data class Snippet(val trigger: String, val text: String)

    /** Text to insert and where the cursor should end up (index into [text]). */
    data class Expansion(val text: String, val cursor: Int)

    private val list = ArrayList<Snippet>()

    val snippets: List<Snippet> get() = list.toList()

    /** Adds or replaces the snippet with the same trigger. Returns false for an invalid trigger. */
    fun put(trigger: String, text: String): Boolean {
        val t = normalizeTrigger(trigger) ?: return false
        if (text.isEmpty()) return false
        list.removeAll { it.trigger == t }
        list.add(Snippet(t, text))
        list.sortBy { it.trigger }
        return true
    }

    fun remove(trigger: String) {
        list.removeAll { it.trigger == normalizeTrigger(trigger) }
    }

    /** Exact (case-insensitive) trigger match for a typed word. */
    fun find(typed: String): Snippet? {
        val t = normalizeTrigger(typed) ?: return null
        return list.firstOrNull { it.trigger == t }
    }

    fun expand(snippet: Snippet, now: Date = Date(), clipboard: String? = null): Expansion {
        var text = snippet.text
            .replace("{date}", SimpleDateFormat("dd/MM/yyyy", Locale.US).format(now))
            .replace("{time}", SimpleDateFormat("HH:mm", Locale.US).format(now))
            .replace("{clipboard}", clipboard ?: "")
        val marker = text.indexOf(CURSOR)
        val cursor = if (marker >= 0) marker else text.replace(CURSOR, "").length
        text = text.replace(CURSOR, "")
        return Expansion(text, cursor)
    }

    fun serialize(): String = buildString {
        for (s in list) append(escape(s.trigger)).append('\t').append(escape(s.text)).append('\n')
    }

    fun load(data: String) {
        list.clear()
        for (line in data.lineSequence()) {
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            put(unescape(line.substring(0, tab)), unescape(line.substring(tab + 1)))
        }
    }

    companion object {
        private const val CURSOR = "{cursor}"

        /** Triggers are one word (letters, digits; Thai marks allowed), stored lower-case. */
        fun normalizeTrigger(raw: String): String? {
            val t = raw.trim().removePrefix(";").lowercase()
            if (t.isEmpty() || t.length > 30 || t.any { it.isWhitespace() }) return null
            return t
        }

        /** Starter set; the user edits these in the app. No clinical doses on purpose. */
        fun defaults() = SnippetStore().apply {
            put("soap", "S: {cursor}\nO: \nA: \nP: ")
            put("nkda", "No known drug allergies (NKDA)")
            put("ty", "ขอบคุณครับ")
            put("date", "{date}")
            put("now", "{date} {time}")
        }

        private fun escape(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r")

        private fun unescape(s: String): String {
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        else -> sb.append(s[i + 1])
                    }
                    i += 2
                } else {
                    sb.append(c); i++
                }
            }
            return sb.toString()
        }
    }
}
