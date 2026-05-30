package com.sosdanfurigana.furigana

import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

object RubyHtmlRenderer {
    fun renderHtml(originalText: String, annotations: List<FuriganaAnnotation>): String {
        val body = renderBody(originalText, annotations)
        return """
            <!doctype html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <style>
                html, body {
                  background: transparent;
                }
                body {
                  margin: 0;
                  padding: 12px;
                  background: transparent;
                  color: #ffffff;
                  font-size: 18px;
                  line-height: 2.1;
                  word-break: break-word;
                  font-family: sans-serif;
                }
                ruby {
                  ruby-position: over;
                }
                rt {
                  font-size: 0.55em;
                  opacity: 0.95;
                }
              </style>
            </head>
            <body>$body</body>
            </html>
        """.trimIndent()
    }

    fun renderPlainText(originalText: String, annotations: List<FuriganaAnnotation>): String {
        val clean = cleanAnnotations(originalText, annotations)
        val builder = StringBuilder()
        var cursor = 0
        clean.forEachIndexed { index, annotation ->
            if (annotation.start < cursor) return@forEachIndexed
            val limitEnd = clean.getOrNull(index + 1)?.start ?: originalText.length
            val display = displayAnnotation(
                sourceText = originalText,
                annotation = annotation,
                limitEnd = limitEnd,
            )
            builder.append(originalText.substring(cursor, annotation.start))
            builder.append(originalText.substring(annotation.start, display.rubyBaseEnd))
            builder.append('(')
            builder.append(display.rubyReading)
            builder.append(')')
            if (display.rubyBaseEnd < annotation.end) {
                builder.append(originalText.substring(display.rubyBaseEnd, annotation.end))
            }
            cursor = display.consumedEnd
        }
        builder.append(originalText.substring(cursor))
        return builder.toString()
    }

    private fun renderBody(originalText: String, annotations: List<FuriganaAnnotation>): String {
        val clean = cleanAnnotations(originalText, annotations)
        val builder = StringBuilder()
        var cursor = 0
        clean.forEachIndexed { index, annotation ->
            if (annotation.start < cursor) return@forEachIndexed
            val limitEnd = clean.getOrNull(index + 1)?.start ?: originalText.length
            val display = displayAnnotation(
                sourceText = originalText,
                annotation = annotation,
                limitEnd = limitEnd,
            )
            builder.append(escapeHtml(originalText.substring(cursor, annotation.start)))
            builder.append("<ruby>")
            builder.append(escapeHtml(originalText.substring(annotation.start, display.rubyBaseEnd)))
            builder.append("<rt>")
            builder.append(escapeHtml(display.rubyReading))
            builder.append("</rt></ruby>")
            if (display.rubyBaseEnd < annotation.end) {
                builder.append(escapeHtml(originalText.substring(display.rubyBaseEnd, annotation.end)))
            }
            cursor = display.consumedEnd
        }
        builder.append(escapeHtml(originalText.substring(cursor)))
        return builder.toString()
    }

    private fun displayAnnotation(
        sourceText: String,
        annotation: FuriganaAnnotation,
        limitEnd: Int
    ): DisplayAnnotation {
        var rubyBaseEnd = annotation.end
        var rubyReading = annotation.reading

        preferredFollowingKanaExtension(
            sourceText = sourceText,
            annotation = annotation,
            limitEnd = limitEnd,
            reading = rubyReading
        )?.let { extension ->
            return DisplayAnnotation(
                rubyBaseEnd = extension.end,
                rubyReading = extension.reading,
                consumedEnd = extension.end
            )
        }

        val following = duplicatedFollowingKanaLength(
            sourceText = sourceText,
            baseEnd = annotation.end,
            limitEnd = limitEnd,
            reading = rubyReading
        )
        if (following > 0) {
            return DisplayAnnotation(
                rubyBaseEnd = annotation.end + following,
                rubyReading = rubyReading,
                consumedEnd = annotation.end + following
            )
        }

        val trailing = trailingKanaText(
            sourceText = sourceText,
            start = annotation.start,
            end = annotation.end
        )
        if (trailing.isNotEmpty() && !katakanaToHiragana(rubyReading).endsWith(trailing)) {
            rubyBaseEnd -= trailing.length
        }

        return DisplayAnnotation(
            rubyBaseEnd = rubyBaseEnd,
            rubyReading = rubyReading.ifBlank { annotation.reading },
            consumedEnd = annotation.end
        )
    }

    private fun trailingKanaText(
        sourceText: String,
        start: Int,
        end: Int
    ): String {
        if (end <= start) return ""

        val kana = StringBuilder()
        var index = end - 1
        while (index >= start) {
            val normalized = normalizeSingleChar(sourceText[index])
            if (!isKanaOrLongVowel(normalized)) break
            kana.insert(0, toHiragana(normalized))
            index--
        }
        return kana.toString()
    }

