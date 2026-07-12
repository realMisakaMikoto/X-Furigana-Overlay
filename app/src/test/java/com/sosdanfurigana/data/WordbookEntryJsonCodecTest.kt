package com.sosdanfurigana.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordbookEntryJsonCodecTest {
    @Test
    fun `legacy entry gets safe defaults for new learning fields`() {
        val entry = WordbookEntryJsonCodec.decode(
            JSONObject(
                """{
                    "id":"legacy",
                    "surface":"人気",
                    "reading":"ひとけ",
                    "sourceText":"人気のない道",
                    "createdAt":100,
                    "updatedAt":200
                }""".trimIndent()
            )
        )

        assertFalse(entry.isFavorite)
        assertTrue(entry.tags.isEmpty())
        assertTrue(entry.acceptedReadings.isEmpty())
    }

    @Test
    fun `new learning fields survive json round trip`() {
        val original = WordbookEntry(
            id = "word",
            surface = "人気",
            reading = "にんき",
            sourceText = "人気の作品",
            createdAt = 100,
            updatedAt = 200,
            meaning = "受欢迎",
            isFavorite = true,
            tags = listOf("网络", "作品"),
            acceptedReadings = listOf("ひとけ")
        )

        val decoded = WordbookEntryJsonCodec.decode(WordbookEntryJsonCodec.encode(original))

        assertEquals(original, decoded)
    }

    @Test
    fun `same surface and source remains one lexeme after a verified reading edit`() {
        val entry = WordbookEntry(
            id = "legacy-id",
            surface = "人気",
            reading = "ひとけ",
            sourceText = "人気のない道",
            createdAt = 100,
            updatedAt = 200
        )

        assertTrue(WordbookRepository.sameLexeme(entry, "人気", "人気のない道"))
        assertFalse(WordbookRepository.sameLexeme(entry, "人気", "人気の作品"))
    }
}
