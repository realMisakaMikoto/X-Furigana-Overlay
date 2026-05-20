package com.example.xjapanesefuriganaoverlay.japanese

object JapaneseTextDetector {
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
        if (normalized in uiExactTexts) return false
        if (isLikelyUiCounter(normalized)) return false
        if (normalized.startsWith("@")) return false
        if (normalized.startsWith("#") && normalized.length <= 20) return false

        val hasKana = normalized.any { isHiragana(it) || isKatakana(it) }
        val hasKanji = normalized.any { isKanji(it) }
        if (!hasKana || !hasKanji) return false

        val meaningfulChars = normalized.count { isHiragana(it) || isKatakana(it) || isKanji(it) }
        return meaningfulChars >= 3
    }

    fun containsKanji(text: String): Boolean = text.any { isKanji(it) }

    private fun normalize(text: String): String {
        return text.replace(Regex("\\s+"), " ").trim()
    }

    private fun isLikelyUiCounter(text: String): Boolean {
        if (text.matches(Regex("^[0-9０-９,.万億]+$"))) return true
        if (text.matches(Regex("^[0-9０-９,.万億]+\\s*(件|回|人)$"))) return true
        return uiExactTexts.any { ui ->
            text.length <= ui.length + 6 && text.contains(ui)
        }
    }

    private fun isHiragana(char: Char): Boolean = char in '\u3040'..'\u309F'

    private fun isKatakana(char: Char): Boolean =
        char in '\u30A0'..'\u30FF' || char in '\u31F0'..'\u31FF'

    private fun isKanji(char: Char): Boolean =
        char in '\u4E00'..'\u9FFF' || char in '\u3400'..'\u4DBF'
}
