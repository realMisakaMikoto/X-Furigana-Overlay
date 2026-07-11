package com.sosdanfurigana.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ApiProfile(
    val id: String,
    val name: String,
    val apiBaseUrl: String,
    val apiKey: String,
    val model: String
)

class SettingsRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var apiBaseUrl: String
        get() = selectedApiProfile().apiBaseUrl
        set(value) = updateSelectedApiProfile { it.copy(apiBaseUrl = value.trim()) }

    var apiKey: String
        get() = selectedApiProfile().apiKey
        set(value) = updateSelectedApiProfile { it.copy(apiKey = value.trim()) }

    var model: String
        get() = selectedApiProfile().model
        set(value) = updateSelectedApiProfile { it.copy(model = value.trim()) }

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var autoGrammarAnalysis: Boolean
        get() = prefs.getBoolean(KEY_AUTO_GRAMMAR_ANALYSIS, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_GRAMMAR_ANALYSIS, value).apply()

    var targetPackages: List<String>
        get() {
            val raw = prefs.getString(KEY_TARGET_PACKAGES, DEFAULT_TARGET_PACKAGES.joinToString("\n"))
                .orEmpty()
            return parseTargetPackages(raw)
        }
        set(value) {
            val packages = value.map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            prefs.edit().putString(KEY_TARGET_PACKAGES, packages.joinToString("\n")).apply()
        }

    fun isTargetPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return targetPackages.contains(packageName)
    }

    fun apiProfiles(): List<ApiProfile> {
        val profiles = readApiProfiles()
        ensureProfilesPersisted(profiles)
        ensureSelectedProfileId(profiles)
        return profiles
    }

    fun selectedApiProfile(): ApiProfile {
        val profiles = apiProfiles()
        val selectedId = selectedApiProfileId()
        return profiles.firstOrNull { it.id == selectedId } ?: profiles.first()
    }

    fun selectedApiProfileId(): String {
        val profiles = readApiProfiles()
        return ensureSelectedProfileId(profiles)
    }

    fun selectApiProfile(id: String): Boolean {
        val profiles = apiProfiles()
        if (profiles.none { it.id == id }) return false
        prefs.edit().putString(KEY_SELECTED_API_PROFILE_ID, id).apply()
        writeLegacyApiKeys(profiles.first { it.id == id })
        return true
    }

    fun newApiProfile(): ApiProfile {
        val nextNumber = apiProfiles().size + 1
        return ApiProfile(
            id = UUID.randomUUID().toString(),
            name = "API $nextNumber",
            apiBaseUrl = DEFAULT_API_BASE_URL,
            apiKey = "",
            model = DEFAULT_MODEL
        )
    }

    fun saveApiProfile(profile: ApiProfile) {
        val normalized = profile.copy(
            name = profile.name.trim().ifBlank { "未命名 API" },
            apiBaseUrl = profile.apiBaseUrl.trim(),
            apiKey = profile.apiKey.trim(),
            model = profile.model.trim().ifBlank { DEFAULT_MODEL }
        )
        val profiles = apiProfiles().toMutableList()
        val existingIndex = profiles.indexOfFirst { it.id == normalized.id }
        if (existingIndex >= 0) {
            profiles[existingIndex] = normalized
        } else {
            profiles.add(normalized)
        }
        writeApiProfiles(profiles)
        prefs.edit().putString(KEY_SELECTED_API_PROFILE_ID, normalized.id).apply()
        writeLegacyApiKeys(normalized)
    }

    fun deleteApiProfile(id: String): Boolean {
        val profiles = apiProfiles()
        if (profiles.size <= 1) return false
        val remaining = profiles.filterNot { it.id == id }
        if (remaining.size == profiles.size) return false
        writeApiProfiles(remaining)
        val selectedId = prefs.getString(KEY_SELECTED_API_PROFILE_ID, null)
        if (selectedId == id) {
            prefs.edit().putString(KEY_SELECTED_API_PROFILE_ID, remaining.first().id).apply()
            writeLegacyApiKeys(remaining.first())
        }
        return true
    }

    private fun updateSelectedApiProfile(transform: (ApiProfile) -> ApiProfile) {
        saveApiProfile(transform(selectedApiProfile()))
    }

    private fun readApiProfiles(): List<ApiProfile> {
        val raw = prefs.getString(KEY_API_PROFILES, null)
        if (raw.isNullOrBlank()) return listOf(legacyApiProfile())

        val parsed = runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    val profile = ApiProfile(
                        id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
                        name = json.optString("name").ifBlank { "未命名 API" },
                        apiBaseUrl = json.optString("apiBaseUrl").ifBlank { DEFAULT_API_BASE_URL },
                        apiKey = json.optString("apiKey"),
                        model = json.optString("model").ifBlank { DEFAULT_MODEL }
                    )
                    add(profile)
                }
            }
        }.getOrDefault(emptyList())

        return parsed.ifEmpty { listOf(legacyApiProfile()) }
    }

    private fun writeApiProfiles(profiles: List<ApiProfile>) {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("apiBaseUrl", profile.apiBaseUrl)
                    .put("apiKey", profile.apiKey)
                    .put("model", profile.model)
            )
        }
        prefs.edit().putString(KEY_API_PROFILES, array.toString()).apply()
    }

    private fun ensureProfilesPersisted(profiles: List<ApiProfile>) {
        if (!prefs.contains(KEY_API_PROFILES)) {
            writeApiProfiles(profiles)
        }
    }

    private fun ensureSelectedProfileId(profiles: List<ApiProfile>): String {
        val selectedId = prefs.getString(KEY_SELECTED_API_PROFILE_ID, null)
        if (selectedId != null && profiles.any { it.id == selectedId }) return selectedId

        val fallback = profiles.first().id
        prefs.edit().putString(KEY_SELECTED_API_PROFILE_ID, fallback).apply()
        writeLegacyApiKeys(profiles.first())
        return fallback
    }

    private fun legacyApiProfile(): ApiProfile {
        return ApiProfile(
            id = LEGACY_PROFILE_ID,
            name = "默认 API",
            apiBaseUrl = prefs.getString(KEY_API_BASE_URL, DEFAULT_API_BASE_URL).orEmpty()
                .ifBlank { DEFAULT_API_BASE_URL },
            apiKey = prefs.getString(KEY_API_KEY, "").orEmpty(),
            model = prefs.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }
        )
    }

    private fun writeLegacyApiKeys(profile: ApiProfile) {
        prefs.edit()
            .putString(KEY_API_BASE_URL, profile.apiBaseUrl)
            .putString(KEY_API_KEY, profile.apiKey)
            .putString(KEY_MODEL, profile.model)
            .apply()
    }

    private fun parseTargetPackages(raw: String): List<String> {
        val parsed = raw.split('\n', ',', ';', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return parsed.ifEmpty { DEFAULT_TARGET_PACKAGES }
    }

    companion object {
        private const val PREFS_NAME = "x_japanese_furigana_settings"
        private const val KEY_API_BASE_URL = "api_base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_API_PROFILES = "api_profiles"
        private const val KEY_SELECTED_API_PROFILE_ID = "selected_api_profile_id"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_AUTO_GRAMMAR_ANALYSIS = "auto_grammar_analysis"
        private const val KEY_TARGET_PACKAGES = "target_packages"
        private const val LEGACY_PROFILE_ID = "default"

        private const val DEFAULT_API_BASE_URL = "https://api.openai.com/v1/chat/completions"
        private const val DEFAULT_MODEL = "gpt-4.1-mini"
        val DEFAULT_TARGET_PACKAGES = listOf("com.twitter.android", "com.x.android")
    }
}
