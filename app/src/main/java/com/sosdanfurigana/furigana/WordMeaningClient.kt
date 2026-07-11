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

/**
 * 查询单个词的中文释义与 JLPT 等级。
 *
 * 独立于注音请求（FuriganaClient），单发、低优先级：失败就失败，
 * 不影响加词流程，也不拖慢注音链路。
 */
class WordMeaningClient(context: Context) {
    private val settingsRepository = SettingsRepository(context.applicationContext)

    data class WordMeaning(
        val meaning: String,
        val jlptLevel: String,
        val partOfSpeech: String
    )

    suspend fun requestMeaning(
        surface: String,
        reading: String,
        sourceText: String
    ): Result<WordMeaning> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val apiKey = settingsRepository.apiKey
                val model = settingsRepository.model
                val baseUrl = settingsRepository.apiBaseUrl
                if (apiKey.isBlank()) error("API Key 缺失")
                if (model.isBlank()) error("模型名缺失")
                if (baseUrl.isBlank()) error("API Base URL 缺失")

                val endpoint = resolveChatCompletionsEndpoint(baseUrl)
                var lastFailure: Throwable? = null
                listOf(true, false).forEach { jsonMode ->
                    try {
                        val payload = buildPayload(model, surface, reading, sourceText, jsonMode)
                        val content = executeChatCompletion(endpoint, apiKey, payload.toString())
                        return@runCatching parseMeaning(content)
                    } catch (exception: IOException) {
                        lastFailure = exception
                    } catch (exception: JSONException) {
                        lastFailure = exception
                    }
                }
                throw lastFailure ?: IOException("释义请求失败")
            }
        }
    }

    private fun buildPayload(
        model: String,
        surface: String,
        reading: String,
        sourceText: String,
        jsonMode: Boolean
    ): JSONObject {
        val systemPrompt =
            "你是日语词汇助手。用户给出一个日语词、它的读音和出现的原句。" +
                "返回一个 JSON 对象：" +
                "{\"meaning\":\"简体中文释义，简短（30字以内）\"," +
                "\"pos\":\"该词的词性，用中文，从这些里选：名词/代词/数词/动词/形容词/形容动词/" +
                "副词/连体词/接续词/助词/助动词/感叹词；没有把握就返回空字符串\"," +
                "\"jlpt\":\"该词大致的 JLPT 等级，N5/N4/N3/N2/N1 之一；没有把握就返回空字符串\"}。" +
                "只返回 JSON，不要输出其他内容。"
        val userPrompt = buildString {
            append("词：").append(surface).append('\n')
            append("读音：").append(reading).append('\n')
            if (sourceText.isNotBlank()) {
                append("原句：").append(sourceText.take(200))
            }
        }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(JSONObject().put("role", "user").put("content", userPrompt))
        val payload = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0)
        if (jsonMode) {
            payload.put("response_format", JSONObject().put("type", "json_object"))
        }
        return payload
    }

    private fun parseMeaning(content: String): WordMeaning {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end <= start) throw JSONException("释义响应中没有 JSON 对象")
        val json = JSONObject(content.substring(start, end + 1))
        val meaning = json.optString("meaning").trim().take(MAX_MEANING_LENGTH)
        if (meaning.isBlank()) throw JSONException("释义为空")
        val jlptRaw = json.optString("jlpt").trim().uppercase()
        val jlpt = if (jlptRaw in VALID_JLPT_LEVELS) jlptRaw else ""
        return WordMeaning(
            meaning = meaning,
            jlptLevel = jlpt,
            partOfSpeech = normalizePartOfSpeech(json.optString("pos"))
        )
    }

    private fun normalizePartOfSpeech(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        if (trimmed in VALID_PART_OF_SPEECH) return trimmed
        // 模型可能返回「他动词」「ナ形容词」这类变体，取白名单里能对上的那个
        return VALID_PART_OF_SPEECH.firstOrNull { trimmed.contains(it) }.orEmpty()
    }

    private fun executeChatCompletion(endpoint: String, apiKey: String, payload: String): String {
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        HTTP_CLIENT.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("释义请求 HTTP ${response.code}: ${body.take(300)}")
            }
            val root = JSONObject(body)
            if (root.has("error")) {
                val error = root.optJSONObject("error")
                throw IOException("LLM error: ${error?.optString("message") ?: root.get("error")}")
            }
            val choices = root.getJSONArray("choices")
            if (choices.length() == 0) throw IOException("LLM 返回 choices 为空")
            return choices.getJSONObject(0).getJSONObject("message").getString("content")
        }
    }

    private fun resolveChatCompletionsEndpoint(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return when {
            trimmed.endsWith("/chat/completions") -> trimmed
            trimmed.endsWith("/v1") -> "$trimmed/chat/completions"
            else -> "$trimmed/v1/chat/completions"
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_MEANING_LENGTH = 80
        private val VALID_JLPT_LEVELS = setOf("N1", "N2", "N3", "N4", "N5")

        // 注意顺序：形容动词要排在形容词、动词之前，避免 contains 误匹配
        private val VALID_PART_OF_SPEECH = listOf(
            "形容动词",
            "形容词",
            "助动词",
            "连体词",
            "接续词",
            "感叹词",
            "名词",
            "代词",
            "数词",
            "动词",
            "副词",
            "助词"
        )
        private val HTTP_CLIENT = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
