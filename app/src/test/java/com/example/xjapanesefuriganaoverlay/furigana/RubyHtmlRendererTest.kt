package com.example.xjapanesefuriganaoverlay.furigana

import org.junit.Assert.assertTrue
import org.junit.Test

class RubyHtmlRendererTest {
    @Test
    fun trimsFollowingOkuriganaFromRubyReading() {
        val html = RubyHtmlRenderer.renderHtml(
            "長持ちする",
            listOf(FuriganaAnnotation("長持", "ながもち", 0, 2, 0.95))
        )

        assertTrue(html.contains("<ruby>長持ち<rt>ながもち</rt></ruby>する"))
    }

    @Test
    fun trimsSurfaceOkuriganaFromRubyReading() {
        val html = RubyHtmlRenderer.renderHtml(
            "長持ちする",
            listOf(FuriganaAnnotation("長持ち", "ながもち", 0, 3, 0.95))
        )

        assertTrue(html.contains("<ruby>長持ち<rt>ながもち</rt></ruby>する"))
    }

    @Test
    fun keepsPreferredMixedSurfaceForDaisuki() {
        val source = "五月七日くみん先輩のことが大好き"
        val start = source.indexOf("大好き")
        val html = RubyHtmlRenderer.renderHtml(
            source,
            listOf(FuriganaAnnotation("大好き", "だいすき", start, start + 3, 0.95))
        )
        val plain = RubyHtmlRenderer.renderPlainText(
            source,
            listOf(FuriganaAnnotation("大好き", "だいすき", start, start + 3, 0.95))
        )

        assertTrue(html.contains("<ruby>大好き<rt>だいすき</rt></ruby>"))
        assertTrue(plain.contains("大好き(だいすき)"))
    }

    @Test
    fun expandsPreferredMixedSurfaceFromKanjiCandidate() {
        val html = RubyHtmlRenderer.renderHtml(
            "大好き",
            listOf(FuriganaAnnotation("大好", "だいす", 0, 2, 0.95))
        )
        val plain = RubyHtmlRenderer.renderPlainText(
            "大好き",
            listOf(FuriganaAnnotation("大好", "だいす", 0, 2, 0.95))
        )

        assertTrue(html.contains("<ruby>大好き<rt>だいすき</rt></ruby>"))
        assertTrue(plain.contains("大好き(だいすき)"))
    }

    @Test
    fun trimsUnrepresentedTrailingKanaFromSurface() {
        val html = RubyHtmlRenderer.renderHtml(
            "聞いたけど、上手すぎる",
            listOf(
                FuriganaAnnotation("聞い", "き", 0, 2, 0.95),
                FuriganaAnnotation("上手す", "じょうず", 6, 9, 0.95)
            )
        )

        assertTrue(html.contains("<ruby>聞<rt>き</rt></ruby>いたけど、<ruby>上手<rt>じょうず</rt></ruby>すぎる"))
    }

    @Test
    fun keepsCompleteCompoundVerbSurfaceWhenReadingCoversKana() {
        val html = RubyHtmlRenderer.renderHtml(
            "書き終える",
            listOf(FuriganaAnnotation("書き終える", "かきおえる", 0, 5, 0.95))
        )

        assertTrue(html.contains("<ruby>書き終える<rt>かきおえる</rt></ruby>"))
    }

    @Test
    fun doesNotExpandParticleAfterNinki() {
        val html = RubyHtmlRenderer.renderHtml(
            "人気がない",
            listOf(FuriganaAnnotation("人気", "ひとけ", 0, 2, 0.95))
        )

        assertTrue(html.contains("<ruby>人気<rt>ひとけ</rt></ruby>がない"))
    }

    @Test
    fun doesNotSwallowRequiredOkuriganaBeforeNextHint() {
        val html = RubyHtmlRenderer.renderHtml(
            "食べ物",
            listOf(
                FuriganaAnnotation("食", "た", 0, 1, 0.95),
                FuriganaAnnotation("物", "もの", 2, 3, 0.95)
            )
        )

        assertTrue(html.contains("<ruby>食<rt>た</rt></ruby>べ<ruby>物<rt>もの</rt></ruby>"))
    }

    @Test
    fun trimsMoushikomiOkuriganaFromRubyReading() {
        val html = RubyHtmlRenderer.renderHtml(
            "申し込み",
            listOf(FuriganaAnnotation("申し込み", "もうしこみ", 0, 4, 0.95))
        )

        assertTrue(html.contains("<ruby>申し込み<rt>もうしこみ</rt></ruby>"))
    }

    @Test
    fun trimsToriatsukaiOkuriganaFromRubyReading() {
        val html = RubyHtmlRenderer.renderHtml(
            "取り扱い",
            listOf(FuriganaAnnotation("取り扱い", "とりあつかい", 0, 4, 0.95))
        )

        assertTrue(html.contains("<ruby>取り扱い<rt>とりあつかい</rt></ruby>"))
    }

    @Test
    fun doesNotExpandPastNextAnnotation() {
        val html = RubyHtmlRenderer.renderHtml(
            "人気発売中",
            listOf(
                FuriganaAnnotation("人気", "にんき", 0, 2, 0.95),
                FuriganaAnnotation("発売", "はつばい", 2, 4, 0.95),
                FuriganaAnnotation("中", "ちゅう", 4, 5, 0.95)
            )
        )

        assertTrue(
            html.contains(
                "<ruby>人気<rt>にんき</rt></ruby><ruby>発売<rt>はつばい</rt></ruby><ruby>中<rt>ちゅう</rt></ruby>"
            )
        )
    }
}
