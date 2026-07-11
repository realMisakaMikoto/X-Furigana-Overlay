package com.sosdanfurigana.furigana

import com.sosdanfurigana.japanese.JapaneseTextDetector
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

object FuriganaJsonParser {
    fun parseCandidateReadings(
        originalText: String,
        rawJson: String,
        candidates: List<FuriganaCandidate>
    ): List<FuriganaAnnotation> {
        val array = extractAnnotationArray(rawJson)
        val candidateById = candidates.associateBy { it.id }
        val annotations = mutableListOf<FuriganaAnnotation>()

        for (index in 0 until array.length()) {
            val parsed = when (val item = array.opt(index)) {
                is JSONArray -> parseCandidateArrayItem(item)
                is JSONObject -> parseCandidateObjectItem(item)
                else -> null
            } ?: continue

            val candidate = candidateById[parsed.id] ?: continue
            if (!isAnnotatableSurface(candidate.surface)) continue
            val reading = contextAdjustedReading(originalText, candidate, parsed.reading)
            if (!isValidReading(reading)) continue
            if (!isReadingPlausibleForSurface(candidate.surface, reading)) continue
            if (candidate.start < 0 || candidate.end > originalText.length) continue
            if (originalText.substring(candidate.start, candidate.end) != candidate.surface) continue
            if (!isReadingContextPlausible(originalText, candidate.surface, candidate.start, candidate.end, reading)) {
                continue
            }

            annotations.add(
                FuriganaAnnotation(
                    surface = candidate.surface,
                    reading = reading,
                    start = candidate.start,
                    end = candidate.end,
                    confidence = parsed.confidence
                )
            )
        }

        if (annotations.isNotEmpty()) return annotations.sortedWith(
            compareBy<FuriganaAnnotation> { it.start }
                .thenByDescending { it.end - it.start }
                .thenByDescending { it.confidence }
        )

        // Keep compatibility with older prompts or providers that ignore the compact id protocol.
        return parse(originalText, rawJson)
    }

    fun parse(originalText: String, rawJson: String): List<FuriganaAnnotation> {
        val array = extractAnnotationArray(rawJson)
        val candidates = mutableListOf<FuriganaAnnotation>()

        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val surface = optString(item, "surface", "s").trim()
            val reading = optString(item, "reading", "r").trim()
            val confidence = optDouble(item, "confidence", "c", 0.5).coerceIn(0.0, 1.0)
            if (surface.isBlank() || reading.isBlank()) continue
            if (!isAnnotatableSurface(surface)) continue
            if (!isValidReading(reading)) continue

            val indexed = readIndexedAnnotation(originalText, item, surface, reading, confidence)
            if (indexed != null) {
                candidates.add(indexed)
                continue
            }

            val fallback = findFallbackAnnotation(originalText, surface, reading, confidence, candidates)
            if (fallback != null) candidates.add(fallback)
        }

