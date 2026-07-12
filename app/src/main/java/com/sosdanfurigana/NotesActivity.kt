package com.sosdanfurigana

import android.app.Activity
import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.sosdanfurigana.data.FuriganaNote
import com.sosdanfurigana.data.NoteRepository
import com.sosdanfurigana.data.WordbookRepository
import com.sosdanfurigana.furigana.FuriganaAnnotation
import com.sosdanfurigana.furigana.FuriganaAnnotationCodec
import com.sosdanfurigana.furigana.RubyAnnotationExtractor
import com.sosdanfurigana.japanese.JapaneseSearch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class NotesActivity : Activity() {
    private lateinit var repository: NoteRepository
    private lateinit var wordbookRepository: WordbookRepository
    private lateinit var list: LinearLayout
    private var searchQuery = ""
    private var timeFilter = TimeFilter.ALL
    private var customRangeStart = 0L
    private var customRangeEndExclusive = 0L
    private val chipViews = mutableMapOf<TimeFilter, TextView>()
    private val corpusCache = mutableMapOf<String, String>()
    private var readingPairs: List<Pair<String, String>> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppMotion.prepareTabActivity(this, savedInstanceState)
        repository = NoteRepository(applicationContext)
        wordbookRepository = WordbookRepository(applicationContext)
        setContentView(
            AppBottomNavigation.wrap(this, createContentView(), BottomDestination.NOTES).also {
                AppMotion.bindContainerTarget(this, it)
            }
        )
        refreshReadingPairs()
        renderNotes()
    }

    private fun createContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = AppUi.appBackground()
        }

        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(18), dp(18), dp(18))
                background = AppUi.heroBackground(this@NotesActivity)
                elevation = dp(3).toFloat()
                addView(
                    LinearLayout(this@NotesActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(
                            View(this@NotesActivity).apply {
                                background = AppUi.headbandRule(this@NotesActivity)
                            },
                            LinearLayout.LayoutParams(dp(86), dp(5))
                        )
                        addView(TextView(this@NotesActivity).apply {
                            text = "搜查记录"
                            textSize = 24f
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                            setTextColor(AppUi.CREAM)
                            setPadding(0, dp(12), 0, 0)
                        })
                        addView(TextView(this@NotesActivity).apply {
                            text = "注音结果会自动归档，句子结构和选词任务也从这里继续。"
                            textSize = 13f
                            setTextColor(AppUi.WARM_WHITE)
                            setLineSpacing(dp(3).toFloat(), 1f)
                            setPadding(0, dp(6), 0, dp(12))
                        })
                        addView(Button(this@NotesActivity).apply {
                            text = "清空笔记"
                            AppUi.secondary(this)
                            setOnClickListener {
                                repository.clear()
                                corpusCache.clear()
                                renderNotes()
                                Toast.makeText(this@NotesActivity, "已清空笔记", Toast.LENGTH_SHORT).show()
                            }
                        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                )
                addView(ImageView(this@NotesActivity).apply {
                    setImageResource(R.drawable.haruhi_clap)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = "团长确认搜查记录"
                }, LinearLayout.LayoutParams(dp(82), dp(104)).apply {
                    marginStart = dp(12)
                })
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            EditText(this).apply {
                hint = "搜索笔记：汉字、假名都能搜到"
                setSingleLine(true)
                textSize = 14f
                setTextColor(AppUi.INK)
                setHintTextColor(AppUi.MUTED)
                background = AppUi.inputBackground(this@NotesActivity)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        searchQuery = s?.toString().orEmpty()
                        renderNotes()
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

        root.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(
                    LinearLayout(this@NotesActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        TimeFilter.entries.forEach { filter ->
                            val chip = filterChip(filter)
                            chipViews[filter] = chip
                            addView(
                                chip,
                                LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    marginEnd = dp(8)
                                }
                            )
                        }
                    }
                )
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dp(10), 0, 0)
            }
        )
        refreshChipStyles()

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

    private fun filterChip(filter: TimeFilter): TextView {
        return TextView(this).apply {
            text = filter.label
            textSize = 12f
            setPadding(dp(12), dp(7), dp(12), dp(7))
            setOnClickListener {
                if (filter == TimeFilter.CUSTOM) {
                    pickCustomRange()
                } else {
                    timeFilter = filter
                    refreshChipStyles()
                    renderNotes()
                }
            }
        }
    }

    private fun refreshChipStyles() {
        chipViews.forEach { (filter, chip) ->
            val selected = filter == timeFilter
            chip.background = if (selected) {
                AppUi.rounded(this, AppUi.HEADBAND_SOFT, 999, AppUi.HEADBAND_DEEP)
            } else {
                AppUi.rounded(this, AppUi.SURFACE, 999, AppUi.STROKE_STRONG)
            }
            chip.setTextColor(if (selected) AppUi.HAIR else AppUi.MUTED)
            chip.typeface = if (selected) {
                android.graphics.Typeface.DEFAULT_BOLD
            } else {
                android.graphics.Typeface.DEFAULT
            }
            if (filter == TimeFilter.CUSTOM) {
                chip.text = if (timeFilter == TimeFilter.CUSTOM && customRangeStart > 0L) {
                    val format = SimpleDateFormat("MM.dd", Locale.getDefault())
                    "${format.format(Date(customRangeStart))}–" +
                        format.format(Date(customRangeEndExclusive - 1))
                } else {
                    TimeFilter.CUSTOM.label
                }
            }
        }
    }

    private fun pickCustomRange() {
        Toast.makeText(this, "团长发问：从哪一天开始？", Toast.LENGTH_SHORT).show()
        val now = Calendar.getInstance()
        DatePickerDialog(
            this,
            { _, startYear, startMonth, startDay ->
                Toast.makeText(this, "那么，到哪一天结束？", Toast.LENGTH_SHORT).show()
                DatePickerDialog(
                    this,
                    { _, endYear, endMonth, endDay ->
                        val start = dayStartMillis(startYear, startMonth, startDay)
                        val end = dayStartMillis(endYear, endMonth, endDay) + DAY_MS
                        customRangeStart = minOf(start, end - DAY_MS)
                        customRangeEndExclusive = maxOf(end, start + DAY_MS)
                        timeFilter = TimeFilter.CUSTOM
                        refreshChipStyles()
                        renderNotes()
                    },
                    startYear,
                    startMonth,
                    startDay
                ).show()
            },
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH),
            now.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun dayStartMillis(year: Int, month: Int, day: Int): Long {
        return Calendar.getInstance().apply {
            clear()
            set(year, month, day)
        }.timeInMillis
    }

    private fun matchesTime(timeMillis: Long): Boolean {
        return when (timeFilter) {
            TimeFilter.ALL -> true
            TimeFilter.TODAY -> timeMillis >= startOfToday()
            TimeFilter.WEEK -> timeMillis >= startOfWeek()
            TimeFilter.MONTH -> timeMillis >= startOfMonth()
            TimeFilter.CUSTOM ->
                timeMillis >= customRangeStart && timeMillis < customRangeEndExclusive
        }
    }

    private fun startOfToday(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun startOfWeek(): Long {
        val today = startOfToday()
        val dayOfWeek = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        val daysSinceMonday = (dayOfWeek + 5) % 7
        return today - daysSinceMonday * DAY_MS
    }

    private fun startOfMonth(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun refreshReadingPairs() {
        val wordPairs = wordbookRepository.getWords().map { it.surface to it.reading }
        val notePairs = repository.getNotes().flatMap { note ->
            decodeAnnotations(note).map { it.surface to it.reading }
        }
        readingPairs = (wordPairs + notePairs).distinct()
    }

    private fun decodeAnnotations(note: FuriganaNote): List<FuriganaAnnotation> {
        return FuriganaAnnotationCodec.decode(note.annotationHintsJson)
            .ifEmpty { RubyAnnotationExtractor.fromRubyHtml(note.originalText, note.rubyHtml) }
    }

    private fun corpusFor(note: FuriganaNote): String {
        return corpusCache.getOrPut(note.id) {
            val hintText = decodeAnnotations(note)
                .joinToString(" ") { "${it.surface} ${it.reading}" }
            JapaneseSearch.normalize("${note.originalText}\n${note.plainText}\n$hintText")
        }
    }

    private fun renderNotes() {
        list.removeAllViews()
        val notes = repository.getNotes()
        if (notes.isEmpty()) {
            list.addView(emptyText("暂无笔记。成功注音的内容会自动保存在这里。"))
            return
        }
        val queryVariants = JapaneseSearch.expandQuery(searchQuery, readingPairs)
        val filtered = notes.filter { note ->
            matchesTime(note.updatedAt) &&
                JapaneseSearch.matches(queryVariants, corpusFor(note))
        }
        if (filtered.isEmpty()) {
            list.addView(
                emptyText("一条都没找到。换个关键词或时间段吧，本团长的搜索可不会冤枉任何一条笔记。")
            )
            return
        }
        filtered.forEach { note ->
            list.addView(noteView(note))
        }
    }

    private fun noteView(note: FuriganaNote): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = AppUi.sectionBackground(this@NotesActivity)
            elevation = dp(1).toFloat()
            foreground = AppUi.ripple(
                AppUi.rounded(this@NotesActivity, android.graphics.Color.TRANSPARENT, 20),
                0x294CAACD
            )
            setOnClickListener {
                AppMotion.startContainer(
                    this@NotesActivity,
                    this,
                    Intent(this@NotesActivity, NoteDetailActivity::class.java)
                        .putExtra(NoteDetailActivity.EXTRA_NOTE_ID, note.id)
                )
            }
            addView(TextView(this@NotesActivity).apply {
                text = "${formatTime(note.updatedAt)} · 点开查看注音，可分析句子结构"
                textSize = 11f
                setTextColor(AppUi.HAIR_SOFT)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@NotesActivity).apply {
                text = note.originalText
                textSize = 15f
                setTextColor(AppUi.INK)
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(0, dp(8), 0, 0)
            })
            addView(TextView(this@NotesActivity).apply {
                text = note.plainText
                textSize = 14f
                setTextColor(AppUi.MUTED)
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(0, dp(8), 0, 0)
            })
            addView(
                LinearLayout(this@NotesActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(10), 0, 0)
                    addView(
                        Button(this@NotesActivity).apply {
                            text = "选词/加词"
                            AppUi.primary(this)
                            setOnClickListener { openAddWord(note, this) }
                        },
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    )
                    addView(
                        Button(this@NotesActivity).apply {
                            text = "删除"
                            AppUi.danger(this)
                            textSize = 12f
                            setOnClickListener {
                                repository.deleteNote(note.id)
                                corpusCache.remove(note.id)
                                renderNotes()
                            }
                        },
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(dp(8), 0, 0, 0)
                        }
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

    private fun openAddWord(note: FuriganaNote, source: View) {
        val hints = JSONArray()
        val annotations = decodeAnnotations(note)
        annotations
            .forEach { annotation ->
                hints.put(
                    JSONObject()
                        .put("s", annotation.surface)
                        .put("r", annotation.reading)
                        .put("b", annotation.start)
                        .put("e", annotation.end)
                )
            }
        AppMotion.startContainer(
            this,
            source,
            Intent(this, AddWordActivity::class.java)
                .putExtra(AddWordActivity.EXTRA_SOURCE_TEXT, note.originalText)
                .putExtra(AddWordActivity.EXTRA_READING_HINTS, hints.toString())
        )
    }

    private fun emptyText(message: String): TextView {
        return TextView(this).apply {
            text = message
            textSize = 15f
            setTextColor(AppUi.MUTED)
            gravity = Gravity.CENTER
            setLineSpacing(dp(3).toFloat(), 1f)
            background = AppUi.sectionBackground(this@NotesActivity, tinted = true)
            setPadding(dp(16), dp(40), dp(16), dp(40))
        }
    }

    private fun formatTime(timeMillis: Long): String {
        if (timeMillis <= 0L) return ""
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timeMillis))
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private enum class TimeFilter(val label: String) {
        ALL("全部"),
        TODAY("今天"),
        WEEK("本周"),
        MONTH("本月"),
        CUSTOM("自定义")
    }

    companion object {
        private const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
