package com.example.xjapanesefuriganaoverlay.furigana

object FuriganaPromptBuilder {
    fun systemPrompt(): String {
        return """
            日语furigana引擎。根据原文语境，为候选片段标平假名读音；不翻译、不解释、不改写。读音只对应候选surface，不要包含候选外的文字。多音词、熟字训、地名、人名、网络语按语境判断。
            候选可能包含整词和单个汉字。单个汉字也必须按它在原文词语里的实际读音输出，例如“発売”里的“発”读“はつ”、“売”读“ばい”。日期/数量后缀必须看完整原文判断，例如“9月”的候选surface为“月”时读“がつ”，不是“げつ”；“1日”的候选surface为“日”时读“ついたち”。
            候选也可能是数字表达式，例如“2025年”“7月1日”“12時30分”。必须结合上下文输出自然日语读法。
            对于含送り仮名的词，优先为完整词候选标注读音。如果候选中同时存在“長持ち”和“長持”，优先输出“長持ち”。避免输出“長持 -> ながもち”这类surface不含送り仮名但reading包含送り仮名读音的结果。如果只能标注纯汉字部分，则reading不要包含候选外的送り仮名读音。
            只输出紧凑JSON：{"a":[[0,"ひょうき",0.95],[1,"みち",0.9]]}
            每项为[id, reading, confidence]。id必须来自候选；reading必须全平假名。尽量为每个候选输出一项，不确定也选现代日语最自然读法。没有候选则{"a":[]}。
        """.trimIndent()
    }

    fun selectionReadingSystemPrompt(): String {
        return """
            你是日语读音识别引擎。请根据完整原文上下文，为用户选中的片段输出整段平假名读音。
            规则：
            1. 只输出选中片段的读音，不解释，不翻译，不改写。
            2. 汉字、平假名、片假名、数字都要合并成一个自然读音。
            3. 片假名要转成平假名。
            4. 数字必须结合上下文判断读法，特别注意日期、年份、时间、数量。例如：2025年=にせんにじゅうごねん，7月1日=しちがつついたち，12時30分=じゅうにじさんじゅっぷん。
            5. 输出严格JSON：{"reading":"..."}，reading 只能包含平假名、长音符和必要的中点。
        """.trimIndent()
    }

    fun selectionReadingUserPrompt(
        sourceText: String,
        selectedText: String,
        start: Int,
        end: Int
    ): String {
        return """
            原文:
            $sourceText

            选区UTF-16范围: $start-$end
            选中文本:
            $selectedText
        """.trimIndent()
    }

    fun userPrompt(text: String): String {
        return userPrompt(text, annotationCandidates(text))
    }

    fun userPrompt(text: String, candidates: List<FuriganaCandidate>): String {
        return buildString {
            append("原文:\n")
            append(text)
            append("\n\n候选(id:start-end:surface):\n")
            if (candidates.isEmpty()) {
                append("none")
            } else {
                candidates.forEach { candidate ->
                    append(candidate.id)
                    append(':')
                    append(candidate.start)
                    append('-')
                    append(candidate.end)
                    append(':')
                    append(candidate.surface)
                    append('\n')
                }
            }
        }
    }

    fun annotationCandidates(text: String): List<FuriganaCandidate> {
        val candidates = mutableListOf<FuriganaCandidate>()
        var index = 0
        while (index < text.length) {
            if (isDigitLike(text[index])) {
                addNumberCandidates(text, index, candidates)
                index = digitRunEnd(text, index)
                continue
            }
            if (!isKanji(text[index])) {
                index++
                continue
            }
            val start = index
            while (index < text.length && isKanji(text[index])) {
                index++
            }
            addRunCandidates(text, start, index, candidates)
        }
        return candidates
    }

    private fun addNumberCandidates(
        text: String,
        start: Int,
        candidates: MutableList<FuriganaCandidate>
    ) {
        var end = digitRunEnd(text, start)
        addCandidate(candidates, text, start, end)

        var cursor = end
        var units = 0
        while (cursor < text.length && text[cursor] in NUMERIC_SUFFIX_KANJI && units < MAX_NUMERIC_UNITS) {
            cursor++
            if (cursor < text.length && isDigitLike(text[cursor])) {
                cursor = digitRunEnd(text, cursor)
            }
            addCandidate(candidates, text, start, cursor)
            units++
        }
    }