        return resolveOverlaps(candidates)
    }

    fun parseSelectionReading(rawJson: String): String {
        val trimmed = stripMarkdownFence(rawJson)
        val reading = if (trimmed.startsWith("{")) {
            JSONObject(extractJsonObject(trimmed)).optString("reading").trim()
        } else {
            trimmed.trim().trim('"')
        }
        if (reading.isBlank()) throw JSONException("reading 为空")
        if (!isValidReading(reading)) throw JSONException("reading 包含非法字符")
        return reading
    }

    private fun extractAnnotationArray(rawJson: String): JSONArray {
        val trimmed = stripMarkdownFence(rawJson)
        if (trimmed.startsWith("[")) {
            return JSONArray(trimmed)
        }

        val json = JSONObject(extractJsonObject(trimmed))
        return when {
            json.has("a") -> json.getJSONArray("a")
            json.has("annotations") -> json.getJSONArray("annotations")
            json.has("result") && json.opt("result") is JSONObject -> {
                val result = json.getJSONObject("result")
                when {
                    result.has("a") -> result.getJSONArray("a")
                    result.has("annotations") -> result.getJSONArray("annotations")
                    else -> JSONArray()
                }
            }
            else -> JSONArray()
        }
    }

    private fun parseCandidateArrayItem(item: JSONArray): CandidateReading? {
        val id = item.optInt(0, -1)
        val reading = item.optString(1, "").trim()
        val confidence = item.optDouble(2, 0.5).coerceIn(0.0, 1.0)
        if (id < 0 || reading.isBlank()) return null
        return CandidateReading(id, reading, confidence)
    }

    private fun parseCandidateObjectItem(item: JSONObject): CandidateReading? {
        val id = when {
            item.has("id") -> item.optInt("id", -1)
            item.has("i") -> item.optInt("i", -1)
            else -> return null
        }
        val reading = optString(item, "reading", "r").trim()
        val confidence = optDouble(item, "confidence", "c", 0.5).coerceIn(0.0, 1.0)
        if (id < 0 || reading.isBlank()) return null
        return CandidateReading(id, reading, confidence)
    }

    private fun readIndexedAnnotation(
        originalText: String,
        item: JSONObject,
        surface: String,
        reading: String,
        confidence: Double
    ): FuriganaAnnotation? {
        if (!item.has("start") && !item.has("b")) return null
        if (!item.has("end") && !item.has("e")) return null
        val start = optInt(item, "start", "b", -1)
        val end = optInt(item, "end", "e", -1)
        if (start < 0 || end <= start || end > originalText.length) return null
        if (originalText.substring(start, end) != surface) return null
        val adjustedReading = contextAdjustedReading(originalText, surface, start, reading)
        if (!isReadingPlausibleForSurface(surface, adjustedReading)) return null
        if (!isReadingContextPlausible(originalText, surface, start, end, adjustedReading)) return null
        return FuriganaAnnotation(
            surface = surface,
            reading = adjustedReading,
            start = start,
            end = end,
            confidence = confidence
        )
    }

    private fun findFallbackAnnotation(
        originalText: String,
        surface: String,
        reading: String,
        confidence: Double,
        existing: List<FuriganaAnnotation>
    ): FuriganaAnnotation? {
        var fromIndex = 0
        while (fromIndex < originalText.length) {
            val start = originalText.indexOf(surface, fromIndex)
            if (start < 0) return null
            val end = start + surface.length
            val overlaps = existing.any { rangesOverlap(start, end, it.start, it.end) }
            if (!overlaps) {
                val adjustedReading = contextAdjustedReading(originalText, surface, start, reading)
                if (!isReadingPlausibleForSurface(surface, adjustedReading)) return null
                if (!isReadingContextPlausible(originalText, surface, start, end, adjustedReading)) return null
                return FuriganaAnnotation(
                    surface = surface,
                    reading = adjustedReading,
                    start = start,
                    end = end,
                    confidence = confidence
                )
            }
            fromIndex = start + max(surface.length, 1)
        }
        return null
    }

    private fun resolveOverlaps(candidates: List<FuriganaAnnotation>): List<FuriganaAnnotation> {
        val selected = mutableListOf<FuriganaAnnotation>()
        candidates.sortedWith(compareBy<FuriganaAnnotation> { it.start }.thenByDescending { it.confidence })
            .forEach { candidate ->
                val overlapping = selected.filter {
                    rangesOverlap(candidate.start, candidate.end, it.start, it.end)
                }
                if (overlapping.isEmpty()) {
                    selected.add(candidate)
                    return@forEach
                }
                if (overlapping.all { candidate.confidence > it.confidence }) {
                    selected.removeAll(overlapping.toSet())
                    selected.add(candidate)
                }
            }
        return selected.sortedBy { it.start }
    }

    private fun rangesOverlap(startA: Int, endA: Int, startB: Int, endB: Int): Boolean {
        return max(startA, startB) < min(endA, endB)
    }

    private fun isValidReading(reading: String): Boolean {
        return reading.all { char ->
            char in '\u3040'..'\u309F' || char == 'ー' || char == '・' || char == '･'
        }
    }

    private fun isAnnotatableSurface(surface: String): Boolean {
        return JapaneseTextDetector.containsKanji(surface) || surface.any { isDigitLike(it) }
    }

    private fun isReadingPlausibleForSurface(surface: String, reading: String): Boolean {
        if (surface.any { isDigitLike(it) }) return true
        if (surface.count { isKanji(it) } == 1 && reading.length > MAX_SINGLE_KANJI_READING_LENGTH) {
            return false
        }
        val maxReadingLength = max(MIN_READING_LENGTH_LIMIT, surface.length * MAX_READING_LENGTH_PER_SOURCE_CHAR)
        return reading.length <= maxReadingLength
    }

    private fun isReadingContextPlausible(
        originalText: String,
        surface: String,
        start: Int,
        end: Int,
        reading: String
    ): Boolean {
        if (FuriganaSurfaceBoundary.hasHardInternalBoundary(originalText, start, end)) return false
        if (surface.any { isDigitLike(it) }) return true
        if (surface.count { isKanji(it) } == 1 && reading.length > MAX_SINGLE_KANJI_READING_LENGTH) {
            return false
        }
        val following = originalText.substring(end.coerceIn(0, originalText.length))
        val kanjiCount = surface.count { isKanji(it) }
        if (kanjiCount == 1) {
            VERB_PHRASE_SUFFIXES.forEach { suffix ->
                if (reading.endsWith(suffix) && !following.startsWith(suffix)) {
                    return false
                }
            }
        } else if (kanjiCount > 1) {
            // 多汉字词只查最不可能出现在名词读音结尾的动词后缀，
            // 且去掉后缀后剩余读音仍需覆盖每个汉字（案内/靴下/虹鱒 这类词不受影响）。
            MULTI_KANJI_VERB_SUFFIXES.forEach { suffix ->
                if (reading.length > suffix.length &&
                    reading.endsWith(suffix) &&
                    !following.startsWith(suffix) &&
                    reading.length - suffix.length >= kanjiCount
                ) {
                    return false
                }
            }
        }
        return true
    }

    private fun contextAdjustedReading(
        originalText: String,
        candidate: FuriganaCandidate,
        modelReading: String
    ): String {
        JapaneseNumberReading.readNumericExpression(candidate.surface)?.let { return it }
        val previous = previousNonWhitespaceChar(originalText, candidate.start)
        return when {
            candidate.surface == "人気" && isHitokeContext(originalText, candidate.end) -> "ひとけ"
            candidate.surface == "月" && previous != null && isDigitLike(previous) -> "がつ"
            candidate.surface == "年" && previous != null && isDigitLike(previous) -> "ねん"
            candidate.surface == "日" -> previousNumberValueBefore(originalText, candidate.start)
                ?.let { dateDayReading(it) }
                ?: modelReading
            else -> modelReading
        }
    }

    private fun contextAdjustedReading(
        originalText: String,
        surface: String,
        start: Int,
        modelReading: String
    ): String {
        JapaneseNumberReading.readNumericExpression(surface)?.let { return it }
        val previous = previousNonWhitespaceChar(originalText, start)
        val end = start + surface.length
        return when {
            surface == "人気" && isHitokeContext(originalText, end) -> "ひとけ"
            surface == "月" && previous != null && isDigitLike(previous) -> "がつ"
            surface == "年" && previous != null && isDigitLike(previous) -> "ねん"
            surface == "日" -> previousNumberValueBefore(originalText, start)
                ?.let { dateDayReading(it) }
                ?: modelReading
            else -> modelReading
        }
    }

    private fun isHitokeContext(text: String, end: Int): Boolean {
        if (end < 0 || end > text.length) return false
        return listOf(
            "がない",
            "が無い",
            "のない",
            "の無い",
            "ない",
            "無い"
        ).any { suffix -> text.startsWith(suffix, end) }
    }

    private fun previousNonWhitespaceChar(text: String, index: Int): Char? {
        var cursor = index - 1
        while (cursor >= 0) {
            val char = text[cursor]
            if (!char.isWhitespace()) return char
            cursor--
        }
        return null
    }

    private fun isDigitLike(char: Char): Boolean {
        return char in '0'..'9' || char in '０'..'９'
    }

    private fun isKanji(char: Char): Boolean {
        return char in '\u4E00'..'\u9FFF' || char in '\u3400'..'\u4DBF'
    }

    private fun isKana(char: Char): Boolean {
        return char in '\u3040'..'\u30FF' || char in '\u31F0'..'\u31FF'
    }

    private fun previousNumberValueBefore(text: String, index: Int): Int? {
        var end = index
        while (end > 0 && text[end - 1].isWhitespace()) {
            end--
        }
        var start = end
        while (start > 0 && isDigitLike(text[start - 1])) {
            start--
        }
        if (start == end) return null
        return normalizeDigits(text.substring(start, end)).toIntOrNull()
    }

    private fun normalizeDigits(value: String): String {
        return buildString {
            value.forEach { char ->
                append(
                    when (char) {
                        in '0'..'9' -> char
                        in '０'..'９' -> ('0'.code + (char.code - '０'.code)).toChar()
                        else -> return@forEach
                    }
                )
            }
        }
    }

    private fun dateDayReading(value: Int): String? {
        return when (value) {
            1 -> "ついたち"
            2 -> "ふつか"
            3 -> "みっか"
            4 -> "よっか"
            5 -> "いつか"
            6 -> "むいか"
            7 -> "なのか"
            8 -> "ようか"
            9 -> "ここのか"
            10 -> "とおか"
            11 -> "じゅういちにち"
            12 -> "じゅうににち"
            13 -> "じゅうさんにち"
            14 -> "じゅうよっか"
            15 -> "じゅうごにち"
            16 -> "じゅうろくにち"
            17 -> "じゅうしちにち"
            18 -> "じゅうはちにち"
            19 -> "じゅうくにち"
            20 -> "はつか"
            21 -> "にじゅういちにち"
            22 -> "にじゅうににち"
            23 -> "にじゅうさんにち"
            24 -> "にじゅうよっか"
            25 -> "にじゅうごにち"
            26 -> "にじゅうろくにち"
            27 -> "にじゅうしちにち"
            28 -> "にじゅうはちにち"
            29 -> "にじゅうくにち"
            30 -> "さんじゅうにち"
            31 -> "さんじゅういちにち"
            else -> null
        }
    }

    private fun optString(json: JSONObject, longKey: String, shortKey: String): String {
        return if (json.has(shortKey)) json.optString(shortKey, "") else json.optString(longKey, "")
    }

    private fun optInt(json: JSONObject, longKey: String, shortKey: String, defaultValue: Int): Int {
        return if (json.has(shortKey)) json.optInt(shortKey, defaultValue) else json.optInt(longKey, defaultValue)
    }

    private fun optDouble(
        json: JSONObject,
        longKey: String,
        shortKey: String,
        defaultValue: Double
    ): Double {
        return if (json.has(shortKey)) {
            json.optDouble(shortKey, defaultValue)
        } else {
            json.optDouble(longKey, defaultValue)
        }
    }

    private fun extractJsonObject(raw: String): String {
        val withoutFence = stripMarkdownFence(raw)
        val start = withoutFence.indexOf('{')
        val end = withoutFence.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return withoutFence.substring(start, end + 1)
        }
        return withoutFence
    }

    private fun stripMarkdownFence(raw: String): String {
        return raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }

    private data class CandidateReading(
        val id: Int,
        val reading: String,
        val confidence: Double
    )

    private const val MIN_READING_LENGTH_LIMIT = 6
    private const val MAX_READING_LENGTH_PER_SOURCE_CHAR = 3
    private const val MAX_SINGLE_KANJI_READING_LENGTH = 4
    private val VERB_PHRASE_SUFFIXES = listOf(
        "して",
        "した",
        "する",
        "いた",
        "いて",
        "った",
        "って",
        "ない",
        "ます"
    )
    private val MULTI_KANJI_VERB_SUFFIXES = listOf(
        "して",
        "する"
    )
}
