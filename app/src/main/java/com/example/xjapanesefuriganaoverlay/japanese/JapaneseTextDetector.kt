package com.example.xjapanesefuriganaoverlay.japanese

object JapaneseTextDetector {
    private val whitespaceRegex = Regex("\\s+")
    private val pureCounterRegex = Regex("^[0-9０-９,.万億]+$")
    private val unitCounterRegex = Regex("^[0-9０-９,.万億]+\\s*(件|回|人)$")
    private val uiExactTexts = setOf(
        "返信",
        "リポスト",
        "いいね",
        "表示",
        "おすすめ",
        "フォロー",
        "フォロー中",
        "プロフィール",
        "通知",
        "メッセージ",
        "検索",
        "ホーム",
        "ブックマーク",
        "共有",
        "ポスト",
        "投稿",
        "さらに表示",
        "もっと見る"
    )

    fun isLikelyJapanesePost(text: String): Boolean {
        val normalized = normalize(text)
        if (normalized.length < 3) return false
        if (isObviousUiText(normalized)) return false
        if (normalized.startsWith("@")) return false
        if (normalized.startsWith("#") && normalized.length <= 20) return false

        var hasKana = false
        var hasKanji = false
        var meaningfulChars = 0
        normalized.forEach { char ->
            when {
                isHiragana(char) || isKatakana(char) -> {
                    hasKana = true
                    meaningfulChars++
                }
                isKanji(char) -> {
                    hasKanji = true
                    meaningfulChars++
                }
            }
        }
        if (!hasKana || !hasKanji) return false

        return meaningfulChars >= 3
    }

    fun containsKanji(text: String): Boolean = text.any { isKanji(it) }

    fun containsKana(text: String): Boolean = text.any { isHiragana(it) || isKatakana(it) }

    fun isObviousUiText(text: String): Boolean {
        val normalized = normalize(text)
        if (normalized in uiExactTexts) return true
        if (isLikelyUiCounter(normalized)) return true
        return uiExactTexts.any { ui ->
            normalized.length <= ui.length + 6 && normalized.contains(ui)
        }
    }

    private fun normalize(text: String): String {
        return whitespaceRegex.replace(text, " ").trim()
    }

    private fun isLikelyUiCounter(text: String): Boolean {
        if (pureCounterRegex.matches(text)) return true
        if (unitCounterRegex.matches(text)) return true
        return false
    }

    private fun isHiragana(char: Char): Boolean = char in '\u3040'..'\u309F'

    private fun isKatakana(char: Char): Boolean =
        char in '\u30A0'..'\u30FF' || char in '\u31F0'..'\u31FF'

    private fun isKanji(char: Char): Boolean =
        char in '\u4E00'..'\u9FFF' || char in '\u3400'..'\u4DBF'
}