    private fun addRunCandidates(
        text: String,
        start: Int,
        end: Int,
        candidates: MutableList<FuriganaCandidate>
    ) {
        var cursor = start
        val previous = previousNonWhitespaceChar(text, start)
        if (
            cursor < end &&
            previous != null &&
            isDigitLike(previous) &&
            text[cursor] in NUMERIC_SUFFIX_KANJI
        ) {
            addCandidate(candidates, text, cursor, cursor + 1)
            cursor++
        }

        val fullWordEnd = extendMixedKanjiWord(text, cursor, end)
        if (fullWordEnd > end) {
            addCandidate(candidates, text, cursor, fullWordEnd)
        }

        val remaining = end - cursor
        when {
            remaining <= 0 -> return
            remaining <= 3 -> addCandidate(candidates, text, cursor, end)
            else -> {
                var local = cursor
                while (local < end) {
                    val left = end - local
                    val size = when {
                        left <= 3 -> left
                        left % 2 == 0 -> 2
                        else -> 3
                    }
                    addCandidate(candidates, text, local, local + size)
                    local += size
                }
            }
        }
        for (singleIndex in start until end) {
            addCandidate(candidates, text, singleIndex, singleIndex + 1)
        }
    }

    private fun extendMixedKanjiWord(text: String, kanjiStart: Int, kanjiEnd: Int): Int {
        if (kanjiStart < 0 || kanjiEnd <= kanjiStart || kanjiEnd > text.length) {
            return kanjiEnd
        }

        var cursor = kanjiEnd
        while (cursor < text.length) {
            val kanaStart = cursor
            while (cursor < text.length && isHiragana(text[cursor])) {
                cursor++
            }
            if (kanaStart == cursor) return cursor

            if (cursor < text.length && isKanji(text[cursor])) {
                while (cursor < text.length && isKanji(text[cursor])) {
                    cursor++
                }
                continue
            }

            val okuriganaEnd = extendWithOkurigana(text, kanaStart, cursor)
            return okuriganaEnd
        }
        return cursor
    }

    private fun extendWithOkurigana(text: String, kanaStart: Int, kanaEnd: Int): Int {
        if (kanaStart >= kanaEnd) return kanaStart
        val kanaRun = text.substring(kanaStart, kanaEnd)
        if (kanaRun.startsWith("する") || kanaRun.startsWith("です")) return kanaStart

        OKURIGANA_SUFFIXES
            .firstOrNull { kanaRun.startsWith(it) }
            ?.let { suffix -> return kanaStart + suffix.length }

        val first = kanaRun.first()
        return if (first in SINGLE_OKURIGANA) kanaStart + 1 else kanaStart
    }

    private fun addCandidate(
        candidates: MutableList<FuriganaCandidate>,
        text: String,
        start: Int,
        end: Int
    ) {
        if (start < 0 || end <= start || end > text.length) return
        if (candidates.any { it.start == start && it.end == end }) return
        candidates.add(
            FuriganaCandidate(
                id = candidates.size,
                surface = text.substring(start, end),
                start = start,
                end = end
            )
        )
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

    private fun isKanji(char: Char): Boolean {
        return char in '\u4E00'..'\u9FFF' || char in '\u3400'..'\u4DBF'
    }

    private fun isHiragana(char: Char): Boolean {
        return char in '\u3041'..'\u3096' || char in '\u309D'..'\u309E'
    }

    private fun isDigitLike(char: Char): Boolean {
        return char in '0'..'9' || char in '０'..'９'
    }

    private fun digitRunEnd(text: String, start: Int): Int {
        var cursor = start
        while (cursor < text.length && isDigitLike(text[cursor])) {
            cursor++
        }
        return cursor
    }

    private const val NUMERIC_SUFFIX_KANJI = "年月日時分秒円歳才人個枚本回話巻号度"
    private const val MAX_NUMERIC_UNITS = 4
    private const val SINGLE_OKURIGANA = "ちりみきぎしむびにいたてでぐげす"
    private val OKURIGANA_SUFFIXES = listOf(
        "させる",
        "られる",
        "れる",
        "せる",
        "ない",
        "べる",
        "める",
        "ける",
        "げる",
        "える",
        "える",
        "いる",
        "ある",
        "する",
        "した",
        "して",
        "ます",
        "た",
        "て",
        "る",
        "す"
    )
}
