package com.neuralkeyboard.ime

import com.neuralkeyboard.core.Language

data class Key(
    val type: Type,
    val label: String = "",
    val text: String = label,
    val weight: Float = 1f,
) {
    enum class Type { CHAR, SHIFT, DELETE, ENTER, SPACE, SYMBOLS, SYMBOLS_MORE, LETTERS, LANGUAGE, SPACER }

    val isSpecial: Boolean
        get() = type != Type.CHAR && type != Type.SPACE && type != Type.SPACER
}

enum class KeyboardMode { LETTERS, SYMBOLS, SYMBOLS_MORE }

enum class ShiftState { OFF, ONCE, LOCKED }

object KeyboardLayouts {

    fun rows(mode: KeyboardMode, language: Language, shift: ShiftState): List<List<Key>> = when (mode) {
        KeyboardMode.LETTERS -> letters(language, shift)
        KeyboardMode.SYMBOLS -> symbols(language, more = false)
        KeyboardMode.SYMBOLS_MORE -> symbols(language, more = true)
    }

    private fun chars(row: String, upper: Boolean = false): List<Key> =
        row.map { c ->
            val s = if (upper) c.uppercase() else c.toString()
            Key(Key.Type.CHAR, s)
        }

    private fun letters(language: Language, shift: ShiftState): List<List<Key>> {
        val shifted = shift != ShiftState.OFF
        val source = if (shifted && language.shiftedRows != null) language.shiftedRows!! else language.letterRows
        val upper = shifted && language.shiftedRows == null
        val rows = source.map { chars(it, upper) }.toMutableList()

        // Put shift / delete around the last letter row, indent the middle row on QWERTY.
        val last = rows.removeAt(rows.lastIndex)
        rows.add(listOf(Key(Key.Type.SHIFT, weight = 1.5f)) + last + Key(Key.Type.DELETE, weight = 1.5f))
        if (language == Language.ENGLISH) {
            rows[1] = listOf(Key(Key.Type.SPACER, weight = 0.5f)) + rows[1] + Key(Key.Type.SPACER, weight = 0.5f)
        }
        rows.add(bottomRow(language, Key(Key.Type.SYMBOLS, "?123", weight = 1.5f)))
        return rows
    }

    private fun symbols(language: Language, more: Boolean): List<List<Key>> {
        val rows = if (!more) {
            listOf(
                chars("1234567890"),
                chars("@#฿_&-+()/"),
                listOf(Key(Key.Type.SYMBOLS_MORE, "=\\<", weight = 1.5f)) + chars("*\"':;!?") +
                    Key(Key.Type.DELETE, weight = 1.5f),
            )
        } else {
            listOf(
                chars("~`|•√π÷×¶∆"),
                chars("£€¥$^°={}%"),
                listOf(Key(Key.Type.SYMBOLS, "?123", weight = 1.5f)) + chars("\\[]<>©™") +
                    Key(Key.Type.DELETE, weight = 1.5f),
            )
        }
        val abc = if (language == Language.THAI) "กขค" else "ABC"
        return rows + listOf(bottomRow(language, Key(Key.Type.LETTERS, abc, weight = 1.5f)))
    }

    private fun bottomRow(language: Language, modeKey: Key): List<Key> = listOf(
        modeKey,
        Key(Key.Type.LANGUAGE, "🌐"),
        Key(Key.Type.CHAR, ","),
        Key(Key.Type.SPACE, language.label, " ", weight = 4f),
        Key(Key.Type.CHAR, "."),
        Key(Key.Type.ENTER, weight = 1.5f),
    )
}
