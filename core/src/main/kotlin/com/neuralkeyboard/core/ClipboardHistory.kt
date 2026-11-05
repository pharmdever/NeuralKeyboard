package com.neuralkeyboard.core

/**
 * Copied texts, newest first.
 *
 * - Unpinned items expire after [ttlMillis] (null = keep until deleted) and at most
 *   [maxUnpinned] of them are kept. Pinned items are never removed automatically.
 * - The whole history can be saved and restored with [serialize] / [load].
 */
class ClipboardHistory(
    private val maxUnpinned: Int = 100,
    var ttlMillis: Long? = null,
    private val maxLength: Int = 10_000,
) {

    data class Item(val text: String, val time: Long, val pinned: Boolean = false)

    private val list = ArrayList<Item>()

    val items: List<Item> get() = list.toList()

    /** Records a copy. Re-copying an existing text moves it to the top (keeping its pin). */
    fun add(text: String, now: Long): Item? {
        if (text.isBlank()) return null
        val clipped = if (text.length > maxLength) text.substring(0, maxLength) else text
        val old = list.firstOrNull { it.text == clipped }
        if (old != null) list.remove(old)
        val item = Item(clipped, now, old?.pinned ?: false)
        list.add(0, item)
        prune(now)
        return item
    }

    fun setPinned(text: String, pinned: Boolean) {
        val i = list.indexOfFirst { it.text == text }
        if (i >= 0) list[i] = list[i].copy(pinned = pinned)
    }

    fun remove(text: String) {
        list.removeAll { it.text == text }
    }

    /** Removes everything that is not pinned. */
    fun clearUnpinned() {
        list.removeAll { !it.pinned }
    }

    fun clearAll() = list.clear()

    /** Drops expired items and keeps at most [maxUnpinned] unpinned ones. */
    fun prune(now: Long) {
        val ttl = ttlMillis
        if (ttl != null) list.removeAll { !it.pinned && now - it.time > ttl }
        var unpinned = 0
        list.removeAll { !it.pinned && ++unpinned > maxUnpinned }
    }

    /** The newest item if it was copied within [windowMillis] (for the quick-paste chip). */
    fun recent(now: Long, windowMillis: Long): Item? =
        list.firstOrNull()?.takeIf { now - it.time <= windowMillis }

    /** One line per item: time, pinned flag (0/1), escaped text. */
    fun serialize(): String = buildString {
        for (item in list) {
            append(item.time).append('\t').append(if (item.pinned) '1' else '0').append('\t')
                .append(escape(item.text)).append('\n')
        }
    }

    /** Restores items written by [serialize], merging with what is already here. */
    fun load(data: String) {
        for (line in data.lineSequence()) {
            val parts = line.split('\t', limit = 3)
            if (parts.size != 3) continue
            val time = parts[0].toLongOrNull() ?: continue
            val text = unescape(parts[2])
            if (text.isNotBlank() && list.none { it.text == text }) list.add(Item(text, time, parts[1] == "1"))
        }
        list.sortByDescending { it.time }
    }

    /** Reads the older format that stored only pinned items ("time<TAB>text"). */
    fun loadLegacyPinned(data: String) {
        for (line in data.lineSequence()) {
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val time = line.substring(0, tab).toLongOrNull() ?: continue
            val text = unescape(line.substring(tab + 1))
            if (list.none { it.text == text }) list.add(Item(text, time, pinned = true))
        }
        list.sortByDescending { it.time }
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r")

    private fun unescape(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    else -> sb.append(s[i + 1])
                }
                i += 2
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }
}
