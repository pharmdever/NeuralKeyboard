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
    fun `only pinned items are persisted, multi-line text survives`() {
        val h = ClipboardHistory()
        h.add("line1\nline2\twith tab \\ slash", 5); h.setPinned("line1\nline2\twith tab \\ slash", true)
        h.add("secret", 6)
        val restored = ClipboardHistory().apply { loadPinned(h.serializePinned()) }
        assertEquals(listOf("line1\nline2\twith tab \\ slash"), restored.items.map { it.text })
        assertTrue(restored.items.single().pinned)
    }
}
