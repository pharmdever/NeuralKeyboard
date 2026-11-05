package com.neuralkeyboard.core

/**
 * Languages supported by the keyboard.
 *
 * Thai does not put spaces between words, so picking a suggestion in Thai must not
 * append a space ([spaceAfterWord] = false).
 */
enum class Language(
    val code: String,
    val label: String,
    val spaceAfterWord: Boolean,
    val letterRows: List<String>,
    val shiftedRows: List<String>?,
    /** Horizontal offset (in key widths) of each row, used for key-proximity. */
    val rowOffsets: List<Double>,
) {
    ENGLISH(
        code = "en",
        label = "EN",
        spaceAfterWord = true,
        letterRows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"),
        shiftedRows = null, // upper-case is derived automatically
        rowOffsets = listOf(0.0, 0.5, 1.5),
    ),

    /** Standard Thai Kedmanee layout. */
    THAI(
        code = "th",
        label = "ไทย",
        spaceAfterWord = false,
        letterRows = listOf("ๅ/-ภถุึคตจขช", "ๆไำพะัีรนยบลฃ", "ฟหกดเ้่าสวง", "ผปแอิืทมใฝ"),
        shiftedRows = listOf("+๑๒๓๔ู฿๕๖๗๘๙", "๐\"ฎฑธํ๊ณฯญฐ,ฅ", "ฤฆฏโฌ็๋ษศซ.", "()ฉฮฺ์?ฒฬฦ"),
        rowOffsets = listOf(0.0, 0.0, 0.5, 1.0),
    );

    fun next(): Language = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromCode(code: String?): Language = entries.firstOrNull { it.code == code } ?: ENGLISH
    }
}
