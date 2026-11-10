package com.neuralkeyboard.core

import java.io.File
import java.io.StringReader
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SuggestionEngineTest {

    private val dictDir = File("../app/src/main/assets/dict")

    private fun load(language: Language): Pair<LanguageModel, SuggestionEngine> {
        val model = LanguageModel()
        File(dictDir, "${language.code}_words.tsv").reader().use(model::loadWords)
        File(dictDir, "${language.code}_bigrams.tsv").reader().use(model::loadBigrams)
        File(dictDir, "${language.code}_shortcuts.tsv").takeIf { it.exists() }?.reader()?.use(model::loadShortcuts)
        File(dictDir, "${language.code}_medical.tsv").takeIf { it.exists() }?.reader()?.use { model.loadTerms(it) }
        return model to SuggestionEngine(model, KeyProximity.forLanguage(language))
    }

    private val en = load(Language.ENGLISH)
    private val th = load(Language.THAI)

    private fun words(r: SuggestionEngine.Result) = r.suggestions.map { it.word }

    @Test
    fun `swapped letters are auto-corrected`() {
        assertEquals("the", en.second.suggest("teh").autoCorrection)
    }

    @Test
    fun `neighbouring key typo is auto-corrected`() {
        // 'r' is next to 'e' on QWERTY
        assertEquals("hello", en.second.suggest("hrllo").autoCorrection)
    }

    @Test
    fun `missing apostrophe is fixed`() {
        assertEquals("don't", en.second.suggest("dont").autoCorrection)
    }

    @Test
    fun `known words are never auto-corrected`() {
        assertNull(en.second.suggest("the").autoCorrection)
        assertNull(en.second.suggest("work").autoCorrection)
    }

    @Test
    fun `lone i becomes capital I`() {
        assertEquals("I", en.second.suggest("i").autoCorrection)
    }

    @Test
    fun `prefix is completed and capitalisation is kept`() {
        val r = en.second.suggest("Hel")
        assertTrue("Hello" in words(r) || "Help" in words(r), "got ${words(r)}")
        assertNull(r.autoCorrection, "must not auto-correct a valid prefix")
    }

    @Test
    fun `typed text is always offered when unknown`() {
        val r = en.second.suggest("xqzt")
        assertEquals("xqzt", words(r).first())
    }

    @Test
    fun `next word is predicted from the previous word`() {
        assertEquals("you", en.second.suggest("", previousWord = "thank").suggestions.first().word)
        assertEquals("ครับ", th.second.suggest("", previousWord = "ขอบคุณ").suggestions.first().word)
    }

    @Test
    fun `previous word reranks completions`() {
        val r = en.second.suggest("y", previousWord = "thank")
        assertEquals("you", words(r).first { it != "y" })
    }

    @Test
    fun `learned words stop being corrected and get suggested`() {
        val (model, engine) = load(Language.ENGLISH)
        assertTrue(engine.suggest("claude").autoCorrection != "claude")
        repeat(3) { model.learn("claude") }
        assertNull(engine.suggest("claude").autoCorrection)
        assertTrue("claude" in words(engine.suggest("clau")), "got ${words(engine.suggest("clau"))}")
    }

    @Test
    fun `learned bigrams drive prediction`() {
        val (model, engine) = load(Language.ENGLISH)
        repeat(10) { model.learn("pizza", previous = "eat") }
        assertEquals("pizza", engine.predictNext("eat").first().word)
    }

    @Test
    fun `user data survives save and load`() {
        val (model, _) = load(Language.ENGLISH)
        repeat(3) { model.learn("kotlin", previous = "love") }
        val out = StringWriter().also(model::saveUserData).toString()

        val (fresh, engine) = load(Language.ENGLISH)
        fresh.loadUserData(StringReader(out))
        assertTrue(fresh.isKnown("kotlin"))
        assertTrue("kotlin" in engine.predictNext("love").map { it.word })
    }

    @Test
    fun `english dictionary covers common misspellings`() {
        assertTrue(en.first.words.size > 30000, "only ${en.first.words.size} English words")
        val fixes = mapOf(
            "recieve" to "receive", "definately" to "definitely", "seperate" to "separate",
            "goverment" to "government", "tommorow" to "tomorrow", "becuase" to "because",
            "freind" to "friend", "wierd" to "weird",
        )
        for ((typo, fix) in fixes) assertEquals(fix, en.second.suggest(typo).autoCorrection, typo)
    }

    @Test
    fun `texting shortcuts expand`() {
        assertEquals("I'm", en.second.suggest("im").autoCorrection)
        assertEquals("thanks", en.second.suggest("thx").autoCorrection)
        assertEquals("Please", en.second.suggest("Pls").autoCorrection)
    }

    @Test
    fun `bundled bigrams predict common phrases`() {
        assertEquals("morning", en.second.predictNext("good").first().word)
        assertEquals("to", en.second.predictNext("going").first().word)
    }

    @Test
    fun `thai prefix completion`() {
        assertTrue("สวัสดี" in words(th.second.suggest("สวัส")))
    }

    @Test
    fun `thai missing tone mark is corrected`() {
        assertEquals("ไม่เป็นไร", th.second.suggest("ไมเป็นไร").autoCorrection)
    }

    @Test
    fun `thai wrong consonant is corrected`() {
        assertEquals("ขอบคุณ", th.second.suggest("ขอบคุน").autoCorrection)
    }

    @Test
    fun `thai run-on text is split at word boundary`() {
        assertEquals("สวัสดี" to "ค", th.second.splitFinishedWord("สวัสดีค"))
        assertNull(th.second.splitFinishedWord("สวัส"))
    }

    @Test
    fun `thai dictionary covers everyday vocabulary`() {
        assertTrue(th.first.words.size > 20000, "only ${th.first.words.size} Thai words")
        for (w in listOf("ประเทศไทย", "โรงพยาบาล", "มหาวิทยาลัย", "คอมพิวเตอร์", "อินเทอร์เน็ต", "ครับ", "ค่ะ")) {
            assertTrue(th.first.isKnown(w), "missing $w")
        }
    }

    @Test
    fun `thai misspellings of longer words are corrected`() {
        assertEquals("ประเทศไทย", th.second.suggest("ประเทสไทย").autoCorrection) // ส vs ศ
        assertEquals("โรงพยาบาล", th.second.suggest("โรงพยาบาน").autoCorrection) // น vs ล
        assertTrue("มหาวิทยาลัย" in words(th.second.suggest("มหาวิทยาลั")))
    }

    @Test
    fun `drug names are completed`() {
        assertTrue("cisplatin" in words(en.second.suggest("cispl")), "got ${words(en.second.suggest("cispl"))}")
        assertTrue("pembrolizumab" in words(en.second.suggest("pembro")))
        assertTrue("paracetamol" in words(en.second.suggest("paraceta")))
        assertTrue("พาราเซตามอล" in words(th.second.suggest("พาราเซ")))
    }

    @Test
    fun `misspelled drug names are suggested but never auto-inserted`() {
        val r = en.second.suggest("vincristin")
        assertTrue("vincristine" in words(r), "got ${words(r)}")
        assertNull(r.autoCorrection)
        val r2 = en.second.suggest("metformim")
        assertTrue("metformin" in words(r2), "got ${words(r2)}")
        assertNull(r2.autoCorrection)
    }

    @Test
    fun `a drug name is never auto-corrected into another drug`() {
        // hydroxyzine and hydralazine are a known look-alike pair
        assertNull(en.second.suggest("hydroxyzine").autoCorrection)
        assertNull(en.second.suggest("hydralazine").autoCorrection)
        assertNull(en.second.suggest("hydroxyzene").autoCorrection)
    }

    @Test
    fun `multi-word drug names are predicted word by word`() {
        assertTrue("glargine" in en.second.predictNext("insulin", 10).map { it.word })
    }

    @Test
    fun `everyday typos are still auto-corrected with the medical list loaded`() {
        assertEquals("because", en.second.suggest("becuase").autoCorrection)
        assertEquals("the", en.second.suggest("teh").autoCorrection)
    }

    @Test
    fun `thai sentence is segmented into words`() {
        val r = th.second.segment("ฉันกินข้าวที่บ้าน")
        assertEquals(listOf("ฉัน", "กิน", "ข้าว", "ที่", "บ้าน"), r.tokens)
        assertTrue(r.clean)
        assertEquals(listOf("วันนี้", "อากาศ", "ดี", "มาก"), th.second.segment("วันนี้อากาศดีมาก").tokens)
    }

    @Test
    fun `thai words are split off as soon as the next word starts`() {
        assertEquals(SuggestionEngine.Split(listOf("ไป"), "กิ"), th.second.splitWords("ไปกิ"))
        // still one (possibly unfinished) word: nothing to commit
        assertNull(th.second.splitWords("มาก"))
        assertNull(th.second.splitWords("อากา"))
        assertNull(th.second.splitWords("ประเทศไ"))
    }

    @Test
    fun `thai text with a typo is kept whole so it can be corrected`() {
        assertNull(th.second.splitWords("ขอบคุน"))
        assertEquals("ขอบคุณ", th.second.suggest("ขอบคุน").autoCorrection)
    }

    @Test
    fun `thai next word comes from corpus bigrams`() {
        assertEquals("ข้าว", th.second.predictNext("กิน").first().word)
        assertTrue(th.second.predictNext("โรง", 10).isNotEmpty())
    }
}
