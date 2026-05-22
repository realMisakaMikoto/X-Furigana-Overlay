package com.example.xjapanesefuriganaoverlay

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.xjapanesefuriganaoverlay.data.WordbookEntry
import com.example.xjapanesefuriganaoverlay.data.WordbookRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WordbookActivity : Activity() {
    private lateinit var repository: WordbookRepository
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = WordbookRepository(applicationContext)
        setContentView(createContentView())
        renderWords()
    }

    private fun createContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = AppUi.appBackground()
        }

        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(18), dp(18), dp(18))
                background = AppUi.heroBackground(this@WordbookActivity)
                elevation = dp(3).toFloat()
                addView(
                    View(this@WordbookActivity).apply {
                        background = AppUi.headbandRule(this@WordbookActivity)
                    },
                    LinearLayout.LayoutParams(dp(86), dp(5))
                )
                addView(
                    TextView(this@WordbookActivity).apply {
                        text = "单词本"
                        textSize = 24f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setTextColor(0xFFFFFFFF.toInt())
                        setPadding(0, dp(14), 0, 0)
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
                addView(TextView(this@WordbookActivity).apply {
                    text = "把真正想记住的词收进这里。团长不负责替你背。"
                    textSize = 14f
                    setTextColor(0xFFEAF8FC.toInt())
                    setPadding(0, dp(6), 0, dp(12))
                })
                addView(Button(this@WordbookActivity).apply {
                    text = "清空单词本"
                    AppUi.secondary(this)
                    setOnClickListener {
                        repository.clear()
                        renderWords()
                        Toast.makeText(this@WordbookActivity, "已清空单词本", Toast.LENGTH_SHORT).show()
                    }
                })
            }
        )

        val scrollView = ScrollView(this)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        scrollView.addView(list)
        root.addView(
            scrollView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        return root
    }

    private fun renderWords() {
        list.removeAllViews()
        val words = repository.getWords()
        if (words.isEmpty()) {
            list.addView(emptyText("暂无单词。注音结果页可以把词加入单词本。"))
            return
        }
        words.forEach { word ->
            list.addView(wordView(word))
        }
    }

    private fun wordView(word: WordbookEntry): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = AppUi.sectionBackground(this@WordbookActivity)
            elevation = dp(1).toFloat()
            addView(TextView(this@WordbookActivity).apply {
                text = "${word.surface}　${word.reading}"
                textSize = 20f
                setTextColor(AppUi.HAIR)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@WordbookActivity).apply {
                text = formatTime(word.updatedAt)
                textSize = 12f
                setTextColor(AppUi.HAIR_SOFT)
                setPadding(0, dp(6), 0, 0)
            })
            addView(TextView(this@WordbookActivity).apply {
                text = word.sourceText
                textSize = 14f
                setTextColor(AppUi.MUTED)
                setPadding(0, dp(8), 0, 0)
            })
            addView(Button(this@WordbookActivity).apply {
                text = "删除"
                AppUi.danger(this)
                setOnClickListener {
                    repository.deleteWord(word.id)
                    renderWords()
                }
            })
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(10))
            }
        }
    }

    private fun emptyText(message: String): TextView {
        return TextView(this).apply {
            text = message
            textSize = 15f
            setTextColor(AppUi.MUTED)
            gravity = Gravity.CENTER
            background = AppUi.sectionBackground(this@WordbookActivity, tinted = true)
            setPadding(dp(12), dp(40), dp(12), dp(40))
        }
    }

    private fun roundedBackground(color: Int): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(8).toFloat()
        }
    }

    private fun formatTime(timeMillis: Long): String {
        if (timeMillis <= 0L) return ""
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timeMillis))
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
