package com.sosdanfurigana.furigana

object RubyAnnotationExtractor {
    private val rubyRegex = Regex(
        "<ruby>(.*?)<rt>(.*?)</rt></ruby>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    fun fromRubyHtml(sourceText: String, html: String): List<FuriganaAnnotation> {
        var searchFrom = 0
        return rubyRegex.findAll(html)
            .mapNotNull { match ->
                val surface = unescapeHtml(match.groupValues[1]).trim()
                val reading = unescapeHtml(match.groupValues[2]).trim()
                if (surface.isBlank() || reading.isBlank()) {
                    null
                } else {
                    val start = sourceText.indexOf(surface, searchFrom)
                        .takeIf { it >= 0 }
                        ?: sourceText.indexOf(surface)
                    val end = if (start >= 0) start + surface.length else -1
                    if (end > searchFrom) searchFrom = end
                    FuriganaAnnotation(
                        surface = surface,
                        reading = reading,
                        start = start,
                        end = end,
                        confidence = if (start >= 0) 1.0 else 0.6
                    )
                }
            }
            .toList()
    }

    private fun unescapeHtml(value: String): String {
        return unescapeNumericEntities(
            value
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
        )
    }

    private fun unescapeNumericEntities(value: String): String {
        val decimal = Regex("&#([0-9]+);").replace(value) { match ->
            match.groupValues[1].toIntOrNull()?.let { code ->
                runCatching { String(Character.toChars(code)) }.getOrNull()
            } ?: match.value
        }
        return Regex("&#x([0-9a-fA-F]+);").replace(decimal) { match ->
            match.groupValues[1].toIntOrNull(16)?.let { code ->
                runCatching { String(Character.toChars(code)) }.getOrNull()
            } ?: match.value
        }
    }
}
