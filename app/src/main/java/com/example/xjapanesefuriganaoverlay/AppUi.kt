package com.example.xjapanesefuriganaoverlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.TextView

object AppUi {
    const val HAIR = 0xFF4B2D22.toInt()
    const val HAIR_DEEP = 0xFF241611.toInt()
    const val HAIR_SOFT = 0xFF7A5140.toInt()
    const val HEADBAND = 0xFFFFD44D.toInt()
    const val HEADBAND_DEEP = 0xFFE59B2D.toInt()
    const val UNIFORM_BLUE = 0xFFA9DBEA.toInt()
    const val UNIFORM_BLUE_DEEP = 0xFF4CAACD.toInt()
    const val PAPER = 0xFFF8FBFC.toInt()
    const val SURFACE = Color.WHITE
    const val SURFACE_TINT = 0xFFEEF8FB.toInt()
    const val INK = 0xFF1C1816.toInt()
    const val MUTED = 0xFF75665F.toInt()
    const val DANGER = 0xFFD84A36.toInt()

    fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }

    fun appBackground(): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFFF9FCFD.toInt(), 0xFFEEF8FB.toInt(), 0xFFF5EFEA.toInt())
        )
    }

    fun heroBackground(context: Context): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(HAIR, HAIR_SOFT, UNIFORM_BLUE)
        ).apply {
            cornerRadius = dp(context, 22).toFloat()
            setStroke(dp(context, 1), 0x44FFFFFF)
        }
    }

    fun sectionBackground(context: Context, tinted: Boolean = false): GradientDrawable {
        return rounded(
            context = context,
            color = if (tinted) SURFACE_TINT else SURFACE,
            radiusDp = if (tinted) 16 else 18,
            strokeColor = if (tinted) 0xFFC8E6EF.toInt() else 0xFFDDEDF2.toInt()
        )
    }

    fun inputBackground(context: Context): GradientDrawable {
        return rounded(
            context = context,
            color = 0xFFFCFEFF.toInt(),
            radiusDp = 12,
            strokeColor = 0xFFBFDCE5.toInt()
        )
    }

    fun hairButton(context: Context): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(HAIR, UNIFORM_BLUE_DEEP)
        ).apply {
            cornerRadius = dp(context, 14).toFloat()
        }
    }

    fun headbandButton(context: Context): GradientDrawable {
        return rounded(context, 0xFFFFF5D3.toInt(), 14, HEADBAND_DEEP)
    }

    fun ghostButton(context: Context): GradientDrawable {
        return rounded(context, 0xFFEAF7FB.toInt(), 14, 0xFF9FD2E0.toInt())
    }

    fun dangerButton(context: Context): GradientDrawable {
        return rounded(context, 0xFFFCE6E1.toInt(), 14, DANGER)
    }

    fun headbandRule(context: Context): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(HEADBAND, HEADBAND_DEEP, UNIFORM_BLUE)
        ).apply {
            cornerRadius = dp(context, 999).toFloat()
        }
    }

    fun rounded(
        context: Context,
        color: Int,
        radiusDp: Int,
        strokeColor: Int? = null,
        strokeWidthDp: Int = 1
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(context, radiusDp).toFloat()
            if (strokeColor != null) {
                setStroke(dp(context, strokeWidthDp), strokeColor)
            }
        }
    }

    fun title(textView: TextView) {
        textView.textSize = 24f
        textView.typeface = Typeface.DEFAULT_BOLD
        textView.setTextColor(INK)
    }

    fun body(textView: TextView) {
        textView.textSize = 14f
        textView.setTextColor(MUTED)
    }

    fun primary(button: Button) {
        button.background = hairButton(button.context)
        button.setTextColor(Color.WHITE)
        button.textSize = 14f
        button.isAllCaps = false
    }

    fun secondary(button: Button) {
        button.background = headbandButton(button.context)
        button.setTextColor(HAIR)
        button.textSize = 14f
        button.isAllCaps = false
    }

    fun ghost(button: Button) {
        button.background = ghostButton(button.context)
        button.setTextColor(HAIR)
        button.textSize = 14f
        button.isAllCaps = false
    }

    fun danger(button: Button) {
        button.background = dangerButton(button.context)
        button.setTextColor(DANGER)
        button.textSize = 14f
        button.isAllCaps = false
    }
}
