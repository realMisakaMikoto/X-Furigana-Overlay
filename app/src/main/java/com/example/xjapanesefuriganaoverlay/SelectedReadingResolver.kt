package com.example.xjapanesefuriganaoverlay

import java.text.Normalizer
import com.example.xjapanesefuriganaoverlay.furigana.JapaneseNumberReading

data class ReadingHint(
    val surface: String,
    val reading: String,
    val start: Int,
    val end: Int,
    val confidence: Double = 0.5
) {
    fun hasValidRange(): Boolean = start >= 0 && end > start
}

data class ResolveResult(
    val reading: String?,
    val hitType: String,
    val shouldUseLlm: Boolean,
    val usedHints: List<ReadingHint> = emptyList(),
    val reason: String = ""
) {
    val matchedHints: List<ReadingHint>
        get() = usedHints
}

class SelectedReadingResolver(
    private val sourceText: String,
    hints: List<ReadingHint>
) {
    private val validHints = hints
        .filter { it.hasValidRange() && it.end <= sourceText.length }
        .filter { sourceText.substring(it.start, it.end) == it.surface }
    private val hintsByStart = validHints.groupBy { it.start }

    fun resolve(start: Int, end: Int): ResolveResult {
        if (start < 0 || end > sourceText.length || start >= end) {
            return ResolveResult(
                reading = null,
                hitType = "invalid_range",
                shouldUseLlm = true,
                reason = "Selection range is outside source text."
            )
        }

        exactRangeHit(start, end)?.let { return it }

        val scan = scanSelection(start, end)
        if (!scan.shouldUseLlm && !scan.reading.isNullOrBlank()) {
            return scan
        }

        localNumberExpression(start, end)?.let { return it }
        uniqueSurfaceFallback(start, end)?.let { return it }

        return scan
    }

    private fun exactRangeHit(start: Int, end: Int): ResolveResult? {
        val hint = validHints
            .filter { it.start == start && it.end == end }
            .maxWithOrNull(compareBy<ReadingHint> { it.confidence }.thenBy { it.surface.length })
            ?: return null
        return ResolveResult(
            reading = hint.reading,
            hitType = "exact_range",
            shouldUseLlm = false,
            usedHints = listOf(hint)
        )
    }

    private fun scanSelection(start: Int, end: Int): ResolveResult {
        val builder = StringBuilder()
        val matched = mutableListOf<ReadingHint>()
        var cursor = start
        var skippedOrLiteralGap = false
        var usedNumberRule = false
        var appendedLiteral = false

        while (cursor < end) {
            val hint = longestHintAt(cursor, end)
            if (hint != null) {
                builder.append(hint.reading)
                matched.add(hint)
                val nextCursor = skipDuplicatedOkuriganaAfterHint(
                    sourceText = sourceText,
                    cursor = hint.end,
                    selectionEnd = end,
                    reading = hint.reading
                )
                if (nextCursor > hint.end) skippedOrLiteralGap = true
                cursor = nextCursor
                continue
            }

            val numericToken = numericTokenAt(cursor, end)
            if (numericToken != null) {
                builder.append(numericToken.reading)
                cursor = numericToken.end
                usedNumberRule = true
                skippedOrLiteralGap = true
                continue
            }

            val normalized = normalizeSingleChar(sourceText[cursor])
            when {
                isKana(normalized) -> {
                    builder.append(kanaToHiragana(normalized))
                    cursor++
                    appendedLiteral = true
                    skippedOrLiteralGap = true
                }

                normalized == 'ー' -> {
                    builder.append(normalized)
                    cursor++
                    appendedLiteral = true
                    skippedOrLiteralGap = true
                }

                isSkippable(normalized) -> {
                    cursor++
                    skippedOrLiteralGap = true
                }

                isKanjiLike(normalized) -> {
                    val covering = coveringHints(cursor)
                    val reason = if (covering.isNotEmpty()) {
                        "Selection enters an existing hint but no sub-hint starts at this cursor."
                    } else {
                        "Kanji at cursor has no covering hint."
                    }
                    return ResolveResult(
                        reading = builder.toString().takeIf { it.isNotBlank() },
                        hitType = if (covering.isNotEmpty()) "inside_hint_needs_llm" else "uncovered_kanji",
                        shouldUseLlm = true,
                        usedHints = matched,
                        reason = reason
                    )
                }

                isDigitLike(normalized) -> {
                    return ResolveResult(
                        reading = builder.toString().takeIf { it.isNotBlank() },
                        hitType = "unresolved_number",
                        shouldUseLlm = true,
                        usedHints = matched,
                        reason = "Number expression could not be resolved locally."
                    )
                }

                else -> {
                    cursor++
                    skippedOrLiteralGap = true
                }
            }
        }

        val reading = builder.toString().takeIf { it.isNotBlank() }
        if (reading == null) {
            return ResolveResult(
                reading = null,
                hitType = "empty_local_reading",
                shouldUseLlm = true,
                usedHints = matched
            )
        }

        val hitType = when {
            matched.isNotEmpty() && skippedOrLiteralGap -> "composed_hints_with_gaps"
            matched.isNotEmpty() -> "composed_hints"
            usedNumberRule -> "local_number_rule"
            appendedLiteral -> "literal_kana"
            else -> "local_literal"
        }
        return ResolveResult(
            reading = reading,
            hitType = hitType,
            shouldUseLlm = false,
            usedHints = matched
        )
    }

    private fun longestHintAt(cursor: Int, selectionEnd: Int): ReadingHint? {
        return hintsByStart[cursor]
            .orEmpty()
            .filter { it.end <= selectionEnd }
            .maxWithOrNull(
                compareBy<ReadingHint> { it.end - it.start }
                    .thenBy { it.confidence }
            )
    }

    private fun coveringHints(cursor: Int): List<ReadingHint> {
        return validHints.filter { it.start <= cursor && cursor < it.end }
    }

    private fun numericTokenAt(cursor: Int, selectionEnd: Int): NumericToken? {
        if (cursor >= selectionEnd || !isDigitLike(sourceText[cursor])) return null
        var scanEnd = cursor
        var expectDigit = true
        val candidateEnds = mutableListOf<Int>()

        while (scanEnd < selectionEnd) {
            if (expectDigit) {
                if (!isDigitLike(sourceText[scanEnd])) break
                while (scanEnd < selectionEnd && isDigitLike(sourceText[scanEnd])) {
                    scanEnd++
                }
                candidateEnds.add(scanEnd)
                expectDigit = false
            } else {
                if (scanEnd >= selectionEnd || sourceText[scanEnd] !in NUMERIC_UNITS) break
                scanEnd++
                candidateEnds.add(scanEnd)
                expectDigit = true
            }
        }

        return candidateEnds
            .asReversed()
            .asSequence()
            .mapNotNull { end ->
                JapaneseNumberReading.readNumericExpression(sourceText.substring(cursor, end))
                    ?.let { NumericToken(it, end) }
            }
            .firstOrNull()
    }

    private fun localNumberExpression(start: Int, end: Int): ResolveResult? {
        val reading = JapaneseNumberReading.readNumericExpression(sourceText.substring(start, end))
            ?: return null
        return ResolveResult(
            reading = reading,
            hitType = "local_number_rule",
            shouldUseLlm = false
        )
    }

    private fun uniqueSurfaceFallback(start: Int, end: Int): ResolveResult? {
        val selectedSurface = sourceText.substring(start, end).trim()
        if (selectedSurface.isBlank()) return null
        val matching = validHints.filter { it.surface == selectedSurface }
        if (matching.isEmpty()) return null
        val readings = matching.map { it.reading }.distinct()
        val reading = readings.singleOrNull() ?: return null
        return ResolveResult(
            reading = reading,
            hitType = "unique_surface",
            shouldUseLlm = false,
            usedHints = matching
        )
    }

    private fun skipDuplicatedOkuriganaAfterHint(
        sourceText: String,
        cursor: Int,
        selectionEnd: Int,
        reading: String
    ): Int {
        if (cursor >= selectionEnd) return cursor

        val kana = StringBuilder()
        var index = cursor
        while (index < selectionEnd) {
            val normalized = normalizeSingleChar(sourceText[index])
            if (!isKanaOrLongVowel(normalized)) break
            kana.append(toHiragana(normalized))
            index++
        }
        if (kana.isEmpty()) return cursor

        val kanaText = kana.toString()
        val normalizedReading = katakanaToHiragana(reading)
        var skipLength = 0
        for (length in kanaText.length downTo 1) {
            val prefix = kanaText.substring(0, length)
            if (normalizedReading.endsWith(prefix)) {
                skipLength = length
                break
            }
        }
        return cursor + skipLength
    }

    private fun normalizeSingleChar(char: Char): Char {
        return Normalizer.normalize(char.toString(), Normalizer.Form.NFKC).firstOrNull() ?: char
    }

    private fun isKana(char: Char): Boolean = isHiragana(char) || isKatakana(char)

    private fun isKanaOrLongVowel(char: Char): Boolean = isKana(char) || char == 'ー'

    private fun isHiragana(char: Char): Boolean {
        return char in '\u3041'..'\u3096' || char in '\u309D'..'\u309E'
    }

    private fun isKatakana(char: Char): Boolean {
        return char in '\u30A1'..'\u30F6' || char in '\u30FD'..'\u30FE'
    }

    private fun kanaToHiragana(char: Char): Char {
        return if (isKatakana(char)) (char.code - KATAKANA_TO_HIRAGANA_OFFSET).toChar() else char
    }

    private fun toHiragana(char: Char): Char {
        return when {
            isKatakana(char) -> (char.code - KATAKANA_TO_HIRAGANA_OFFSET).toChar()
            else -> char
        }
    }

    private fun katakanaToHiragana(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
            .map { char -> toHiragana(char) }
            .joinToString("")
    }

    private fun isKanjiLike(char: Char): Boolean {
        return char in '\u3400'..'\u4DBF' ||
            char in '\u4E00'..'\u9FFF' ||
            char in '\uF900'..'\uFAFF' ||
            char == '々' ||
            char == '〆'
    }

    private fun isDigitLike(char: Char): Boolean {
        return char in '0'..'9' || char in '０'..'９'
    }

    private fun isSkippable(char: Char): Boolean {
        if (char.isWhitespace()) return true
        if (char in SKIPPABLE_CHARS) return true
        return when (Character.getType(char)) {
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(),
            Character.MATH_SYMBOL.toInt(),
            Character.CURRENCY_SYMBOL.toInt(),
            Character.MODIFIER_SYMBOL.toInt(),
            Character.OTHER_SYMBOL.toInt(),
            Character.SURROGATE.toInt() -> true
            else -> char in 'A'..'Z' || char in 'a'..'z'
        }
    }

    private data class NumericToken(
        val reading: String,
        val end: Int
    )

    companion object {
        private const val KATAKANA_TO_HIRAGANA_OFFSET = 0x60
        private const val NUMERIC_UNITS = "年月日時分秒円歳才人個枚本回話巻号度"
        private const val SKIPPABLE_CHARS =
            "、。，．・･!！?？「」『』（）()[]【】<>《》〈〉…‥〜～-—―/／\\|｜:：;；,.\"'“”‘’#＃@＠"
    }
}
