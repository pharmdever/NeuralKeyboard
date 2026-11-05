package com.neuralkeyboard.ime

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.neuralkeyboard.core.KeyProximity
import com.neuralkeyboard.core.Language
import com.neuralkeyboard.core.LanguageModel
import com.neuralkeyboard.core.SuggestionEngine
import java.util.EnumMap
import java.util.concurrent.Executors

/**
 * The smart keyboard itself.
 *
 * The word being typed is kept as "composing text" (underlined in the editor) so it
 * can be completed or corrected. On space / punctuation it is committed, possibly
 * auto-corrected, and learned. Backspace right after an auto-correction undoes it.
 */
class SmartKeyboardService : InputMethodService(), KeyboardUi.Listener {

    private lateinit var prefs: Prefs
    private var ui: KeyboardUi? = null

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private val models = EnumMap<Language, LanguageModel>(Language::class.java)
    private val engines = EnumMap<Language, SuggestionEngine>(Language::class.java)
    private val loading = HashSet<Language>()
    private var loadedResetToken = 0

    private var language = Language.ENGLISH
    private var mode = KeyboardMode.LETTERS
    private var shift = ShiftState.OFF
    private var lastShiftTap = 0L
    private var lastSpaceTap = 0L

    private val composing = StringBuilder()
    private var result = SuggestionEngine.Result.EMPTY
    private var previousWord: String? = null
    private var pendingUndo: Undo? = null
    /** True right after we appended a space ourselves (picking a suggestion). */
    private var autoSpaced = false
    private var unsavedLearns = 0

    // What the current text field allows.
    private var predictionOn = true
    private var autocorrectOn = true
    private var learningOn = true
    private var autoCapOn = true

