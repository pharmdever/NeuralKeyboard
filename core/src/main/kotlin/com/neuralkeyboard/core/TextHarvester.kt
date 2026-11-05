package com.neuralkeyboard.core

/**
 * Pulls words worth learning out of copied text.
 *
 * Only plain words are returned: tokens containing digits (HN / phone numbers,
 * codes, dates) or symbols are dropped, as is anything very long. Each word comes
 * with the language it is written in and the word before it (for bigrams).
 */
object TextHarvester {

    data class Word(val text: String, val language: Language, val previous: String?)

    fun words(text: String, maxWords: Int = 300): List<Word> {
        val out = ArrayList<Word>()
        var previous: String? = null
        for (raw in text.split(SEPARATORS)) {
            if (out.size >= maxWords) break
            val token = raw.trim('\'', '’')
            if (token.length !in 2..30 || token.any { it.isDigit() }) {
                previous = null
                continue
            }
            val isThai = token.all { it in 'ก'..'๛' }
            val isLatin = token.all { it in 'a'..'z' || it in 'A'..'Z' || it == '\'' }
            val language = when {
                isThai -> Language.THAI
                isLatin -> Language.ENGLISH
                else -> { previous = null; continue }
            }
            out.add(Word(token, language, previous?.takeIf { language == Language.ENGLISH }))
            previous = token
        }
        return out
    }

    /** Whitespace and punctuation (apostrophes stay inside words like "don't"). */
    private val SEPARATORS = Regex("[\\s.,;:!?()\\[\\]{}\"/\\\\|<>=+*&^%$#@~`_\\-–—•…“”]+")
}
