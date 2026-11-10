package com.neuralkeyboard.ime

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
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
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import com.neuralkeyboard.core.ClipboardHistory
import com.neuralkeyboard.core.KeyProximity
import com.neuralkeyboard.core.Language
import com.neuralkeyboard.core.LanguageModel
import com.neuralkeyboard.core.SnippetStore
import com.neuralkeyboard.core.SuggestionEngine
import com.neuralkeyboard.core.TextHarvester
import java.util.Date
import java.util.EnumMap
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

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
    /**
     * Guards the language models. Reading (suggesting, splitting Thai words) can
     * happen on the main and the predictor thread at the same time; only learning
     * takes the write lock, and it runs on the predictor thread, so a key press
     * never waits for a suggestion that is still being computed.
     */
    private val modelLock = ReentrantReadWriteLock()
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
    /** Snippet offered in the first suggestion slot because its trigger was typed. */
    private var snippetChip: SnippetStore.Snippet? = null

    private var snippets = SnippetStore()
    private var snippetsVersion = -1L
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
        loadAllLanguages()

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
        predictor.shutdown()
        io.shutdown()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val keyboard = KeyboardUi(this, this)
        keyboard.setEffects(popup = prefs.keyPopup, pressAnimation = prefs.keyAnimation)
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
        ui?.setEffects(popup = prefs.keyPopup, pressAnimation = prefs.keyAnimation)
        ui?.hidePanel()
        reloadSnippetsIfChanged()
        clipboard.ttlMillis = prefs.clipboardTtlMillis
        if (!prefs.clipboardHistory) {
            clipboard.clearAll()
            prefs.clipboardFile.delete()
        }
        clipChip = null
        snippetChip = null
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
        val split = modelLock.read { engine.splitWords(composing.toString()) } ?: return
        val headLength = split.finished.sumOf { it.length }
        ic.commitText(composing.substring(0, headLength), 1)
        for (word in split.finished) {
            learn(word)
            previousWord = word
        }
        composing.delete(0, headLength)
    }

    private fun onSpace(ic: InputConnection) {
        // ";soap" + space expands the snippet right away.
        if (composing.isNotEmpty()) {
            val snippet = snippets.find(composing.toString())
            if (snippet != null && charBeforeComposing(ic) == ';') {
                insertSnippet(ic, snippet, removeSemicolon = true)
                return
            }
        }
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
        val snippet = snippetChip
        val clip = clipChip
        if (index == 0 && snippet != null) {
            insertSnippet(ic, snippet, removeSemicolon = charBeforeComposing(ic) == ';')
            return
        }
        if (index == 0 && clip != null) {
            paste(ic, clip)
            return
        }
        val offset = if (snippet != null || clip != null) 1 else 0
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

    // ---------------------------------------------------------------- snippets

    private fun reloadSnippetsIfChanged() {
        val file = prefs.snippetsFile
        val version = if (file.exists()) file.lastModified() else 0L
        if (version == snippetsVersion) return
        snippets = prefs.loadSnippets()
        snippetsVersion = if (file.exists()) file.lastModified() else 0L
    }

    override fun onSnippetButton() {
        val keyboard = ui ?: return
        if (keyboard.openPanel == KeyboardUi.Panel.SNIPPETS) {
            keyboard.hidePanel()
            updateSuggestions()
        } else {
            currentInputConnection?.let { commitComposing(it, autocorrect = false) }
            reloadSnippetsIfChanged()
            keyboard.showSnippets(snippets.snippets)
        }
    }

    override fun onSnippetPicked(trigger: String) {
        val ic = currentInputConnection ?: return
        val snippet = snippets.find(trigger) ?: return
        insertSnippet(ic, snippet, removeSemicolon = false)
    }

    override fun onEditSnippets() {
        ui?.hidePanel()
        startActivity(Intent(this, SnippetsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** The character just before the word being composed (or before the cursor). */
    private fun charBeforeComposing(ic: InputConnection): Char? {
        val before = ic.getTextBeforeCursor(composing.length + 1, 0) ?: return null
        return if (before.length == composing.length + 1) before[0] else null
    }

    /**
     * Replaces the typed trigger (and a leading ";") with the snippet text, then puts
     * the cursor where the snippet's {cursor} marker was.
     */
    private fun insertSnippet(ic: InputConnection, snippet: SnippetStore.Snippet, removeSemicolon: Boolean) {
        val expansion = snippets.expand(snippet, Date(), clipboard.items.firstOrNull()?.text)
        ic.beginBatchEdit()
        if (composing.isNotEmpty()) {
            ic.setComposingText("", 1)
            composing.clear()
        }
        if (removeSemicolon) ic.deleteSurroundingText(1, 0)
        ic.commitText(expansion.text, 1)
        val back = expansion.text.length - expansion.cursor
        if (back > 0) {
            ic.getExtractedText(ExtractedTextRequest(), 0)?.let { et ->
                val end = et.startOffset + et.selectionEnd
                ic.setSelection(end - back, end - back)
            }
        }
        ic.endBatchEdit()
        previousWord = null
        pendingUndo = null
        snippetChip = null
        lastTypedAt = System.currentTimeMillis()
        ui?.hidePanel()
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
        if (keyboard.openPanel == KeyboardUi.Panel.CLIPBOARD) refreshClipboardPanel() else updateSuggestions()
    }

    override fun onClipboardButton() {
        val keyboard = ui ?: return
        if (keyboard.openPanel == KeyboardUi.Panel.CLIPBOARD) {
            keyboard.hidePanel()
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
        ui?.hidePanel()
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
     * Thai has no spaces, so each run is segmented and learned only when it splits
     * cleanly into known words (their frequencies and word pairs are reinforced).
     */
    private fun learnFromCopiedText(text: String) {
        for ((lang, words) in TextHarvester.words(text).groupBy { it.language }) {
            if (models[lang] == null) {
                pendingClipWords.getOrPut(lang) { ArrayList() }.addAll(words)
                ensureLoaded(lang)
                continue
            }
            learnHarvested(lang, words)
        }
    }

    private fun learnHarvested(lang: Language, words: List<TextHarvester.Word>) {
        val model = models[lang] ?: return
        val engine = engines[lang] ?: return
        predictor.execute {
            modelLock.write {
                for (w in words) {
                    if (w.language != Language.THAI) {
                        model.learn(w.text, w.previous)
                        continue
                    }
                    val segmented = engine.segment(w.text)
                    if (!segmented.clean) continue
                    var previous: String? = null
                    for (token in segmented.tokens) {
                        model.learn(token, previous)
                        previous = token
                    }
                }
            }
            main.post {
                unsavedLearns += words.size
                saveUserData()
            }
        }
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
            val r = modelLock.read { engine.suggest(typed, previous, KeyboardUi.SLOTS) }
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
        val previous = previousWord ?: wordBeforeCursor()
        return modelLock.read { engine.suggest(typed, previous, KeyboardUi.SLOTS) }
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
        // The typed word is a snippet trigger: offer its expansion first.
        snippetChip = if (predictionOn && composing.isNotEmpty()) snippets.find(composing.toString()) else null
        val chipText = snippetChip?.text ?: clipChip
        chipText?.let { text ->
            words = listOf(text.trim().replace(Regex("\\s+"), " ")) + words.take(KeyboardUi.SLOTS - 1)
            highlight = if (highlight >= 0) highlight + 1 else -1
        }
        val chip = when {
            snippetChip != null -> KeyboardUi.Chip.SNIPPET
            clipChip != null -> KeyboardUi.Chip.CLIPBOARD
            else -> null
        }
        keyboard.setSuggestions(words, highlight, chip)
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

    /** Loads the current language first, then the others, so switching is instant. */
    private fun loadAllLanguages() {
        ensureLoaded(language)
        Language.entries.forEach(::ensureLoaded)
    }

    private fun ensureLoaded(lang: Language) {
        if (lang in models || !loading.add(lang)) return
        val token = loadedResetToken
        io.execute {
            val model = LanguageModel()
            try {
                val files = assets.list("dict").orEmpty()
                fun open(name: String) = assets.open("dict/${lang.code}_$name.tsv").reader()
                fun has(name: String) = "${lang.code}_$name.tsv" in files
                open("words").use(model::loadWords)
                if (prefs.medicalTerms) {
                    if (has("medwords")) open("medwords").use(model::loadWords)
                    if (has("medical")) open("medical").use { model.loadTerms(it) }
                }
                if (has("shortcuts")) open("shortcuts").use(model::loadShortcuts)
                // After the words, so word pairs can share the dictionary's strings.
                open("bigrams").use(model::loadBigrams)
                val user = prefs.userDataFile(lang)
                if (user.exists()) user.reader().use(model::loadUserData)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load dictionary for ${lang.code}", e)
            }
            val engine = SuggestionEngine(model, KeyProximity.forLanguage(lang))
            main.post {
                loading.remove(lang)
                if (token != loadedResetToken) return@post // settings changed meanwhile
                models[lang] = model
                engines[lang] = engine
                pendingClipWords.remove(lang)?.let { learnHarvested(lang, it) }
                if (lang == language) updateSuggestions()
            }
        }
    }

    private fun reloadAll() {
        saveUserData()
        models.clear()
        engines.clear()
        loading.clear()
        loadedResetToken = prefs.resetToken
        loadAllLanguages()
    }

    private fun learn(word: String) {
        if (!learningOn) return
        val model = models[language] ?: return
        val previous = previousWord
        // On the predictor thread: the main thread never waits for the write lock.
        predictor.execute { modelLock.write { model.learn(word, previous) } }
        if (++unsavedLearns >= SAVE_EVERY) saveUserData()
    }

    private fun saveUserData() {
        if (unsavedLearns == 0) return
        unsavedLearns = 0
        val current = models.toMap()
        val files = current.keys.associateWith { prefs.userDataFile(it) }
        // Snapshot after the learning queued so far, then write on the io thread.
        runCatching {
            predictor.execute {
                val snapshots = modelLock.read {
                    current.map { (lang, model) -> lang to java.io.StringWriter().also(model::saveUserData).toString() }
                }
                for ((lang, data) in snapshots) {
                    try {
                        val file = files.getValue(lang)
                        val tmp = java.io.File(file.path + ".tmp")
                        tmp.writeText(data)
                        tmp.renameTo(file)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save user data", e)
                    }
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
