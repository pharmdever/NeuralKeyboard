package com.neuralkeyboard.core

import java.util.Calendar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SnippetStoreTest {

    private val noon = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 5, 14, 30) }.time

    @Test
    fun `trigger match ignores case and a leading semicolon`() {
        val s = SnippetStore().apply { put("SOAP", "x") }
        assertEquals("soap", s.find("Soap")?.trigger)
        assertEquals("soap", s.find(";soap")?.trigger)
        assertNull(s.find("soa"))
    }

    @Test
    fun `placeholders and cursor are expanded`() {
        val s = SnippetStore()
        val e = s.expand(SnippetStore.Snippet("x", "S: {cursor}\nDate {date} {time} [{clipboard}]"), noon, "copied")
        assertEquals("S: \nDate 05/11/2026 14:30 [copied]", e.text)
        assertEquals(3, e.cursor)
    }

    @Test
    fun `cursor defaults to the end`() {
        val e = SnippetStore().expand(SnippetStore.Snippet("x", "hello"), noon)
        assertEquals(5, e.cursor)
    }

    @Test
    fun `invalid triggers are rejected`() {
        val s = SnippetStore()
        assertFalse(s.put("two words", "x"))
        assertFalse(s.put("", "x"))
        assertFalse(s.put("ok", ""))
    }

    @Test
    fun `save and load keep multi-line text and replace on same trigger`() {
        val s = SnippetStore().apply { put("a", "line1\nline2\ttab"); put("A", "replaced\\nnot newline") }
        val r = SnippetStore().apply { load(s.serialize()) }
        assertEquals(listOf(SnippetStore.Snippet("a", "replaced\\nnot newline")), r.snippets)
    }

    @Test
    fun `thai triggers work`() {
        val s = SnippetStore().apply { put("ขค", "ขอบคุณค่ะ") }
        assertEquals("ขอบคุณค่ะ", s.find("ขค")?.text)
    }
}
