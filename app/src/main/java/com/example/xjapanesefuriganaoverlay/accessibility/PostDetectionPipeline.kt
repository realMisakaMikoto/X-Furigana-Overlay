package com.example.xjapanesefuriganaoverlay.accessibility

import com.example.xjapanesefuriganaoverlay.data.DetectedPost
import com.example.xjapanesefuriganaoverlay.japanese.JapaneseTextDetector
import com.example.xjapanesefuriganaoverlay.util.TextHash
import kotlin.math.abs
import kotlin.math.min

data class PostDetectionResult(
    val posts: List<DetectedPost>,
    val postsHash: String,
    val durationMs: Long
)

object PostDetectionPipeline {
    private val urlRegex = Regex("""https?://|www\.""", RegexOption.IGNORE_CASE)
    private val pureNumberRegex = Regex("""^[0-9０-９\s,.\uFF0C\uFF0E万億]+$""")
    private val handleRegex = Regex("""^@[\w_]{1,20}$""")
    private val whitespaceRegex = Regex("\\s+")
    private val uiFragments = listOf(
        "返信",
        "リポスト",
        "いいね",
        "表示",
        "おすすめ",
        "フォロー",
        "プロフィール",
        "通知",
        "メッセージ",
        "検索",
        "ホーム"
    )

    fun detect(snapshot: ScreenTextSnapshot, limit: Int): PostDetectionResult {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val bestByText = LinkedHashMap<String, ScoredPost>()
        snapshot.rawTexts.forEach { raw ->
            val text = raw.text
            if (isCheapReject(text)) return@forEach
            if (!JapaneseTextDetector.containsKana(text)) return@forEach
            if (!JapaneseTextDetector.containsKanji(text)) return@forEach
            if (!JapaneseTextDetector.isLikelyJapanesePost(text)) return@forEach

            val post = DetectedPost(
                id = TextHash.sha256Short("${raw.packageName}\n$text\n${raw.bounds}"),
                text = text,
                boundsInScreen = raw.bounds,
                packageName = raw.packageName
            )
            val scored = ScoredPost(
                post = post,
                score = scorePost(post, snapshot.screenBounds.height())
            )
            val key = normalizeForDedup(text)
            val existing = bestByText[key]
            if (existing == null || scored.score > existing.score) {
                bestByText[key] = scored
            }
        }

        val selected = ArrayList<DetectedPost>(limit)
        bestByText.values
            .sortedWith(
                compareByDescending<ScoredPost> { it.score }
                    .thenBy { it.post.boundsInScreen?.top ?: Int.MAX_VALUE }
            )
            .forEach { scored ->
                if (selected.size >= limit) return@forEach
                if (!isRedundantShortText(scored.post, selected)) {
                    selected.add(scored.post)
                }
            }

        val postsHash = TextHash.sha256Short(
            selected.joinToString("\n") { "${it.packageName}\t${it.text}" }
        )
        return PostDetectionResult(
            posts = selected,
            postsHash = postsHash,
            durationMs = android.os.SystemClock.elapsedRealtime() - startedAt
        )
    }

    private fun isCheapReject(text: String): Boolean {
        if (text.length < 3) return true
        if (text.startsWith("@") && handleRegex.matches(text)) return true
        if (text.startsWith("#") && text.length <= 20) return true
        if (pureNumberRegex.matches(text)) return true
        if (urlRegex.containsMatchIn(text)) return true
        if (JapaneseTextDetector.isObviousUiText(text)) return true
        if (text.length <= 12 && uiFragments.any { text.contains(it) }) return true
        return false
    }

    private fun normalizeForDedup(text: String): String {
        return whitespaceRegex.replace(text, " ").trim()
    }

    private fun scorePost(post: DetectedPost, screenHeight: Int): Int {
        val text = post.text
        val lengthScore = min(text.length, 160)
        val mixedJapaneseScore = 40
        val centerScore = post.boundsInScreen?.let { bounds ->
            if (screenHeight <= 0) return@let 0
            val centerY = bounds.centerY()
            val distance = abs(centerY - screenHeight / 2)
            ((1f - min(1f, distance.toFloat() / screenHeight)) * 30).toInt()
        } ?: 0
        val tooShortPenalty = if (text.length < 12) 25 else 0
        val uiPenalty = uiFragments.count { text.contains(it) } * 15
        return lengthScore + mixedJapaneseScore + centerScore - tooShortPenalty - uiPenalty
    }

    private fun isRedundantShortText(post: DetectedPost, selected: List<DetectedPost>): Boolean {
        if (post.text.length >= 40) return false
        return selected.any { existing ->
            existing.text.length > post.text.length + 10 && existing.text.contains(post.text)
        }
    }

    private data class ScoredPost(
        val post: DetectedPost,
        val score: Int
    )
}
