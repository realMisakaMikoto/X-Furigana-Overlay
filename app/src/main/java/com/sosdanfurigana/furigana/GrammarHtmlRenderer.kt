package com.sosdanfurigana.furigana

/**
 * 语法作用荧光标注渲染：在注音 ruby 之上叠一层。
 *
 * 每个词块 = 外层 <ruby>：底为该词的注音 HTML（内层 ruby 是振假名），
 * 外层 <rt> 是语法作用标签；词块整体铺该作用的荧光底色。
 * 「其他」不着色不标签，按普通注音渲染，避免满屏噪音。
 * 亮色主题（笔记详情页）。
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
                  line-height: 3.1;
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
                ruby.tk {
                  border-radius: 6px;
                  padding: 1px 3px;
                  margin: 0 2px;
                }
                ruby.tk > rt.role {
                  font-size: 0.42em;
                  font-weight: bold;
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
                builder.append(renderRange(originalText, annotations, cursor, token.start))
            }
            val inner = renderRange(originalText, annotations, token.start, token.end)
            val colors = GrammarRoles.ROLE_COLORS[token.role]
            if (colors == null) {
                builder.append(inner)
            } else {
                val (background, labelColor) = colors
                builder.append("<ruby class=\"tk\" style=\"background:$background\">")
                builder.append(inner)
                builder.append("<rt class=\"role\" style=\"color:$labelColor\">")
                builder.append(RubyHtmlRenderer.escapeText(token.role))
                builder.append("</rt></ruby>")
            }
            cursor = token.end
        }
        if (cursor < originalText.length) {
            builder.append(renderRange(originalText, annotations, cursor, originalText.length))
        }
        return builder.toString()
    }

    /** 渲染原文 [start, end) 区间：只带完全落在区间内的注音，偏移归零后复用 ruby 渲染。 */
    private fun renderRange(
        originalText: String,
        annotations: List<FuriganaAnnotation>,
        start: Int,
        end: Int
    ): String {
        val slice = originalText.substring(start, end)
        val shifted = annotations
            .filter { it.start >= start && it.end <= end }
            .map { it.copy(start = it.start - start, end = it.end - start) }
        return RubyHtmlRenderer.renderBodyHtml(slice, shifted)
    }

    private fun legendHtml(): String {
        val chips = GrammarRoles.ROLE_COLORS.entries.joinToString("") { (role, colors) ->
            "<span class=\"sw\" style=\"background:${colors.first}\">$role</span>"
        }
        return "<div class=\"legend\">$chips</div>"
    }
}
