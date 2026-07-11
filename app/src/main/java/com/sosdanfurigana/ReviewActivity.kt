package com.sosdanfurigana

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.sosdanfurigana.data.ReviewScheduler
import com.sosdanfurigana.data.WordbookEntry
import com.sosdanfurigana.data.WordbookRepository

class ReviewActivity : Activity() {
    private lateinit var repository: WordbookRepository
    private lateinit var root: LinearLayout
    private lateinit var progressChip: TextView
    private lateinit var cardArea: LinearLayout
    private lateinit var actionArea: LinearLayout
    private val queue = ArrayDeque<WordbookEntry>()
    private var passedCount = 0
    private var revealed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = WordbookRepository(applicationContext)
        repository.dueWords().forEach { queue.addLast(it) }
        setContentView(createContentView())
        if (queue.isEmpty()) {
            renderAllDone(nothingWasDue = true)
        } else {
            renderCard()
        }
    }

    private fun createContentView(): View {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = AppUi.appBackground()
        }

        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    TextView(this@ReviewActivity).apply {
                        text = "复习时间"
                        textSize = 24f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(AppUi.INK)
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                )
                progressChip = TextView(this@ReviewActivity).apply {
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(AppUi.HAIR)
                    background = AppUi.rounded(
                        this@ReviewActivity,
                        AppUi.HEADBAND_SOFT,
                        999,
                        AppUi.HEADBAND_DEEP
                    )
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                }
                addView(progressChip)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val cardScroll = ScrollView(this).apply {
            isFillViewport = true
        }
        cardArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        cardScroll.addView(
            cardArea,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            cardScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        actionArea = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        root.addView(
            actionArea,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dp(12), 0, 0)
            }
        )
        return root
    }

    private fun renderCard() {
        val entry = queue.firstOrNull() ?: run {
            renderAllDone(nothingWasDue = false)
            return
        }
        revealed = false
        progressChip.text = "还剩 ${queue.size} 个"
        cardArea.removeAllViews()
        actionArea.removeAllViews()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(28), dp(24), dp(28))
            background = AppUi.sectionBackground(this@ReviewActivity)
            elevation = dp(2).toFloat()
        }
        card.addView(TextView(this).apply {
            text = "想起来读音了再翻面，不许偷看！"
            textSize = 12f
            setTextColor(AppUi.MUTED)
        })
        card.addView(TextView(this).apply {
            text = entry.surface
            textSize = 36f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(AppUi.INK)
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(6))
        })
        cardArea.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        actionArea.addView(
            Button(this).apply {
                text = "翻面看答案"
                AppUi.secondary(this)
                setOnClickListener { revealCard(card, entry) }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(50)
            )
        )
    }

    private fun revealCard(card: LinearLayout, entry: WordbookEntry) {
        if (revealed) return
        revealed = true

        card.addView(TextView(this).apply {
            text = entry.reading
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(AppUi.HEADBAND_DEEP)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        })
        if (entry.meaning.isNotBlank() || entry.jlptLevel.isNotBlank()) {
            card.addView(TextView(this).apply {
                val jlpt = if (entry.jlptLevel.isNotBlank()) "［${entry.jlptLevel}］" else ""
                text = "$jlpt${entry.meaning}".trim()
                textSize = 14f
                setTextColor(AppUi.INK)
                gravity = Gravity.CENTER
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(0, dp(10), 0, 0)
            })
        }
        if (entry.sourceText.isNotBlank()) {
            card.addView(View(this).apply {
                background = AppUi.headbandRule(this@ReviewActivity)
            }, LinearLayout.LayoutParams(dp(48), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setMargins(0, dp(16), 0, dp(12))
            })
            card.addView(TextView(this).apply {
                text = entry.sourceText
                textSize = 13f
                setTextColor(AppUi.MUTED)
                setLineSpacing(dp(3).toFloat(), 1f)
            })
        }

        actionArea.removeAllViews()
        addRatingButton("不记得", ReviewScheduler.Rating.AGAIN) { AppUi.danger(it) }
        addRatingButton("有点难", ReviewScheduler.Rating.HARD) { AppUi.secondary(it) }
        addRatingButton("记得", ReviewScheduler.Rating.GOOD) { AppUi.primary(it) }
        addRatingButton("很简单", ReviewScheduler.Rating.EASY) { AppUi.ghost(it) }
    }

    private fun addRatingButton(
        label: String,
        rating: ReviewScheduler.Rating,
        style: (Button) -> Unit
    ) {
        actionArea.addView(
            Button(this).apply {
                text = label
                style(this)
                textSize = 13f
                setOnClickListener { rate(rating) }
            },
            LinearLayout.LayoutParams(0, dp(50), 1f).apply {
                marginStart = if (actionArea.childCount == 0) 0 else dp(6)
            }
        )
    }

    private fun rate(rating: ReviewScheduler.Rating) {
        val entry = queue.removeFirstOrNull() ?: return
        val reviewed = ReviewScheduler.review(entry, rating)
        repository.updateWord(reviewed)
        if (rating == ReviewScheduler.Rating.AGAIN) {
            queue.addLast(reviewed)
        } else {
            passedCount++
        }
        Toast.makeText(this, ReviewScheduler.intervalDescription(reviewed), Toast.LENGTH_SHORT).show()
        renderCard()
    }

    private fun renderAllDone(nothingWasDue: Boolean) {
        progressChip.text = "还剩 0 个"
        cardArea.removeAllViews()
        actionArea.removeAllViews()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(36), dp(24), dp(36))
            background = AppUi.sectionBackground(this@ReviewActivity, tinted = true)
        }
        card.addView(TextView(this).apply {
            text = if (nothingWasDue) "今日无任务" else "复习完毕！"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(AppUi.INK)
            gravity = Gravity.CENTER
        })
        card.addView(TextView(this).apply {
            text = if (nothingWasDue) {
                "今天没有该复习的词。想偷懒？正好，去 X 上再抓几条日文 post 回来充实单词本！"
            } else {
                "$passedCount 个词全部过关。SOS 团的字典里可没有『遗忘』这两个字——明天也要来报到，这是团长命令！"
            }
            textSize = 14f
            setTextColor(AppUi.MUTED)
            gravity = Gravity.CENTER
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(0, dp(12), 0, 0)
        })
        cardArea.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        actionArea.addView(
            Button(this).apply {
                text = "返回单词本"
                AppUi.primary(this)
                setOnClickListener { finish() }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(50)
            )
        )
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
