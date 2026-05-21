package com.example.xjapanesefuriganaoverlay.accessibility

import android.util.Log
import com.example.xjapanesefuriganaoverlay.BuildConfig

data class ScanMetrics(
    val reason: String,
    val eventType: Int? = null,
    val packageName: String? = null,
    val totalDurationMs: Long = 0L,
    val snapshotDurationMs: Long = 0L,
    val filterDurationMs: Long = 0L,
    val visitedNodeCount: Int = 0,
    val rawTextCount: Int = 0,
    val candidatePostCount: Int = 0,
    val rawHashCacheHit: Boolean = false,
    val hitNodeLimit: Boolean = false,
    val repositoryReplaced: Boolean = false,
    val usedRepositoryFallback: Boolean = false,
    val failure: String? = null
)

object XFuriganaPerf {
    const val TAG = "XFuriganaPerf"

    fun d(message: String) {
        if (BuildConfig.DEBUG || Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, message)
        }
    }
}
