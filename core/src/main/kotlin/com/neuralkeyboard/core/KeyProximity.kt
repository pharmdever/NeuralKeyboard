package com.neuralkeyboard.core

import kotlin.math.hypot

/**
 * Edit costs that know the physical keyboard: hitting a neighbouring key is a
 * much more likely typo than hitting a key on the other side of the keyboard.
 */
class KeyProximity(
    rows: List<String>,
    rowOffsets: List<Double>,
    private val nearCost: Double = 0.55,
    private val farCost: Double = 1.0,
) : EditCosts {

    private val positions = HashMap<Char, Pair<Double, Double>>()

    init {
        rows.forEachIndexed { r, row ->
            val offset = rowOffsets.getOrElse(r) { 0.0 }
            row.forEachIndexed { c, ch -> positions[ch] = Pair(c + offset, r.toDouble()) }
        }
    }

    fun distance(a: Char, b: Char): Double? {
        val pa = positions[a.lowercaseChar()] ?: return null
        val pb = positions[b.lowercaseChar()] ?: return null
        return hypot(pa.first - pb.first, pa.second - pb.second)
    }

    override fun substitution(typed: Char, dict: Char): Double {
        val t = typed.lowercaseChar()
        val d = dict.lowercaseChar()
        if (t == d) return 0.0
        if (t in THAI_TONES && d in THAI_TONES) return 0.5
        if (t in THAI_SIMILAR_VOWELS && d in THAI_SIMILAR_VOWELS) return 0.5
        val dist = distance(t, d) ?: return farCost
        return if (dist <= 1.25) nearCost else farCost
    }

    override fun insertion(dict: Char): Double = when (dict) {
        '\'', '-' -> 0.3            // "dont" -> "don't"
        in THAI_TONES, '์' -> 0.6     // tone marks / karan are often forgotten
        else -> 1.0
    }

    override fun deletion(typed: Char): Double = 1.0

    override val transposition: Double = 0.75

    companion object {
        private const val THAI_TONES = "่้๊๋"
        private const val THAI_SIMILAR_VOWELS = "ิีึื"

        fun forLanguage(language: Language) = KeyProximity(language.letterRows, language.rowOffsets)
    }
}
