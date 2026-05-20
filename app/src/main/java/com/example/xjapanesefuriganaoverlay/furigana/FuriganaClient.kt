package com.example.xjapanesefuriganaoverlay.furigana

import android.content.Context
import com.example.xjapanesefuriganaoverlay.data.SettingsRepository
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

class FuriganaClient(context: Context) {
    private val settingsRepository = SettingsRepository(context.applicationContext)

    suspend fun requestAnnotations(originalText: String): Result<List<FuriganaAnnotation>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val apiKey = settingsRepository.apiKey
                val model = settingsRepository.model
                val baseUrl = settingsRepository.apiBaseUrl
                if (apiKey.isBlank()) error("API Key 缺失")
                if (model.isBlank()) error("模型名缺失")
                if (baseUrl.isBlank()) error("API Base URL 缺失")

                val candidates = FuriganaPromptBuilder.annotationCandidates(originalText)
                if (candidates.isEmpty()) return@runCatching emptyList()

                val endpoint = resolveChatCompletionsEndpoint(baseUrl)
                requestWithFallbacks(endpoint, apiKey, model, originalText, candidates)
            }
        }
    }

    suspend fun requestSelectionReading(
        sourceText: String,
        selectedText: String,
        start: Int,
        end: Int
    ): Result<String> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val apiKey = settingsRepository.apiKey
                val model = settingsRepository.model
                val baseUrl = settingsRepository.apiBaseUrl
                if (apiKey.isBlank()) error("API Key 缺失")
                if (model.isBlank()) error("模型名缺失")
                if (baseUrl.isBlank()) error("API Base URL 缺失")
                if (selectedText.isBlank()) error("选中文本为空")

                val endpoint = resolveChatCompletionsEndpoint(baseUrl)
                requestSelectionReadingWithFallbacks(
                    endpoint = endpoint,
                    apiKey = apiKey,
                    model = model,
                    sourceText = sourceText,
                    selectedText = selectedText,
                    start = start,
                    end = end
                )
            }
        }
    }

    private fun requestWithFallbacks(
        endpoint: String,
        apiKey: String,
        model: String,
        originalText: String,
        candidates: List<FuriganaCandidate>
    ): List<FuriganaAnnotation> {
        val variants = listOf(
            RequestVariant(
                includeJsonMode = true,
                includeTemperature = true
            ),
            RequestVariant(
                includeJsonMode = false,
                includeTemperature = true
            ),
            RequestVariant(
                includeJsonMode = false,
                includeTemperature = false
            )
        )

        var lastFailure: Throwable? = null
        variants.forEach { variant ->
            try {
                val payload = buildRequestPayload(model, originalText, candidates, variant).toString()
                val content = executeChatCompletion(endpoint, apiKey, payload)
                return FuriganaJsonParser.parseCandidateReadings(originalText, content, candidates)
            } catch (exception: LlmHttpException) {
                lastFailure = exception
                if (!exception.isLikelyParameterCompatibilityError()) {
                    throw exception
                }
            } catch (exception: JSONException) {
                lastFailure = IOException(
                    "模型返回 JSON 不完整或非法，已尝试兼容重试：${exception.message}",
                    exception
                )
            }
        }
        throw lastFailure ?: IOException("LLM 请求失败")
    }

    private fun requestSelectionReadingWithFallbacks(
        endpoint: String,
        apiKey: String,
        model: String,
        sourceText: String,
        selectedText: String,
        start: Int,
        end: Int
    ): String {
        val variants = listOf(
            RequestVariant(includeJsonMode = true, includeTemperature = true),
            RequestVariant(includeJsonMode = false, includeTemperature = true),
            RequestVariant(includeJsonMode = false, includeTemperature = false)
        )

        var lastFailure: Throwable? = null
        variants.forEach { variant ->
            try {
                val payload = buildSelectionReadingPayload(
                    model = model,
                    sourceText = sourceText,
                    selectedText = selectedText,
                    start = start,
                    end = end,
                    variant = variant
                ).toString()
                val content = executeChatCompletion(endpoint, apiKey, payload)
                return FuriganaJsonParser.parseSelectionReading(content)
            } catch (exception: LlmHttpException) {
                lastFailure = exception
                if (!exception.isLikelyParameterCompatibilityError()) {
                    throw exception
                }
            } catch (exception: JSONException) {
                lastFailure = IOException("模型返回的读音 JSON 非法：${exception.message}", exception)
            }
        }
        throw lastFailure ?: IOException("LLM 请求失败")
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
                throw LlmHttpException(response.code, body)
            }
            return extractMessageContent(body)
        }
    }

    private fun buildRequestPayload(
        model: String,
        originalText: String,
        candidates: List<FuriganaCandidate>,
        variant: RequestVariant
    ): JSONObject {
        val messages = JSONArray()
            .put(
                JSONObject()
                    .put("role", "system")
                    .put("content", FuriganaPromptBuilder.systemPrompt())
            )
            .put(
                JSONObject()
                    .put("role", "user")
                    .put("content", FuriganaPromptBuilder.userPrompt(originalText, candidates))
            )

        val payload = JSONObject()
            .put("model", model)
            .put("messages", messages)

        if (variant.includeTemperature) {
            payload.put("temperature", 0)
        }
        if (variant.includeJsonMode) {
            payload.put("response_format", JSONObject().put("type", "json_object"))
        }
        return payload
    }

    private fun buildSelectionReadingPayload(
        model: String,
        sourceText: String,
        selectedText: String,
        start: Int,
        end: Int,
        variant: RequestVariant
    ): JSONObject {
        val messages = JSONArray()
            .put(
                JSONObject()
                    .put("role", "system")
                    .put("content", FuriganaPromptBuilder.selectionReadingSystemPrompt())
            )
            .put(
                JSONObject()
                    .put("role", "user")
                    .put(
                        "content",
                        FuriganaPromptBuilder.selectionReadingUserPrompt(
                            sourceText = sourceText,
                            selectedText = selectedText,
                            start = start,
                            end = end
                        )
                    )
            )

        val payload = JSONObject()
            .put("model", model)
            .put("messages", messages)

        if (variant.includeTemperature) {
            payload.put("temperature", 0)
        }
        if (variant.includeJsonMode) {
            payload.put("response_format", JSONObject().put("type", "json_object"))
        }
        return payload
    }

    private fun extractMessageContent(responseBody: String): String {
        val root = JSONObject(responseBody)
        if (root.has("error")) {
            val error = root.optJSONObject("error")
            error("LLM error: ${error?.optString("message") ?: root.get("error")}")
        }
        val choices = root.getJSONArray("choices")
        if (choices.length() == 0) error("LLM 返回 choices 为空")
        val message = choices.getJSONObject(0).getJSONObject("message")
        return message.getString("content")
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
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private data class RequestVariant(
        val includeJsonMode: Boolean,
        val includeTemperature: Boolean
    )

    private class LlmHttpException(
        val statusCode: Int,
        val responseBody: String
    ) : IOException("LLM HTTP $statusCode: ${responseBody.take(500)}") {
        fun isLikelyParameterCompatibilityError(): Boolean {
            if (statusCode !in setOf(400, 422)) return false
            val lower = responseBody.lowercase()
            return listOf(
                "unsupported",
                "unknown",
                "unrecognized",
                "invalid",
                "max_tokens",
                "max_completion_tokens",
                "response_format",
                "temperature",
                "json_object"
            ).any { lower.contains(it) }
        }
    }
}
