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
 * 整句语法作用分析：一次请求拿回整条笔记的分词 + 每个词的句子成分。
 * 独立于注音链路，按需触发，结果由调用方缓存。
 */
class GrammarAnalysisClient(context: Context) {
    private val settingsRepository = SettingsRepository(context.applicationContext)

    fun isConfigured(): Boolean {
        return settingsRepository.apiKey.isNotBlank() &&
            settingsRepository.model.isNotBlank() &&
            settingsRepository.apiBaseUrl.isNotBlank()
    }

    suspend fun analyze(originalText: String): Result<List<GrammarToken>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val apiKey = settingsRepository.apiKey
                val model = settingsRepository.model
                val baseUrl = settingsRepository.apiBaseUrl
                if (apiKey.isBlank()) error("API Key 缺失")
                if (model.isBlank()) error("模型名缺失")
                if (baseUrl.isBlank()) error("API Base URL 缺失")
                if (originalText.isBlank()) error("笔记原文为空")

                val endpoint = resolveChatCompletionsEndpoint(baseUrl)
                var lastFailure: Throwable? = null
                listOf(true, false).forEach { jsonMode ->
                    try {
                        val payload = buildPayload(model, originalText, jsonMode)
                        val content = executeChatCompletion(endpoint, apiKey, payload.toString())
                        return@runCatching parseTokens(originalText, content)
                    } catch (exception: IOException) {
                        lastFailure = exception
                    } catch (exception: JSONException) {
                        lastFailure = exception
                    }
                }
                throw lastFailure ?: IOException("语法分析请求失败")
            }
        }
    }

    private fun buildPayload(model: String, originalText: String, jsonMode: Boolean): JSONObject {
        val roles = GrammarRoles.ROLE_COLORS.keys.joinToString("、") + "、${GrammarRoles.OTHER}"
        val systemPrompt =
            "你是日语语法老师。把用户给出的日文原文按词切分，为每个词标注它在句子里的语法作用。" +
                "作用只能从这些里选：$roles。" +
                "助词一律标「助词」；动词及其活用标「谓语」；だ/です/である标「系动词」；" +
                "修饰名词或用言的成分标「修饰语」；「XはYです」中的 Y 标「补语」。" +
                "返回 JSON：{\"tokens\":[{\"t\":\"词面\",\"r\":\"作用\"}]}。" +
                "tokens 必须按原文出现顺序排列，词面必须是原文中的连续片段，" +
                "标点、空格、表情可以跳过。只返回 JSON，不要输出其他内容。"
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(JSONObject().put("role", "user").put("content", originalText.take(MAX_TEXT_LENGTH)))
        val payload = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0)
        if (jsonMode) {
            payload.put("response_format", JSONObject().put("type", "json_object"))
        }
        return payload
    }

    /** 把模型返回的顺序 token 对齐到原文字符区间；对不上的词跳过。 */
    private fun parseTokens(originalText: String, content: String): List<GrammarToken> {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end <= start) throw JSONException("语法响应中没有 JSON 对象")
        val json = JSONObject(content.substring(start, end + 1))
        val array = json.optJSONArray("tokens") ?: throw JSONException("语法响应缺少 tokens")

        val tokens = mutableListOf<GrammarToken>()
        var cursor = 0
        for (index in 0 until minOf(array.length(), MAX_TOKENS)) {
            val item = array.optJSONObject(index) ?: continue
            val surface = item.optString("t").trim()
            if (surface.isBlank()) continue
            val foundAt = originalText.indexOf(surface, cursor)
            if (foundAt < 0) continue
            tokens.add(
                GrammarToken(
                    surface = surface,
                    role = GrammarRoles.normalize(item.optString("r")),
                    start = foundAt,
                    end = foundAt + surface.length
                )
            )
            cursor = foundAt + surface.length
        }
        if (tokens.isEmpty()) throw JSONException("语法分析结果与原文对不上")
        return tokens
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
                throw IOException("语法分析 HTTP ${response.code}: ${body.take(300)}")
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
        private const val MAX_TEXT_LENGTH = 1000
        private const val MAX_TOKENS = 200
        private val HTTP_CLIENT = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
