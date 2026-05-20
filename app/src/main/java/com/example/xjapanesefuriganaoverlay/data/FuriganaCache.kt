package com.example.xjapanesefuriganaoverlay.data

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

class FuriganaCache(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(originalText: String, modelName: String): CachedFurigana? {
        val raw = prefs.getString(entryKey(originalText, modelName), null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            CachedFurigana(
                rubyHtml = json.getString("rubyHtml"),
                annotationHintsJson = json.optString("annotationHintsJson"),
                createdAt = json.getLong("createdAt")
            )
        }.getOrNull()
    }

    fun put(
        originalText: String,
        modelName: String,
        rubyHtml: String,
        annotationHintsJson: String = ""
    ) {
        val value = JSONObject()
            .put("rubyHtml", rubyHtml)
            .put("annotationHintsJson", annotationHintsJson)
            .put("createdAt", System.currentTimeMillis())
            .toString()
        prefs.edit().putString(entryKey(originalText, modelName), value).apply()
        trimIfNeeded()
    }

    private fun trimIfNeeded() {
        val entries = prefs.all
            .filterKeys { it.startsWith(ENTRY_PREFIX) }
            .mapNotNull { (key, value) ->
                val createdAt = runCatching {
                    JSONObject(value as String).optLong("createdAt", 0L)
                }.getOrDefault(0L)
                key to createdAt
            }
            .sortedBy { it.second }

        val overflow = entries.size - MAX_ENTRIES
        if (overflow <= 0) return

        val editor = prefs.edit()
        entries.take(overflow).forEach { (key, _) -> editor.remove(key) }
        editor.apply()
    }

    private fun entryKey(originalText: String, modelName: String): String {
        return ENTRY_PREFIX + sha256("$CACHE_VERSION\n$originalText\n$modelName")
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    data class CachedFurigana(
        val rubyHtml: String,
        val annotationHintsJson: String,
        val createdAt: Long
    )

    companion object {
        private const val PREFS_NAME = "x_japanese_furigana_cache"
        private const val ENTRY_PREFIX = "entry_"
        private const val CACHE_VERSION = "candidate_segmentation_v5"
        private const val MAX_ENTRIES = 500
    }
}
