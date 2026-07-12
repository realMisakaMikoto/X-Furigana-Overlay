package com.sosdanfurigana.data

import java.text.Normalizer

object ReadingAnswerNormalizer {
    private const val KATAKANA_TO_HIRAGANA_OFFSET = 0x60
    private val ignoredSeparators = setOf(
        ' ', '\t', '\n', '\r', '　',
        '・', '･', '。', '、', '.', ',',
        '「', '」', '『', '』', '（', '）', '(', ')',
        '［', '］', '[', ']', '【', '】'
    )

    fun normalize(value: String): String {
        val widthNormalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
        return buildString(widthNormalized.length) {
            widthNormalized.forEach { char ->
                when {
                    char in ignoredSeparators || char.isWhitespace() -> Unit
                    char in '\u30A1'..'\u30F6' || char in '\u30FD'..'\u30FE' -> {
                        append((char.code - KATAKANA_TO_HIRAGANA_OFFSET).toChar())
                    }
                    else -> append(char)
                }
            }
        }
    }

    fun isAccepted(
        answer: String,
        savedReading: String,
        acceptedReadings: List<String>
    ): Boolean {
        val normalizedAnswer = normalize(answer)
        if (normalizedAnswer.isBlank()) return false
        return sequenceOf(savedReading)
            .plus(acceptedReadings.asSequence())
            .map(::normalize)
            .filter { it.isNotBlank() }
            .any { it == normalizedAnswer }
    }
}

object ReadingReviewPolicy {
    const val MAX_FAILED_ATTEMPTS = 2
    const val MATURE_STREAK = 3

    fun ratingForLocalMatch(
        streak: Int,
        failedAttempts: Int
    ): ReviewScheduler.Rating {
        return when {
            failedAttempts > 0 -> ReviewScheduler.Rating.HARD
            streak >= MATURE_STREAK -> ReviewScheduler.Rating.EASY
            else -> ReviewScheduler.Rating.GOOD
        }
    }

    fun ratingForModelMatch(): ReviewScheduler.Rating = ReviewScheduler.Rating.HARD

    fun hasReachedFailureLimit(failedAttempts: Int): Boolean {
        return failedAttempts >= MAX_FAILED_ATTEMPTS
    }
}

data class ReviewTargetRange(
    val start: Int,
    val endExclusive: Int
)

object ReviewSentenceMatcher {
    fun targetRanges(sourceText: String, target: String): List<ReviewTargetRange> {
        if (sourceText.isBlank() || target.isBlank()) return emptyList()
        return buildList {
            var start = sourceText.indexOf(target)
            while (start >= 0) {
                add(ReviewTargetRange(start, start + target.length))
                start = sourceText.indexOf(target, start + target.length)
            }
        }
    }
}
