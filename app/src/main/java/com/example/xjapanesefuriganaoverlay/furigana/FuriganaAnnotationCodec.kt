package com.example.xjapanesefuriganaoverlay.furigana

import org.json.JSONArray
import org.json.JSONObject

object FuriganaAnnotationCodec {
    fun encode(annotations: List<FuriganaAnnotation>): String {
        val array = JSONArray()
        annotations.forEach { annotation ->
            array.put(
                JSONObject()
                    .put("s", annotation.surface)
                    .put("r", annotation.reading)
                    .put("b", annotation.start)
                    .put("e", annotation.end)
                    .put("c", annotation.confidence)
            )
        }
        return array.toString()
    }

    fun decode(raw: String): List<FuriganaAnnotation> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val surface = item.optString("s").trim()
                    val reading = item.optString("r").trim()
                    val start = item.optInt("b", -1)
                    val end = item.optInt("e", -1)
                    val confidence = item.optDouble("c", 0.5).coerceIn(0.0, 1.0)
                    if (surface.isNotBlank() && reading.isNotBlank()) {
                        add(FuriganaAnnotation(surface, reading, start, end, confidence))
                    }
                }
            }
        }.getOrDefault(emptyList())
    }
}
