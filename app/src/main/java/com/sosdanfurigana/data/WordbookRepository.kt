package com.sosdanfurigana.data

import android.content.Context
import com.sosdanfurigana.util.TextHash
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

class WordbookRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveWord(surface: String, reading: String, sourceText: String): String? {
        val normalizedSurface = surface.trim()
        val normalizedReading = reading.trim()
        if (normalizedSurface.isBlank() || normalizedReading.isBlank()) return null

        val words = getWords().toMutableList()
        val legacyId = TextHash.sha256Short("$normalizedSurface\n$normalizedReading")
        val now = System.currentTimeMillis()
        val existingIndex = words.indexOfFirst { entry ->
            entry.id == legacyId || Companion.sameLexeme(entry, normalizedSurface, sourceText)
        }
        val existing = if (existingIndex >= 0) words[existingIndex] else null
        val id = existing?.id ?: legacyId
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
            reviewCount = existing?.reviewCount ?: 0,
            isFavorite = existing?.isFavorite ?: false,
            tags = existing?.tags.orEmpty(),
            acceptedReadings = existing?.acceptedReadings.orEmpty()
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

    fun setFavorite(id: String, isFavorite: Boolean): Boolean {
        return updateExisting(id) { it.copy(isFavorite = isFavorite, updatedAt = System.currentTimeMillis()) }
    }

    fun setTags(id: String, tags: List<String>): Boolean {
        val normalizedTags = tags
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy { it.lowercase() }
        return updateExisting(id) {
            it.copy(tags = normalizedTags, updatedAt = System.currentTimeMillis())
        }
    }

    fun addAcceptedReading(id: String, reading: String): Boolean {
        val normalizedReading = reading.trim()
        if (normalizedReading.isBlank()) return false
        val words = getWords().toMutableList()
        val index = words.indexOfFirst { it.id == id }
        if (index < 0) return false
        val entry = words[index]
        val knownKeys = (listOf(entry.reading) + entry.acceptedReadings)
            .map(::readingKey)
            .toSet()
        if (readingKey(normalizedReading) in knownKeys) return false
        words[index] = entry.copy(
            acceptedReadings = entry.acceptedReadings + normalizedReading,
            updatedAt = System.currentTimeMillis()
        )
        writeWords(words)
        return true
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
                    add(WordbookEntryJsonCodec.decode(json))
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
            array.put(WordbookEntryJsonCodec.encode(word))
        }
        prefs.edit().putString(KEY_WORDS, array.toString()).apply()
    }

    private fun updateExisting(id: String, transform: (WordbookEntry) -> WordbookEntry): Boolean {
        val words = getWords().toMutableList()
        val index = words.indexOfFirst { it.id == id }
        if (index < 0) return false
        words[index] = transform(words[index])
        writeWords(words)
        return true
    }

    private fun readingKey(reading: String): String {
        return Normalizer.normalize(reading, Normalizer.Form.NFKC)
            .asSequence()
            .filterNot(Char::isWhitespace)
            .map { char ->
                if (char in '\u30A1'..'\u30F6') {
                    (char.code - KATAKANA_TO_HIRAGANA_OFFSET).toChar()
                } else {
                    char
                }
            }
            .joinToString("")
    }

    companion object {
        internal fun sameLexeme(entry: WordbookEntry, surface: String, sourceText: String): Boolean {
            if (entry.surface != surface) return false
            return entry.sourceText == sourceText || entry.sourceText.isBlank() || sourceText.isBlank()
        }

        private const val PREFS_NAME = "x_japanese_furigana_wordbook"
        private const val KEY_WORDS = "words"
        private const val MAX_WORDS = 1000
        private const val KATAKANA_TO_HIRAGANA_OFFSET = 0x60
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
    val reviewCount: Int = 0,
    val isFavorite: Boolean = false,
    val tags: List<String> = emptyList(),
    val acceptedReadings: List<String> = emptyList()
)

internal object WordbookEntryJsonCodec {
    fun decode(json: JSONObject): WordbookEntry {
        return WordbookEntry(
            id = json.optString("id"),
            surface = json.optString("surface"),
            reading = json.optString("reading"),
            sourceText = json.optString("sourceText"),
            createdAt = json.optLong("createdAt"),
            updatedAt = json.optLong("updatedAt"),
            meaning = json.optString("meaning"),
            jlptLevel = json.optString("jlptLevel"),
            partOfSpeech = json.optString("partOfSpeech"),
            dueAt = json.optLong("dueAt", 0L),
            streak = json.optInt("streak", 0),
            reviewCount = json.optInt("reviewCount", 0),
            isFavorite = json.optBoolean("isFavorite", false),
            tags = json.optStringList("tags"),
            acceptedReadings = json.optStringList("acceptedReadings")
        )
    }

    fun encode(word: WordbookEntry): JSONObject {
        return JSONObject()
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
            .put("isFavorite", word.isFavorite)
            .put("tags", JSONArray(word.tags))
            .put("acceptedReadings", JSONArray(word.acceptedReadings))
    }

    private fun JSONObject.optStringList(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val value = array.optString(index).trim()
                if (value.isNotBlank() && value !in this) add(value)
            }
        }
    }
}
