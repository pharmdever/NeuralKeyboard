package com.neuralkeyboard.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClipboardHistoryTest {

    @Test
    fun `newest first and re-copy moves to top`() {
        val h = ClipboardHistory()
        h.add("a", 1); h.add("b", 2); h.add("a", 3)
        assertEquals(listOf("a", "b"), h.items.map { it.text })
    }

    @Test
    fun `unpinned items expire, pinned ones stay`() {
        val h = ClipboardHistory(ttlMillis = 100)
        h.add("keep", 0); h.setPinned("keep", true)
        h.add("drop", 0)
        h.prune(now = 1000)
        assertEquals(listOf("keep"), h.items.map { it.text })
    }

    @Test
    fun `history is capped`() {
        val h = ClipboardHistory(maxUnpinned = 3)
        repeat(10) { h.add("t$it", it.toLong()) }
        assertEquals(listOf("t9", "t8", "t7"), h.items.map { it.text })
    }

    @Test
    fun `blank text is ignored`() {
        val h = ClipboardHistory()
        assertNull(h.add("  ", 1))
        assertTrue(h.items.isEmpty())
    }

    @Test
    fun `recent item only within window`() {
        val h = ClipboardHistory()
        h.add("x", 1000)
        assertEquals("x", h.recent(now = 1500, windowMillis = 1000)?.text)
        assertNull(h.recent(now = 5000, windowMillis = 1000))
    }

    @Test
    fun `whole history survives save and load, multi-line text included`() {
        val h = ClipboardHistory()
        val tricky = "line1\nline2\twith tab \\ slash"
        h.add(tricky, 5); h.setPinned(tricky, true)
        h.add("plain", 6)
        val restored = ClipboardHistory().apply { load(h.serialize()) }
        assertEquals(listOf("plain", tricky), restored.items.map { it.text })
        assertEquals(listOf(false, true), restored.items.map { it.pinned })
    }

    @Test
    fun `items are kept forever by default`() {
        val h = ClipboardHistory()
        h.add("old", 0)
        h.prune(now = 365L * 24 * 3600 * 1000)
        assertEquals(listOf("old"), h.items.map { it.text })
    }

    @Test
    fun `legacy pinned file is still readable`() {
        val h = ClipboardHistory().apply { loadLegacyPinned("5\tkept\\nline\n") }
        assertEquals("kept\nline", h.items.single().text)
        assertTrue(h.items.single().pinned)
    }
}
