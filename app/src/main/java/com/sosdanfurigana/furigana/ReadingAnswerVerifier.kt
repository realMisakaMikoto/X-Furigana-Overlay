package com.sosdanfurigana.furigana

import android.content.Context
import com.sosdanfurigana.data.ReadingAnswerNormalizer
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

data class ReadingVerification(
    val accepted: Boolean
)

object ReadingVerificationParser {
    fun parse(content: String): ReadingVerification {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end <= start) {
            throw JSONException("读音验证响应中没有 JSON 对象")
        }
        val json = JSONObject(content.substring(start, end + 1))
        if (!json.has("accepted")) {
            throw JSONException("读音验证响应缺少 accepted")
        }
        return ReadingVerification(
            accepted = json.getBoolean("accepted")
        )
    }
}

class ReadingAnswerVerifier(context: Context) {
    private val settingsRepository = SettingsRepository(context.applicationContext)

    suspend fun verify(
        sourceText: String,
        target: String,
        savedReading: String,
        userReading: String
    ): Result<ReadingVerification> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val apiKey = settingsRepository.apiKey
                val model = settingsRepository.model
                val baseUrl = settingsRepository.apiBaseUrl
                if (apiKey.isBlank()) error("API Key 缺失")
                if (model.isBlank()) error("模型名缺失")
                if (baseUrl.isBlank()) error("API 地址缺失")

                val normalizedUserReading = ReadingAnswerNormalizer.normalize(userReading)
                if (normalizedUserReading.isBlank()) error("读音为空")

                val endpoint = resolveChatCompletionsEndpoint(baseUrl)
                var lastFailure: Throwable? = null
                listOf(true, false).forEach { jsonMode ->
                    try {
                        val payload = buildPayload(
                            model = model,
                            sourceText = sourceText,
                            target = target,
                            savedReading = savedReading,
                            userReading = normalizedUserReading,
                            jsonMode = jsonMode
                        )
                        val content = executeChatCompletion(endpoint, apiKey, payload.toString())
                        return@runCatching ReadingVerificationParser.parse(content)
                    } catch (exception: IOException) {
                        lastFailure = exception
                    } catch (exception: JSONException) {
                        lastFailure = exception
                    }
                }
                throw lastFailure ?: IOException("读音验证失败")
            }
        }
    }

    private fun buildPayload(
        model: String,
        sourceText: String,
        target: String,
        savedReading: String,
        userReading: String,
        jsonMode: Boolean
    ): JSONObject {
        val systemPrompt =
            "你是严格的日语读音验证器。根据原句判断用户输入是否是目标词在该语境中的正确读音。" +
                "只验证当前语境，不要因为该词在其他语境可以这样读就通过。" +
                "保存读音仅供参考，它可能不完整；若用户读音在原句中确实成立，即使与保存读音不同也应通过。" +
                "拒绝时绝对不要写出、暗示或拼写正确读音，也不要改正用户输入。" +
                "返回 JSON：{\"accepted\":true/false}。只返回 JSON。"
        val userPrompt = buildString {
            append("原句：").append(sourceText.take(300)).append('\n')
            append("目标词：").append(target).append('\n')
            append("保存读音：").append(savedReading).append('\n')
            append("用户输入：").append(userReading)
        }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(JSONObject().put("role", "user").put("content", userPrompt))
        return JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0)
            .apply {
                if (jsonMode) put("response_format", JSONObject().put("type", "json_object"))
            }
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
                throw IOException("读音验证 HTTP ${response.code}: ${body.take(300)}")
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
        private val HTTP_CLIENT = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