    private fun duplicatedFollowingKanaLength(
        sourceText: String,
        baseEnd: Int,
        limitEnd: Int,
        reading: String
    ): Int {
        if (baseEnd >= limitEnd || baseEnd >= sourceText.length || reading.isBlank()) {
            return 0
        }

        val safeLimit = min(limitEnd, sourceText.length)
        val kana = StringBuilder()
        var index = baseEnd
        while (index < safeLimit) {
            val normalized = normalizeSingleChar(sourceText[index])
            if (!isKanaOrLongVowel(normalized)) break
            kana.append(toHiragana(normalized))
            index++
        }
        if (kana.isEmpty()) return 0

        val kanaText = kana.toString()
        val normalizedReading = katakanaToHiragana(reading)
        for (length in kanaText.length downTo 1) {
            val prefix = kanaText.substring(0, length)
            if (normalizedReading.endsWith(prefix)) {
                return length
            }
        }
        return 0
    }

    private fun preferredFollowingKanaExtension(
        sourceText: String,
        annotation: FuriganaAnnotation,
        limitEnd: Int,
        reading: String
    ): FollowingExtension? {
        if (annotation.end >= limitEnd || annotation.end >= sourceText.length) return null
        val safeLimit = min(limitEnd, sourceText.length)
        val maxEnd = min(annotation.end + MAX_PREFERRED_FOLLOWING_KANA, safeLimit)
        for (end in maxEnd downTo annotation.end + 1) {
            val added = sourceText.substring(annotation.end, end)
            if (added.any { !isKanaOrLongVowel(normalizeSingleChar(it)) }) continue
            val surface = sourceText.substring(annotation.start, end)
            val expectedReading = PREFERRED_MIXED_SURFACE_READINGS[surface] ?: continue
            val normalizedReading = katakanaToHiragana(reading)
            val addedReading = katakanaToHiragana(added)
            if (normalizedReading == expectedReading || normalizedReading + addedReading == expectedReading) {
                return FollowingExtension(end, expectedReading)
            }
        }
        return null
    }

    private fun cleanAnnotations(
        originalText: String,
        annotations: List<FuriganaAnnotation>
    ): List<FuriganaAnnotation> {
        val selected = mutableListOf<FuriganaAnnotation>()
        annotations
            .filter { it.start >= 0 && it.end <= originalText.length && it.start < it.end }
            .filter { originalText.substring(it.start, it.end) == it.surface }
            .sortedWith(
                compareBy<FuriganaAnnotation> { it.start }
                    .thenByDescending { it.confidence }
                    .thenByDescending { it.end - it.start }
            )
            .forEach { candidate ->
                val overlapping = selected.filter {
                    rangesOverlap(candidate.start, candidate.end, it.start, it.end)
                }
                if (overlapping.isEmpty()) {
                    selected.add(candidate)
                } else if (overlapping.all { shouldReplace(candidate, it) }) {
                    selected.removeAll(overlapping.toSet())
                    selected.add(candidate)
                }
            }
        return selected.sortedBy { it.start }
    }

    private fun rangesOverlap(startA: Int, endA: Int, startB: Int, endB: Int): Boolean {
        return max(startA, startB) < min(endA, endB)
    }

    private fun shouldReplace(candidate: FuriganaAnnotation, existing: FuriganaAnnotation): Boolean {
        val candidateLength = candidate.end - candidate.start
        val existingLength = existing.end - existing.start
        return candidate.confidence > existing.confidence ||
            (candidate.confidence == existing.confidence && candidateLength > existingLength)
    }

    private fun normalizeSingleChar(char: Char): Char {
        return Normalizer.normalize(char.toString(), Normalizer.Form.NFKC).firstOrNull() ?: char
    }

    private fun isKanaOrLongVowel(char: Char): Boolean {
        return isHiragana(char) || isKatakana(char) || char == 'ー'
    }

    private fun isHiragana(char: Char): Boolean {
        return char in '\u3041'..'\u3096' || char in '\u309D'..'\u309E'
    }

    private fun isKatakana(char: Char): Boolean {
        return char in '\u30A1'..'\u30F6' || char in '\u30FD'..'\u30FE'
    }

    private fun toHiragana(char: Char): Char {
        return if (isKatakana(char)) {
            (char.code - KATAKANA_TO_HIRAGANA_OFFSET).toChar()
        } else {
            char
        }
    }

    private fun katakanaToHiragana(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
            .map { char -> toHiragana(char) }
            .joinToString("")
    }

    private fun escapeHtml(value: String): String {
        val builder = StringBuilder(value.length)
        value.forEach { char ->
            when (char) {
                '&' -> builder.append("&amp;")
                '<' -> builder.append("&lt;")
                '>' -> builder.append("&gt;")
                '"' -> builder.append("&quot;")
                '\'' -> builder.append("&#39;")
                else -> builder.append(char)
            }
        }
        return builder.toString()
    }

    private data class DisplayAnnotation(
        val rubyBaseEnd: Int,
        val rubyReading: String,
        val consumedEnd: Int
    )

    private data class FollowingExtension(
        val end: Int,
        val reading: String
    )

    private const val KATAKANA_TO_HIRAGANA_OFFSET = 0x60
    private const val MAX_PREFERRED_FOLLOWING_KANA = 3
    private val PREFERRED_MIXED_SURFACE_READINGS = mapOf(
        "大好き" to "だいすき"
    )
}
