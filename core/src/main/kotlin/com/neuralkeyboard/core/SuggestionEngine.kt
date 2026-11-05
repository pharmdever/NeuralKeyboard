package com.neuralkeyboard.core

import kotlin.math.ln

/**
 * Turns what the user is typing into suggestions:
 *  - completion   "hel"  -> "hello", "help"
 *  - correction   "teh"  -> "the" (keyboard-aware fuzzy match)
 *  - prediction   after "thank" with nothing typed -> "you"
 * and decides whether the space bar should auto-correct the current word.
 */
class SuggestionEngine(
    private val model: LanguageModel,
    private val costs: EditCosts = EditCosts.DEFAULT,
) {

    enum class Kind { TYPED, COMPLETION, CORRECTION, PREDICTION }

    data class Suggestion(val word: String, val kind: Kind, val score: Double, val editCost: Double = 0.0)

    data class Result(
        /** Ordered best-first. Contains the literal typed text when it is not a known word. */
        val suggestions: List<Suggestion>,
        /** What the space bar should replace the typed word with, or null to keep it. */
        val autoCorrection: String?,
    ) {
        companion object {
            val EMPTY = Result(emptyList(), null)
        }
    }

    fun suggest(typed: String, previousWord: String? = null, limit: Int = 3): Result {
        if (typed.isEmpty()) {
            return Result(predictNext(previousWord, limit), null)
        }
        if (typed.any { it.isDigit() }) {
            return Result(listOf(Suggestion(typed, Kind.TYPED, 0.0)), null)
        }

        val key = LanguageModel.normalize(typed)
        val candidates = HashMap<String, Suggestion>()
        fun offer(s: Suggestion) {
            val old = candidates[s.word]
            if (old == null || s.score > old.score) candidates[s.word] = s
        }

        val shortcut = model.shortcut(key)
        if (shortcut != null) offer(Suggestion(shortcut, Kind.CORRECTION, Double.MAX_VALUE / 2))

        val typedKnown = model.isKnown(key)
        if (typedKnown) {
            offer(Suggestion(key, Kind.TYPED, score(key, previousWord) + EXACT_BONUS))
        }

        for (m in model.words.complete(key, COMPLETION_POOL)) {
            if (m.word == key) continue
            val extra = m.word.length - key.length
            offer(Suggestion(m.word, Kind.COMPLETION, score(m.word, previousWord) - COMPLETION_LENGTH_PENALTY * extra))
        }

        val maxCost = maxEditCost(key.length)
        if (maxCost > 0) {
            for (m in model.words.fuzzy(key, maxCost, costs)) {
                if (m.word == key || m.cost == 0.0) continue
                offer(Suggestion(m.word, Kind.CORRECTION, score(m.word, previousWord) - CORRECTION_PENALTY * m.cost, m.cost))
            }
        }

        val ranked = candidates.values.sortedByDescending { it.score }.toMutableList()
        val autoCorrection = when {
            shortcut != null -> shortcut
            typedKnown -> null
            else -> pickAutoCorrection(key, ranked)
        }

        // Always let the user keep exactly what they typed.
        if (!typedKnown) ranked.add(0, Suggestion(key, Kind.TYPED, Double.NEGATIVE_INFINITY))

        val out = ranked.take(limit).map {
            if (it.word.contains(' ')) it else it.copy(word = matchCase(typed, it.word))
        }
        val fix = when {
            autoCorrection != null && autoCorrection.contains(' ') -> autoCorrection // "idk" -> "I don't know"
            autoCorrection != null -> matchCase(typed, autoCorrection)
            // "i" -> "I" and "i'm" -> "I'm"
            typedKnown && (key == "i" || key.startsWith("i'")) && typed[0] == 'i' -> matchCase(typed, key)
            else -> null
        }
        return Result(out, fix)
    }

    fun predictNext(previousWord: String?, limit: Int = 3): List<Suggestion> {
        if (previousWord.isNullOrEmpty()) return emptyList()
        return model.followers(previousWord, limit).map { (w, f) ->
            Suggestion(w, Kind.PREDICTION, ln(1.0 + f))
        }
    }

    /**
     * For languages written without spaces (Thai): when [typed] has run past the end
     * of a word, split it into the finished word and the part the user is still
     * typing, e.g. "สวัสดีค" -> ("สวัสดี", "ค"). Returns null when no split is needed.
     */
    fun splitFinishedWord(typed: String): Pair<String, String>? {
        val key = LanguageModel.normalize(typed)
        if (key.length < 2 || model.hasCompletions(key)) return null
        for (i in key.length - 1 downTo 1) {
            val head = key.substring(0, i)
            val tail = key.substring(i)
            if (model.isKnown(head) && model.hasCompletions(tail)) {
                return typed.substring(0, i) to typed.substring(i)
            }
        }
        return null
    }

    private fun pickAutoCorrection(key: String, ranked: List<Suggestion>): String? {
        if (key.length < 2) return null
        val best = ranked.firstOrNull { it.kind == Kind.CORRECTION } ?: return null
        // The user may still be typing a longer word ("hel" -> "hello"). Then only
        // correct when the fix is very cheap (missing apostrophe, tone mark...) or
        // far more likely than any completion ("teh" -> "the" despite "tehran").
        val bestCompletion = ranked.firstOrNull { it.kind == Kind.COMPLETION }
        if (bestCompletion != null && best.editCost > PREFIX_SAFE_COST &&
            best.score < bestCompletion.score + PREFIX_MARGIN
        ) return null
        return best.word
    }

    private fun score(word: String, previous: String?): Double {
        val uses = model.userUses(word)
        val unigram = ln(1.0 + model.frequency(word)) + if (uses > 0) USER_WORD_BONUS * ln(1.0 + uses) else 0.0
        val bigram = model.bigramCount(previous, word)
        return unigram + if (bigram > 0) BIGRAM_WEIGHT * ln(1.0 + bigram) + BIGRAM_BONUS else 0.0
    }

    private fun maxEditCost(length: Int): Double = when {
        length <= 1 -> 0.0
        length <= 3 -> 1.0
        length <= 6 -> 1.6
        else -> 2.2
    }

    companion object {
        private const val COMPLETION_POOL = 12
        private const val PREFIX_SAFE_COST = 0.6
        private const val PREFIX_MARGIN = 2.0
        private const val USER_WORD_BONUS = 1.5
        private const val EXACT_BONUS = 2.0
        private const val COMPLETION_LENGTH_PENALTY = 0.25
        private const val CORRECTION_PENALTY = 3.0
        private const val BIGRAM_WEIGHT = 1.2
        private const val BIGRAM_BONUS = 1.5

        /** Copies the capitalisation style of [typed] onto [word]. */
        fun matchCase(typed: String, word: String): String {
            if (word == "i" || word.startsWith("i'")) return "I" + word.substring(1)
            val letters = typed.filter { it.isLetter() }
            return when {
                letters.length > 1 && letters.all { it.isUpperCase() } -> word.uppercase()
                typed.firstOrNull()?.isUpperCase() == true -> word.replaceFirstChar { it.uppercaseChar() }
                else -> word
            }
        }
    }
}
