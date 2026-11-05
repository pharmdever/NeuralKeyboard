package com.neuralkeyboard.ime

import com.neuralkeyboard.core.Language

data class Key(
    val type: Type,
    val label: String = "",
    val text: String = label,
    val weight: Float = 1f,
    /** Small symbol shown in the corner; long-press types it (Samsung style). */
    val hint: String? = null,
) {
    enum class Type { CHAR, SHIFT, DELETE, ENTER, SPACE, SYMBOLS, SYMBOLS_MORE, LETTERS, LANGUAGE, SPACER }

    val isSpecial: Boolean
        get() = type != Type.CHAR && type != Type.SPACE && type != Type.SPACER
}

data class KeyRow(val keys: List<Key>, val height: Float = 1f)

enum class KeyboardMode { LETTERS, SYMBOLS, SYMBOLS_MORE }

enum class ShiftState { OFF, ONCE, LOCKED }

/** What the Enter key does in the current field; picks its icon. */
object EnterAction {
    const val ENTER = "enter"
    const val SEARCH = "search"
    const val SEND = "send"
    const val NEXT = "next"
    const val GO = "go"
    const val DONE = "done"
}

/** Key arrangement modelled on Samsung Keyboard. */
object KeyboardLayouts {

    /** Long-press symbols per key, copied from Samsung Keyboard (English). */
    private val EN_HINTS = rowHints(
        "qwertyuiop" to "+×÷=/_<>[]",
        "asdfghjkl" to "!@#£%&*()",
        "zxcvbnm" to "-'\":;,?",
    )

    /** Long-press symbols per key, copied from Samsung Keyboard (Thai). */
    private val TH_HINTS = rowHints(
        "ๆไำพะัีรนยบล" to "+×÷=/_<>[]`~",
        "ฟหกดเ้่าสวง" to "!@#฿%^&*()\\",
        "ผปแอิืทมใฝ" to "-'\":;,?{}|",
    )

    private fun rowHints(vararg rows: Pair<String, String>): Map<Char, String> =
        rows.flatMap { (keys, hints) -> keys.toList().zip(hints.map { it.toString() }) }.toMap()

    fun rows(mode: KeyboardMode, language: Language, shift: ShiftState): List<KeyRow> = when (mode) {
        KeyboardMode.LETTERS -> letters(language, shift)
        KeyboardMode.SYMBOLS -> symbols(language, more = false)
        KeyboardMode.SYMBOLS_MORE -> symbols(language, more = true)
    }

    private fun chars(row: String, upper: Boolean = false, hints: Map<Char, String> = emptyMap()): List<Key> =
        row.map { c -> Key(Key.Type.CHAR, if (upper) c.uppercase() else c.toString(), hint = hints[c]) }

    private fun letters(language: Language, shift: ShiftState): List<KeyRow> {
        val shifted = shift != ShiftState.OFF
        val thaiShifted = shifted && language.shiftedRows != null
        val source = if (thaiShifted) language.shiftedRows!! else language.letterRows
        val upper = shifted && language.shiftedRows == null
        val hints = when {
            thaiShifted -> emptyMap()
            language == Language.THAI -> TH_HINTS
            else -> EN_HINTS
        }
        val rows = source.map { chars(it, upper, hints) }.toMutableList()

        // Shift / delete around the last letter row; QWERTY middle row is indented.
        val last = rows.removeAt(rows.lastIndex)
        rows.add(listOf(Key(Key.Type.SHIFT, weight = 1.5f)) + last + Key(Key.Type.DELETE, weight = 1.5f))
        if (language == Language.ENGLISH) {
            rows[1] = listOf(Key(Key.Type.SPACER, weight = 0.5f)) + rows[1] + Key(Key.Type.SPACER, weight = 0.5f)
        }

        val out = ArrayList<KeyRow>()
        // Samsung shows a number row above English letters (not above Thai).
        if (language == Language.ENGLISH) out.add(KeyRow(chars("1234567890"), height = 0.85f))
        rows.forEach { out.add(KeyRow(it)) }
        out.add(KeyRow(bottomRow(language, Key(Key.Type.SYMBOLS, "!#1", weight = 1.5f))))
        return out
    }

    private fun symbols(language: Language, more: Boolean): List<KeyRow> {
        val rows = if (!more) {
            listOf(
                chars("1234567890"),
                chars("@#฿_&-+()/"),
                listOf(Key(Key.Type.SYMBOLS_MORE, "1/2", weight = 1.5f)) + chars("*\"':;!?") +
                    Key(Key.Type.DELETE, weight = 1.5f),
            )
        } else {
            listOf(
                if (language == Language.THAI) chars("๑๒๓๔๕๖๗๘๙๐") else chars("~`|•√π÷×¶∆"),
                chars("£€¥$^°={}%"),
                listOf(Key(Key.Type.SYMBOLS, "2/2", weight = 1.5f)) + chars("\\[]<>©™") +
                    Key(Key.Type.DELETE, weight = 1.5f),
            )
        }
        val abc = if (language == Language.THAI) "กขค" else "ABC"
        return rows.map { KeyRow(it) } + KeyRow(bottomRow(language, Key(Key.Type.LETTERS, abc, weight = 1.5f)))
    }

    private fun bottomRow(language: Language, modeKey: Key): List<Key> = listOf(
        modeKey,
        Key(Key.Type.LANGUAGE, "Language"),
        Key(Key.Type.CHAR, ","),
        Key(Key.Type.SPACE, language.label, " ", weight = 4f),
        Key(Key.Type.CHAR, "."),
        Key(Key.Type.ENTER, weight = 1.5f),
    )
}
