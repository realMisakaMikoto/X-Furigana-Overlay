package com.sosdanfurigana.data

import android.content.Context
import com.sosdanfurigana.util.TextHash
import org.json.JSONArray
import org.json.JSONObject

class WordbookRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveWord(surface: String, reading: String, sourceText: String) {
        val normalizedSurface = surface.trim()
        val normalizedReading = reading.trim()
        if (normalizedSurface.isBlank() || normalizedReading.isBlank()) return

        val words = getWords().toMutableList()
        val id = TextHash.sha256Short("$normalizedSurface\n$normalizedReading")
        val now = System.currentTimeMillis()
        val existingIndex = words.indexOfFirst { it.id == id }
        val word = WordbookEntry(
            id = id,
            surface = normalizedSurface,
            reading = normalizedReading,
            sourceText = sourceText,
            createdAt = if (existingIndex >= 0) words[existingIndex].createdAt else now,
            updatedAt = now
        )
        if (existingIndex >= 0) {
            words[existingIndex] = word
        } else {
            words.add(0, word)
        }
        writeWords(words.take(MAX_WORDS))
    }

    fun getWords(): List<WordbookEntry> {
        val raw = prefs.getString(KEY_WORDS, "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    add(
                        WordbookEntry(
                            id = json.optString("id"),
                            surface = json.optString("surface"),
                            reading = json.optString("reading"),
                            sourceText = json.optString("sourceText"),
                            createdAt = json.optLong("createdAt"),
                            updatedAt = json.optLong("updatedAt")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
            .sortedByDescending { it.updatedAt }
    }

    fun deleteWord(id: String) {
        writeWords(getWords().filterNot { it.id == id })
    }

    fun clear() {
        prefs.edit().remove(KEY_WORDS).apply()
    }

    private fun writeWords(words: List<WordbookEntry>) {
        val array = JSONArray()
        words.forEach { word ->
            array.put(
                JSONObject()
                    .put("id", word.id)
                    .put("surface", word.surface)
                    .put("reading", word.reading)
                    .put("sourceText", word.sourceText)
                    .put("createdAt", word.createdAt)
                    .put("updatedAt", word.updatedAt)
            )
        }
        prefs.edit().putString(KEY_WORDS, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "x_japanese_furigana_wordbook"
        private const val KEY_WORDS = "words"
        private const val MAX_WORDS = 1000
    }
}

data class WordbookEntry(
    val id: String,
    val surface: String,
    val reading: String,
    val sourceText: String,
    val createdAt: Long,
    val updatedAt: Long
)
