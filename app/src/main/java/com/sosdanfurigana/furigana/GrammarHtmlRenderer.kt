package com.sosdanfurigana.furigana

/**
 * 语法作用荧光标注渲染：在注音 ruby 之上叠一层，亮色主题（笔记详情页）。
 *
 * 层级严格分离：每个词块是一个 inline-block，上行固定是语法作用标签，
 * 下行是正文；正文里没有注音的文字也垫一层不可见的空白 rt，
 * 保证「平假名一层、成分一层」在整行内高度一致，不会串行。
 * 「其他」不着色不标签，按普通注音渲染，避免满屏噪音。
 */
object GrammarHtmlRenderer {

    fun renderHtml(
        originalText: String,
        annotations: List<FuriganaAnnotation>,
        tokens: List<GrammarToken>
    ): String {
        val body = renderBody(originalText, annotations, tokens)
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
                  padding: 14px 12px;
                  background: transparent;
                  color: #201613;
                  font-size: 19px;
                  line-height: 2.1;
                  letter-spacing: 0.02em;
                  word-break: break-word;
                  font-family: sans-serif;
                }
                ruby {
                  ruby-position: over;
                }
                rt {
                  font-size: 0.5em;
                  color: #B25E00;
                  letter-spacing: 0;
                }
                rt.pad {
                  visibility: hidden;
                }
                .tk {
                  display: inline-block;
                  text-align: center;
                  border-radius: 6px;
                  padding: 1px 3px 2px 3px;
                  margin: 2px 2px;
                }
                .tk .role {
                  display: block;
                  font-size: 0.42em;
                  font-weight: bold;
                  line-height: 1.7;
                  letter-spacing: 0;
                }
                .tk .body {
                  display: block;
                  line-height: 2.0;
                }
                .legend {
                  margin-top: 20px;
                  padding-top: 12px;
                  border-top: 1px solid #E2EDF1;
                  font-size: 12px;
                  color: #6E5F58;
                  line-height: 2.4;
                }
                .legend .sw {
                  display: inline-block;
                  padding: 1px 9px;
                  border-radius: 6px;
                  margin-right: 8px;
                  color: #201613;
                }
              </style>
            </head>
            <body>$body${legendHtml()}</body>
            </html>
        """.trimIndent()
    }

    private fun renderBody(
        originalText: String,
        annotations: List<FuriganaAnnotation>,
        tokens: List<GrammarToken>
    ): String {
        val orderedTokens = tokens
            .filter { it.start >= 0 && it.end <= originalText.length && it.start < it.end }
            .filter { originalText.substring(it.start, it.end) == it.surface }
            .sortedBy { it.start }
        val builder = StringBuilder()
        var cursor = 0
        orderedTokens.forEach { token ->
            if (token.start < cursor) return@forEach
            if (token.start > cursor) {
                builder.append(renderPlainRange(originalText, annotations, cursor, token.start))
            }
            val colors = GrammarRoles.ROLE_COLORS[token.role]
            if (colors == null) {
                builder.append(renderPlainRange(originalText, annotations, token.start, token.end))
            } else {
                val (background, labelColor) = colors
                builder.append("<span class=\"tk\" style=\"background:$background\">")
                builder.append("<span class=\"role\" style=\"color:$labelColor\">")
                builder.append(RubyHtmlRenderer.escapeText(token.role))
                builder.append("</span><span class=\"body\">")
                builder.append(renderPaddedRange(originalText, annotations, token.start, token.end))
                builder.append("</span></span>")
            }
            cursor = token.end
        }
        if (cursor < originalText.length) {
            builder.append(renderPlainRange(originalText, annotations, cursor, originalText.length))
        }
        return builder.toString()
    }

    /** 词块外的文字：普通 ruby 渲染。 */
    private fun renderPlainRange(
        originalText: String,
        annotations: List<FuriganaAnnotation>,
        start: Int,
        end: Int
    ): String {
        val slice = originalText.substring(start, end)
        val shifted = shiftedAnnotations(annotations, start, end)
        return RubyHtmlRenderer.renderBodyHtml(slice, shifted)
    }

    /**
     * 词块内的文字：没有注音的部分也垫一层空白 rt，
     * 让整个词块的假名层高度一致，作用标签不会掉到假名那一行。
     */
    private fun renderPaddedRange(
        originalText: String,
        annotations: List<FuriganaAnnotation>,
        start: Int,
        end: Int
    ): String {
        val slice = originalText.substring(start, end)
        val shifted = shiftedAnnotations(annotations, start, end)
            .sortedBy { it.start }
        val builder = StringBuilder()
        var cursor = 0
        shifted.forEach { annotation ->
            if (annotation.start < cursor) return@forEach
            if (annotation.start > cursor) {
                builder.append(paddedRuby(slice.substring(cursor, annotation.start)))
            }
            builder.append("<ruby>")
            builder.append(RubyHtmlRenderer.escapeText(slice.substring(annotation.start, annotation.end)))
            builder.append("<rt>")
            builder.append(RubyHtmlRenderer.escapeText(annotation.reading))
            builder.append("</rt></ruby>")
            cursor = annotation.end
        }
        if (cursor < slice.length) {
            builder.append(paddedRuby(slice.substring(cursor)))
        }
        return builder.toString()
    }

    private fun paddedRuby(text: String): String {
        if (text.isEmpty()) return ""
        return "<ruby>${RubyHtmlRenderer.escapeText(text)}<rt class=\"pad\">&#160;</rt></ruby>"
    }

    private fun shiftedAnnotations(
        annotations: List<FuriganaAnnotation>,
        start: Int,
        end: Int
    ): List<FuriganaAnnotation> {
        return annotations
            .filter { it.start >= start && it.end <= end }
            .map { it.copy(start = it.start - start, end = it.end - start) }
    }

    private fun legendHtml(): String {
        val chips = GrammarRoles.ROLE_COLORS.entries.joinToString("") { (role, colors) ->
            "<span class=\"sw\" style=\"background:${colors.first}\">$role</span>"
        }
        return "<div class=\"legend\">$chips</div>"
    }
}
