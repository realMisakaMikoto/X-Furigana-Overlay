package com.sosdanfurigana.furigana

import org.json.JSONArray
import org.json.JSONObject

/** 一个词在句子里的语法作用标注，start/end 指向原文字符区间。 */
data class GrammarToken(
    val surface: String,
    val role: String,
    val start: Int,
    val end: Int
)

/**
 * 成分级作用体系：助词统一一种颜色，颜色为亮色底上的荧光笔配色，
 * 标签文字用同色系深色保证可读。
 */
object GrammarRoles {
    const val OTHER = "其他"

    /** role -> (荧光底色, 标签文字色)；其他 不着色。 */
    val ROLE_COLORS: Map<String, Pair<String, String>> = linkedMapOf(
        "主题" to ("#FFE08A" to "#7A5200"),
        "主语" to ("#BDE3F5" to "#0B5E7E"),
        "宾语" to ("#C8E8B0" to "#33641A"),
        "谓语" to ("#F8C8C2" to "#9B2B1C"),
        "系动词" to ("#DECFF5" to "#5B3A96"),
        "补语" to ("#B7E6DB" to "#0F5F4E"),
        "修饰语" to ("#FFD4AE" to "#8C4A0B"),
        "接续" to ("#E7D9C4" to "#6B4E22"),
        "助词" to ("#E8E8E8" to "#575757")
    )

    private val SYNONYMS = mapOf(
        "表语" to "补语",
        "状语" to "修饰语",
        "定语" to "修饰语",
        "连体修饰" to "修饰语",
        "连用修饰" to "修饰语",
        "副词" to "修饰语",
        "谓语动词" to "谓语",
        "动词" to "谓语",
        "助动词" to "助词",
        "接续词" to "接续",
        "连接词" to "接续"
    )

    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return OTHER
        if (trimmed in ROLE_COLORS) return trimmed
        SYNONYMS[trimmed]?.let { return it }
        ROLE_COLORS.keys.firstOrNull { trimmed.contains(it) }?.let { return it }
        SYNONYMS.entries.firstOrNull { trimmed.contains(it.key) }?.let { return it.value }
        return OTHER
    }
}

object GrammarTokenCodec {
    fun encode(tokens: List<GrammarToken>): String {
        val array = JSONArray()
        tokens.forEach { token ->
            array.put(
                JSONObject()
                    .put("s", token.surface)
                    .put("r", token.role)
                    .put("b", token.start)
                    .put("e", token.end)
            )
        }
        return array.toString()
    }

    fun decode(raw: String): List<GrammarToken> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    val surface = json.optString("s")
                    val start = json.optInt("b", -1)
                    val end = json.optInt("e", -1)
                    if (surface.isBlank() || start < 0 || end <= start) continue
                    add(
                        GrammarToken(
                            surface = surface,
                            role = GrammarRoles.normalize(json.optString("r")),
                            start = start,
                            end = end
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
