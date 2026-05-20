package com.example.xjapanesefuriganaoverlay.furigana

import kotlin.math.max
import kotlin.math.min

object RubyHtmlRenderer {
    fun renderHtml(originalText: String, annotations: List<FuriganaAnnotation>): String {
        val body = renderBody(originalText, annotations)
        return """
            <!doctype html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <style>
                html, body {
                  background: transparent;
                }
                body {
                  margin: 0;
                  padding: 12px;
                  background: transparent;
                  color: #ffffff;
                  font-size: 18px;
                  line-height: 2.1;
                  word-break: break-word;
                  font-family: sans-serif;
                }
                ruby {
                  ruby-position: over;
                }
                rt {
                  font-size: 0.55em;
                  opacity: 0.95;
                }
              </style>
            </head>
            <body>$body</body>
            </html>
        """.trimIndent()
    }

    fun renderPlainText(originalText: String, annotations: List<FuriganaAnnotation>): String {
        val clean = cleanAnnotations(originalText, annotations)
        val builder = StringBuilder()
        var cursor = 0
        clean.forEach { annotation ->
            if (annotation.start < cursor) return@forEach
            builder.append(originalText.substring(cursor, annotation.start))
            builder.append(annotation.surface)
            builder.append('(')
            builder.append(annotation.reading)
            builder.append(')')
            cursor = annotation.end
        }
        builder.append(originalText.substring(cursor))
        return builder.toString()
    }

    private fun renderBody(originalText: String, annotations: List<FuriganaAnnotation>): String {
        val clean = cleanAnnotations(originalText, annotations)
        val builder = StringBuilder()
        var cursor = 0
        clean.forEach { annotation ->
            if (annotation.start < cursor) return@forEach
            builder.append(escapeHtml(originalText.substring(cursor, annotation.start)))
            builder.append("<ruby>")
            builder.append(escapeHtml(annotation.surface))
            builder.append("<rt>")
            builder.append(escapeHtml(annotation.reading))
            builder.append("</rt></ruby>")
            cursor = annotation.end
        }
        builder.append(escapeHtml(originalText.substring(cursor)))
        return builder.toString()
    }

    private fun cleanAnnotations(
        originalText: String,
        annotations: List<FuriganaAnnotation>
    ): List<FuriganaAnnotation> {
        val selected = mutableListOf<FuriganaAnnotation>()
        annotations
            .filter { it.start >= 0 && it.end <= originalText.length && it.start < it.end }
            .filter { originalText.substring(it.start, it.end) == it.surface }
            .sortedWith(
                compareBy<FuriganaAnnotation> { it.start }
                    .thenByDescending { it.confidence }
                    .thenByDescending { it.end - it.start }
            )
            .forEach { candidate ->
                val overlapping = selected.filter {
                    rangesOverlap(candidate.start, candidate.end, it.start, it.end)
                }
                if (overlapping.isEmpty()) {
                    selected.add(candidate)
                } else if (overlapping.all { shouldReplace(candidate, it) }) {
                    selected.removeAll(overlapping.toSet())
                    selected.add(candidate)
                }
            }
        return selected.sortedBy { it.start }
    }

    private fun rangesOverlap(startA: Int, endA: Int, startB: Int, endB: Int): Boolean {
        return max(startA, startB) < min(endA, endB)
    }

    private fun shouldReplace(candidate: FuriganaAnnotation, existing: FuriganaAnnotation): Boolean {
        val candidateLength = candidate.end - candidate.start
        val existingLength = existing.end - existing.start
        return candidate.confidence > existing.confidence ||
            (candidate.confidence == existing.confidence && candidateLength > existingLength)
    }

    private fun escapeHtml(value: String): String {
        val builder = StringBuilder(value.length)
        value.forEach { char ->
            when (char) {
                '&' -> builder.append("&amp;")
                '<' -> builder.append("&lt;")
                '>' -> builder.append("&gt;")
                '"' -> builder.append("&quot;")
                '\'' -> builder.append("&#39;")
                else -> builder.append(char)
            }
        }
        return builder.toString()
    }
}
