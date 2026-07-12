package com.sosdanfurigana

import android.os.Build
import android.view.View
import android.view.WindowInsets
import kotlin.math.max

object AppWindowInsets {
    fun apply(view: View, includeIme: Boolean = false) {
        val initialLeft = view.paddingLeft
        val initialTop = view.paddingTop
        val initialRight = view.paddingRight
        val initialBottom = view.paddingBottom

        view.setOnApplyWindowInsetsListener { target, windowInsets ->
            val bars = systemBarInsets(windowInsets)
            val keyboardBottom = if (includeIme && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                windowInsets.getInsets(WindowInsets.Type.ime()).bottom
            } else {
                0
            }
            target.setPadding(
                initialLeft + bars.left,
                initialTop + bars.top,
                initialRight + bars.right,
                initialBottom + max(bars.bottom, keyboardBottom)
            )
            windowInsets
        }
        view.requestApplyInsets()
    }

    private fun systemBarInsets(windowInsets: WindowInsets): EdgeInsets {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = windowInsets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            EdgeInsets(insets.left, insets.top, insets.right, insets.bottom)
        } else {
            @Suppress("DEPRECATION")
            EdgeInsets(
                windowInsets.systemWindowInsetLeft,
                windowInsets.systemWindowInsetTop,
                windowInsets.systemWindowInsetRight,
                windowInsets.systemWindowInsetBottom
            )
        }
    }

    private data class EdgeInsets(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )
}
