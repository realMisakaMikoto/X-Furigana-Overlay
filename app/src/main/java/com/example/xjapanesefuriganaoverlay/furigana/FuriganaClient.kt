package com.example.xjapanesefuriganaoverlay.furigana

import android.content.Context
import android.os.SystemClock
import com.example.xjapanesefuriganaoverlay.accessibility.XFuriganaPerf
import com.example.xjapanesefuriganaoverlay.data.FuriganaLexiconRepository
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
    private val lexiconRepository = FuriganaLexiconRepository(context.applicationContext)

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
                val rawCandidates = FuriganaPromptBuilder.annotationCandidates(originalText)
                val deterministicAnnotations = deterministicLocalAnnotations(originalText, rawCandidates)
                val lexiconAnnotations = lexiconAnnotations(
                    originalText = originalText,
                    candidates = rawCandidates,
                    existingLocalAnnotations = deterministicAnnotations
                )
                val localAnnotations = resolveOverlaps(deterministicAnnotations + lexiconAnnotations)
                val candidates = prepareLlmCandidates(originalText, rawCandidates, localAnnotations)
                val candidateMs = SystemClock.elapsedRealtime() - candidateStart
                XFuriganaPerf.d(
                    "llm annotations prepare textLen=${originalText.length} " +
                        "rawCandidates=${rawCandidates.size} requestCandidates=${candidates.size} " +
                        "localAnnotations=${localAnnotations.size} " +
                        "numericLocal=${deterministicAnnotations.size} " +
                        "lexiconLocal=${lexiconAnnotations.size} " +
                        "candidateMs=$candidateMs model=$model"
                )
                if (candidates.isEmpty()) return@runCatching localAnnotations

                val endpoint = resolveChatCompletionsEndpoint(baseUrl)
                val llmAnnotations = requestAnnotationsStrictlyByCandidate(
                    endpoint,
                    apiKey,
                    model,
                    originalText,
                    candidates
                )
                val repairAnnotations = requestMissingCoverageAnnotations(
                    endpoint = endpoint,
                    apiKey = apiKey,
                    model = model,
                    originalText = originalText,
                    rawCandidates = rawCandidates,
                    currentAnnotations = resolveOverlaps(localAnnotations + llmAnnotations)
                )
                val learnedAnnotations = resolveOverlaps(llmAnnotations + repairAnnotations)
                lexiconRepository.observeAnnotations(learnedAnnotations)
                resolveOverlaps(localAnnotations + learnedAnnotations)
                    .also { annotations ->
                    XFuriganaPerf.d(
                        "llm annotations totalMs=${SystemClock.elapsedRealtime() - startedAt} " +
                            "annotations=${annotations.size} local=${localAnnotations.size} " +
                            "llm=${llmAnnotations.size} repair=${repairAnnotations.size} strategy=strict_candidate"
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
        requestAnnotationsByTextSegments(
            endpoint = endpoint,
            apiKey = apiKey,
            model = model,
            originalText = originalText,
            candidates = candidates
        )?.let { return it }

        return requestAnnotationsWithoutSegmentation(
            endpoint = endpoint,
            apiKey = apiKey,
            model = model,
            originalText = originalText,
            candidates = candidates
        )
    }

    private suspend fun requestAnnotationsStrictlyByCandidate(
        endpoint: String,
        apiKey: String,
        model: String,
        originalText: String,
        candidates: List<FuriganaCandidate>
    ): List<FuriganaAnnotation> = coroutineScope {
        if (candidates.isEmpty()) return@coroutineScope emptyList()

        val startedAt = SystemClock.elapsedRealtime()
        XFuriganaPerf.d(
            "llm annotations strict start candidates=${candidates.size} " +
                "parallel=$MAX_PARALLEL_STRICT_CANDIDATE_REQUESTS"
        )

        val allResults = mutableListOf<Result<List<FuriganaAnnotation>>>()
        for ((waveIndex, waveCandidates) in candidates.chunked(MAX_PARALLEL_STRICT_CANDIDATE_REQUESTS).withIndex()) {
            val waveStartedAt = SystemClock.elapsedRealtime()
            val waveResults = waveCandidates.map { candidate ->
                async(Dispatchers.IO) {
                    val candidateStartedAt = SystemClock.elapsedRealtime()
                    runCatching {
                        requestWithFallbacksSuspend(
                            endpoint = endpoint,
                            apiKey = apiKey,
                            model = model,
                            originalText = originalText,
                            candidates = listOf(candidate),
                            httpClient = STRICT_CANDIDATE_HTTP_CLIENT
                        ).filterStrictCandidateResult(candidate)
                    }.onSuccess { annotations ->
                        XFuriganaPerf.d(
                            "llm annotations strict candidate=${candidate.id} " +
                                "surface=${candidate.surface} success=true annotations=${annotations.size} " +
                                "ms=${SystemClock.elapsedRealtime() - candidateStartedAt}"
                        )
                    }.onFailure { throwable ->
                        XFuriganaPerf.d(
                            "llm annotations strict candidate=${candidate.id} " +
                                "surface=${candidate.surface} success=false " +
                                "ms=${SystemClock.elapsedRealtime() - candidateStartedAt} " +
                                "error=${throwable.message}"
                        )
                    }
                }
            }.awaitAll()
            allResults.addAll(waveResults)
            XFuriganaPerf.d(
                "llm annotations strict wave=$waveIndex candidates=${waveCandidates.size} " +
                    "success=${waveResults.count { it.isSuccess }} " +
                    "failure=${waveResults.count { it.isFailure }} " +
                    "waveMs=${SystemClock.elapsedRealtime() - waveStartedAt}"
            )
        }

        val annotations = allResults.flatMap { it.getOrElse { emptyList() } }
        val failures = allResults.count { it.isFailure }
        XFuriganaPerf.d(
            "llm annotations strict complete annotations=${annotations.size} " +
                "failures=$failures elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
        )
        resolveOverlaps(annotations)
    }

    private fun List<FuriganaAnnotation>.filterStrictCandidateResult(
        candidate: FuriganaCandidate
    ): List<FuriganaAnnotation> {
        return filter { annotation ->
            annotation.start == candidate.start &&
                annotation.end == candidate.end &&
                annotation.surface == candidate.surface
        }.take(1)
    }

    private suspend fun requestAnnotationsWithoutSegmentation(
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

    private suspend fun requestAnnotationsByTextSegments(
        endpoint: String,
        apiKey: String,
        model: String,
        originalText: String,
        candidates: List<FuriganaCandidate>
    ): List<FuriganaAnnotation>? = coroutineScope {
        val segments = candidateSegmentsForParallelRequests(originalText, candidates)
        if (segments.size < 2) return@coroutineScope null

        val startedAt = SystemClock.elapsedRealtime()
        XFuriganaPerf.d(
            "llm annotations segmented start textLen=${originalText.length} " +
                "candidates=${candidates.size} segments=${segments.size} " +
                "parallel=$MAX_PARALLEL_SEGMENT_REQUESTS"
        )

        val allResults = mutableListOf<Result<List<FuriganaAnnotation>>>()
        for ((waveIndex, waveSegments) in segments.chunked(MAX_PARALLEL_SEGMENT_REQUESTS).withIndex()) {
            val waveStartedAt = SystemClock.elapsedRealtime()
            val waveResults = waveSegments.mapIndexed { indexInWave, segment ->
                val segmentIndex = waveIndex * MAX_PARALLEL_SEGMENT_REQUESTS + indexInWave
                async(Dispatchers.IO) {
                    val segmentStartedAt = SystemClock.elapsedRealtime()
                    runCatching {
                        requestWithFallbacksSuspend(
                            endpoint = endpoint,
                            apiKey = apiKey,
                            model = model,
                            originalText = originalText,
                            candidates = segment.candidates,
                            httpClient = SEGMENT_HTTP_CLIENT
                        )
                    }.onSuccess { annotations ->
                        XFuriganaPerf.d(
                            "llm annotations segment=$segmentIndex success=true " +
                                "range=${segment.range.start}-${segment.range.end} " +
                                "candidates=${segment.candidates.size} annotations=${annotations.size} " +
                                "ms=${SystemClock.elapsedRealtime() - segmentStartedAt}"
                        )
                    }.onFailure { throwable ->
                        XFuriganaPerf.d(
                            "llm annotations segment=$segmentIndex success=false " +
                                "range=${segment.range.start}-${segment.range.end} " +
                                "candidates=${segment.candidates.size} " +
                                "ms=${SystemClock.elapsedRealtime() - segmentStartedAt} " +
                                "error=${throwable.message}"
                        )
                    }
                }
            }.awaitAll()

            allResults.addAll(waveResults)
            XFuriganaPerf.d(
                "llm annotations segmented wave=$waveIndex segments=${waveSegments.size} " +
                    "success=${waveResults.count { it.isSuccess }} " +
                    "failure=${waveResults.count { it.isFailure }} " +
                    "waveMs=${SystemClock.elapsedRealtime() - waveStartedAt}"
            )
        }

        val failures = allResults.filter { it.isFailure }
        if (failures.isNotEmpty()) {
            val firstError = failures.first().exceptionOrNull()
            XFuriganaPerf.d(
                "llm annotations segmented fallback failures=${failures.size}/${allResults.size} " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt} error=${firstError?.message}"
            )
            return@coroutineScope null
        }

        val annotations = resolveOverlaps(allResults.flatMap { it.getOrThrow() })
        XFuriganaPerf.d(
            "llm annotations segmented complete annotations=${annotations.size} " +
                "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
        )
        annotations
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

    private fun deterministicLocalAnnotations(
        originalText: String,
        candidates: List<FuriganaCandidate>
    ): List<FuriganaAnnotation> {
        val deterministicAnnotations = candidates.mapNotNull { candidate ->
            val reading = SPECIAL_SURFACE_READINGS[candidate.surface]
                ?: JapaneseNumberReading.readNumericExpression(candidate.surface)
                ?: return@mapNotNull null
            if (candidate.start < 0 || candidate.end > originalText.length) {
                return@mapNotNull null
            }
            if (originalText.substring(candidate.start, candidate.end) != candidate.surface) {
                return@mapNotNull null
            }
            FuriganaAnnotation(
                surface = candidate.surface,
                reading = reading,
                start = candidate.start,
                end = candidate.end,
                confidence = 1.0
            )
        }.sortedWith(
            compareBy<FuriganaAnnotation> { it.start }
                .thenByDescending { it.end - it.start }
        )

        val selected = mutableListOf<FuriganaAnnotation>()
        deterministicAnnotations.forEach { annotation ->
            if (selected.none { rangesOverlap(annotation.start, annotation.end, it.start, it.end) }) {
                selected.add(annotation)
            }
        }
        return selected.sortedBy { it.start }
    }

    private fun lexiconAnnotations(
        originalText: String,
        candidates: List<FuriganaCandidate>,
        existingLocalAnnotations: List<FuriganaAnnotation>
    ): List<FuriganaAnnotation> {
        val coveredRanges = existingLocalAnnotations.map { TextRange(it.start, it.end) }
        val selected = mutableListOf<FuriganaAnnotation>()
        candidates
            .filter { candidate ->
                containsAnnotatableChar(candidate.surface) &&
                    coveredRanges.none { rangesOverlap(candidate.start, candidate.end, it.start, it.end) }
            }
            .sortedWith(
                compareBy<FuriganaCandidate> { it.start }
                    .thenByDescending { it.end - it.start }
            )
            .forEach { candidate ->
                if (selected.any { rangesOverlap(candidate.start, candidate.end, it.start, it.end) }) {
                    return@forEach
                }
                if (candidate.start < 0 || candidate.end > originalText.length) return@forEach
                if (originalText.substring(candidate.start, candidate.end) != candidate.surface) return@forEach
                val hit = lexiconRepository.lookup(candidate.surface) ?: return@forEach
                selected.add(
                    FuriganaAnnotation(
                        surface = candidate.surface,
                        reading = hit.reading,
                        start = candidate.start,
                        end = candidate.end,
                        confidence = LEXICON_HIT_CONFIDENCE
                    )
                )
            }

        if (selected.isNotEmpty()) {
            XFuriganaPerf.d(
                "llm annotations lexicon hits=${selected.size} " +
                    "surfaces=${selected.joinToString("|") { it.surface }.take(120)}"
            )
        }
        return selected.sortedBy { it.start }
    }

    private fun prepareLlmCandidates(
        originalText: String,
        candidates: List<FuriganaCandidate>,
        localAnnotations: List<FuriganaAnnotation>
    ): List<FuriganaCandidate> {
        val locallyCoveredRanges = localAnnotations.map { TextRange(it.start, it.end) }
        val eligibleCandidates = candidates.filter { candidate ->
            containsAnnotatableChar(candidate.surface) &&
                locallyCoveredRanges.none {
                    rangesOverlap(candidate.start, candidate.end, it.start, it.end)
                }
        }
        if (eligibleCandidates.isEmpty()) return emptyList()
        return selectCompleteCoverageCandidates(
            originalText = originalText,
            candidates = eligibleCandidates,
            alreadyCoveredRanges = locallyCoveredRanges
        )
    }

    private suspend fun requestMissingCoverageAnnotations(
        endpoint: String,
        apiKey: String,
        model: String,
        originalText: String,
        rawCandidates: List<FuriganaCandidate>,
        currentAnnotations: List<FuriganaAnnotation>
    ): List<FuriganaAnnotation> {
        val repairCandidates = missingCoverageCandidates(
            originalText = originalText,
            rawCandidates = rawCandidates,
            currentAnnotations = currentAnnotations
        )
        if (repairCandidates.isEmpty()) return emptyList()

        XFuriganaPerf.d(
            "llm annotations repair start missingCandidates=${repairCandidates.size} " +
                "surfaces=${repairCandidates.joinToString("|") { it.surface }.take(160)}"
        )
        val startedAt = SystemClock.elapsedRealtime()
        return runCatching {
            requestAnnotationsStrictlyByCandidate(
                endpoint = endpoint,
                apiKey = apiKey,
                model = model,
                originalText = originalText,
                candidates = repairCandidates
            )
        }.onSuccess { annotations ->
            XFuriganaPerf.d(
                "llm annotations repair success annotations=${annotations.size} " +
                    "ms=${SystemClock.elapsedRealtime() - startedAt}"
            )
        }.onFailure { throwable ->
            XFuriganaPerf.d(
                "llm annotations repair failed candidates=${repairCandidates.size} " +
                    "ms=${SystemClock.elapsedRealtime() - startedAt} error=${throwable.message}"
            )
        }.getOrDefault(emptyList())
    }

    private fun missingCoverageCandidates(
        originalText: String,
        rawCandidates: List<FuriganaCandidate>,
        currentAnnotations: List<FuriganaAnnotation>
    ): List<FuriganaCandidate> {
        val coveredRanges = currentAnnotations.map { TextRange(it.start, it.end) }
        val candidatesByStart = rawCandidates
            .filter { candidate ->
                containsAnnotatableChar(candidate.surface) &&
                    !hasHardSeparator(candidate.surface) &&
                    coveredRanges.none { rangesOverlap(candidate.start, candidate.end, it.start, it.end) }
            }
            .groupBy { it.start }

        val selected = mutableListOf<FuriganaCandidate>()
        var cursor = 0
        while (cursor < originalText.length) {
            if (isCoveredByRange(cursor, coveredRanges) || !isAnnotatableChar(originalText[cursor])) {
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

        return selected
            .distinctBy { it.start to it.end }
            .sortedWith(compareByDescending<FuriganaCandidate> { candidatePriority(it) }.thenBy { it.start })
            .take(REPAIR_REQUEST_CANDIDATE_LIMIT)
            .sortedBy { it.start }
    }

    private fun candidateSegmentsForParallelRequests(
        originalText: String,
        candidates: List<FuriganaCandidate>
    ): List<CandidateSegment> {
        if (originalText.length < MIN_SEGMENTED_TEXT_LENGTH ||
            candidates.size < MIN_SEGMENTED_CANDIDATES
        ) {
            return emptyList()
        }

        val textRanges = splitTextRangesForParallelRequests(originalText)
        if (textRanges.size < 2) return emptyList()

        val segments = textRanges.mapNotNull { range ->
            val segmentCandidates = candidates.filter { candidate ->
                candidate.start >= range.start && candidate.end <= range.end
            }
            if (segmentCandidates.isEmpty()) {
                null
            } else {
                CandidateSegment(
                    range = range,
                    candidates = segmentCandidates
                )
            }
        }

        val assignedCount = segments.sumOf { it.candidates.size }
        if (assignedCount != candidates.size) {
            XFuriganaPerf.d(
                "llm annotations segmented disabled unassignedCandidates=" +
                    "${candidates.size - assignedCount}/${candidates.size}"
            )
            return emptyList()
        }

        return mergeCandidateSegmentsToLimit(segments, MAX_SEGMENTED_REQUESTS)
            .filter { it.candidates.isNotEmpty() }
            .takeIf { it.size >= 2 }
            .orEmpty()
    }

    private fun splitTextRangesForParallelRequests(text: String): List<TextRange> {
        val hardRanges = splitRangesAtDelimiters(
            text = text,
            sourceRanges = listOf(TextRange(0, text.length)),
            delimiters = HARD_SEGMENT_DELIMITERS,
            minLeftLength = MIN_SEGMENT_TEXT_LENGTH
        )

        val softRanges = splitRangesAtDelimiters(
            text = text,
            sourceRanges = hardRanges,
            delimiters = SOFT_SEGMENT_DELIMITERS,
            minLeftLength = MIN_SEGMENT_TEXT_LENGTH
        )

        val boundedRanges = softRanges.flatMap { range ->
            splitLongRangeAtSafeBoundary(text, range, TARGET_SEGMENT_TEXT_LENGTH)
        }

        return boundedRanges
            .mapNotNull { trimTextRange(text, it.start, it.end) }
            .filter { range -> range.end > range.start }
    }

    private fun splitRangesAtDelimiters(
        text: String,
        sourceRanges: List<TextRange>,
        delimiters: Set<Char>,
        minLeftLength: Int
    ): List<TextRange> {
        return sourceRanges.flatMap { sourceRange ->
            val ranges = mutableListOf<TextRange>()
            var start = sourceRange.start
            var index = sourceRange.start
            while (index < sourceRange.end) {
                if (text[index] in delimiters && index + 1 - start >= minLeftLength) {
                    ranges.add(TextRange(start, index + 1))
                    start = index + 1
                }
                index++
            }
            if (start < sourceRange.end) {
                ranges.add(TextRange(start, sourceRange.end))
            }
            mergeTinyTailRanges(ranges, minLeftLength)
        }
    }

    private fun splitLongRangeAtSafeBoundary(
        text: String,
        range: TextRange,
        targetLength: Int
    ): List<TextRange> {
        if (range.end - range.start <= targetLength + MIN_SEGMENT_TEXT_LENGTH) {
            return listOf(range)
        }

        val ranges = mutableListOf<TextRange>()
        var start = range.start
        while (range.end - start > targetLength + MIN_SEGMENT_TEXT_LENGTH) {
            val targetEnd = (start + targetLength).coerceAtMost(range.end)
            val splitAt = findSafeSplitIndex(text, start, targetEnd, range.end)
            if (splitAt <= start) break
            ranges.add(TextRange(start, splitAt))
            start = splitAt
        }
        ranges.add(TextRange(start, range.end))
        return mergeTinyTailRanges(ranges, MIN_SEGMENT_TEXT_LENGTH)
    }

    private fun findSafeSplitIndex(
        text: String,
        start: Int,
        targetEnd: Int,
        rangeEnd: Int
    ): Int {
        val searchStart = (targetEnd - SAFE_SPLIT_SEARCH_RADIUS).coerceAtLeast(start + MIN_SEGMENT_TEXT_LENGTH)
        val searchEnd = (targetEnd + SAFE_SPLIT_SEARCH_RADIUS).coerceAtMost(rangeEnd - MIN_SEGMENT_TEXT_LENGTH)
        if (searchStart >= searchEnd) return targetEnd

        for (index in searchEnd - 1 downTo searchStart) {
            val char = text[index]
            if (char in SOFT_SEGMENT_DELIMITERS || char in HARD_SEGMENT_DELIMITERS) {
                return index + 1
            }
            if (isKana(char) && char in SAFE_SPLIT_AFTER_KANA) {
                return index + 1
            }
        }
        return targetEnd
    }

    private fun mergeTinyTailRanges(
        ranges: List<TextRange>,
        minLength: Int
    ): List<TextRange> {
        if (ranges.size <= 1) return ranges
        val merged = ranges.toMutableList()
        var index = 1
        while (index < merged.size) {
            val current = merged[index]
            if (current.end - current.start < minLength) {
                val previous = merged[index - 1]
                merged[index - 1] = TextRange(previous.start, current.end)
                merged.removeAt(index)
            } else {
                index++
            }
        }
        return merged
    }

    private fun trimTextRange(text: String, start: Int, end: Int): TextRange? {
        var trimmedStart = start.coerceIn(0, text.length)
        var trimmedEnd = end.coerceIn(trimmedStart, text.length)
        while (trimmedStart < trimmedEnd && text[trimmedStart].isWhitespace()) {
            trimmedStart++
        }
        while (trimmedEnd > trimmedStart && text[trimmedEnd - 1].isWhitespace()) {
            trimmedEnd--
        }
        return if (trimmedEnd > trimmedStart) {
            TextRange(trimmedStart, trimmedEnd)
        } else {
            null
        }
    }

    private fun mergeCandidateSegmentsToLimit(
        segments: List<CandidateSegment>,
        maxSegments: Int
    ): List<CandidateSegment> {
        if (segments.size <= maxSegments) return segments
        val chunkSize = ((segments.size + maxSegments - 1) / maxSegments).coerceAtLeast(1)
        return segments.chunked(chunkSize).map { chunk ->
            CandidateSegment(
                range = TextRange(chunk.first().range.start, chunk.last().range.end),
                candidates = chunk.flatMap { it.candidates }
            )
        }
    }

    private fun selectCompleteCoverageCandidates(
        originalText: String,
        candidates: List<FuriganaCandidate>,
        alreadyCoveredRanges: List<TextRange> = emptyList()
    ): List<FuriganaCandidate> {
        if (candidates.isEmpty()) return emptyList()
        val candidatesByStart = candidates
            .filter { containsAnnotatableChar(it.surface) }
            .groupBy { it.start }
        val selected = mutableListOf<FuriganaCandidate>()
        var cursor = 0
        while (cursor < originalText.length) {
            if (isCoveredByRange(cursor, alreadyCoveredRanges) ||
                !isAnnotatableChar(originalText[cursor])
            ) {
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

        return if (coversAllAnnotatableChars(originalText, deduped, alreadyCoveredRanges)) {
            deduped
        } else {
            candidates
        }
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
        candidates: List<FuriganaCandidate>,
        alreadyCoveredRanges: List<TextRange> = emptyList()
    ): Boolean {
        if (originalText.none { isAnnotatableChar(it) }) return true
        val covered = BooleanArray(originalText.length)
        alreadyCoveredRanges.forEach { range ->
            for (index in range.start.coerceAtLeast(0) until range.end.coerceAtMost(originalText.length)) {
                covered[index] = true
            }
        }
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

    private fun isCoveredByRange(index: Int, ranges: List<TextRange>): Boolean {
        return ranges.any { index >= it.start && index < it.end }
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
        private const val MIN_SEGMENTED_TEXT_LENGTH = 50
        private const val MIN_SEGMENTED_CANDIDATES = 8
        private const val MIN_SEGMENT_TEXT_LENGTH = 18
        private const val TARGET_SEGMENT_TEXT_LENGTH = 38
        private const val SAFE_SPLIT_SEARCH_RADIUS = 10
        private const val MAX_SEGMENTED_REQUESTS = 3
        private const val MAX_PARALLEL_SEGMENT_REQUESTS = 3
        private const val REPAIR_REQUEST_CANDIDATE_LIMIT = 12
        private const val MAX_PARALLEL_STRICT_CANDIDATE_REQUESTS = 4
        private const val LEXICON_HIT_CONFIDENCE = 0.99
        private val SPECIAL_SURFACE_READINGS = mapOf(
            "月見里" to "やまなし",
            "小鳥遊" to "たかなし"
        )
        private val HARD_SEGMENT_DELIMITERS = setOf('。', '！', '？', '!', '?', '\n', '\r')
        private val SOFT_SEGMENT_DELIMITERS = setOf('、', '，', ',', '；', ';')
        private val SAFE_SPLIT_AFTER_KANA = setOf(
            'は',
            'が',
            'を',
            'に',
            'へ',
            'で',
            'と',
            'も',
            'や',
            'の'
        )
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
        private val SEGMENT_HTTP_CLIENT = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(BATCH_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
        private val STRICT_CANDIDATE_HTTP_CLIENT = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(BATCH_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private data class RequestVariant(
        val includeJsonMode: Boolean,
        val includeTemperature: Boolean
    )

    private data class TextRange(
        val start: Int,
        val end: Int
    )

    private data class CandidateSegment(
        val range: TextRange,
        val candidates: List<FuriganaCandidate>
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
