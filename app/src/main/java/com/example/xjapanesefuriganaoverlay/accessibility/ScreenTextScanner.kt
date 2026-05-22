package com.example.xjapanesefuriganaoverlay.accessibility

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.example.xjapanesefuriganaoverlay.util.TextHash

data class RawNodeText(
    val text: String,
    val bounds: Rect,
    val packageName: String
)

data class ScreenTextSnapshot(
    val packageName: String,
    val rawTexts: List<RawNodeText>,
    val rawTextsHash: String,
    val visitedNodeCount: Int,
    val hitNodeLimit: Boolean,
    val durationMs: Long,
    val screenBounds: Rect
)

object ScreenTextScanner {
    private val whitespaceRegex = Regex("\\s+")

    fun collect(
        root: AccessibilityNodeInfo,
        packageName: String,
        screenBounds: Rect,
        maxNodes: Int,
        maxTextLength: Int
    ): ScreenTextSnapshot {
        val startedAt = SystemClock.elapsedRealtime()
        val rawTexts = ArrayList<RawNodeText>(64)
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        var visitedCount = 0
        var hitNodeLimit = false
        stack.add(root)

        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (visitedCount >= maxNodes) {
                hitNodeLimit = true
                if (node !== root) node.recycle()
                break
            }

            visitedCount++
            try {
                if (node.isVisibleToUser) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    if (isReasonableBounds(bounds, screenBounds)) {
                        normalizeNodeText(node.text?.toString(), maxTextLength)?.let { text ->
                            rawTexts.add(
                                RawNodeText(
                                    text = text,
                                    bounds = Rect(bounds),
                                    packageName = packageName
                                )
                            )
                        }
                    }
                }

                for (index in node.childCount - 1 downTo 0) {
                    val child = node.getChild(index) ?: continue
                    stack.add(child)
                }
            } finally {
                if (node !== root) node.recycle()
            }
        }

        val rawHash = TextHash.sha256Short(
            rawTexts.asSequence()
                .map { it.text }
                .distinct()
                .sorted()
                .joinToString("\n")
        )
        return ScreenTextSnapshot(
            packageName = packageName,
            rawTexts = rawTexts,
            rawTextsHash = rawHash,
            visitedNodeCount = visitedCount,
            hitNodeLimit = hitNodeLimit,
            durationMs = SystemClock.elapsedRealtime() - startedAt,
            screenBounds = Rect(screenBounds)
        )
    }

    private fun normalizeNodeText(text: String?, maxTextLength: Int): String? {
        if (text.isNullOrBlank()) return null
        val normalized = whitespaceRegex.replace(text, " ").trim()
        if (normalized.isBlank()) return null
        return if (normalized.length > maxTextLength) {
            normalized.take(maxTextLength)
        } else {
            normalized
        }
    }

    private fun isReasonableBounds(bounds: Rect, screenBounds: Rect): Boolean {
        if (bounds.isEmpty) return false
        if (!Rect.intersects(bounds, screenBounds)) return false
        if (bounds.width() < 2 || bounds.height() < 2) return false
        return bounds.height() <= screenBounds.height() * 0.9f
    }
}
