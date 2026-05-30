package com.example.xjapanesefuriganaoverlay.furigana

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FuriganaJsonParserTest {
    @Test
    fun rejectsPhraseReadingBoundToShortCompactCandidate() {
        val source = "五月七日、私は上海にいました"
        val start = source.indexOf("上海")
        val candidates = listOf(FuriganaCandidate(0, "上海", start, start + "上海".length))

        val annotations = FuriganaJsonParser.parseCandidateReadings(
            originalText = source,
            rawJson = """{"a":[[0,"わたしはしゃんは",0.95]]}""",
            candidates = candidates
        )

        assertTrue(annotations.isEmpty())
    }

    @Test
    fun acceptsNormalReadingForCompactCandidate() {
        val source = "五月七日、私は上海にいました"
        val start = source.indexOf("上海")
        val candidates = listOf(FuriganaCandidate(0, "上海", start, start + "上海".length))

        val annotations = FuriganaJsonParser.parseCandidateReadings(
            originalText = source,
            rawJson = """{"a":[[0,"しゃんはい",0.95]]}""",
            candidates = candidates
        )

        assertEquals(listOf(FuriganaAnnotation("上海", "しゃんはい", start, start + 2, 0.95)), annotations)
    }

    @Test
    fun rejectsCandidateThatCrossesTemporalVerbBoundary() {
        val source = "昨日見た映画を思い出した。"
        val candidates = listOf(FuriganaCandidate(0, "昨日見た", 0, "昨日見た".length))

        val annotations = FuriganaJsonParser.parseCandidateReadings(
            originalText = source,
            rawJson = """{"a":[[0,"きのうみた",0.95]]}""",
            candidates = candidates
        )

        assertTrue(annotations.isEmpty())
    }

    @Test
    fun rejectsCandidateThatCrossesNounVerbBoundary() {
        val source = "映画見たあと本読んだ。"
        val candidates = listOf(
            FuriganaCandidate(0, "映画見た", 0, "映画見た".length),
            FuriganaCandidate(1, "本読", source.indexOf("本読"), source.indexOf("本読") + "本読".length)
        )

        val annotations = FuriganaJsonParser.parseCandidateReadings(
            originalText = source,
            rawJson = """{"a":[[0,"えいがみた",0.95],[1,"ほんよ",0.95]]}""",
            candidates = candidates
        )

        assertTrue(annotations.isEmpty())
    }

    @Test
    fun rejectsPhraseReadingBoundToShortLegacyAnnotation() {
        val source = "今日は人気のない道を一人で歩いていたら"
        val start = source.indexOf("歩")
        val annotations = FuriganaJsonParser.parse(
            originalText = source,
            rawJson = """
                {
                  "annotations": [
                    {
                      "surface": "歩",
                      "reading": "みちをひとりである",
                      "start": $start,
                      "end": ${start + 1},
                      "confidence": 0.95
                    }
                  ]
                }
            """.trimIndent()
        )

        assertTrue(annotations.isEmpty())
    }

    @Test
    fun rejectsReadingFromLaterVerbBoundToEarlierNoun() {
        val source = "生物の授業で生物を観察していたら"
        val start = source.indexOf("授業")
        val candidates = listOf(FuriganaCandidate(0, "授業", start, start + 2))

        val annotations = FuriganaJsonParser.parseCandidateReadings(
            originalText = source,
            rawJson = """{"a":[[0,"かんさつして",0.95]]}""",
            candidates = candidates
        )

        assertTrue(annotations.isEmpty())
    }

    @Test
    fun allowsVerbSuffixReadingWhenSuffixFollowsCandidate() {
        val source = "生物を観察していたら"
        val start = source.indexOf("観察")
        val candidates = listOf(FuriganaCandidate(0, "観察", start, start + 2))

        val annotations = FuriganaJsonParser.parseCandidateReadings(
            originalText = source,
            rawJson = """{"a":[[0,"かんさつして",0.95]]}""",
            candidates = candidates
        )

        assertEquals(listOf(FuriganaAnnotation("観察", "かんさつして", start, start + 2, 0.95)), annotations)
    }

    @Test
    fun rejectsPreviousPhraseReadingBoundToSingleKanji() {
        val source = "少し怖い"
        val start = source.indexOf("怖")
        val candidates = listOf(FuriganaCandidate(0, "怖", start, start + 1))

        val annotations = FuriganaJsonParser.parseCandidateReadings(
            originalText = source,
            rawJson = """{"a":[[0,"すこしこわ",0.95]]}""",
            candidates = candidates
        )

        assertTrue(annotations.isEmpty())
    }

    @Test
    fun adjustsNinkiToHitokeInNoPeopleContextOnly() {
        val source = "今日は人気のない道を一人で歩いていたら、人気バンドの新曲が流れてきた。"
        val firstStart = source.indexOf("人気")
        val secondStart = source.indexOf("人気", firstStart + 1)
        val candidates = listOf(
            FuriganaCandidate(0, "人気", firstStart, firstStart + 2),
            FuriganaCandidate(1, "人気", secondStart, secondStart + 2)
        )

        val annotations = FuriganaJsonParser.parseCandidateReadings(
            originalText = source,
            rawJson = """{"a":[[0,"にんき",0.95],[1,"にんき",0.95]]}""",
            candidates = candidates
        )

        assertEquals("ひとけ", annotations.first { it.start == firstStart }.reading)
        assertEquals("にんき", annotations.first { it.start == secondStart }.reading)
    }
}
