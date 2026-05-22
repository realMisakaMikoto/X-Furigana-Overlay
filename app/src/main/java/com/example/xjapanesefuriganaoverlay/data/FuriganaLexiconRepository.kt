package com.example.xjapanesefuriganaoverlay.data

import android.content.Context
import com.example.xjapanesefuriganaoverlay.furigana.FuriganaAnnotation
import com.example.xjapanesefuriganaoverlay.util.TextHash
import org.json.JSONObject

class FuriganaLexiconRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun lookup(surface: String): LexiconHit? {
        val normalizedSurface = surface.trim()
        if (!isCacheableSurface(normalizedSurface)) return null
        val entry = readEntry(normalizedSurface) ?: return null
        if (entry.ambiguous) return null
        if (entry.readings.size != 1) return null
        val reading = entry.readings.keys.firstOrNull() ?: return null
        if (!isValidReading(reading)) return null

        writeEntry(
            entry.copy(
                hitCount = entry.hitCount + 1,
                lastUsedAt = System.currentTimeMillis()
            )
        )
        return LexiconHit(
            surface = normalizedSurface,
            reading = reading,
            observedCount = entry.readings.getValue(reading),
            hitCount = entry.hitCount + 1
        )
    }

    fun observeAnnotations(annotations: List<FuriganaAnnotation>) {
        annotations.forEach { annotation ->
            observe(
                surface = annotation.surface,
                reading = annotation.reading,
                confidence = annotation.confidence
            )
        }
        trimIfNeeded()
    }

    private fun observe(surface: String, reading: String, confidence: Double) {
        val normalizedSurface = surface.trim()
        val normalizedReading = reading.trim()
        if (confidence < MIN_CONFIDENCE_TO_CACHE) return
        if (!isCacheableSurface(normalizedSurface)) return
        if (!isValidReading(normalizedReading)) return

        val now = System.currentTimeMillis()
        val existing = readEntry(normalizedSurface)
        if (existing == null) {
            writeEntry(
                LexiconEntry(
                    surface = normalizedSurface,
                    readings = mapOf(normalizedReading to 1),
                    ambiguous = false,
                    observedCount = 1,
                    hitCount = 0,
                    createdAt = now,
                    updatedAt = now,
                    lastUsedAt = 0L
                )
            )
            return
        }

        val nextReadings = existing.readings.toMutableMap()
        nextReadings[normalizedReading] = (nextReadings[normalizedReading] ?: 0) + 1
        writeEntry(
            existing.copy(
                readings = nextReadings,
                ambiguous = existing.ambiguous || nextReadings.size > 1,
                observedCount = existing.observedCount + 1,
                updatedAt = now
            )
        )
    }

    private fun readEntry(surface: String): LexiconEntry? {
        val raw = prefs.getString(entryKey(surface), null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val readingsJson = json.optJSONObject("readings") ?: JSONObject()
            val readings = buildMap {
                readingsJson.keys().forEach { reading ->
                    val count = readingsJson.optInt(reading, 0)
                    if (reading.isNotBlank() && count > 0) {
                        put(reading, count)
                    }
                }
            }
            LexiconEntry(
                surface = json.optString("surface"),
                readings = readings,
                ambiguous = json.optBoolean("ambiguous", readings.size > 1),
                observedCount = json.optInt("observedCount", readings.values.sum()),
                hitCount = json.optInt("hitCount", 0),
                createdAt = json.optLong("createdAt", 0L),
                updatedAt = json.optLong("updatedAt", 0L),
                lastUsedAt = json.optLong("lastUsedAt", 0L)
            )
        }.getOrNull()
    }

    private fun writeEntry(entry: LexiconEntry) {
        val readingsJson = JSONObject()
        entry.readings.forEach { (reading, count) ->
            readingsJson.put(reading, count)
        }
        val json = JSONObject()
            .put("surface", entry.surface)
            .put("readings", readingsJson)
            .put("ambiguous", entry.ambiguous)
            .put("observedCount", entry.observedCount)
            .put("hitCount", entry.hitCount)
            .put("createdAt", entry.createdAt)
            .put("updatedAt", entry.updatedAt)
            .put("lastUsedAt", entry.lastUsedAt)
        prefs.edit().putString(entryKey(entry.surface), json.toString()).apply()
    }

    private fun trimIfNeeded() {
        val entries = prefs.all
            .filterKeys { it.startsWith(ENTRY_PREFIX) }
            .mapNotNull { (key, value) ->
                val raw = value as? String ?: return@mapNotNull null
                val lastTouched = runCatching {
                    val json = JSONObject(raw)
                    maxOf(json.optLong("lastUsedAt", 0L), json.optLong("updatedAt", 0L))
                }.getOrDefault(0L)
                key to lastTouched
            }
            .sortedBy { it.second }

        val overflow = entries.size - MAX_ENTRIES
        if (overflow <= 0) return
        val editor = prefs.edit()
        entries.take(overflow).forEach { (key, _) -> editor.remove(key) }
        editor.apply()
    }

    private fun entryKey(surface: String): String {
        return ENTRY_PREFIX + TextHash.sha256Short("$CACHE_VERSION\n$surface")
    }

    private fun isCacheableSurface(surface: String): Boolean {
        if (surface.length < MIN_SURFACE_LENGTH || surface.length > MAX_SURFACE_LENGTH) return false
        if (!surface.any { isKanji(it) }) return false
        if (surface.any { isDigitLike(it) }) return false
        if (surface.any { hasHardSeparator(it) }) return false
        if (surface.any { it in PARTICLE_HIRAGANA }) return false
        return true
    }

    private fun isValidReading(reading: String): Boolean {
        return reading.isNotBlank() && reading.all { char ->
            char in '\u3040'..'\u309F' || char == 'ー' || char == '・' || char == '･'
        }
    }

    private fun isKanji(char: Char): Boolean {
        return char in '\u4E00'..'\u9FFF' || char in '\u3400'..'\u4DBF'
    }

    private fun isDigitLike(char: Char): Boolean {
        return char in '0'..'9' || char in '０'..'９'
    }

    private fun hasHardSeparator(char: Char): Boolean {
        return char.isWhitespace() ||
            char in setOf(
                '。', '、', '，', '．', '.', ',', '!', '！', '?', '？',
                '「', '」', '『', '』', '【', '】', '（', '）', '(', ')',
                '[', ']', '{', '}', '/', '\\', '|', '\n', '\r', '\t'
            )
    }

    data class LexiconHit(
        val surface: String,
        val reading: String,
        val observedCount: Int,
        val hitCount: Int
    )

    private data class LexiconEntry(
        val surface: String,
        val readings: Map<String, Int>,
        val ambiguous: Boolean,
        val observedCount: Int,
        val hitCount: Int,
        val createdAt: Long,
        val updatedAt: Long,
        val lastUsedAt: Long
    )

    companion object {
        private const val PREFS_NAME = "x_japanese_furigana_lexicon"
        private const val ENTRY_PREFIX = "entry_"
        private const val CACHE_VERSION = "lexicon_v1"
        private const val MIN_CONFIDENCE_TO_CACHE = 0.85
        private const val MIN_SURFACE_LENGTH = 2
        private const val MAX_SURFACE_LENGTH = 16
        private const val MAX_ENTRIES = 3000
        private const val PARTICLE_HIRAGANA = "はがをにへでともやの"
    }
}
