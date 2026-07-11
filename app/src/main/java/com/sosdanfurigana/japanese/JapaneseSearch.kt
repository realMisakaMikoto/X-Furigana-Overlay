package com.sosdanfurigana.japanese

import java.text.Normalizer

/**
 * 本地假名/汉字互通搜索。
 *
 * - normalize: NFKC（统一全半角）+ 片假名转平假名 + 小写，让「ホンジツ」「ほんじつ」「ﾎﾝｼﾞﾂ」互相命中。
 * - expandQuery: 用已知的 词面↔读音 对（来自单词本与笔记注音记录）把查询扩展成多个变体，
 *   使「本日」能命中只写了「ほんじつ」的文本，反之亦然。纯本地，不依赖网络。
 */
object JapaneseSearch {

    fun normalize(text: String): String {
        if (text.isEmpty()) return text
        val nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val builder = StringBuilder(nfkc.length)
        nfkc.forEach { char ->
            val kana = if (char in 'ァ'..'ヶ') char - 0x60 else char
            builder.append(kana.lowercaseChar())
        }
        return builder.toString()
    }

    fun expandQuery(rawQuery: String, readingPairs: List<Pair<String, String>>): List<String> {
        val base = normalize(rawQuery.trim())
        if (base.isEmpty()) return emptyList()
        val variants = linkedSetOf(base)
        readingPairs.forEach { (rawSurface, rawReading) ->
            if (variants.size >= MAX_QUERY_VARIANTS) return@forEach
            val surface = normalize(rawSurface.trim())
            val reading = normalize(rawReading.trim())
            if (surface.isBlank() || reading.isBlank() || surface == reading) return@forEach
            if (base.contains(surface)) {
                variants.add(base.replace(surface, reading))
            }
            if (base.contains(reading)) {
                variants.add(base.replace(reading, surface))
            }
        }
        return variants.toList()
    }

    fun matches(queryVariants: List<String>, normalizedCorpus: String): Boolean {
        if (queryVariants.isEmpty()) return true
        return queryVariants.any { normalizedCorpus.contains(it) }
    }

    private const val MAX_QUERY_VARIANTS = 24
}
