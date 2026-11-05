package com.neuralkeyboard.core

import kotlin.test.Test
import kotlin.test.assertEquals

class TextHarvesterTest {

    @Test
    fun `plain words are kept with their language and previous word`() {
        val words = TextHarvester.words("Start pembrolizumab 200 mg, ให้ยา ครบ")
        assertEquals(listOf("Start", "pembrolizumab", "mg", "ให้ยา", "ครบ"), words.map { it.text })
        assertEquals("Start", words[1].previous)
        assertEquals(null, words[2].previous) // a number breaks the phrase
        assertEquals(Language.THAI, words[3].language)
    }

    @Test
    fun `identifiers and numbers are skipped`() {
        val words = TextHarvester.words("HN 6501234 tel 081-234-5678 abc123 ref#A12")
        assertEquals(listOf("HN", "tel", "ref"), words.map { it.text })
    }
}
