package com.neuralkeyboard.core

import kotlin.math.ln

/**
 * Splits text written without spaces (Thai) into words.
 *
 * Finds the most probable sequence of dictionary words (shortest path over word
 * costs, where a common word is cheap). Because it runs while the user is still
 * typing, the last token may be the beginning of a word that is not finished yet.
 */
class WordSegmenter(private val words: Trie) {

    /**
     * @param tokens the words; the last one may be incomplete
     * @param clean  true if every token is a dictionary word (or, for the last one,
     *               the start of one). False means the text contains something
     *               unknown - a typo, a name - and should not be split blindly.
     * @param rarestFreq frequency of the least common token: a very rare word in
     *               the middle of a split is often a typo of a longer word.
     */
    data class Result(val tokens: List<String>, val clean: Boolean, val rarestFreq: Int = Int.MAX_VALUE)

    fun segment(text: String): Result {
        val n = text.length
        if (n == 0) return Result(emptyList(), true)
        val total = ln(words.totalFreq.toDouble() + 1.0)
        val cost = DoubleArray(n + 1) { Double.MAX_VALUE }
        val from = IntArray(n + 1) { -1 }
        val unknown = IntArray(n + 1)
        val rarest = IntArray(n + 1) { Int.MAX_VALUE }
        cost[0] = 0.0

        fun relax(start: Int, end: Int, edge: Double, freq: Int) {
            val c = cost[start] + edge
            if (c < cost[end]) {
                cost[end] = c
                from[end] = start
                unknown[end] = unknown[start] + if (freq == 0) 1 else 0
                rarest[end] = minOf(rarest[start], freq)
            }
        }

        for (i in 0 until n) {
            if (cost[i] == Double.MAX_VALUE) continue
            // A word cannot begin with a vowel/tone mark that attaches to a consonant.
            if (text[i] !in CANNOT_START) {
                words.scan(
                    text, i,
                    onWord = { end, freq -> relax(i, end, total - ln(freq + 1.0) + WORD_COST, freq) },
                    onPrefix = { best -> relax(i, n, total - ln(best + 1.0) + UNFINISHED_COST, best) },
                )
            }
            relax(i, i + 1, UNKNOWN_COST, 0)
        }

        val tokens = ArrayList<String>()
        var end = n
        while (end > 0) {
            val start = from[end]
            tokens.add(text.substring(start, end))
            end = start
        }
        tokens.reverse()
        return Result(tokens, unknown[n] == 0, rarest[n])
    }

    private companion object {
        const val WORD_COST = 0.5
        /** Extra cost for assuming the last token is a word still being typed. */
        const val UNFINISHED_COST = 2.0
        const val UNKNOWN_COST = 25.0
        const val CANNOT_START = "ะัาำิีึืฺุู็่้๊๋์ํ๎ๆฯ"
    }
}
