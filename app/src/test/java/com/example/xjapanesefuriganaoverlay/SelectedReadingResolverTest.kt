package com.example.xjapanesefuriganaoverlay

import com.example.xjapanesefuriganaoverlay.furigana.FuriganaPromptBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectedReadingResolverTest {
    @Test
    fun resolvesLongLastingWithoutDuplicatingOkurigana() {
        val source = "長持ちする"
        val result = resolver(
            source,
            ReadingHint("長持", "ながもち", 0, 2)
        ).resolve(0, 3)

        assertEquals("ながもち", result.reading)
        assertFalse(result.shouldUseLlm)
    }

    @Test
    fun resolvesLongLastingWithFollowingSuru() {
        val source = "長持ちする"
        val result = resolver(
            source,
            ReadingHint("長持", "ながもち", 0, 2)
        ).resolve(0, 5)

        assertEquals("ながもちする", result.reading)
        assertFalse(result.shouldUseLlm)
    }

    @Test
    fun doesNotSkipRequiredOkurigana() {
        val source = "食べ物"
        val result = resolver(
            source,
            ReadingHint("食", "た", 0, 1),
            ReadingHint("物", "もの", 2, 3)
        ).resolve(0, 3)

        assertEquals("たべもの", result.reading)
        assertFalse(result.shouldUseLlm)
    }

    @Test
    fun skipsDuplicatedOkuriganaForMoushikomi() {
        val source = "申し込み"
        val result = resolver(
            source,
            ReadingHint("申し込", "もうしこみ", 0, 3)
        ).resolve(0, 4)

        assertEquals("もうしこみ", result.reading)
        assertFalse(result.shouldUseLlm)
    }

    @Test
    fun skipsDuplicatedOkuriganaForToriatsukai() {
        val source = "取り扱い"
        val result = resolver(
            source,
            ReadingHint("取り扱", "とりあつかい", 0, 3)
        ).resolve(0, 4)

        assertEquals("とりあつかい", result.reading)
        assertFalse(result.shouldUseLlm)
    }

    @Test
    fun keepsParticleAfterNinki() {
        val source = "人気がない"
        val result = resolver(
            source,
            ReadingHint("人気", "ひとけ", 0, 2)
        ).resolve(0, 3)

        assertEquals("ひとけが", result.reading)
        assertFalse(result.shouldUseLlm)
    }

    @Test
    fun composesHintsAcrossSpace() {
        val source = "人気 発売中！"
        val result = resolver(
            source,
            ReadingHint("人気", "にんき", 0, 2),
            ReadingHint("発売", "はつばい", 3, 5),
            ReadingHint("中", "ちゅう", 5, 6)
        ).resolve(0, 5)

        assertEquals("にんきはつばい", result.reading)
        assertEquals("composed_hints_with_gaps", result.hitType)
        assertFalse(result.shouldUseLlm)
    }

    @Test
    fun doesNotSplitInsideJukujikunWithoutSubHint() {
        val source = "今日"
        val result = resolver(
            source,
            ReadingHint("今日", "きょう", 0, 2)
        ).resolve(0, 1)

        assertTrue(result.shouldUseLlm)
    }

    @Test
    fun candidateGenerationPrefersFullOkuriganaSurface() {
        assertFirstCandidate("長持ちする", "長持ち")
        assertFirstCandidate("食べ物", "食べ物")
        assertFirstCandidate("申し込み", "申し込み")
        assertFirstCandidate("取り扱い", "取り扱い")
    }

    private fun resolver(source: String, vararg hints: ReadingHint): SelectedReadingResolver {
        return SelectedReadingResolver(source, hints.toList())
    }

    private fun assertFirstCandidate(source: String, expectedSurface: String) {
        val first = FuriganaPromptBuilder.annotationCandidates(source).first()
        assertEquals(expectedSurface, first.surface)
    }
}
