package com.sosdanfurigana.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewSchedulerTest {

    private val now = 1_000_000_000_000L
    private val dayMs = 24 * 60 * 60 * 1000L

    private fun entry(streak: Int = 0, reviewCount: Int = 0) = WordbookEntry(
        id = "id",
        surface = "本日",
        reading = "ほんじつ",
        sourceText = "本日は晴天なり",
        createdAt = now,
        updatedAt = now,
        streak = streak,
        reviewCount = reviewCount
    )

    @Test
    fun `good rating grows interval step by step`() {
        var word = entry()
        word = ReviewScheduler.review(word, ReviewScheduler.Rating.GOOD, now)
        assertEquals(1, word.streak)
        assertEquals(now + 1 * dayMs, word.dueAt)

        word = ReviewScheduler.review(word, ReviewScheduler.Rating.GOOD, now)
        assertEquals(2, word.streak)
        assertEquals(now + 3 * dayMs, word.dueAt)

        word = ReviewScheduler.review(word, ReviewScheduler.Rating.GOOD, now)
        assertEquals(now + 7 * dayMs, word.dueAt)
    }

    @Test
    fun `again rating resets streak and comes back soon`() {
        val trained = entry(streak = 5)
        val reviewed = ReviewScheduler.review(trained, ReviewScheduler.Rating.AGAIN, now)
        assertEquals(0, reviewed.streak)
        assertTrue(reviewed.dueAt > now)
        assertTrue(reviewed.dueAt < now + dayMs)
    }

    @Test
    fun `hard rating does not advance the ladder`() {
        val word = entry(streak = 3)
        val reviewed = ReviewScheduler.review(word, ReviewScheduler.Rating.HARD, now)
        assertEquals(3, reviewed.streak)
        assertEquals(now + 7 * dayMs, reviewed.dueAt)
    }

    @Test
    fun `easy rating skips a step`() {
        val word = entry(streak = 1)
        val reviewed = ReviewScheduler.review(word, ReviewScheduler.Rating.EASY, now)
        assertEquals(3, reviewed.streak)
        assertEquals(now + 7 * dayMs, reviewed.dueAt)
    }

    @Test
    fun `streak is capped at the top of the ladder`() {
        val word = entry(streak = 8)
        val reviewed = ReviewScheduler.review(word, ReviewScheduler.Rating.EASY, now)
        assertEquals(8, reviewed.streak)
        assertEquals(now + 240 * dayMs, reviewed.dueAt)
    }

    @Test
    fun `review count increments on every rating`() {
        val word = entry(reviewCount = 4)
        val reviewed = ReviewScheduler.review(word, ReviewScheduler.Rating.AGAIN, now)
        assertEquals(5, reviewed.reviewCount)
    }

    @Test
    fun `legacy entries with dueAt zero are due immediately`() {
        val words = listOf(entry(), entry().copy(id = "id2", dueAt = now + dayMs))
        assertEquals(1, ReviewScheduler.dueCount(words, now))
    }
}
