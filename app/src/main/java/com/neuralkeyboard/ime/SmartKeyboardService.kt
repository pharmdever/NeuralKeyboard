package com.neuralkeyboard.ime

import android.content.ClipDescription
import android.content.ClipboardManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.neuralkeyboard.core.ClipboardHistory
import com.neuralkeyboard.core.KeyProximity
import com.neuralkeyboard.core.Language
import com.neuralkeyboard.core.LanguageModel
import com.neuralkeyboard.core.SuggestionEngine
import com.neuralkeyboard.core.TextHarvester
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
    /** Suggestions are computed here so typing never waits for them. */
    private val predictor = Executors.newSingleThreadExecutor()
    @Volatile private var latestRequest = 0
    /** The (typed, previous word) that [result] was computed for. */
    private var resultFor: Pair<String, String?>? = null

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

    private val clipboard = ClipboardHistory()
    private var clipboardManager: ClipboardManager? = null
    /** Text of the "just copied" chip, or null when it is not shown. */
    private var clipChip: String? = null
    /** Wall-clock time of the last key press or paste. */
    private var lastTypedAt = 0L
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { onClipboardChanged() }

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

        clipboard.ttlMillis = prefs.clipboardTtlMillis
        runCatching {
            if (prefs.clipboardFile.exists()) clipboard.load(prefs.clipboardFile.readText())
            val legacy = prefs.legacyPinnedClipsFile
            if (legacy.exists()) {
                clipboard.loadLegacyPinned(legacy.readText())
                saveClipboard()
                legacy.delete()
            }
        }.onFailure { Log.e(TAG, "Failed to load clipboard history", it) }
        clipboardManager = getSystemService(ClipboardManager::class.java)?.also {
            it.addPrimaryClipChangedListener(clipListener)
        }
    }

    override fun onDestroy() {
        clipboardManager?.removePrimaryClipChangedListener(clipListener)
        saveUserData()
        io.shutdown()
        predictor.shutdownNow()
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
        ui?.hideClipboard()
        clipboard.ttlMillis = prefs.clipboardTtlMillis
        if (!prefs.clipboardHistory) {
            clipboard.clearAll()
            prefs.clipboardFile.delete()
        }
        clipChip = null
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
        if (key.type in TYPING_KEYS) lastTypedAt = System.currentTimeMillis()
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
        val model = models[language] ?: return
        val (head, tail) = synchronized(model) { engine.splitFinishedWord(composing.toString()) } ?: return
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
        val chip = clipChip
        if (chip != null) {
            if (index == 0) {
                paste(ic, chip)
                return
            }
        }
        val offset = if (chip != null) 1 else 0
        val picked = result.suggestions.getOrNull(index - offset)?.word ?: return
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

    // ---------------------------------------------------------------- clipboard

    private fun onClipboardChanged() {
        if (!prefs.clipboardHistory) return
        val clip = runCatching { clipboardManager?.primaryClip }.getOrNull() ?: return
        if (clip.itemCount == 0) return
        // Apps (password managers...) mark secrets as sensitive: never keep those.
        val extras = clip.description?.extras
        val sensitive = extras?.getBoolean(EXTRA_IS_SENSITIVE) == true ||
            (Build.VERSION.SDK_INT >= 33 && extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true)
        if (sensitive) return
        val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
        clipboard.add(text, System.currentTimeMillis()) ?: return
        saveClipboard()
        if (prefs.learnFromClipboard && prefs.learning) learnFromCopiedText(text)
        val keyboard = ui ?: return
        if (keyboard.isClipboardOpen) refreshClipboardPanel() else updateSuggestions()
    }

    override fun onClipboardButton() {
        val keyboard = ui ?: return
        if (keyboard.isClipboardOpen) {
            keyboard.hideClipboard()
            updateSuggestions()
        } else {
            currentInputConnection?.let { commitComposing(it, autocorrect = false) }
            refreshClipboardPanel()
        }
    }

    override fun onClipPaste(text: String) {
        val ic = currentInputConnection ?: return
        paste(ic, text)
    }

    override fun onClipPin(text: String, pinned: Boolean) {
        clipboard.setPinned(text, pinned)
        saveClipboard()
        refreshClipboardPanel()
    }

    override fun onClipDelete(text: String) {
        clipboard.remove(text)
        saveClipboard()
        refreshClipboardPanel()
    }

    override fun onClipClear() {
        clipboard.clearUnpinned()
        refreshClipboardPanel()
    }

    private fun refreshClipboardPanel() {
        clipboard.prune(System.currentTimeMillis())
        ui?.showClipboard(clipboard.items, prefs.clipboardHistory)
    }

    private fun paste(ic: InputConnection, text: String) {
        commitComposing(ic, autocorrect = false)
        ic.commitText(text, 1)
        previousWord = null
        pendingUndo = null
        clipChip = null
        lastTypedAt = System.currentTimeMillis()
        ui?.hideClipboard()
        updateAutoShift()
        renderKeys()
        updateSuggestions()
    }

    private fun saveClipboard() {
        val data = clipboard.serialize()
        val file = prefs.clipboardFile
        io.execute {
            runCatching {
                if (data.isEmpty()) {
                    file.delete()
                } else {
                    val tmp = java.io.File(file.path + ".tmp")
                    tmp.writeText(data)
                    tmp.renameTo(file)
                }
            }.onFailure { Log.e(TAG, "Failed to save clipboard history", it) }
        }
    }

    /** Words from copied text queued until that language's dictionary has loaded. */
    private val pendingClipWords = EnumMap<Language, MutableList<TextHarvester.Word>>(Language::class.java)

    /**
     * Learns words from copied text. English words are learned as typed words;
     * Thai has no spaces, so a run is only reinforced when it is already a known word.
     */
    private fun learnFromCopiedText(text: String) {
        for ((lang, words) in TextHarvester.words(text).groupBy { it.language }) {
            val model = models[lang]
            if (model == null) {
                pendingClipWords.getOrPut(lang) { ArrayList() }.addAll(words)
                ensureLoaded(lang)
                continue
            }
            learnHarvested(model, words)
        }
    }

    private fun learnHarvested(model: LanguageModel, words: List<TextHarvester.Word>) {
        synchronized(model) {
            for (w in words) {
                if (w.language == Language.THAI && !model.isKnown(w.text)) continue
                model.learn(w.text, w.previous)
            }
        }
        unsavedLearns += words.size
        saveUserData()
    }

    /** Commits the composing word, auto-correcting it if allowed, then appends [separator]. */
    private fun commitComposing(ic: InputConnection, autocorrect: Boolean, separator: String = "") {
        if (composing.isEmpty()) return
        val typed = composing.toString()
        val fix = if (autocorrect && autocorrectOn) freshResult(typed)?.autoCorrection else null
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

    /** Asks for new suggestions in the background; the newest request wins. */
    private fun updateSuggestions() {
        val engine = engines[language]
        val model = models[language]
        if (ui == null) return
        if (!predictionOn || engine == null || model == null) {
            latestRequest++
            result = SuggestionEngine.Result.EMPTY
            resultFor = null
            showSuggestions()
            return
        }
        val typed = composing.toString()
        val previous = previousWord ?: wordBeforeCursor()
        val request = ++latestRequest
        predictor.execute {
            if (request != latestRequest) return@execute // the user already typed more
            val r = synchronized(model) { engine.suggest(typed, previous, KeyboardUi.SLOTS) }
            main.post {
                if (request != latestRequest) return@post
                result = r
                resultFor = typed to previous
                showSuggestions()
            }
        }
    }

    /**
     * The result for exactly [typed], computing it now if the background one is not
     * ready yet, so the space bar never auto-corrects from a stale result.
     */
    private fun freshResult(typed: String): SuggestionEngine.Result? {
        if (resultFor?.first == typed) return result
        val engine = engines[language] ?: return null
        val model = models[language] ?: return null
        val previous = previousWord ?: wordBeforeCursor()
        return synchronized(model) { engine.suggest(typed, previous, KeyboardUi.SLOTS) }
    }

    private fun showSuggestions() {
        val keyboard = ui ?: return
        var words = result.suggestions.map { it.word }
        var highlight = when {
            !autocorrectOn -> -1
            result.autoCorrection != null -> words.indexOf(result.autoCorrection)
            else -> -1
        }
        // Something was just copied: offer it as a one-tap paste in the first slot.
        clipChip = if (predictionOn && composing.isEmpty() && prefs.clipboardHistory) {
            clipboard.recent(System.currentTimeMillis(), CLIP_CHIP_WINDOW_MS)
                ?.takeIf { it.time > lastTypedAt } // hide once the user has typed since copying
                ?.text
        } else null
        clipChip?.let { text ->
            words = listOf(text.trim().replace(Regex("\\s+"), " ")) + words.take(KeyboardUi.SLOTS - 1)
            highlight = if (highlight >= 0) highlight + 1 else -1
        }
        keyboard.setSuggestions(words, highlight, clipChip = clipChip != null)
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
        val info = currentInputEditorInfo ?: return EnterAction.ENTER
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return EnterAction.ENTER
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> EnterAction.GO
            EditorInfo.IME_ACTION_SEARCH -> EnterAction.SEARCH
            EditorInfo.IME_ACTION_SEND -> EnterAction.SEND
            EditorInfo.IME_ACTION_NEXT -> EnterAction.NEXT
            EditorInfo.IME_ACTION_DONE -> EnterAction.DONE
            else -> EnterAction.ENTER
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
                val files = assets.list("dict").orEmpty()
                if (prefs.medicalTerms && "${lang.code}_medical.tsv" in files) {
                    assets.open("dict/${lang.code}_medical.tsv").reader().use { model.loadTerms(it) }
                }
                if ("${lang.code}_shortcuts.tsv" in files) {
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
                pendingClipWords.remove(lang)?.let { learnHarvested(model, it) }
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
        val previous = previousWord
        synchronized(model) { model.learn(word, previous) }
        if (++unsavedLearns >= SAVE_EVERY) saveUserData()
    }

    private fun saveUserData() {
        if (unsavedLearns == 0) return
        unsavedLearns = 0
        // Snapshot on the main thread (where the model is mutated), write in the background.
        val snapshots = models.map { (lang, model) ->
            lang to synchronized(model) { java.io.StringWriter().also(model::saveUserData).toString() }
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
        private const val CLIP_CHIP_WINDOW_MS = 60_000L
        private val TYPING_KEYS = setOf(Key.Type.CHAR, Key.Type.SPACE, Key.Type.DELETE, Key.Type.ENTER)
        /** Same key as ClipDescription.EXTRA_IS_SENSITIVE, which many apps set on older Android too. */
        private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
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
