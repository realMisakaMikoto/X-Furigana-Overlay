package com.example.xjapanesefuriganaoverlay.furigana

internal object FuriganaSurfaceBoundary {
    fun splitHardKanjiRun(text: String, start: Int, end: Int): Int? {
        if (end - start <= 1) return null
        findTemporalAdverbialBoundary(text, start, end)?.let { return it }
        findKanjiNumberUnitBoundary(text, start, end)?.let { return it }
        findTrailingVerbBoundary(text, start, end)?.let { return it }
        return null
    }

    fun hasHardInternalBoundary(surface: String): Boolean {
        return hasHardInternalBoundary(surface, 0, surface.length)
    }

    fun hasHardInternalBoundary(text: String, start: Int, end: Int): Boolean {
        if (start < 0 || end > text.length || start >= end) return false
        var index = start
        while (index < end) {
            if (!isKanji(text[index])) {
                index++
                continue
            }

            val runStart = index
            while (index < end && isKanji(text[index])) {
                index++
            }
            if (splitHardKanjiRun(text, runStart, index) != null) return true
        }
        return false
    }

    private fun findTemporalAdverbialBoundary(text: String, start: Int, end: Int): Int? {
        val maxLength = end - start
        val prefix = TEMPORAL_ADVERBIAL_PREFIXES.firstOrNull { prefix ->
            prefix.length < maxLength && text.startsWith(prefix, start)
        } ?: return null
        val split = start + prefix.length
        val remainingKanji = end - split
        return when {
            remainingKanji >= 2 -> split
            findTrailingVerbBoundary(text, start, end) == split -> split
            else -> null
        }
    }

    private fun findKanjiNumberUnitBoundary(text: String, start: Int, end: Int): Int? {
        var cursor = start
        while (cursor < end && text[cursor] in KANJI_NUMERAL_CHARS) {
            cursor++
        }
        if (cursor == start || cursor >= end) return null
        if (text[cursor] !in KANJI_NUMBER_SPLIT_UNITS) return null
        val split = cursor + 1
        if (split >= end) return null
        val remainingKanji = end - split
        return when {
            remainingKanji >= 2 -> split
            findTrailingVerbBoundary(text, start, end) == split -> split
            else -> null
        }
    }

    private fun findTrailingVerbBoundary(text: String, start: Int, end: Int): Int? {
        if (end - start < 2 || end >= text.length || !isHiragana(text[end])) return null
        val split = end - 1
        val prefix = text.substring(start, split)
        if (!isLikelyStandalonePrefixBeforeVerb(prefix)) return null
        if (text.substring(start, end) in PROTECTED_MIXED_STEMS) return null

        val kanaRun = hiraganaRun(text, end)
        val lastKanji = text[split]
        val okurigana = TRAILING_VERB_OKURIGANA[lastKanji] ?: return null
        return if (okurigana.any { kanaRun.startsWith(it) }) split else null
    }

    private fun hiraganaRun(text: String, start: Int): String {
        var cursor = start
        while (cursor < text.length && isHiragana(text[cursor])) {
            cursor++
        }
        return text.substring(start, cursor)
    }

    private fun isLikelyStandalonePrefixBeforeVerb(prefix: String): Boolean {
        if (prefix.length >= 2) return true
        val only = prefix.singleOrNull() ?: return false
        return only in SINGLE_KANJI_STANDALONE_PREFIXES
    }

    private fun isKanji(char: Char): Boolean {
        return char in '\u4E00'..'\u9FFF' || char in '\u3400'..'\u4DBF'
    }

    private fun isHiragana(char: Char): Boolean {
        return char in '\u3041'..'\u3096' || char in '\u309D'..'\u309E'
    }

    private val TEMPORAL_ADVERBIAL_PREFIXES = listOf(
        "一昨日",
        "明後日",
        "昨日",
        "今日",
        "明日",
        "今朝",
        "今夜",
        "今晩",
        "先日",
        "後日",
        "当日",
        "毎日",
        "毎朝",
        "毎晩",
        "今年",
        "去年",
        "来年",
        "先週",
        "今週",
        "来週",
        "先月",
        "今月",
        "来月"
    )
    private const val KANJI_NUMERAL_CHARS = "零〇一二三四五六七八九十百千万億兆"
    private const val KANJI_NUMBER_SPLIT_UNITS = "度回人個枚本話巻号歳才円"
    private const val SINGLE_KANJI_STANDALONE_PREFIXES = "昔本歌話字文絵飯水酒茶店駅村町国山川海道人犬猫"
    private val PROTECTED_MIXED_STEMS = setOf("長持", "大好")
    private val TRAILING_VERB_OKURIGANA = mapOf(
        '見' to listOf("た", "て", "る", "ない", "ます", "まし"),
        '読' to listOf("ん", "み", "ま", "め"),
        '聞' to listOf("い", "か", "こ"),
        '書' to listOf("い", "き", "か", "け"),
        '行' to listOf("っ", "き", "く", "か"),
        '来' to listOf("た", "て", "る", "ない", "ます"),
        '食' to listOf("べ"),
        '飲' to listOf("ん", "ま", "め"),
        '歩' to listOf("い", "か", "け"),
        '立' to listOf("と", "っ", "ち", "て"),
        '入' to listOf("っ", "り", "る", "れ"),
        '出' to listOf("し", "た", "て", "る", "な"),
        '探' to listOf("し", "さ", "せ"),
        '撮' to listOf("っ", "り", "る", "れ"),
        '解' to listOf("い", "か", "け"),
        '変' to listOf("え", "わ"),
        '始' to listOf("め", "ま"),
        '終' to listOf("え", "わ"),
        '思' to listOf("い", "っ", "わ"),
        '知' to listOf("っ", "り", "る", "ら"),
        '使' to listOf("っ", "い", "う", "わ"),
        '作' to listOf("っ", "り", "る", "ら"),
        '買' to listOf("っ", "い", "う", "わ"),
        '売' to listOf("っ", "り", "る", "ら"),
        '話' to listOf("し", "す", "さ", "せ"),
        '寝' to listOf("た", "て", "る", "な", "ま")
    )
}
