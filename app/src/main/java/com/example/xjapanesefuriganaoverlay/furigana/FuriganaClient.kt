package com.example.xjapanesefuriganaoverlay.furigana

import android.content.Context
import android.os.SystemClock
import com.example.xjapanesefuriganaoverlay.accessibility.XFuriganaPerf
import com.example.xjapanesefuriganaoverlay.data.SettingsRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Callback
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class FuriganaClient(context: Context) {
    private val settingsRepository = SettingsRepository(context.applicationContext)

    suspend fun requestAnnotations(originalText: String): Result<List<FuriganaAnnotation>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val startedAt = SystemClock.elapsedRealtime()
                val apiKey = settingsRepository.apiKey
                val model = settingsRepository.model
                val baseUrl = settingsRepository.apiBaseUrl
                if (apiKey.isBlank()) error("API Key 缺失")
                if (model.isBlank()) error("模型名缺失")
                if (baseUrl.isBlank()) error("API Base URL 缺失")

                val candidateStart = SystemClock.elapsedRealtime()
                val candidates = FuriganaPromptBuilder.annotationCandidates(originalText)
                val candidateMs = SystemClock.elapsedRealtime() - candidateStart
                XFuriganaPerf.d(
                    "llm annotations prepare textLen=${originalText.length} " +
                        "candidates=${candidates.size} candidateMs=$candidateMs model=$model"
                )
                if (candidates.isEmpty()) return@runCatching emptyList()

                val endpoint = resolveChatCompletionsEndpoint(baseUrl)
                requestAnnotationsWithStrategy(endpoint, apiKey, model, originalText, candidates)
                    .also { annotations ->
                    XFuriganaPerf.d(
                        "llm annotations totalMs=${SystemClock.elapsedRealtime() - startedAt} " +
                            "annotations=${annotations.size}"
                    )
                }
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
        variants.forEachIndexed { index, variant ->
            try {
                val payloadStart = SystemClock.elapsedRealtime()
                val payload = buildRequestPayload(model, originalText, candidates, variant).toString()
                val payloadMs = SystemClock.elapsedRealtime() - payloadStart
                val httpStart = SystemClock.elapsedRealtime()
                val content = executeChatCompletion(endpoint, apiKey, payload)
                val httpMs = SystemClock.elapsedRealtime() - httpStart
                val parseStart = SystemClock.elapsedRealtime()
                val annotations = FuriganaJsonParser.parseCandidateReadings(originalText, content, candidates)
                val parseMs = SystemClock.elapsedRealtime() - parseStart
                XFuriganaPerf.d(
                    "llm annotations variant=$index payloadBytes=${payload.toByteArray().size} " +
                        "payloadMs=$payloadMs httpMs=$httpMs parseMs=$parseMs contentLen=${content.length}"
                )
                return annotations
            } catch (exception: LlmHttpException) {
                lastFailure = exception
                XFuriganaPerf.d(
                    "llm annotations variant=$index http_error=${exception.statusCode} " +
                        "compat=${exception.isLikelyParameterCompatibilityError()}"
                )
                if (!exception.isLikelyParameterCompatibilityError()) {
                    throw exception
                }
            } catch (exception: JSONException) {
                lastFailure = IOException(
                    "模型返回 JSON 不完整或非法，已尝试兼容重试：${exception.message}",
                    exception
                )
                XFuriganaPerf.d("llm annotations variant=$index json_error=${exception.message}")
            }
        }
        throw lastFailure ?: IOException("LLM 请求失败")
    }

    private suspend fun requestAnnotationsWithStrategy(
        endpoint: String,
        apiKey: String,
        model: String,
        originalText: String,
        candidates: List<FuriganaCandidate>
    ): List<FuriganaAnnotation> {
        if (candidates.size <= SINGLE_REQUEST_CANDIDATE_LIMIT) {
            return requestWithFallbacks(endpoint, apiKey, model, originalText, candidates)
        }

        val compactCandidates = selectCompleteCoverageCandidates(originalText, candidates)
        if (compactCandidates.size < candidates.size) {
            XFuriganaPerf.d(
                "llm annotations compact candidates=${candidates.size} -> ${compactCandidates.size} " +
                    "coverageComplete=${coversAllAnnotatableChars(originalText, compactCandidates)}"
            )
        }

        if (compactCandidates.size <= LONG_REQUEST_DIRECT_CANDIDATE_LIMIT) {
            return requestWithFallbacksSuspend(
                endpoint = endpoint,
                apiKey = apiKey,
                model = model,
                originalText = originalText,
                candidates = compactCandidates,
                httpClient = BATCH_HTTP_CLIENT
            )
        }

        return requestWithCompleteCandidateBatches(
            endpoint = endpoint,
            apiKey = apiKey,
            model = model,
            originalText = originalText,
            candidates = compactCandidates
        )
    }

    private suspend fun requestWithCompleteCandidateBatches(
        endpoint: String,
        apiKey: String,
        model: String,
        originalText: String,
        candidates: List<FuriganaCandidate>
    ): List<FuriganaAnnotation> = coroutineScope {
        val prioritizedCandidates = prioritizeCandidatesForLongRequest(candidates)
        val batches = prioritizedCandidates.chunked(LONG_REQUEST_CANDIDATE_BATCH_SIZE)
        val startedAt = SystemClock.elapsedRealtime()
        XFuriganaPerf.d(
            "llm annotations batched candidates=${candidates.size} batches=${batches.size} " +
                "batchSize=$LONG_REQUEST_CANDIDATE_BATCH_SIZE parallel=$MAX_PARALLEL_BATCH_REQUESTS " +
                "timeoutMs=$BATCH_READ_TIMEOUT_MS completeRequired=true"
        )

        val allResults = mutableListOf<Result<List<FuriganaAnnotation>>>()
        var batchIndexOffset = 0
        for ((waveIndex, waveBatches) in batches.chunked(MAX_PARALLEL_BATCH_REQUESTS).withIndex()) {
            val waveStartedAt = SystemClock.elapsedRealtime()
            val waveResults = waveBatches.mapIndexed { indexInWave, batch ->
                val batchIndex = batchIndexOffset + indexInWave
                async(Dispatchers.IO) {
                    requestBatchWithSplitRetry(
                        endpoint = endpoint,
                        apiKey = apiKey,
                        model = model,
                        originalText = originalText,
                        batch = batch,
                        label = batchIndex.toString()
                    )
                }
            }.awaitAll()
            allResults.addAll(waveResults)
            batchIndexOffset += waveBatches.size
            XFuriganaPerf.d(
                "llm annotations wave=$waveIndex batches=${waveBatches.size} " +
                    "success=${waveResults.count { it.isSuccess }} " +
                    "failure=${waveResults.count { it.isFailure }} " +
                    "waveMs=${SystemClock.elapsedRealtime() - waveStartedAt} " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
            )
        }

        val failures = allResults.filter { it.isFailure }
        if (failures.isNotEmpty()) {
            val firstError = failures.first().exceptionOrNull()
            XFuriganaPerf.d(
                "llm annotations batched complete=false failures=${failures.size}/${allResults.size} " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt} error=${firstError?.message}"
            )
            throw IOException(
                "髟ｿ譁・悽蛻・音隸ｷ豎ょ､ｱ雍･: ${failures.size}/${allResults.size}",
                firstError
            )
        }

        val annotations = allResults.flatMap { it.getOrThrow() }
        XFuriganaPerf.d(
            "llm annotations batched complete=true annotations=${annotations.size} " +
                "failures=0 elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
        )
        resolveOverlaps(annotations)
    }

    private suspend fun requestBatchWithSplitRetry(
        endpoint: String,
        apiKey: String,
        model: String,
        originalText: String,
        batch: List<FuriganaCandidate>,
        label: String,
        depth: Int = 0
    ): Result<List<FuriganaAnnotation>> = coroutineScope {
        val batchStartedAt = SystemClock.elapsedRealtime()
        val firstAttempt = runCatching {
            requestWithFallbacksSuspend(
                endpoint = endpoint,
                apiKey = apiKey,
                model = model,
                originalText = originalText,
                candidates = batch,
                httpClient = BATCH_HTTP_CLIENT
            )
        }.onSuccess { annotations ->
            XFuriganaPerf.d(
                "llm annotations batch=$label depth=$depth success=true candidates=${batch.size} " +
                    "annotations=${annotations.size} ms=${SystemClock.elapsedRealtime() - batchStartedAt}"
            )
        }.onFailure { throwable ->
            XFuriganaPerf.d(
                "llm annotations batch=$label depth=$depth success=false candidates=${batch.size} " +
                    "ms=${SystemClock.elapsedRealtime() - batchStartedAt} error=${throwable.message}"
            )
        }

        if (firstAttempt.isSuccess ||
            batch.size <= MIN_SPLIT_BATCH_SIZE ||
            depth >= MAX_SPLIT_RETRY_DEPTH
        ) {
            return@coroutineScope firstAttempt
        }

        val splitStartedAt = SystemClock.elapsedRealtime()
        val splitSize = ((batch.size + 1) / 2).coerceAtLeast(MIN_SPLIT_BATCH_SIZE)
        val chunks = batch.chunked(splitSize)
        XFuriganaPerf.d(
            "llm annotations batch=$label split depth=$depth chunks=${chunks.size} " +
                "splitSize=$splitSize originalCandidates=${batch.size}"
        )
        val splitResults = chunks.mapIndexed { index, chunk ->
            async(Dispatchers.IO) {
                requestBatchWithSplitRetry(
                    endpoint = endpoint,
                    apiKey = apiKey,
                    model = model,
                    originalText = originalText,
                    batch = chunk,
                    label = "$label.$index",
                    depth = depth + 1
                )
            }
        }.awaitAll()

        val failed = splitResults.firstOrNull { it.isFailure }
        if (failed != null) {
            val error = failed.exceptionOrNull()
            XFuriganaPerf.d(
                "llm annotations batch=$label split success=false depth=$depth " +
                    "failed=${splitResults.count { it.isFailure }}/${splitResults.size} " +
                    "ms=${SystemClock.elapsedRealtime() - splitStartedAt} error=${error?.message}"
            )
            return@coroutineScope Result.failure(
                IOException("蛻・音蟆剰ｯ募､ｱ雍･ batch=$label depth=$depth", error)
            )
        }

        val annotations = splitResults.flatMap { it.getOrThrow() }
        XFuriganaPerf.d(
            "llm annotations batch=$label split success=true depth=$depth " +
                "annotations=${annotations.size} ms=${SystemClock.elapsedRealtime() - splitStartedAt}"
        )
        Result.success(resolveOverlaps(annotations))
    }

    private suspend fun requestWithCandidateBatches(
        endpoint: String,
        apiKey: String,
        model: String,
        originalText: String,
        candidates: List<FuriganaCandidate>
    ): List<FuriganaAnnotation> = coroutineScope {
        val prioritizedCandidates = prioritizeCandidatesForLongRequest(candidates)
        val batches = prioritizedCandidates.chunked(LONG_REQUEST_CANDIDATE_BATCH_SIZE)
        XFuriganaPerf.d(
            "llm annotations batched candidates=${candidates.size} batches=${batches.size} " +
                "batchSize=$LONG_REQUEST_CANDIDATE_BATCH_SIZE parallel=$MAX_PARALLEL_BATCH_REQUESTS " +
                "budgetMs=$LONG_REQUEST_RESULT_BUDGET_MS"
        )

        val allResults = mutableListOf<Result<List<FuriganaAnnotation>>>()
        val startedAt = SystemClock.elapsedRealtime()
        var batchIndexOffset = 0
        var partial = false
        var stopBatches = false
        waveLoop@ for ((waveIndex, waveBatches) in batches.chunked(MAX_PARALLEL_BATCH_REQUESTS).withIndex()) {
            if (stopBatches) break
            if (SystemClock.elapsedRealtime() - startedAt >= LONG_REQUEST_RESULT_BUDGET_MS &&
                allResults.any { it.isSuccess }
            ) {
                XFuriganaPerf.d("llm annotations budget reached before wave=$waveIndex")
                partial = true
                break
            }

            val wave = waveBatches.mapIndexed { indexInWave, batch ->
                val batchIndex = batchIndexOffset + indexInWave
                async(Dispatchers.IO) {
                    val batchStartedAt = SystemClock.elapsedRealtime()
                    runCatching {
                        requestWithFallbacksSuspend(endpoint, apiKey, model, originalText, batch)
                    }.onSuccess { annotations ->
                        XFuriganaPerf.d(
                            "llm annotations batch=$batchIndex success=true candidates=${batch.size} " +
                                "annotations=${annotations.size} ms=${SystemClock.elapsedRealtime() - batchStartedAt}"
                        )
                    }.onFailure { throwable ->
                        XFuriganaPerf.d(
                            "llm annotations batch=$batchIndex success=false candidates=${batch.size} " +
                                "ms=${SystemClock.elapsedRealtime() - batchStartedAt} error=${throwable.message}"
                        )
                    }
                }
            }
            batchIndexOffset += waveBatches.size

            val collectedIndexes = mutableSetOf<Int>()
            var firstSuccessAt: Long? = null
            while (true) {
                wave.forEachIndexed { index, deferred ->
                    if (index in collectedIndexes || !deferred.isCompleted) return@forEachIndexed
                    val result = runCatching { deferred.await() }.getOrElse { Result.failure(it) }
                    allResults.add(result)
                    collectedIndexes.add(index)
                    if (result.isSuccess && firstSuccessAt == null) {
                        firstSuccessAt = SystemClock.elapsedRealtime()
                    }
                }

                val now = SystemClock.elapsedRealtime()
                val elapsedMs = now - startedAt
                val hasSuccess = allResults.any { it.isSuccess }
                val waveCompleted = collectedIndexes.size == wave.size
                val firstSuccessGraceElapsed = firstSuccessAt
                    ?.let { now - it >= FIRST_SUCCESS_GRACE_MS }
                    ?: false
                val successBudgetElapsed = hasSuccess && elapsedMs >= LONG_REQUEST_RESULT_BUDGET_MS
                val noSuccessBudgetElapsed = !hasSuccess &&
                    elapsedMs >= LONG_REQUEST_RESULT_BUDGET_MS + NO_SUCCESS_EXTRA_WAIT_MS

                if (waveCompleted) {
                    XFuriganaPerf.d(
                        "llm annotations wave=$waveIndex batches=${wave.size} completed=true " +
                            "elapsedMs=$elapsedMs"
                    )
                    break
                }

                if (firstSuccessGraceElapsed || successBudgetElapsed || noSuccessBudgetElapsed) {
                    XFuriganaPerf.d(
                        "llm annotations wave=$waveIndex batches=${wave.size} completed=false " +
                            "completedBatches=${collectedIndexes.size} elapsedMs=$elapsedMs " +
                            "hasSuccess=$hasSuccess"
                    )
                    wave.filter { it.isActive }.forEach { it.cancel() }
                    if (hasSuccess) {
                        partial = true
                        stopBatches = true
                    }
                    break
                }

                delay(RESULT_POLL_INTERVAL_MS)
            }

            if (partial && allResults.any { it.isSuccess }) {
                break@waveLoop
            }
        }

        val annotations = allResults.flatMap { result ->
            result.getOrElse { emptyList() }
        }
        if (annotations.isEmpty()) {
            val error = allResults.firstOrNull { it.isFailure }?.exceptionOrNull()
            throw IOException(error?.message ?: "长文本分批请求全部失败", error)
        }

        val failures = allResults.count { it.isFailure }
        XFuriganaPerf.d(
            "llm annotations batched complete annotations=${annotations.size} failures=$failures " +
                "partial=$partial"
        )
        resolveOverlaps(annotations)
    }

    private suspend fun requestWithFallbacksSuspend(
        endpoint: String,
        apiKey: String,
        model: String,
        originalText: String,
        candidates: List<FuriganaCandidate>,
        httpClient: OkHttpClient = BATCH_HTTP_CLIENT
    ): List<FuriganaAnnotation> {
        val variants = listOf(
            RequestVariant(includeJsonMode = true, includeTemperature = true),
            RequestVariant(includeJsonMode = false, includeTemperature = true),
            RequestVariant(includeJsonMode = false, includeTemperature = false)
        )

        var lastFailure: Throwable? = null
        variants.forEachIndexed { index, variant ->
            try {
                val payloadStart = SystemClock.elapsedRealtime()
                val payload = buildRequestPayload(model, originalText, candidates, variant).toString()
                val payloadMs = SystemClock.elapsedRealtime() - payloadStart
                val httpStart = SystemClock.elapsedRealtime()
                val content = executeChatCompletionSuspend(endpoint, apiKey, payload, httpClient)
                val httpMs = SystemClock.elapsedRealtime() - httpStart
                val parseStart = SystemClock.elapsedRealtime()
                val annotations = FuriganaJsonParser.parseCandidateReadings(originalText, content, candidates)
                val parseMs = SystemClock.elapsedRealtime() - parseStart
                XFuriganaPerf.d(
                    "llm annotations variant=$index payloadBytes=${payload.toByteArray().size} " +
                        "payloadMs=$payloadMs httpMs=$httpMs parseMs=$parseMs contentLen=${content.length}"
                )
                return annotations
            } catch (exception: LlmHttpException) {
                lastFailure = exception
                XFuriganaPerf.d(
                    "llm annotations variant=$index http_error=${exception.statusCode} " +
                        "compat=${exception.isLikelyParameterCompatibilityError()}"
                )
                if (!exception.isLikelyParameterCompatibilityError()) {
                    throw exception
                }
            } catch (exception: JSONException) {
                lastFailure = IOException(
                    "模型返回 JSON 不完整或非法，已尝试兼容重试：${exception.message}",
                    exception
                )
                XFuriganaPerf.d("llm annotations variant=$index json_error=${exception.message}")
            }
        }
        throw lastFailure ?: IOException("LLM 请求失败")
    }

    private fun selectCompleteCoverageCandidates(
        originalText: String,
        candidates: List<FuriganaCandidate>
    ): List<FuriganaCandidate> {
        if (candidates.isEmpty()) return emptyList()
        val candidatesByStart = candidates
            .filter { containsAnnotatableChar(it.surface) }
            .groupBy { it.start }
        val selected = mutableListOf<FuriganaCandidate>()
        var cursor = 0
        while (cursor < originalText.length) {
            if (!isAnnotatableChar(originalText[cursor])) {
                cursor++
                continue
            }

            val candidate = chooseCompactCandidate(candidatesByStart[cursor].orEmpty())
            if (candidate == null) {
                cursor++
                continue
            }
            selected.add(candidate)
            cursor = maxOf(cursor + 1, candidate.end)
        }

        val deduped = selected
            .distinctBy { it.start to it.end }
            .sortedWith(compareBy<FuriganaCandidate> { it.start }.thenBy { it.end })

        return if (coversAllAnnotatableChars(originalText, deduped)) deduped else candidates
    }

    private fun chooseCompactCandidate(
        candidates: List<FuriganaCandidate>
    ): FuriganaCandidate? {
        if (candidates.isEmpty()) return null
        val notTooLong = candidates.filter {
            (it.end - it.start) <= MAX_COMPACT_CANDIDATE_LENGTH &&
                !hasHardSeparator(it.surface)
        }
        val pool = notTooLong.ifEmpty { candidates }
        return pool.maxWithOrNull(
            compareBy<FuriganaCandidate> { compactCandidateScore(it) }
                .thenBy { it.start }
                .thenBy { it.id }
        )
    }

    private fun compactCandidateScore(candidate: FuriganaCandidate): Int {
        val length = candidate.end - candidate.start
        val hasKana = candidate.surface.any { isKana(it) }
        val hasDigit = candidate.surface.any { isDigitLike(it) }
        val hasSeparator = hasHardSeparator(candidate.surface)
        val tooLongPenalty = if (length > MAX_COMPACT_CANDIDATE_LENGTH) 200 else 0
        val separatorPenalty = if (hasSeparator) 80 else 0
        val kanaBonus = if (hasKana && length <= MAX_COMPACT_CANDIDATE_LENGTH) 20 else 0
        val digitBonus = if (hasDigit && length > 1) 15 else 0
        return candidatePriority(candidate) + length * 4 + kanaBonus + digitBonus -
            tooLongPenalty - separatorPenalty
    }

    private fun coversAllAnnotatableChars(
        originalText: String,
        candidates: List<FuriganaCandidate>
    ): Boolean {
        if (originalText.none { isAnnotatableChar(it) }) return true
        val covered = BooleanArray(originalText.length)
        candidates.forEach { candidate ->
            val start = candidate.start.coerceIn(0, originalText.length)
            val end = candidate.end.coerceIn(start, originalText.length)
            for (index in start until end) {
                covered[index] = true
            }
        }
        return originalText.indices.all { index ->
            !isAnnotatableChar(originalText[index]) || covered[index]
        }
    }

    private fun containsAnnotatableChar(text: String): Boolean {
        return text.any { isAnnotatableChar(it) }
    }

    private fun isAnnotatableChar(char: Char): Boolean {
        return isKanji(char) || isDigitLike(char)
    }

    private fun isKanji(char: Char): Boolean {
        return char in '\u4E00'..'\u9FFF' || char in '\u3400'..'\u4DBF'
    }

    private fun isKana(char: Char): Boolean {
        return char in '\u3040'..'\u30FF' || char in '\u31F0'..'\u31FF'
    }

    private fun isDigitLike(char: Char): Boolean {
        return char in '0'..'9' || char in '\uFF10'..'\uFF19'
    }

    private fun hasHardSeparator(text: String): Boolean {
        return text.any { char ->
            char.isWhitespace() ||
                char in setOf(
                    '。', '、', '，', '．', '.', ',', '!', '！', '?', '？',
                    '「', '」', '『', '』', '【', '】', '（', '）', '(', ')',
                    '[', ']', '{', '}', '・', '/', '\\', '|', '\n', '\r', '\t'
                )
        }
    }

    private fun prioritizeCandidatesForLongRequest(
        candidates: List<FuriganaCandidate>
    ): List<FuriganaCandidate> {
        return candidates.sortedWith(
            compareByDescending<FuriganaCandidate> { candidatePriority(it) }
                .thenBy { it.start }
                .thenByDescending { it.end - it.start }
        )
    }

    private fun candidatePriority(candidate: FuriganaCandidate): Int {
        val length = candidate.end - candidate.start
        val hasDigit = candidate.surface.any { isDigitLike(it) }
        val hasKana = candidate.surface.any { isKana(it) }
        return when {
            hasDigit && length > 1 -> 50
            length >= 2 && hasKana -> 45
            length >= 3 -> 40
            length == 2 -> 30
            hasDigit -> 25
            else -> 10
        }
    }

    private suspend fun executeChatCompletionSuspend(
        endpoint: String,
        apiKey: String,
        payload: String,
        httpClient: OkHttpClient
    ): String = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val call = httpClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isActive) return
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        runCatching {
                            val body = response.body?.string().orEmpty()
                            if (!response.isSuccessful) {
                                throw LlmHttpException(response.code, body)
                            }
                            extractMessageContent(body)
                        }.fold(
                            onSuccess = { content ->
                                if (continuation.isActive) continuation.resume(content)
                            },
                            onFailure = { throwable ->
                                if (continuation.isActive) continuation.resumeWithException(throwable)
                            }
                        )
                    }
                }
            }
        )
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
        private const val SINGLE_REQUEST_CANDIDATE_LIMIT = 60
        private const val LONG_REQUEST_DIRECT_CANDIDATE_LIMIT = 12
        private const val LONG_REQUEST_CANDIDATE_BATCH_SIZE = 12
        private const val MAX_PARALLEL_BATCH_REQUESTS = 4
        private const val MAX_COMPACT_CANDIDATE_LENGTH = 8
        private const val BATCH_READ_TIMEOUT_MS = 24_000L
        private const val MIN_SPLIT_BATCH_SIZE = 4
        private const val MAX_SPLIT_RETRY_DEPTH = 2
        private const val LONG_REQUEST_RESULT_BUDGET_MS = 18_000L
        private const val NO_SUCCESS_EXTRA_WAIT_MS = 6_000L
        private const val FIRST_SUCCESS_GRACE_MS = 1_500L
        private const val RESULT_POLL_INTERVAL_MS = 200L
        private val HTTP_CLIENT = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
        private val BATCH_HTTP_CLIENT = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(BATCH_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private data class RequestVariant(
        val includeJsonMode: Boolean,
        val includeTemperature: Boolean
    )

    private fun resolveOverlaps(candidates: List<FuriganaAnnotation>): List<FuriganaAnnotation> {
        val selected = mutableListOf<FuriganaAnnotation>()
        candidates.sortedWith(
            compareBy<FuriganaAnnotation> { it.start }
                .thenByDescending { it.confidence }
                .thenByDescending { it.end - it.start }
        ).forEach { candidate ->
            val overlapping = selected.filter {
                rangesOverlap(candidate.start, candidate.end, it.start, it.end)
            }
            if (overlapping.isEmpty()) {
                selected.add(candidate)
            } else if (overlapping.all { shouldReplace(candidate, it) }) {
                selected.removeAll(overlapping.toSet())
                selected.add(candidate)
            }
        }
        return selected.sortedBy { it.start }
    }

    private fun rangesOverlap(startA: Int, endA: Int, startB: Int, endB: Int): Boolean {
        return maxOf(startA, startB) < minOf(endA, endB)
    }

    private fun shouldReplace(candidate: FuriganaAnnotation, existing: FuriganaAnnotation): Boolean {
        val candidateLength = candidate.end - candidate.start
        val existingLength = existing.end - existing.start
        return candidate.confidence > existing.confidence ||
            (candidate.confidence == existing.confidence && candidateLength > existingLength)
    }

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
