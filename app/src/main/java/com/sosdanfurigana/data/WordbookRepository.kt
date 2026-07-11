package com.sosdanfurigana.data

import android.content.Context
import com.sosdanfurigana.util.TextHash
import org.json.JSONArray
import org.json.JSONObject

class WordbookRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveWord(surface: String, reading: String, sourceText: String): String? {
        val normalizedSurface = surface.trim()
        val normalizedReading = reading.trim()
        if (normalizedSurface.isBlank() || normalizedReading.isBlank()) return null

        val words = getWords().toMutableList()
        val id = TextHash.sha256Short("$normalizedSurface\n$normalizedReading")
        val now = System.currentTimeMillis()
        val existingIndex = words.indexOfFirst { it.id == id }
        val existing = if (existingIndex >= 0) words[existingIndex] else null
        val word = WordbookEntry(
            id = id,
            surface = normalizedSurface,
            reading = normalizedReading,
            sourceText = sourceText,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            meaning = existing?.meaning.orEmpty(),
            jlptLevel = existing?.jlptLevel.orEmpty(),
            partOfSpeech = existing?.partOfSpeech.orEmpty(),
            dueAt = existing?.dueAt ?: 0L,
            streak = existing?.streak ?: 0,
            reviewCount = existing?.reviewCount ?: 0
        )
        if (existingIndex >= 0) {
            words[existingIndex] = word
        } else {
            words.add(0, word)
        }
        writeWords(words.take(MAX_WORDS))
        return id
    }

    fun getWord(id: String): WordbookEntry? {
        return getWords().firstOrNull { it.id == id }
    }

    fun updateWord(entry: WordbookEntry) {
        val words = getWords().toMutableList()
        val index = words.indexOfFirst { it.id == entry.id }
        if (index < 0) return
        words[index] = entry
        writeWords(words)
    }

    fun dueWords(now: Long = System.currentTimeMillis()): List<WordbookEntry> {
        return getWords()
            .filter { it.dueAt <= now }
            .sortedBy { it.dueAt }
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
                            updatedAt = json.optLong("updatedAt"),
                            meaning = json.optString("meaning"),
                            jlptLevel = json.optString("jlptLevel"),
                            partOfSpeech = json.optString("partOfSpeech"),
                            // 老数据没有 dueAt，默认 0 = 立即到期，进入复习队列
                            dueAt = json.optLong("dueAt", 0L),
                            streak = json.optInt("streak", 0),
                            reviewCount = json.optInt("reviewCount", 0)
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
                    .put("meaning", word.meaning)
                    .put("jlptLevel", word.jlptLevel)
                    .put("partOfSpeech", word.partOfSpeech)
                    .put("dueAt", word.dueAt)
                    .put("streak", word.streak)
                    .put("reviewCount", word.reviewCount)
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
    val updatedAt: Long,
    val meaning: String = "",
    val jlptLevel: String = "",
    val partOfSpeech: String = "",
    val dueAt: Long = 0L,
    val streak: Int = 0,
    val reviewCount: Int = 0
)
