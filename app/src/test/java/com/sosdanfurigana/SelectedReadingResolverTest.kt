package com.sosdanfurigana

import com.sosdanfurigana.furigana.FuriganaPromptBuilder
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

    @Test
    fun candidateGenerationDoesNotCrossParticlesBetweenWords() {
        val dateSurfaces = FuriganaPromptBuilder.annotationCandidates("五月七日、私は上海にいました")
            .map { it.surface }

        assertTrue(dateSurfaces.contains("私"))
        assertTrue(dateSurfaces.contains("上海"))
        assertFalse(dateSurfaces.contains("私は上海"))
        assertFalse(dateSurfaces.contains("上海に"))

        val sentenceSurfaces = FuriganaPromptBuilder.annotationCandidates(
            "今日は人気のない道を一人で歩いていたら、人気バンドの新曲が流れてきた。"
        ).map { it.surface }

        assertFalse(sentenceSurfaces.contains("道を一人"))
        assertFalse(sentenceSurfaces.contains("新曲が流"))
    }

    @Test
    fun candidateGenerationKeepsDenseLongSentenceTargets() {
        val surfaces = FuriganaPromptBuilder.annotationCandidates(
            "四月一日、東京の日本橋で四月一日君尋の話をしていたら、人気のない道から小鳥遊さんが一人で歩いてきた。"
        ).map { it.surface }

        assertTrue(surfaces.contains("東京"))
        assertTrue(surfaces.contains("日本橋"))
        assertTrue(surfaces.contains("四月一日君尋"))
        assertTrue(surfaces.contains("話"))
    }

    @Test
    fun candidateGenerationDoesNotMergeModifierPhrasesIntoNouns() {
        val surfaces = FuriganaPromptBuilder.annotationCandidates(
            "少し怖い話を聞いたあと、少し楽しい動画を見て気分を変えた。"
        ).map { it.surface }

        assertFalse(surfaces.contains("少し怖い話"))
        assertFalse(surfaces.contains("少し楽しい動画"))
        assertTrue(surfaces.contains("少し"))
        assertTrue(surfaces.contains("怖い"))
        assertTrue(surfaces.contains("話"))
        assertTrue(surfaces.contains("楽しい"))
        assertTrue(surfaces.contains("動画"))
    }

    @Test
    fun candidateGenerationDoesNotMergeAdverbialNounOrKanjiNumberWithFollowingVerb() {
        val surfaces = FuriganaPromptBuilder.annotationCandidates(
            "昔読んだ小説を思い出して、もう一度読み始めたけど、感想を書き終える前に寝てしまった。"
        ).map { it.surface }

        assertFalse(surfaces.contains("昔読"))
        assertFalse(surfaces.contains("一度読"))
        assertFalse(surfaces.contains("一度読み始"))
        assertTrue(surfaces.contains("昔"))
        assertTrue(surfaces.contains("読"))
        assertTrue(surfaces.contains("一度"))
        assertTrue(surfaces.contains("読み始め") || surfaces.contains("読み始めた"))
        assertTrue(surfaces.contains("書き終える"))
    }

    @Test
    fun candidateGenerationDoesNotMergeTemporalAdverbWithFollowingVerb() {
        val surfaces = FuriganaPromptBuilder.annotationCandidates(
            "昨日見た映画を思い出しながら、もう一度見直して、感想を短く書き直した。"
        ).map { it.surface }

        assertFalse(surfaces.contains("昨日見"))
        assertFalse(surfaces.contains("昨日見た"))
        assertTrue(surfaces.contains("昨日"))
        assertTrue(surfaces.contains("見た"))
    }

    @Test
    fun candidateGenerationSplitsStandaloneNounBeforeFollowingVerb() {
        val surfaces = FuriganaPromptBuilder.annotationCandidates(
            "映画見たあと本読んだ。"
        ).map { it.surface }

        assertFalse(surfaces.contains("映画見"))
        assertFalse(surfaces.contains("映画見た"))
        assertFalse(surfaces.contains("本読"))
        assertTrue(surfaces.contains("映画"))
        assertTrue(surfaces.contains("見た"))
        assertTrue(surfaces.contains("本"))
        assertTrue(surfaces.contains("読"))
    }

    @Test
    fun candidateGenerationKeepsLikelyProperNameThatLooksTemporal() {
        val surfaces = FuriganaPromptBuilder.annotationCandidates(
            "明日香さんは昨日見た映画の話をした。"
        ).map { it.surface }

        assertTrue(surfaces.contains("明日香"))
        assertFalse(surfaces.contains("昨日見"))
        assertFalse(surfaces.contains("昨日見た"))
    }

    private fun resolver(source: String, vararg hints: ReadingHint): SelectedReadingResolver {
        return SelectedReadingResolver(source, hints.toList())
    }

    private fun assertFirstCandidate(source: String, expectedSurface: String) {
        val first = FuriganaPromptBuilder.annotationCandidates(source).first()
        assertEquals(expectedSurface, first.surface)
    }
}
