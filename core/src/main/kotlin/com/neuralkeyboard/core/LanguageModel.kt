package com.neuralkeyboard.core

import java.io.BufferedReader
import java.io.Reader
import java.io.Writer

/**
 * Unigram + bigram language model with on-device learning.
 *
 * - Base data comes from a bundled dictionary ("word<TAB>freq" per line) and an
 *   optional bigram list ("prev<TAB>word<TAB>freq").
 * - Everything the user types is learned into a separate user layer that can be
 *   saved/restored, so the keyboard adapts to the user without sending anything
 *   off the device.
 */
class LanguageModel {

    val words = Trie()
    private val bigrams = HashMap<String, HashMap<String, Int>>()

    private val userWords = HashMap<String, Int>()
    private val userBigrams = HashMap<String, HashMap<String, Int>>()

    /** Fixed replacements such as "im" -> "i'm" or "thx" -> "thanks". */
    private val shortcuts = HashMap<String, String>()

    /**
     * Words that may be suggested but never inserted by auto-correct (drug names):
     * silently swapping one drug for a look-alike one would be dangerous.
     */
    private val protectedWords = HashSet<String>()

    /** Load "word<TAB>freq" lines (whitespace also accepted). Lines starting with # are ignored. */
    fun loadWords(reader: Reader) {
        reader.buffered().forEachDataLine { parts ->
            val word = normalize(parts[0])
            val freq = parts.getOrNull(1)?.toIntOrNull() ?: 1
            words.add(word, freq)
        }
    }

    /** Load "prev<TAB>word<TAB>freq" lines. */
    fun loadBigrams(reader: Reader) {
        reader.buffered().forEachDataLine { parts ->
            if (parts.size < 2) return@forEachDataLine
            val freq = parts.getOrNull(2)?.toIntOrNull() ?: 1
            addBigram(bigrams, normalize(parts[0]), normalize(parts[1]), freq)
        }
    }

    /** Load "typed<TAB>replacement" lines. The replacement may contain spaces. */
    fun loadShortcuts(reader: Reader) {
        reader.buffered().useLines { lines ->
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#")) continue
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                shortcuts[normalize(line.substring(0, tab).trim())] = line.substring(tab + 1).trim()
            }
        }
    }

    fun shortcut(typed: String): String? = shortcuts[normalize(typed)]

    /**
     * Load specialist terms ("term<TAB>freq", the term may have several words, e.g.
     * "insulin glargine"). Each word becomes known; consecutive words become a bigram
     * so the second word is predicted. Words that were not already in the general
     * dictionary are protected from auto-correct (see [isProtected]).
     */
    fun loadTerms(reader: Reader, bigramFreq: Int = TERM_BIGRAM_FREQ) {
        reader.buffered().useLines { lines ->
            for (raw in lines) {
                if (raw.isBlank() || raw.startsWith("#")) continue
                val tab = raw.indexOf('\t')
                val term = normalize(if (tab >= 0) raw.substring(0, tab) else raw)
                val freq = (if (tab >= 0) raw.substring(tab + 1).trim().toIntOrNull() else null) ?: 1
                val tokens = term.split(' ', '-', '/', ',').filter { t -> t.length >= 3 && t.all { it.isLetter() || isMark(it) } }
                for (t in tokens) {
                    val existing = words.freq(t)
                    if (existing == 0) protectedWords.add(t)
                    if (existing < freq) words.add(t, freq - existing)
                }
                for ((a, b) in tokens.zipWithNext()) addBigram(bigrams, a, b, bigramFreq)
            }
        }
    }

    fun isProtected(word: String): Boolean = normalize(word) in protectedWords

    fun frequency(word: String): Int = words.freq(normalize(word))

    /** How many times the user has committed [word] (0 for words never typed). */
    fun userUses(word: String): Int = (userWords[normalize(word)] ?: 0) / LEARN_WEIGHT

    fun isKnown(word: String): Boolean = normalize(word) in words

    /** True if some known word starts with [prefix]. */
    fun hasCompletions(prefix: String): Boolean = words.hasPrefix(normalize(prefix))

    fun bigramCount(previous: String?, word: String): Int {
        if (previous == null) return 0
        return bigrams[normalize(previous)]?.get(normalize(word)) ?: 0
    }

    /** Most likely words to follow [previous]; the user's own habits count extra. */
    fun followers(previous: String, limit: Int): List<Pair<String, Int>> {
        val p = normalize(previous)
        val user = userBigrams[p]
        return bigrams[p]
            ?.entries
            ?.map { (w, c) -> w to c + USER_BIGRAM_BOOST * (user?.get(w) ?: 0) }
            ?.sortedByDescending { it.second }
            ?.take(limit)
            ?: emptyList()
    }

    /**
     * Records that the user committed [word] (after [previous], if any).
     * New words become part of the vocabulary immediately.
     */
    fun learn(word: String, previous: String? = null, weight: Int = LEARN_WEIGHT) {
        val w = normalize(word)
        if (!isLearnable(w)) return
        words.add(w, weight)
        userWords.merge(w, weight, Int::plus)
        if (previous != null) {
            val p = normalize(previous)
            if (isLearnable(p)) {
                addBigram(bigrams, p, w, weight)
                addBigram(userBigrams, p, w, weight)
            }
        }
    }

    /** Removes a learned word (e.g. the user rejected it). Bundled words are kept. */
    fun forget(word: String) {
        val w = normalize(word)
        val learned = userWords.remove(w) ?: return
        words.add(w, -learned)
    }

    /** Writes only the user's learned data. */
    fun saveUserData(writer: Writer) {
        writer.write("# NeuralKeyboard user data v1\n")
        for ((w, f) in userWords) writer.write("W\t$w\t$f\n")
        for ((p, map) in userBigrams) for ((w, f) in map) writer.write("B\t$p\t$w\t$f\n")
        writer.flush()
    }

    fun loadUserData(reader: Reader) {
        reader.buffered().forEachDataLine { parts ->
            when (parts[0]) {
                "W" -> if (parts.size >= 3) {
                    val f = parts[2].toIntOrNull() ?: return@forEachDataLine
                    words.add(parts[1], f)
                    userWords.merge(parts[1], f, Int::plus)
                }
                "B" -> if (parts.size >= 4) {
                    val f = parts[3].toIntOrNull() ?: return@forEachDataLine
                    addBigram(bigrams, parts[1], parts[2], f)
                    addBigram(userBigrams, parts[1], parts[2], f)
                }
            }
        }
    }

    val learnedWordCount: Int get() = userWords.size

    private fun addBigram(map: HashMap<String, HashMap<String, Int>>, p: String, w: String, f: Int) {
        map.getOrPut(p) { HashMap() }.merge(w, f, Int::plus)
    }

    private inline fun BufferedReader.forEachDataLine(action: (List<String>) -> Unit) {
        useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                action(line.split('\t', ' ').filter { it.isNotEmpty() })
            }
        }
    }

    companion object {
        /** How much one use of a word counts, relative to bundled frequencies. */
        const val LEARN_WEIGHT = 400

        private const val TERM_BIGRAM_FREQ = 300

        private fun isMark(c: Char): Boolean {
            val type = Character.getType(c)
            return type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt()
        }

        /** Personal word pairs weigh this many times more than bundled ones when predicting. */
        const val USER_BIGRAM_BOOST = 2

        fun normalize(word: String): String = word.lowercase()

        private fun isLearnable(w: String): Boolean =
            w.length in 1..40 && w.any { it.isLetter() } && w.none { it.isWhitespace() }
    }
}
