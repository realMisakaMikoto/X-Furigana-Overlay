package com.sosdanfurigana.furigana

import android.content.Context
import com.sosdanfurigana.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class WordVerificationClient(context: Context) {
    private val settings = SettingsRepository(context.applicationContext)

    data class ProposedWord(
        val reading: String,
        val meaning: String,
        val partOfSpeech: String,
        val jlptLevel: String
    )

    data class Verification(
        val approved: Boolean,
        val reason: String,
        val suggestion: ProposedWord
    )

    suspend fun verifyEdit(
        surface: String,
        sourceText: String,
        proposal: ProposedWord
    ): Result<Verification> = withContext(Dispatchers.IO) {
        runCatching {
            val response = request(
                systemPrompt = EDIT_SYSTEM_PROMPT,
                userPrompt = buildString {
                    appendLine("词面：$surface")
                    appendLine("原句：${sourceText.take(400)}")
                    appendLine("用户修改的读音：${proposal.reading}")
                    appendLine("用户修改的中文释义：${proposal.meaning}")
                    appendLine("用户修改的词性：${proposal.partOfSpeech}")
                    append("用户修改的 JLPT：${proposal.jlptLevel}")
                }
            )
            Companion.parseVerification(response, proposal)
        }
    }

    suspend fun suggestTags(
        surface: String,
        reading: String,
        meaning: String,
        sourceText: String,
        currentTags: List<String>
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val response = request(
                systemPrompt = TAG_SYSTEM_PROMPT,
                userPrompt = buildString {
                    appendLine("词面：$surface")
                    appendLine("读音：$reading")
                    appendLine("中文释义：$meaning")
                    appendLine("原句：${sourceText.take(400)}")
                    append("已有标签：${currentTags.joinToString("、")}")
                }
            )
            Companion.parseTags(response)
        }
    }

    private fun request(systemPrompt: String, userPrompt: String): String {
        val apiKey = settings.apiKey
        val model = settings.model
        val baseUrl = settings.apiBaseUrl
        if (apiKey.isBlank()) error("请先在设置中填写 API Key")
        if (model.isBlank()) error("请先在设置中填写模型名称")
        if (baseUrl.isBlank()) error("请先在设置中填写 API 地址")

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(JSONObject().put("role", "user").put("content", userPrompt))
        val payload = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0)
        var lastError: Throwable? = null
        listOf(true, false).forEach { jsonMode ->
            try {
                val attempt = JSONObject(payload.toString())
                if (jsonMode) {
                    attempt.put("response_format", JSONObject().put("type", "json_object"))
                }
                return execute(resolveEndpoint(baseUrl), apiKey, attempt.toString())
            } catch (error: IOException) {
                lastError = error
            } catch (error: JSONException) {
                lastError = error
            }
        }
        throw lastError ?: IOException("模型验证失败")
    }

    private fun execute(endpoint: String, apiKey: String, payload: String): String {
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        HTTP_CLIENT.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("验证请求失败（HTTP ${response.code}）")
            val root = JSONObject(body)
            val error = root.optJSONObject("error")
            if (error != null) throw IOException(error.optString("message", "模型验证失败"))
            val choices = root.optJSONArray("choices")
            if (choices == null || choices.length() == 0) throw IOException("模型没有返回验证结果")
            return choices.getJSONObject(0).getJSONObject("message").getString("content")
        }
    }

    private fun resolveEndpoint(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return when {
            trimmed.endsWith("/chat/completions") -> trimmed
            trimmed.endsWith("/v1") -> "$trimmed/chat/completions"
            else -> "$trimmed/v1/chat/completions"
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val VALID_JLPT = setOf("", "N1", "N2", "N3", "N4", "N5")
        private const val MAX_TAG_LENGTH = 16
        private const val MAX_SUGGESTED_TAGS = 3
        private val HTTP_CLIENT = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()

        internal fun parseVerification(content: String, fallback: ProposedWord): Verification {
            val json = content.extractJsonObject()
            val suggestionJson = json.optJSONObject("suggestion") ?: JSONObject()
            val suggestion = ProposedWord(
                reading = suggestionJson.optString("reading").trim().ifBlank { fallback.reading },
                meaning = suggestionJson.optString("meaning").trim().ifBlank { fallback.meaning },
                partOfSpeech = suggestionJson.optString("pos").trim().ifBlank { fallback.partOfSpeech },
                jlptLevel = normalizeJlpt(
                    suggestionJson.optString("jlpt").trim().ifBlank { fallback.jlptLevel }
                )
            )
            return Verification(
                approved = json.optBoolean("approved", false),
                reason = json.optString("reason").trim().take(160),
                suggestion = suggestion
            )
        }

        internal fun parseTags(content: String): List<String> {
            val json = content.extractJsonObject()
            val tags = json.optJSONArray("tags") ?: throw JSONException("模型没有返回标签")
            return buildList {
                for (index in 0 until tags.length()) {
                    val value = tags.optString(index).trim().take(MAX_TAG_LENGTH)
                    if (value.isNotBlank() && value !in this) add(value)
                    if (size == MAX_SUGGESTED_TAGS) break
                }
            }.ifEmpty { throw JSONException("模型没有返回可用标签") }
        }

        private fun String.extractJsonObject(): JSONObject {
            val start = indexOf('{')
            val end = lastIndexOf('}')
            if (start < 0 || end <= start) throw JSONException("模型返回内容不是 JSON")
            return JSONObject(substring(start, end + 1))
        }

        private fun normalizeJlpt(raw: String): String {
            val normalized = raw.uppercase()
            return if (normalized in VALID_JLPT) normalized else ""
        }

        private const val EDIT_SYSTEM_PROMPT =
            "你是严谨的日语词典校对助手。结合词面和原句检查用户修改的读音、中文释义、词性和 JLPT 等级。" +
                "只有四项在当前语境中都可接受时 approved 才为 true。返回 JSON：" +
                "{\"approved\":true或false,\"reason\":\"简短中文说明\",\"suggestion\":{" +
                "\"reading\":\"平假名读音\",\"meaning\":\"简短中文释义\",\"pos\":\"中文词性\",\"jlpt\":\"N1-N5或空字符串\"}}。" +
                "只返回 JSON。"

        private const val TAG_SYSTEM_PROMPT =
            "你是日语学习词库整理助手。结合词义和原句推荐 2 到 3 个简短中文主题标签。" +
                "不要返回 JLPT、词性或复习状态，不要重复已有标签。返回 JSON：{\"tags\":[\"标签\"]}。只返回 JSON。"
    }
}
