package com.sosdanfurigana

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

object AppUi {
    const val HAIR = 0xFF4B2D22.toInt()
    const val HAIR_DEEP = 0xFF241611.toInt()
    const val HAIR_SOFT = 0xFF7A5140.toInt()
    const val HEADBAND = 0xFFFFD44D.toInt()
    const val HEADBAND_DEEP = 0xFFE59B2D.toInt()
    const val HEADBAND_SOFT = 0xFFFFF3CC.toInt()
    const val UNIFORM_BLUE = 0xFFA9DBEA.toInt()
    const val UNIFORM_BLUE_DEEP = 0xFF4CAACD.toInt()
    const val PAPER = 0xFFF8FBFC.toInt()
    const val SURFACE = Color.WHITE
    const val SURFACE_TINT = 0xFFEEF8FB.toInt()
    const val STROKE = 0xFFE2EDF1.toInt()
    const val STROKE_STRONG = 0xFFC9DEE6.toInt()
    const val INK = 0xFF201613.toInt()
    const val MUTED = 0xFF6E5F58.toInt()
    const val DANGER = 0xFFC93A28.toInt()
    const val DANGER_SOFT = 0xFFFDEDEA.toInt()
    const val SUCCESS = 0xFF287A4D.toInt()
    const val SUCCESS_SOFT = 0xFFE8F5ED.toInt()
    const val WARNING = 0xFF9A5B00.toInt()
    const val WARNING_SOFT = 0xFFFFF0CC.toInt()
    const val CREAM = 0xFFFFF3CC.toInt()
    const val WARM_WHITE = 0xFFF6EBE0.toInt()

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
            intArrayOf(HAIR_DEEP, 0xFF3A241C.toInt(), HAIR)
        ).apply {
            cornerRadius = dp(context, 16).toFloat()
            setStroke(dp(context, 1), 0x40FFD44D)
        }
    }

    fun sectionBackground(context: Context, tinted: Boolean = false): GradientDrawable {
        return rounded(
            context = context,
            color = if (tinted) SURFACE_TINT else SURFACE,
            radiusDp = if (tinted) 12 else 16,
            strokeColor = if (tinted) 0xFFD2E8EF.toInt() else STROKE
        )
    }

    fun missionBackground(context: Context): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(HAIR_DEEP, HAIR)
        ).apply {
            cornerRadius = dp(context, 16).toFloat()
            setStroke(dp(context, 1), HEADBAND_DEEP)
        }
    }

    fun commandBackground(context: Context): GradientDrawable {
        return rounded(context, SURFACE_TINT, 12, STROKE_STRONG)
    }

    fun highlightedBackground(context: Context): GradientDrawable {
        return rounded(context, HEADBAND_SOFT, 10, HEADBAND_DEEP)
    }

    fun successBackground(context: Context): GradientDrawable {
        return rounded(context, SUCCESS_SOFT, 12, SUCCESS)
    }

    fun warningBackground(context: Context): GradientDrawable {
        return rounded(context, WARNING_SOFT, 12, WARNING)
    }

    fun inputBackground(context: Context): GradientDrawable {
        return rounded(
            context = context,
            color = 0xFFFCFEFF.toInt(),
            radiusDp = 12,
            strokeColor = STROKE_STRONG
        )
    }

    fun hairButton(context: Context): Drawable {
        return ripple(rounded(context, HAIR, 14), 0x40FFD44D)
    }

    fun headbandButton(context: Context): Drawable {
        return ripple(rounded(context, HEADBAND_SOFT, 14, HEADBAND_DEEP), 0x33E59B2D)
    }

    fun ghostButton(context: Context): Drawable {
        return ripple(rounded(context, SURFACE, 14, STROKE_STRONG), 0x294CAACD)
    }

    fun dangerButton(context: Context): Drawable {
        return ripple(rounded(context, DANGER_SOFT, 14, 0xFFE8A79D.toInt()), 0x26C93A28)
    }

    fun headbandRule(context: Context): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(HEADBAND, HEADBAND_DEEP, UNIFORM_BLUE)
        ).apply {
            cornerRadius = dp(context, 999).toFloat()
        }
    }

    fun ripple(content: GradientDrawable, rippleColor: Int): RippleDrawable {
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = content.cornerRadius
        }
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
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

    fun screenHeader(
        context: Context,
        title: String,
        subtitle: String,
        imageRes: Int? = null
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(context, 18), dp(context, 18), dp(context, 18), dp(context, 18))
            background = missionBackground(context)
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply {
                        text = title
                        textSize = 24f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(CREAM)
                    })
                    addView(TextView(context).apply {
                        text = subtitle
                        textSize = 13f
                        setTextColor(WARM_WHITE)
                        setLineSpacing(dp(context, 3).toFloat(), 1f)
                        setPadding(0, dp(context, 6), 0, 0)
                    })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            imageRes?.let { drawableRes ->
                addView(ImageView(context).apply {
                    setImageResource(drawableRes)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    contentDescription = null
                    background = rounded(context, Color.WHITE, 12, HEADBAND)
                    setPadding(dp(context, 3), dp(context, 3), dp(context, 3), dp(context, 3))
                }, LinearLayout.LayoutParams(dp(context, 76), dp(context, 76)).apply {
                    marginStart = dp(context, 14)
                })
            }
        }
    }

    fun sectionLabel(context: Context, label: String): TextView {
        return TextView(context).apply {
            text = label
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(HAIR)
            letterSpacing = 0.04f
        }
    }

    fun statusBadge(context: Context, label: String, active: Boolean): TextView {
        return TextView(context).apply {
            text = label
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            setTextColor(if (active) SUCCESS else DANGER)
            background = if (active) {
                successBackground(context)
            } else {
                rounded(context, DANGER_SOFT, 12, DANGER)
            }
            setPadding(dp(context, 10), dp(context, 6), dp(context, 10), dp(context, 6))
        }
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
