package com.sosdanfurigana.data

/**
 * 间隔复习调度：阶梯式间隔（Leitner 变体），完全离线。
 *
 * - streak 表示连续记住的次数，决定下次间隔在阶梯上的位置。
 * 评分由语境读音填空结果自动映射：
 * - AGAIN：未答对，streak 归零，10 分钟后重新到期。
 * - HARD：重试后答对，或模型确认了另一种语境读音；不进阶。
 * - GOOD：首次命中本地已知读音；进一档。
 * - EASY：熟练词首次命中本地已知读音；跳两档。
 */
object ReviewScheduler {

    enum class Rating { AGAIN, HARD, GOOD, EASY }

    private val INTERVAL_DAYS = intArrayOf(1, 3, 7, 14, 30, 60, 120, 240)
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val RELEARN_DELAY_MS = 10 * 60 * 1000L

    fun review(entry: WordbookEntry, rating: Rating, now: Long = System.currentTimeMillis()): WordbookEntry {
        val nextStreak = when (rating) {
            Rating.AGAIN -> 0
            Rating.HARD -> maxOf(entry.streak, 1)
            Rating.GOOD -> entry.streak + 1
            Rating.EASY -> entry.streak + 2
        }.coerceAtMost(INTERVAL_DAYS.size)

        val nextDueAt = if (nextStreak == 0) {
            now + RELEARN_DELAY_MS
        } else {
            now + INTERVAL_DAYS[nextStreak - 1] * DAY_MS
        }

        return entry.copy(
            streak = nextStreak,
            dueAt = nextDueAt,
            reviewCount = entry.reviewCount + 1
        )
    }

    fun intervalDescription(entry: WordbookEntry): String {
        if (entry.streak == 0) return "待会儿再来一次！"
        val days = INTERVAL_DAYS[(entry.streak - 1).coerceIn(0, INTERVAL_DAYS.size - 1)]
        return "${days} 天后再见"
    }

    fun dueCount(words: List<WordbookEntry>, now: Long = System.currentTimeMillis()): Int {
        return words.count { it.dueAt <= now }
    }
}
