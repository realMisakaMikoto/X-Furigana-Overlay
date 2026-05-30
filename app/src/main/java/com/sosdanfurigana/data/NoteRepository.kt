package com.sosdanfurigana.data

import android.content.Context
import com.sosdanfurigana.util.TextHash
import org.json.JSONArray
import org.json.JSONObject

class NoteRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveNote(
        originalText: String,
        rubyHtml: String,
        plainText: String,
        modelName: String,
        annotationHintsJson: String = ""
    ) {
        val notes = getNotes().toMutableList()
        val id = TextHash.sha256Short("$originalText\n$modelName")
        val now = System.currentTimeMillis()
        val existingIndex = notes.indexOfFirst { it.id == id }
        val note = FuriganaNote(
            id = id,
            originalText = originalText,
            rubyHtml = rubyHtml,
            plainText = plainText,
            modelName = modelName,
            annotationHintsJson = annotationHintsJson,
            createdAt = if (existingIndex >= 0) notes[existingIndex].createdAt else now,
            updatedAt = now
        )
        if (existingIndex >= 0) {
            notes[existingIndex] = note
        } else {
            notes.add(0, note)
        }
        writeNotes(notes.take(MAX_NOTES))
    }

    fun getNotes(): List<FuriganaNote> {
        val raw = prefs.getString(KEY_NOTES, "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    add(
                        FuriganaNote(
                            id = json.optString("id"),
                            originalText = json.optString("originalText"),
                            rubyHtml = json.optString("rubyHtml"),
                            plainText = json.optString("plainText"),
                            modelName = json.optString("modelName"),
                            annotationHintsJson = json.optString("annotationHintsJson"),
                            createdAt = json.optLong("createdAt"),
                            updatedAt = json.optLong("updatedAt")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
            .sortedByDescending { it.updatedAt }
    }

    fun deleteNote(id: String) {
        writeNotes(getNotes().filterNot { it.id == id })
    }

    fun clear() {
        prefs.edit().remove(KEY_NOTES).apply()
    }

    private fun writeNotes(notes: List<FuriganaNote>) {
        val array = JSONArray()
        notes.forEach { note ->
            array.put(
                JSONObject()
                    .put("id", note.id)
                    .put("originalText", note.originalText)
                    .put("rubyHtml", note.rubyHtml)
                    .put("plainText", note.plainText)
                    .put("modelName", note.modelName)
                    .put("annotationHintsJson", note.annotationHintsJson)
                    .put("createdAt", note.createdAt)
                    .put("updatedAt", note.updatedAt)
            )
        }
        prefs.edit().putString(KEY_NOTES, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "x_japanese_furigana_notes"
        private const val KEY_NOTES = "notes"
        private const val MAX_NOTES = 300
    }
}

data class FuriganaNote(
    val id: String,
    val originalText: String,
    val rubyHtml: String,
    val plainText: String,
    val modelName: String,
    val annotationHintsJson: String,
    val createdAt: Long,
    val updatedAt: Long
)
