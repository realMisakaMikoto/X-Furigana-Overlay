package com.sosdanfurigana.furigana

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GrammarAnalysisTest {

    @Test
    fun `normalize keeps known roles and maps synonyms`() {
        assertEquals("主题", GrammarRoles.normalize("主题"))
        assertEquals("补语", GrammarRoles.normalize("表语"))
        assertEquals("修饰语", GrammarRoles.normalize("状语"))
        assertEquals("谓语", GrammarRoles.normalize("谓语动词"))
        assertEquals("助词", GrammarRoles.normalize("助动词"))
        assertEquals(GrammarRoles.OTHER, GrammarRoles.normalize("感叹词"))
        assertEquals(GrammarRoles.OTHER, GrammarRoles.normalize(""))
    }

    @Test
    fun `codec round trip preserves tokens`() {
        val tokens = listOf(
            GrammarToken("これ", "主题", 0, 2),
            GrammarToken("は", "助词", 2, 3),
            GrammarToken("ペン", "补语", 3, 5),
            GrammarToken("です", "系动词", 5, 7)
        )
        val decoded = GrammarTokenCodec.decode(GrammarTokenCodec.encode(tokens))
        assertEquals(tokens, decoded)
    }

    @Test
    fun `codec tolerates blank and invalid payloads`() {
        assertTrue(GrammarTokenCodec.decode("").isEmpty())
        assertTrue(GrammarTokenCodec.decode("not json").isEmpty())
        assertTrue(GrammarTokenCodec.decode("""[{"s":"","r":"主题","b":0,"e":2}]""").isEmpty())
        assertTrue(GrammarTokenCodec.decode("""[{"s":"は","r":"助词","b":3,"e":3}]""").isEmpty())
    }

    @Test
    fun `renderer highlights tokens with role labels and legend`() {
        val source = "これはペンです"
        val tokens = listOf(
            GrammarToken("これ", "主题", 0, 2),
            GrammarToken("は", "助词", 2, 3),
            GrammarToken("ペン", "补语", 3, 5),
            GrammarToken("です", "系动词", 5, 7)
        )
        val html = GrammarHtmlRenderer.renderHtml(source, emptyList(), tokens)

        assertTrue(html.contains("class=\"tk\""))
        assertTrue(html.contains(">主题</span>"))
        assertTrue(html.contains(">系动词</span>"))
        assertTrue(html.contains("class=\"legend\""))
        assertTrue(html.contains("#FFE08A"))
    }

    @Test
    fun `kana only token still reserves the furigana layer`() {
        // これ 没有注音，也要垫空白 rt，保证假名一层、成分一层不串行
        val source = "これはペンです"
        val tokens = listOf(GrammarToken("これ", "主题", 0, 2))
        val html = GrammarHtmlRenderer.renderHtml(source, emptyList(), tokens)

        assertTrue(html.contains("<rt class=\"pad\">"))
        assertTrue(html.contains("class=\"role\""))
    }

    @Test
    fun `renderer skips tokens that no longer match the text`() {
        val source = "これはペンです"
        val stale = listOf(GrammarToken("違う", "主语", 0, 2))
        val html = GrammarHtmlRenderer.renderHtml(source, emptyList(), stale)

        assertFalse(html.contains("class=\"tk\""))
        assertTrue(html.contains("これはペンです"))
    }

    @Test
    fun `renderer keeps furigana ruby inside highlighted token`() {
        val source = "観察する"
        val tokens = listOf(GrammarToken("観察する", "谓语", 0, 4))
        val annotations = listOf(FuriganaAnnotation("観察", "かんさつ", 0, 2, 0.95))
        val html = GrammarHtmlRenderer.renderHtml(source, annotations, tokens)

        assertTrue(html.contains("<rt>かんさつ</rt>"))
        assertTrue(html.contains(">谓语</span>"))
    }

    @Test
    fun `other role renders without highlight`() {
        val source = "ペン"
        val tokens = listOf(GrammarToken("ペン", GrammarRoles.OTHER, 0, 2))
        val html = GrammarHtmlRenderer.renderHtml(source, emptyList(), tokens)

        assertFalse(html.contains("class=\"tk\""))
    }
}
