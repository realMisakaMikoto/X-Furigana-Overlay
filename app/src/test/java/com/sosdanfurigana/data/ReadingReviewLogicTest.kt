package com.sosdanfurigana.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingReviewLogicTest {
    @Test
    fun `normalizer unifies kana width and spacing`() {
        assertEquals("みた", ReadingAnswerNormalizer.normalize("  ﾐﾀ  "))
        assertEquals("きょう", ReadingAnswerNormalizer.normalize("キョウ"))
        assertEquals("がっこう", ReadingAnswerNormalizer.normalize("がっ・こう。"))
    }

    @Test
    fun `saved and model approved readings are accepted locally`() {
        assertTrue(ReadingAnswerNormalizer.isAccepted("カラ", "から", emptyList()))
        assertTrue(ReadingAnswerNormalizer.isAccepted("つら", "から", listOf("つら")))
        assertFalse(ReadingAnswerNormalizer.isAccepted("そら", "から", listOf("つら")))
        assertFalse(ReadingAnswerNormalizer.isAccepted("  ", "から", listOf("つら")))
    }

    @Test
    fun `automatic rating rewards first local matches`() {
        assertEquals(
            ReviewScheduler.Rating.GOOD,
            ReadingReviewPolicy.ratingForLocalMatch(streak = 1, failedAttempts = 0)
        )
        assertEquals(
            ReviewScheduler.Rating.EASY,
            ReadingReviewPolicy.ratingForLocalMatch(
                streak = ReadingReviewPolicy.MATURE_STREAK,
                failedAttempts = 0
            )
        )
    }

    @Test
    fun `model matches and recovered answers are hard`() {
        assertEquals(ReviewScheduler.Rating.HARD, ReadingReviewPolicy.ratingForModelMatch())
        assertEquals(
            ReviewScheduler.Rating.HARD,
            ReadingReviewPolicy.ratingForLocalMatch(streak = 5, failedAttempts = 1)
        )
    }

    @Test
    fun `failure limit is reached on second rejected answer`() {
        assertFalse(ReadingReviewPolicy.hasReachedFailureLimit(1))
        assertTrue(ReadingReviewPolicy.hasReachedFailureLimit(2))
    }

    @Test
    fun `all exact target occurrences can be highlighted`() {
        assertEquals(
            listOf(ReviewTargetRange(0, 2), ReviewTargetRange(5, 7)),
            ReviewSentenceMatcher.targetRanges("映画を見る映画館", "映画")
        )
        assertTrue(ReviewSentenceMatcher.targetRanges("映画館", "動画").isEmpty())
    }
}
