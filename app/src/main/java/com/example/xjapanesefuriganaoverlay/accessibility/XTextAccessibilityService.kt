package com.example.xjapanesefuriganaoverlay.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.example.xjapanesefuriganaoverlay.data.CurrentPostRepository
import com.example.xjapanesefuriganaoverlay.data.DetectedPost
import com.example.xjapanesefuriganaoverlay.data.SettingsRepository
import com.example.xjapanesefuriganaoverlay.overlay.OverlayController
import java.lang.ref.WeakReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class XTextAccessibilityService : AccessibilityService() {
    private lateinit var settingsRepository: SettingsRepository
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pendingEventScanJob: Job? = null
    private var activeScanJob: Job? = null
    private var lastRawTextsHash: String? = null
    private var lastDetectedPosts: List<DetectedPost> = emptyList()
    private var lastScanCompletedAt = 0L
    private var contentDirtySinceLastScan = true
    private var overlayButtonShownForTarget = false
    private var lastTargetPackageName: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        currentService = WeakReference(this)
        settingsRepository = SettingsRepository(applicationContext)
        if (settingsRepository.enabled && Settings.canDrawOverlays(this)) {
            showOverlayButtonIfNeeded()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        ensureSettings()
        if (event == null) return

        val eventType = event.eventType
        val packageName = event.packageName?.toString()
        XFuriganaPerf.d("event type=$eventType package=$packageName")

        if (!settingsRepository.enabled) {
            clearStateAndOverlay()
            return
        }

        if (packageName == packageNameOfThisApp()) return
        if (!isSupportedEvent(eventType)) return

        if (!settingsRepository.isTargetPackage(packageName)) {
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                XFuriganaPerf.d("event non-target state package=$packageName clear=true")
                clearStateAndOverlay()
            }
            return
        }

        lastTargetPackageName = packageName
        showOverlayButtonIfNeeded()

        when (eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                contentDirtySinceLastScan = true
                scheduleEventScan(
                    packageName = packageName,
                    eventType = eventType,
                    reason = "event_state_changed",
                    delayMs = WINDOW_STATE_SCAN_DELAY_MS
                )
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                contentDirtySinceLastScan = true
                if (!OverlayController.isPanelVisible()) {
                    XFuriganaPerf.d(
                        "event content_changed marked_dirty panel_open=false full_scan=false"
                    )
                    return
                }
                scheduleEventScan(
                    packageName = packageName,
                    eventType = eventType,
                    reason = "event_content_changed",
                    delayMs = CONTENT_CHANGE_DEBOUNCE_MS
                )
            }
        }
    }

    override fun onInterrupt() {
        clearStateAndOverlay()
    }

    override fun onDestroy() {
        currentService = null
        pendingEventScanJob?.cancel()
        activeScanJob?.cancel()
        serviceScope.cancel()
        clearStateAndOverlay()
        super.onDestroy()
    }

    private fun requestScanNowInternal(callback: (List<DetectedPost>, ScanMetrics) -> Unit) {
        ensureSettings()
        pendingEventScanJob?.cancel()

        val now = SystemClock.elapsedRealtime()
        if (!contentDirtySinceLastScan &&
            lastDetectedPosts.isNotEmpty() &&
            now - lastScanCompletedAt <= RECENT_MANUAL_SCAN_REUSE_MS
        ) {
            val metrics = ScanMetrics(
                reason = "manual_recent_cache",
                packageName = lastTargetPackageName,
                totalDurationMs = 0L,
                candidatePostCount = lastDetectedPosts.size,
                rawHashCacheHit = true,
                repositoryReplaced = false
            )
            XFuriganaPerf.d(
                "scan reuse recent manual posts=${lastDetectedPosts.size} ageMs=${now - lastScanCompletedAt}"
            )
            callback(lastDetectedPosts, metrics)
            return
        }

        startScan(
            reason = "manual_open_panel",
            eventType = null,
            expectedPackageName = lastTargetPackageName,
            callback = callback
        )
    }

    private fun scheduleEventScan(
        packageName: String?,
        eventType: Int,
        reason: String,
        delayMs: Long
    ) {
        if (pendingEventScanJob?.isActive == true) {
            XFuriganaPerf.d("event debounce coalesced reason=$reason package=$packageName")
        }
        pendingEventScanJob?.cancel()
        pendingEventScanJob = serviceScope.launch {
            delay(delayMs)
            startScan(
                reason = reason,
                eventType = eventType,
                expectedPackageName = packageName,
                callback = null
            )
        }
    }

    private fun startScan(
        reason: String,
        eventType: Int?,
        expectedPackageName: String?,
        callback: ((List<DetectedPost>, ScanMetrics) -> Unit)?
    ) {
        activeScanJob?.cancel()
        activeScanJob = serviceScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            XFuriganaPerf.d("scan start reason=$reason package=$expectedPackageName")
            try {
                val result = scanCurrentWindow(
                    reason = reason,
                    eventType = eventType,
                    expectedPackageName = expectedPackageName,
                    startedAt = startedAt
                )
                callback?.invoke(result.posts, result.metrics)
                logScanFinished(result.metrics)
            } catch (exception: CancellationException) {
                XFuriganaPerf.d("scan cancelled reason=$reason")
                throw exception
            } catch (throwable: Throwable) {
                val fallback = CurrentPostRepository.getPosts()
                val metrics = ScanMetrics(
                    reason = reason,
                    eventType = eventType,
                    packageName = expectedPackageName,
                    totalDurationMs = SystemClock.elapsedRealtime() - startedAt,
                    candidatePostCount = fallback.size,
                    usedRepositoryFallback = true,
                    failure = throwable.message ?: throwable.javaClass.simpleName
                )
                XFuriganaPerf.d("scan failed reason=$reason error=${metrics.failure}")
                callback?.invoke(fallback, metrics)
            }
        }
    }

    private suspend fun scanCurrentWindow(
        reason: String,
        eventType: Int?,
        expectedPackageName: String?,
        startedAt: Long
    ): ScanResult {
        val root = rootInActiveWindow ?: return fallbackScanResult(
            reason = reason,
            eventType = eventType,
            packageName = expectedPackageName,
            startedAt = startedAt,
            failure = "rootInActiveWindow is null"
        )

        val packageName = root.packageName?.toString()
            ?.takeIf { settingsRepository.isTargetPackage(it) }
            ?: expectedPackageName
            ?: lastTargetPackageName

        if (!settingsRepository.isTargetPackage(packageName)) {
            root.recycle()
            return fallbackScanResult(
                reason = reason,
                eventType = eventType,
                packageName = packageName,
                startedAt = startedAt,
                failure = "active package is not in target package list"
            )
        }

        val screenBounds = screenBounds()
        val snapshot = try {
            ScreenTextScanner.collect(
                root = root,
                packageName = packageName.orEmpty(),
                screenBounds = screenBounds,
                maxNodes = MAX_NODES_PER_WINDOW,
                maxTextLength = MAX_TEXT_LENGTH
            )
        } finally {
            root.recycle()
        }

        if (snapshot.rawTextsHash == lastRawTextsHash) {
            contentDirtySinceLastScan = false
            lastScanCompletedAt = SystemClock.elapsedRealtime()
            val posts = lastDetectedPosts
            val metrics = ScanMetrics(
                reason = reason,
                eventType = eventType,
                packageName = packageName,
                totalDurationMs = SystemClock.elapsedRealtime() - startedAt,
                snapshotDurationMs = snapshot.durationMs,
                filterDurationMs = 0L,
                visitedNodeCount = snapshot.visitedNodeCount,
                rawTextCount = snapshot.rawTexts.size,
                candidatePostCount = posts.size,
                rawHashCacheHit = true,
                hitNodeLimit = snapshot.hitNodeLimit,
                repositoryReplaced = false
            )
            return ScanResult(posts, metrics)
        }

        val detection = withContext(Dispatchers.Default) {
            PostDetectionPipeline.detect(snapshot, MAX_CANDIDATE_POSTS)
        }
        lastRawTextsHash = snapshot.rawTextsHash
        lastDetectedPosts = detection.posts
        contentDirtySinceLastScan = false
        lastScanCompletedAt = SystemClock.elapsedRealtime()
        val replaced = CurrentPostRepository.replace(detection.posts, detection.postsHash)
        XFuriganaPerf.d(
            "repository replace changed=$replaced posts=${detection.posts.size} hash=${detection.postsHash}"
        )

        val metrics = ScanMetrics(
            reason = reason,
            eventType = eventType,
            packageName = packageName,
            totalDurationMs = SystemClock.elapsedRealtime() - startedAt,
            snapshotDurationMs = snapshot.durationMs,
            filterDurationMs = detection.durationMs,
            visitedNodeCount = snapshot.visitedNodeCount,
            rawTextCount = snapshot.rawTexts.size,
            candidatePostCount = detection.posts.size,
            rawHashCacheHit = false,
            hitNodeLimit = snapshot.hitNodeLimit,
            repositoryReplaced = replaced
        )
        return ScanResult(detection.posts, metrics)
    }

    private fun fallbackScanResult(
        reason: String,
        eventType: Int?,
        packageName: String?,
        startedAt: Long,
        failure: String
    ): ScanResult {
        val fallback = CurrentPostRepository.getPosts()
        return ScanResult(
            posts = fallback,
            metrics = ScanMetrics(
                reason = reason,
                eventType = eventType,
                packageName = packageName,
                totalDurationMs = SystemClock.elapsedRealtime() - startedAt,
                candidatePostCount = fallback.size,
                usedRepositoryFallback = true,
                failure = failure
            )
        )
    }

    private fun logScanFinished(metrics: ScanMetrics) {
        XFuriganaPerf.d(
            "scan end reason=${metrics.reason} package=${metrics.packageName} totalMs=${metrics.totalDurationMs} " +
                "snapshotMs=${metrics.snapshotDurationMs} filterMs=${metrics.filterDurationMs} " +
                "nodes=${metrics.visitedNodeCount} rawTexts=${metrics.rawTextCount} " +
                "posts=${metrics.candidatePostCount} rawCache=${metrics.rawHashCacheHit} " +
                "nodeLimit=${metrics.hitNodeLimit} repoReplace=${metrics.repositoryReplaced} " +
                "fallback=${metrics.usedRepositoryFallback} failure=${metrics.failure}"
        )
    }

    private fun clearStateAndOverlay() {
        pendingEventScanJob?.cancel()
        activeScanJob?.cancel()
        lastRawTextsHash = null
        lastDetectedPosts = emptyList()
        lastScanCompletedAt = 0L
        contentDirtySinceLastScan = true
        overlayButtonShownForTarget = false
        lastTargetPackageName = null
        CurrentPostRepository.clear()
        OverlayController.hideAll()
    }

    private fun showOverlayButtonIfNeeded() {
        if (!Settings.canDrawOverlays(this)) return
        if (overlayButtonShownForTarget && OverlayController.isButtonVisible()) return
        OverlayController.showButton(applicationContext)
        overlayButtonShownForTarget = OverlayController.isButtonVisible()
    }

    private fun screenBounds(): Rect {
        val metrics = resources.displayMetrics
        return Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    private fun ensureSettings() {
        if (!::settingsRepository.isInitialized) {
            settingsRepository = SettingsRepository(applicationContext)
        }
    }

    private fun isSupportedEvent(eventType: Int): Boolean {
        return eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
    }

    private fun packageNameOfThisApp(): String = applicationContext.packageName

    private data class ScanResult(
        val posts: List<DetectedPost>,
        val metrics: ScanMetrics
    )

    companion object {
        private var currentService: WeakReference<XTextAccessibilityService>? = null
        private const val CONTENT_CHANGE_DEBOUNCE_MS = 1000L
        private const val WINDOW_STATE_SCAN_DELAY_MS = 160L
        private const val RECENT_MANUAL_SCAN_REUSE_MS = 1000L
        private const val MAX_NODES_PER_WINDOW = 450
        private const val MAX_TEXT_LENGTH = 500
        private const val MAX_CANDIDATE_POSTS = 20

        fun requestScanNow(callback: (List<DetectedPost>, ScanMetrics) -> Unit): Boolean {
            val service = currentService?.get() ?: return false
            service.requestScanNowInternal(callback)
            return true
        }
    }
}