    private data class Undo(val original: String, val committed: String)

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        language = prefs.language
        loadedResetToken = prefs.resetToken
        ensureLoaded(language)
    }

    override fun onDestroy() {
        saveUserData()
        io.shutdown()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val keyboard = KeyboardUi(this, this)
        ui = keyboard
        renderKeys()
        return keyboard.root
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (prefs.resetToken != loadedResetToken) reloadAll()

        composing.clear()
        pendingUndo = null
        previousWord = null

        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val isPassword = isPassword(cls, variation)
        val noSuggestions = info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0
        val isTechnical = cls == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
        )
        val incognito = info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0

        mode = when (cls) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> KeyboardMode.SYMBOLS
            else -> KeyboardMode.LETTERS
        }
        predictionOn = cls == InputType.TYPE_CLASS_TEXT && !isPassword
        autocorrectOn = predictionOn && !noSuggestions && !isTechnical && prefs.autocorrect
        // Never learn from passwords, e-mail/URL fields or incognito editors.
        learningOn = predictionOn && !isTechnical && !incognito && prefs.learning
        autoCapOn = predictionOn && !isTechnical && prefs.autoCapitalize

        ui?.hapticsEnabled = prefs.haptics
        shift = ShiftState.OFF
        updateAutoShift()
        renderKeys()
        updateSuggestions()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        currentInputConnection?.finishComposingText()
        composing.clear()
        saveUserData()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // The user moved the cursor away from the word we were composing: let it go.
        if (composing.isNotEmpty() && (newSelStart != candidatesEnd || newSelEnd != candidatesEnd)) {
            composing.clear()
            currentInputConnection?.finishComposingText()
            pendingUndo = null
            previousWord = null
            updateSuggestions()
        }
    }

    // ---------------------------------------------------------------- key handling

    override fun onKey(key: Key) {
        val ic = currentInputConnection ?: return
        if (key.type != Key.Type.DELETE) pendingUndo = null
        val wasAutoSpaced = autoSpaced
        autoSpaced = false
        when (key.type) {
            Key.Type.CHAR -> onText(ic, key.text, wasAutoSpaced)
            Key.Type.SPACE -> onSpace(ic)
            Key.Type.DELETE -> onDelete(ic)
            Key.Type.ENTER -> onEnter(ic)
            Key.Type.SHIFT -> onShift()
            Key.Type.SYMBOLS -> { mode = KeyboardMode.SYMBOLS; renderKeys() }
            Key.Type.SYMBOLS_MORE -> { mode = KeyboardMode.SYMBOLS_MORE; renderKeys() }
            Key.Type.LETTERS -> { mode = KeyboardMode.LETTERS; renderKeys() }
            Key.Type.LANGUAGE -> switchLanguage(ic)
            Key.Type.SPACER -> Unit
        }
    }

    private fun onText(ic: InputConnection, raw: String, afterAutoSpace: Boolean = false) {
        val text = if (shift != ShiftState.OFF && language.shiftedRows == null) raw.uppercase() else raw
        if (predictionOn && isWordText(text)) {
            composing.append(text)
            if (!language.spaceAfterWord) splitThaiWord(ic)
            ic.setComposingText(composing, 1)
        } else {
            val punctuation = text in SENTENCE_PUNCTUATION || text == ","
            commitComposing(ic, autocorrect = punctuation)
            if (punctuation && afterAutoSpace && ic.getTextBeforeCursor(1, 0)?.toString() == " ") {
                // "hello |" + "." -> "hello. |"
                ic.deleteSurroundingText(1, 0)
                ic.commitText("$text ", 1)
            } else {
                ic.commitText(text, 1)
            }
            if (text in SENTENCE_PUNCTUATION) previousWord = null
        }
        if (shift == ShiftState.ONCE) shift = ShiftState.OFF // one-shot, also for Thai
        updateAutoShift()
        renderKeys()
        updateSuggestions()
    }

    /** Thai has no spaces: commit a finished word as soon as the next one starts. */
    private fun splitThaiWord(ic: InputConnection) {
        val engine = engines[language] ?: return
        val (head, tail) = engine.splitFinishedWord(composing.toString()) ?: return
        ic.commitText(head, 1)
        learn(head)
        previousWord = head
        composing.setLength(0)
        composing.append(tail)
    }

    private fun onSpace(ic: InputConnection) {
        val now = SystemClock.uptimeMillis()
        if (composing.isNotEmpty()) {
            commitComposing(ic, autocorrect = true, separator = " ")
            autoSpaced = language.spaceAfterWord
        } else {
            val before = ic.getTextBeforeCursor(2, 0)
            val doubleTap = now - lastSpaceTap < DOUBLE_SPACE_MS
            if (prefs.doubleSpacePeriod && predictionOn && doubleTap && before != null && before.length == 2 &&
                before[1] == ' ' && before[0].isLetterOrDigit()
            ) {
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                previousWord = null
            } else {
                ic.commitText(" ", 1)
            }
        }
        lastSpaceTap = now
        updateAutoShift()
        renderKeys()
        updateSuggestions()
    }

    private fun onDelete(ic: InputConnection) {
        val undo = pendingUndo
        if (undo != null) {
            pendingUndo = null
            val before = ic.getTextBeforeCursor(undo.committed.length, 0)?.toString()
            if (before == undo.committed) {
                ic.deleteSurroundingText(undo.committed.length, 0)
                ic.commitText(undo.original, 1)
                learn(undo.original) // the user wants this word: stop correcting it
                previousWord = undo.original
                updateSuggestions()
                return
            }
        }
        if (composing.isNotEmpty()) {
            composing.setLength(composing.length - 1)
            if (composing.isEmpty()) ic.commitText("", 1) else ic.setComposingText(composing, 1)
        } else {
            val selected = ic.getSelectedText(0)
            if (selected.isNullOrEmpty()) {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
            } else {
                ic.commitText("", 1)
            }
            previousWord = null
        }
        updateAutoShift()
        renderKeys()
        updateSuggestions()
    }

    private fun onEnter(ic: InputConnection) {
        commitComposing(ic, autocorrect = false)
        val info = currentInputEditorInfo
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val noEnterAction = info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        if (!noEnterAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            sendKeyChar('\n')
        }
        previousWord = null
        updateAutoShift()
        renderKeys()
        updateSuggestions()
    }

    private fun onShift() {
        val now = SystemClock.uptimeMillis()
        shift = when {
            shift == ShiftState.LOCKED -> ShiftState.OFF
            shift == ShiftState.ONCE && now - lastShiftTap < DOUBLE_TAP_MS -> ShiftState.LOCKED
            shift == ShiftState.ONCE -> ShiftState.OFF
            else -> ShiftState.ONCE
        }
        lastShiftTap = now
        renderKeys()
    }

    private fun switchLanguage(ic: InputConnection) {
        commitComposing(ic, autocorrect = false)
        language = language.next()
        prefs.language = language
        ensureLoaded(language)
        mode = KeyboardMode.LETTERS
        shift = ShiftState.OFF
        previousWord = null
        updateAutoShift()
        renderKeys()
        updateSuggestions()
    }

    override fun onSuggestionPicked(index: Int) {
        val ic = currentInputConnection ?: return
        val picked = result.suggestions.getOrNull(index)?.word ?: return
        val separator = if (language.spaceAfterWord) " " else ""
        ic.commitText(picked + separator, 1)
        autoSpaced = separator.isNotEmpty()
        learn(picked)
        previousWord = picked
        composing.clear()
        pendingUndo = null
        updateAutoShift()
        renderKeys()
        updateSuggestions()
    }

    /** Commits the composing word, auto-correcting it if allowed, then appends [separator]. */
    private fun commitComposing(ic: InputConnection, autocorrect: Boolean, separator: String = "") {
        if (composing.isEmpty()) return
        val typed = composing.toString()
        val fix = if (autocorrect && autocorrectOn) result.autoCorrection else null
        val word = fix ?: typed
        ic.commitText(word + separator, 1)
        if (fix != null && fix != typed) {
            pendingUndo = Undo(original = typed, committed = word + separator)
        }
        learn(word)
        previousWord = word
        composing.clear()
    }

    // ---------------------------------------------------------------- suggestions

    private fun updateSuggestions() {
        val engine = engines[language]
        val keyboard = ui ?: return
        if (!predictionOn || engine == null) {
            result = SuggestionEngine.Result.EMPTY
            keyboard.setSuggestions(emptyList(), -1)
            return
        }
        result = engine.suggest(composing.toString(), previousWord ?: wordBeforeCursor(), KeyboardUi.SLOTS)
        val words = result.suggestions.map { it.word }
        val highlight = when {
            !autocorrectOn -> -1
            result.autoCorrection != null -> words.indexOf(result.autoCorrection)
            else -> -1
        }
        keyboard.setSuggestions(words, highlight)
    }

    /** Last whole word before the cursor, used for prediction after the cursor moves. */
    private fun wordBeforeCursor(): String? {
        if (composing.isNotEmpty()) return null
        val text = currentInputConnection?.getTextBeforeCursor(48, 0)?.toString() ?: return null
        if (text.isEmpty() || text.trimEnd().lastOrNull()?.let { it in ".!?\n" } == true) return null
        return text.trimEnd().split(Regex("\\s+")).lastOrNull()?.trim { !it.isLetterOrDigit() && it != '\'' }
            ?.takeIf { it.isNotEmpty() }
    }

    /** English only: turn shift on at the start of a sentence. Thai uses manual shift. */
    private fun updateAutoShift() {
        if (shift == ShiftState.LOCKED || language.shiftedRows != null) return
        if (!autoCapOn || mode != KeyboardMode.LETTERS || composing.isNotEmpty()) return
        val info = currentInputEditorInfo ?: return
        val caps = currentInputConnection?.getCursorCapsMode(info.inputType) ?: 0
        shift = if (caps != 0) ShiftState.ONCE else ShiftState.OFF
    }

    private fun renderKeys() {
        val keyboard = ui ?: return
        keyboard.setKeys(KeyboardLayouts.rows(mode, language, shift), shift, enterLabel())
    }

    private fun enterLabel(): String {
        val info = currentInputEditorInfo ?: return "⏎"
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return "⏎"
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> "Go"
            EditorInfo.IME_ACTION_SEARCH -> "🔍"
            EditorInfo.IME_ACTION_SEND -> "➤"
            EditorInfo.IME_ACTION_NEXT -> "→"
            EditorInfo.IME_ACTION_DONE -> "✓"
            else -> "⏎"
        }
    }

    // ---------------------------------------------------------------- model management

    private fun ensureLoaded(lang: Language) {
        if (lang in models || !loading.add(lang)) return
        io.execute {
            val model = LanguageModel()
            try {
                assets.open("dict/${lang.code}_words.tsv").reader().use(model::loadWords)
                assets.open("dict/${lang.code}_bigrams.tsv").reader().use(model::loadBigrams)
                if ("${lang.code}_shortcuts.tsv" in assets.list("dict").orEmpty()) {
                    assets.open("dict/${lang.code}_shortcuts.tsv").reader().use(model::loadShortcuts)
                }
                val user = prefs.userDataFile(lang)
                if (user.exists()) user.reader().use(model::loadUserData)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load dictionary for ${lang.code}", e)
            }
            val engine = SuggestionEngine(model, KeyProximity.forLanguage(lang))
            main.post {
                models[lang] = model
                engines[lang] = engine
                loading.remove(lang)
                if (lang == language) updateSuggestions()
            }
        }
    }

    private fun reloadAll() {
        models.clear()
        engines.clear()
        loadedResetToken = prefs.resetToken
        ensureLoaded(language)
    }

    private fun learn(word: String) {
        if (!learningOn) return
        val model = models[language] ?: return
        model.learn(word, previousWord)
        if (++unsavedLearns >= SAVE_EVERY) saveUserData()
    }

    private fun saveUserData() {
        if (unsavedLearns == 0) return
        unsavedLearns = 0
        // Snapshot on the main thread (where the model is mutated), write in the background.
        val snapshots = models.map { (lang, model) ->
            lang to java.io.StringWriter().also(model::saveUserData).toString()
        }
        io.execute {
            for ((lang, data) in snapshots) {
                try {
                    val file = prefs.userDataFile(lang)
                    val tmp = java.io.File(file.path + ".tmp")
                    tmp.writeText(data)
                    tmp.renameTo(file)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save user data", e)
                }
            }
        }
    }

    companion object {
        private const val TAG = "NeuralKeyboard"
        private const val SAVE_EVERY = 20
        private const val DOUBLE_TAP_MS = 350L
        private const val DOUBLE_SPACE_MS = 600L
        private val SENTENCE_PUNCTUATION = setOf(".", "!", "?")

        private fun isPassword(cls: Int, variation: Int): Boolean =
            (cls == InputType.TYPE_CLASS_TEXT && variation in setOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            )) || (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)

        /** Letters, Thai vowel/tone marks and apostrophes are part of a word. */
        private fun isWordText(text: String): Boolean = text.all { c ->
            c.isLetter() || c == '\'' ||
                Character.getType(c) == Character.NON_SPACING_MARK.toInt() ||
                Character.getType(c) == Character.COMBINING_SPACING_MARK.toInt()
        }
    }
}
