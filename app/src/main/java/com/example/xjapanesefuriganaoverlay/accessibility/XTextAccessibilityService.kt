package com.example.xjapanesefuriganaoverlay.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.xjapanesefuriganaoverlay.data.CurrentPostRepository
import com.example.xjapanesefuriganaoverlay.data.DetectedPost
import com.example.xjapanesefuriganaoverlay.data.SettingsRepository
import com.example.xjapanesefuriganaoverlay.japanese.JapaneseTextDetector
import com.example.xjapanesefuriganaoverlay.overlay.OverlayController
import com.example.xjapanesefuriganaoverlay.util.TextHash

class XTextAccessibilityService : AccessibilityService() {
    private lateinit var settingsRepository: SettingsRepository
    private var lastProcessedAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        settingsRepository = SettingsRepository(applicationContext)
        if (settingsRepository.enabled && Settings.canDrawOverlays(this)) {
            OverlayController.showButton(applicationContext)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::settingsRepository.isInitialized) {
            settingsRepository = SettingsRepository(applicationContext)
        }
        if (event == null) return
        if (!settingsRepository.enabled) {
            CurrentPostRepository.clear()
            OverlayController.hideAll()
            return
        }

        val packageName = event.packageName?.toString()
        if (packageName == packageNameOfThisApp()) {
            return
        }
        if (!settingsRepository.isTargetPackage(packageName)) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                CurrentPostRepository.clear()
            }
            return
        }

        if (!isSupportedEvent(event.eventType)) return

        val now = System.currentTimeMillis()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            now - lastProcessedAt < PROCESS_THROTTLE_MS
        ) {
            return
        }
        lastProcessedAt = now

        if (Settings.canDrawOverlays(this)) {
            OverlayController.showButton(applicationContext)
        }

        val root = rootInActiveWindow ?: return
        val collected = LinkedHashMap<String, DetectedPost>()
        collectTextNodes(root, packageName.orEmpty(), collected, IntArray(1))
        CurrentPostRepository.replace(collected.values.toList())
    }

    override fun onInterrupt() {
        CurrentPostRepository.clear()
        OverlayController.hideAll()
    }

    override fun onDestroy() {
        CurrentPostRepository.clear()
        OverlayController.hideAll()
        super.onDestroy()
    }

    private fun isSupportedEvent(eventType: Int): Boolean {
        return eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
    }

    private fun packageNameOfThisApp(): String = applicationContext.packageName

    private fun collectTextNodes(
        node: AccessibilityNodeInfo,
        packageName: String,
        out: LinkedHashMap<String, DetectedPost>,
        visitedCount: IntArray
    ) {
        if (visitedCount[0]++ > MAX_NODES_PER_WINDOW) return

        if (node.isVisibleToUser) {
            val text = normalizeNodeText(node.text?.toString())
            if (text != null && JapaneseTextDetector.isLikelyJapanesePost(text)) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val id = TextHash.sha256Short("$packageName\n$text\n$bounds")
                out.putIfAbsent(
                    text,
                    DetectedPost(
                        id = id,
                        text = text,
                        boundsInScreen = Rect(bounds),
                        packageName = packageName
                    )
                )
            }
        }

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            collectTextNodes(child, packageName, out, visitedCount)
        }
    }

    private fun normalizeNodeText(text: String?): String? {
        val normalized = text
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?: return null
        if (normalized.isBlank()) return null
        if (normalized.length > MAX_TEXT_LENGTH) return normalized.take(MAX_TEXT_LENGTH)
        return normalized
    }

    companion object {
        private const val PROCESS_THROTTLE_MS = 450L
        private const val MAX_NODES_PER_WINDOW = 700
        private const val MAX_TEXT_LENGTH = 500
    }
}
