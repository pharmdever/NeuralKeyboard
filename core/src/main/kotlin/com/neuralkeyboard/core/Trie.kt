package com.neuralkeyboard.core

import java.util.PriorityQueue
import kotlin.math.min

/**
 * A frequency-weighted prefix tree. Supports exact lookup, prefix completion and
 * fuzzy (Damerau-Levenshtein) search with a pluggable substitution cost, which is
 * what lets the keyboard treat "neighbouring key" typos as cheaper than random ones.
 */
class Trie {

    class Node {
        val children = HashMap<Char, Node>(4)
        var freq: Int = 0
        var word: String? = null
    }

    data class Match(val word: String, val freq: Int, val cost: Double)

    private val root = Node()

    var size: Int = 0
        private set

    /** Adds [delta] to the frequency of [word], inserting it if needed. */
    fun add(word: String, delta: Int = 1) {
        if (word.isEmpty()) return
        var node = root
        for (c in word) node = node.children.getOrPut(c) { Node() }
        if (node.word == null) {
            node.word = word
            size++
        }
        node.freq = (node.freq + delta).coerceAtLeast(1)
    }

    fun freq(word: String): Int = find(word)?.takeIf { it.word != null }?.freq ?: 0

    operator fun contains(word: String): Boolean = freq(word) > 0

    private fun find(prefix: String): Node? {
        var node = root
        for (c in prefix) node = node.children[c] ?: return null
        return node
    }

    /** Most frequent words starting with [prefix] (the prefix itself included if it is a word). */
    fun complete(prefix: String, limit: Int): List<Match> {
        val start = find(prefix) ?: return emptyList()
        val best = PriorityQueue<Match>(compareBy { it.freq })
        val stack = ArrayDeque<Node>()
        stack.addLast(start)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            node.word?.let { w ->
                if (best.size < limit) best.add(Match(w, node.freq, 0.0))
                else if (node.freq > best.peek().freq) {
                    best.poll(); best.add(Match(w, node.freq, 0.0))
                }
            }
            for (child in node.children.values) stack.addLast(child)
        }
        return best.sortedByDescending { it.freq }
    }

    /**
     * Every word whose weighted edit distance to [target] is at most [maxCost].
     * Uses the optimal-string-alignment variant of Damerau-Levenshtein so that
     * swapped letters ("teh" -> "the") count as a single cheap edit.
     */
    fun fuzzy(
        target: String,
        maxCost: Double,
        costs: EditCosts = EditCosts.DEFAULT,
    ): List<Match> {
        val results = ArrayList<Match>()
        val n = target.length
        val firstRow = DoubleArray(n + 1)
        for (i in 1..n) firstRow[i] = firstRow[i - 1] + costs.deletion(target[i - 1])
        for ((c, child) in root.children) {
            search(child, c, null, firstRow, null, target, maxCost, costs, results)
        }
        return results
    }

    private fun search(
        node: Node,
        ch: Char,
        prevCh: Char?,
        prevRow: DoubleArray,
        prevPrevRow: DoubleArray?,
        target: String,
        maxCost: Double,
        costs: EditCosts,
        out: MutableList<Match>,
    ) {
        val n = target.length
        val row = DoubleArray(n + 1)
        row[0] = prevRow[0] + costs.insertion(ch)
        var rowMin = row[0]
        for (i in 1..n) {
            val t = target[i - 1]
            var v = min(
                row[i - 1] + costs.insertion(ch),
                prevRow[i] + costs.deletion(t),
            )
            v = min(v, prevRow[i - 1] + if (t == ch) 0.0 else costs.substitution(t, ch))
            if (prevPrevRow != null && i > 1 && t == prevCh && target[i - 2] == ch) {
                v = min(v, prevPrevRow[i - 2] + costs.transposition)
            }
            row[i] = v
            if (v < rowMin) rowMin = v
        }
        val w = node.word
        if (w != null && row[n] <= maxCost) out.add(Match(w, node.freq, row[n]))
        if (rowMin <= maxCost) {
            for ((c, child) in node.children) {
                search(child, c, ch, row, prevRow, target, maxCost, costs, out)
            }
        }
    }

    /** Visits every stored word. */
    fun forEach(action: (word: String, freq: Int) -> Unit) {
        val stack = ArrayDeque<Node>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            node.word?.let { action(it, node.freq) }
            for (child in node.children.values) stack.addLast(child)
        }
    }
}

/** Edit costs used by [Trie.fuzzy]. [typed] is the character the user typed. */
interface EditCosts {
    /** Replace the typed char with [dict] from the dictionary word. */
    fun substitution(typed: Char, dict: Char): Double

    /** Dictionary word has an extra char [dict] the user did not type. */
    fun insertion(dict: Char): Double

    /** User typed an extra char [typed] not in the dictionary word. */
    fun deletion(typed: Char): Double

    val transposition: Double

    companion object {
        val DEFAULT: EditCosts = object : EditCosts {
            override fun substitution(typed: Char, dict: Char) = 1.0
            override fun insertion(dict: Char) = 1.0
            override fun deletion(typed: Char) = 1.0
            override val transposition = 1.0
        }
    }
}
