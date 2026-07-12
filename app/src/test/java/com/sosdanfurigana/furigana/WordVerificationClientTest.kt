package com.sosdanfurigana.furigana

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WordVerificationClientTest {
    private val fallback = WordVerificationClient.ProposedWord(
        reading = "にんき",
        meaning = "受欢迎",
        partOfSpeech = "名词",
        jlptLevel = "N3"
    )

    @Test
    fun `rejected edit exposes model suggestion`() {
        val result = WordVerificationClient.parseVerification(
            """{
                "approved":false,
                "reason":"原句表达无人烟",
                "suggestion":{
                    "reading":"ひとけ",
                    "meaning":"人的气息",
                    "pos":"名词",
                    "jlpt":"N2"
                }
            }""".trimIndent(),
            fallback
        )

        assertFalse(result.approved)
        assertEquals("ひとけ", result.suggestion.reading)
        assertEquals("人的气息", result.suggestion.meaning)
    }

    @Test
    fun `tag parser removes duplicates and limits suggestions`() {
        val tags = WordVerificationClient.parseTags(
            """{"tags":["旅行","会话","旅行","口语"]}"""
        )

        assertEquals(listOf("旅行", "会话", "口语"), tags)
    }
}
