package com.sosdanfurigana

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

enum class BottomDestination(
    val label: String,
    val iconRes: Int
) {
    HOME("团部", R.drawable.ic_nav_home),
    NOTES("笔记", R.drawable.ic_nav_notes),
    WORDS("单词", R.drawable.ic_nav_words),
    SETTINGS("设置", R.drawable.ic_nav_settings)
}

object AppBottomNavigation {
    fun wrap(activity: Activity, content: View, selected: BottomDestination): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ColorDrawable(activity.getColor(R.color.haruhi_paper))
        }
        root.addView(
            content,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        root.addView(View(activity).apply {
            setBackgroundColor(activity.getColor(R.color.haruhi_stroke_strong))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, activity.dp(1)))
        root.addView(createBar(activity, selected))
        AppWindowInsets.apply(root)
        return root
    }

    private fun createBar(activity: Activity, selected: BottomDestination): View {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(activity.dp(6), activity.dp(5), activity.dp(6), activity.dp(7))
            setBackgroundColor(activity.getColor(R.color.haruhi_surface))
            elevation = activity.dp(8).toFloat()

            BottomDestination.entries.forEach { destination ->
                addView(
                    createItem(activity, destination, destination == selected),
                    LinearLayout.LayoutParams(0, activity.dp(58), 1f).apply {
                        marginStart = activity.dp(2)
                        marginEnd = activity.dp(2)
                    }
                )
            }
        }
    }

    private fun createItem(
        activity: Activity,
        destination: BottomDestination,
        selected: Boolean
    ): View {
        val foreground = activity.getColor(
            if (selected) R.color.haruhi_hair_deep else R.color.haruhi_muted
        )
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = !selected
            isFocusable = true
            isSelected = selected
            contentDescription = if (selected) "${destination.label}，当前页面" else destination.label
            background = if (selected) {
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = activity.dp(14).toFloat()
                    setColor(activity.getColor(R.color.haruhi_headband_soft))
                }
            } else {
                activity.getDrawable(android.R.drawable.list_selector_background)
            }

            addView(ImageView(activity).apply {
                setImageResource(destination.iconRes)
                setColorFilter(foreground)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(activity.dp(23), activity.dp(23)))
            addView(TextView(activity).apply {
                text = destination.label
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(foreground)
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setPadding(0, activity.dp(3), 0, 0)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })

            if (!selected) {
                setOnClickListener { navigate(activity, destination) }
            }
        }
    }

    private fun navigate(activity: Activity, destination: BottomDestination) {
        val target = when (destination) {
            BottomDestination.HOME -> MainActivity::class.java
            BottomDestination.NOTES -> NotesActivity::class.java
            BottomDestination.WORDS -> WordbookActivity::class.java
            BottomDestination.SETTINGS -> SettingsActivity::class.java
        }
        activity.startActivity(
            Intent(activity, target).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
        // Home remains the task root; switching tabs replaces the current secondary tab.
        if (activity !is MainActivity) activity.finish()
        @Suppress("DEPRECATION")
        if (AppMotion.animationsEnabled()) {
            val currentIndex = BottomDestination.entries.indexOfFirst { current ->
                when (current) {
                    BottomDestination.HOME -> activity is MainActivity
                    BottomDestination.NOTES -> activity is NotesActivity
                    BottomDestination.WORDS -> activity is WordbookActivity
                    BottomDestination.SETTINGS -> activity is SettingsActivity
                }
            }
            val targetIndex = BottomDestination.entries.indexOf(destination)
            if (targetIndex > currentIndex) {
                activity.overridePendingTransition(
                    R.anim.motion_tab_enter_from_right,
                    R.anim.motion_tab_exit_to_left
                )
            } else {
                activity.overridePendingTransition(
                    R.anim.motion_tab_enter_from_left,
                    R.anim.motion_tab_exit_to_right
                )
            }
        } else {
            activity.overridePendingTransition(0, 0)
        }
    }

    private fun Activity.dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
