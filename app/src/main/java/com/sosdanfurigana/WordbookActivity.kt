package com.sosdanfurigana

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.sosdanfurigana.data.ReviewScheduler
import com.sosdanfurigana.data.WordbookEntry
import com.sosdanfurigana.data.WordbookRepository
import com.sosdanfurigana.japanese.JapaneseSearch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WordbookActivity : Activity() {
    private lateinit var repository: WordbookRepository
    private lateinit var list: LinearLayout
    private lateinit var dueText: TextView
    private var searchQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = WordbookRepository(applicationContext)
        setContentView(createContentView())
        renderWords()
    }

    override fun onResume() {
        super.onResume()
        refreshDueText()
        renderWords()
    }

    private fun refreshDueText() {
        val dueCount = ReviewScheduler.dueCount(repository.getWords())
        dueText.text = if (dueCount > 0) {
            "今天有 $dueCount 个词排队等你复习，别想装没看见。"
        } else {
            "今天没有该复习的词，团长准你休息。"
        }
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
                        setTextColor(AppUi.CREAM)
                        setPadding(0, dp(14), 0, 0)
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
                addView(TextView(this@WordbookActivity).apply {
                    text = "把真正想记住的词收进这里。团长不负责替你背，但会盯着你复习。"
                    textSize = 13f
                    setTextColor(AppUi.WARM_WHITE)
                    setLineSpacing(dp(3).toFloat(), 1f)
                    setPadding(0, dp(6), 0, dp(10))
                })
                dueText = TextView(this@WordbookActivity).apply {
                    textSize = 13f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(AppUi.CREAM)
                    setPadding(0, 0, 0, dp(12))
                }
                addView(dueText)
                refreshDueText()
                addView(
                    LinearLayout(this@WordbookActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(
                            Button(this@WordbookActivity).apply {
                                text = "开始复习"
                                AppUi.secondary(this)
                                setOnClickListener {
                                    startActivity(
                                        Intent(this@WordbookActivity, ReviewActivity::class.java)
                                    )
                                }
                            },
                            LinearLayout.LayoutParams(0, dp(44), 1f)
                        )
                        addView(
                            Button(this@WordbookActivity).apply {
                                text = "导出 Anki"
                                AppUi.ghost(this)
                                textSize = 12f
                                setOnClickListener { exportToAnki() }
                            },
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                dp(44)
                            ).apply {
                                marginStart = dp(8)
                            }
                        )
                        addView(
                            Button(this@WordbookActivity).apply {
                                text = "清空单词本"
                                AppUi.danger(this)
                                textSize = 12f
                                setOnClickListener {
                                    repository.clear()
                                    refreshDueText()
                                    renderWords()
                                    Toast.makeText(
                                        this@WordbookActivity,
                                        "已清空单词本",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                dp(44)
                            ).apply {
                                marginStart = dp(8)
                            }
                        )
                    }
                )
            }
        )

        root.addView(
            EditText(this).apply {
                hint = "搜索单词：汉字、假名都能搜到"
                setSingleLine(true)
                textSize = 14f
                setTextColor(AppUi.INK)
                setHintTextColor(AppUi.MUTED)
                background = AppUi.inputBackground(this@WordbookActivity)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        searchQuery = s?.toString().orEmpty()
                        renderWords()
                    }
                })
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply {
                setMargins(0, dp(12), 0, 0)
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
        val readingPairs = words.map { it.surface to it.reading }
        val queryVariants = JapaneseSearch.expandQuery(searchQuery, readingPairs)
        val filtered = words.filter { word ->
            JapaneseSearch.matches(
                queryVariants,
                JapaneseSearch.normalize("${word.surface}\n${word.reading}\n${word.sourceText}")
            )
        }
        if (filtered.isEmpty()) {
            list.addView(emptyText("没有找到这个词。要么换个搜法，要么就趁现在去把它抓回来！"))
            return
        }
        filtered.forEach { word ->
            list.addView(wordView(word))
        }
    }

    private fun wordView(word: WordbookEntry): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = AppUi.sectionBackground(this@WordbookActivity)
            elevation = dp(1).toFloat()
            addView(TextView(this@WordbookActivity).apply {
                text = word.reading
                textSize = 13f
                setTextColor(AppUi.HAIR_SOFT)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@WordbookActivity).apply {
                text = word.surface
                textSize = 24f
                setTextColor(AppUi.INK)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(0, dp(2), 0, 0)
            })
            if (word.meaning.isNotBlank() || word.jlptLevel.isNotBlank() ||
                word.partOfSpeech.isNotBlank()
            ) {
                addView(
                    LinearLayout(this@WordbookActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(8), 0, 0)
                        if (word.jlptLevel.isNotBlank()) {
                            addView(
                                TextView(this@WordbookActivity).apply {
                                    text = word.jlptLevel
                                    textSize = 10f
                                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                                    setTextColor(AppUi.HAIR)
                                    background = AppUi.rounded(
                                        this@WordbookActivity,
                                        AppUi.HEADBAND_SOFT,
                                        999,
                                        AppUi.HEADBAND_DEEP
                                    )
                                    setPadding(dp(8), dp(3), dp(8), dp(3))
                                },
                                LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    marginEnd = dp(8)
                                }
                            )
                        }
                        if (word.partOfSpeech.isNotBlank()) {
                            addView(
                                TextView(this@WordbookActivity).apply {
                                    text = word.partOfSpeech
                                    textSize = 10f
                                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                                    setTextColor(AppUi.HAIR)
                                    background = AppUi.rounded(
                                        this@WordbookActivity,
                                        AppUi.SURFACE_TINT,
                                        999,
                                        AppUi.STROKE_STRONG
                                    )
                                    setPadding(dp(8), dp(3), dp(8), dp(3))
                                },
                                LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    marginEnd = dp(8)
                                }
                            )
                        }
                        addView(
                            TextView(this@WordbookActivity).apply {
                                text = word.meaning
                                textSize = 13f
                                setTextColor(AppUi.INK)
                                setLineSpacing(dp(2).toFloat(), 1f)
                            },
                            LinearLayout.LayoutParams(
                                0,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                1f
                            )
                        )
                    }
                )
            }
            addView(TextView(this@WordbookActivity).apply {
                text = word.sourceText
                textSize = 13f
                setTextColor(AppUi.MUTED)
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(0, dp(8), 0, 0)
            })
            addView(
                LinearLayout(this@WordbookActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(10), 0, 0)
                    addView(
                        TextView(this@WordbookActivity).apply {
                            text = "${formatTime(word.updatedAt)} · ${dueLabel(word)}"
                            textSize = 11f
                            setTextColor(AppUi.HAIR_SOFT)
                        },
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    )
                    if (word.meaning.isBlank()) {
                        addView(
                            Button(this@WordbookActivity).apply {
                                text = "查释义"
                                AppUi.ghost(this)
                                textSize = 12f
                                setOnClickListener {
                                    isEnabled = false
                                    com.sosdanfurigana.data.WordMeaningFetcher
                                        .fetchIfMissing(applicationContext, word.id)
                                    Toast.makeText(
                                        this@WordbookActivity,
                                        "已经派模型去查了，过几秒再回来看",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                marginEnd = dp(8)
                            }
                        )
                    }
                    addView(
                        Button(this@WordbookActivity).apply {
                            text = "删除"
                            AppUi.danger(this)
                            textSize = 12f
                            minHeight = 0
                            minimumHeight = 0
                            setPadding(dp(16), dp(8), dp(16), dp(8))
                            setOnClickListener {
                                repository.deleteWord(word.id)
                                renderWords()
                            }
                        },
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    )
                }
            )
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

    private fun dueLabel(word: WordbookEntry): String {
        return if (word.dueAt <= System.currentTimeMillis()) {
            "该复习了"
        } else {
            "下次复习 " + SimpleDateFormat("MM.dd", Locale.getDefault()).format(Date(word.dueAt))
        }
    }

    private fun exportToAnki() {
        if (repository.getWords().isEmpty()) {
            Toast.makeText(this, "单词本还是空的，先去收点词再谈导出！", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "sos_wordbook_anki.txt")
        }
        startActivityForResult(intent, REQUEST_EXPORT_ANKI)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT_ANKI || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val words = repository.getWords()
        runCatching {
            val stream = contentResolver.openOutputStream(uri)
                ?: error("无法打开导出文件")
            stream.use { it.write(buildAnkiTsv(words).toByteArray(Charsets.UTF_8)) }
        }.onSuccess {
            Toast.makeText(
                this,
                "已导出 ${words.size} 个词。拿去喂 Anki 吧，记得说是团长的功劳！",
                Toast.LENGTH_LONG
            ).show()
        }.onFailure { throwable ->
            Toast.makeText(this, "导出失败：${throwable.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildAnkiTsv(words: List<WordbookEntry>): String {
        return buildString {
            append("#separator:tab\n")
            append("#html:false\n")
            append("#columns:词面\t读音\t释义\t原句\n")
            words.forEach { word ->
                val meaning = buildString {
                    if (word.jlptLevel.isNotBlank()) append("［${word.jlptLevel}］")
                    append(word.meaning)
                }
                append(tsvField(word.surface)).append('\t')
                append(tsvField(word.reading)).append('\t')
                append(tsvField(meaning)).append('\t')
                append(tsvField(word.sourceText)).append('\n')
            }
        }
    }

    private fun tsvField(value: String): String {
        return value
            .replace('\t', ' ')
            .replace('\r', ' ')
            .replace('\n', ' ')
            .trim()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val REQUEST_EXPORT_ANKI = 1001
    }
}
