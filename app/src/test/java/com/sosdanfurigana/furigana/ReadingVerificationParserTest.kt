package com.sosdanfurigana.furigana

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingVerificationParserTest {
    @Test
    fun `parses approved answer from fenced response`() {
        val result = ReadingVerificationParser.parse(
            """
            ```json
            {"accepted":true,"reason":"在该句中读作みた"}
            ```
            """.trimIndent()
        )
        assertTrue(result.accepted)
    }

    @Test
    fun `parses explicit rejection`() {
        val result = ReadingVerificationParser.parse(
            "{\"accepted\":false,\"reason\":\"该语境不成立\"}"
        )
        assertFalse(result.accepted)
    }

    @Test
    fun `extra answer text in rejection is discarded`() {
        val result = ReadingVerificationParser.parse(
            "{\"accepted\":false,\"reason\":\"正确读音是しょかい\"}"
        )

        assertFalse(result.accepted)
    }
}
