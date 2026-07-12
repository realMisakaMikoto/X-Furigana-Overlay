package com.sosdanfurigana

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
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
import com.sosdanfurigana.data.JlptLevelFilter
import com.sosdanfurigana.data.ReviewScheduler
import com.sosdanfurigana.data.WordMeaningFetcher
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
    private lateinit var countText: TextView
    private lateinit var tagFilters: LinearLayout
    private lateinit var reviewButton: Button
    private val filterButtons = mutableMapOf<Filter, Button>()
    private val jlptFilterButtons = mutableMapOf<JlptLevelFilter, Button>()
    private var searchQuery = ""
    private var filter = Filter.ALL
    private var jlptFilter = JlptLevelFilter.ALL
    private var selectedTag: String? = null
    private var sort = Sort.RECENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppMotion.prepareTabActivity(this, savedInstanceState)
        repository = WordbookRepository(applicationContext)
        val content = createContentView()
        setContentView(AppBottomNavigation.wrap(this, content, BottomDestination.WORDS).also {
            AppMotion.bindContainerTarget(this, it)
        })
        renderWords()
    }

    override fun onResume() {
        super.onResume()
        renderWords()
    }

    private fun createContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), 0)
            background = AppUi.appBackground()
        }
        root.addView(header())
        root.addView(reviewBanner(), matchWrap(top = 12))
        root.addView(searchBar(), matchHeight(48, top = 12))
        root.addView(jlptFilterRow(), matchWrap(top = 10))
        root.addView(filterRow(), matchWrap(top = 8))
        tagFilters = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(tagFilters)
            },
            matchWrap(top = 8)
        )
        countText = TextView(this).apply {
            textSize = 12f
            setTextColor(AppUi.MUTED)
            setPadding(dp(2), dp(10), 0, dp(6))
        }
        root.addView(countText)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(14))
        }
        root.addView(
            ScrollView(this).apply { addView(list) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        return root
    }

    private fun header(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(4))
            addView(
                LinearLayout(this@WordbookActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(this@WordbookActivity).apply {
                        text = "团员词库"
                        textSize = 26f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(AppUi.INK)
                    })
                    addView(TextView(this@WordbookActivity).apply {
                        text = "收下的词要练熟，不许只收藏不复习。"
                        textSize = 13f
                        setTextColor(AppUi.HAIR_SOFT)
                        setPadding(0, dp(2), 0, 0)
                    })
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(ImageView(this@WordbookActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "团长监督中"
                loadHaruhi(this, R.drawable.haruhi_hmph)
            }, LinearLayout.LayoutParams(dp(54), dp(54)).apply {
                marginEnd = dp(8)
            })
            addView(Button(this@WordbookActivity).apply {
                text = "管理"
                contentDescription = "导出或清空单词本"
                AppUi.ghost(this)
                setOnClickListener { showManageMenu() }
            }, LinearLayout.LayoutParams(dp(76), dp(48)))
        }
    }

    private fun reviewBanner(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = AppUi.heroBackground(this@WordbookActivity)
            addView(TextView(this@WordbookActivity).apply {
                text = "TODAY'S MISSION"
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.08f
                setTextColor(AppUi.HEADBAND)
            })
            dueText = TextView(this@WordbookActivity).apply {
                textSize = 19f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(AppUi.CREAM)
                setPadding(0, dp(5), 0, dp(4))
            }
            addView(dueText)
            addView(TextView(this@WordbookActivity).apply {
                text = "原句会标出目标词，写出符合语境的读音。"
                textSize = 13f
                setTextColor(AppUi.WARM_WHITE)
            })
            reviewButton = Button(this@WordbookActivity).apply {
                text = "开始填空"
                AppUi.secondary(this)
                setOnClickListener {
                    AppMotion.startContainer(
                        this@WordbookActivity,
                        this,
                        Intent(this@WordbookActivity, ReviewActivity::class.java)
                    )
                }
            }
            addView(reviewButton, matchHeight(48, top = 12))
        }
    }

    private fun searchBar(): View {
        return EditText(this).apply {
            hint = "搜索词面、读音、释义或原句"
            setSingleLine(true)
            textSize = 14f
            setTextColor(AppUi.INK)
            setHintTextColor(AppUi.MUTED)
            setPadding(dp(14), 0, dp(14), 0)
            background = AppUi.inputBackground(this@WordbookActivity)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    searchQuery = s?.toString().orEmpty()
                    renderWords()
                }
            })
        }
    }

    private fun filterRow(): View {
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(this@WordbookActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(filterLabel("状态"), wrapHeight(48, end = 8))
                Filter.entries.forEach { item ->
                    addView(filterButton(item), wrapHeight(48, end = 8))
                }
                addView(Button(this@WordbookActivity).apply {
                    text = "排序：${sort.label}"
                    AppUi.ghost(this)
                    setOnClickListener { showSortMenu(this) }
                }, wrapHeight(48))
            })
        }
    }

    private fun jlptFilterRow(): View {
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            contentDescription = "按 JLPT 等级筛选单词"
            addView(LinearLayout(this@WordbookActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(filterLabel("等级"), wrapHeight(48, end = 8))
                JlptLevelFilter.entries.forEach { item ->
                    addView(jlptFilterButton(item), wrapHeight(48, end = 8))
                }
            })
        }
    }

    private fun filterLabel(label: String): TextView {
        return TextView(this).apply {
            text = label
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(AppUi.HAIR_SOFT)
            gravity = Gravity.CENTER
            setPadding(dp(2), 0, dp(2), 0)
        }
    }

    private fun jlptFilterButton(item: JlptLevelFilter): Button {
        return Button(this).apply {
            jlptFilterButtons[item] = this
            text = item.label
            contentDescription = if (item == JlptLevelFilter.ALL) {
                "显示全部等级单词"
            } else {
                "只看 ${item.label} 单词"
            }
            if (jlptFilter == item) AppUi.secondary(this) else AppUi.ghost(this)
            setOnClickListener {
                jlptFilter = item
                renderWords()
                refreshJlptFilterStyles()
            }
        }
    }

    private fun filterButton(item: Filter): Button {
        return Button(this).apply {
            filterButtons[item] = this
            text = item.label
            if (filter == item) AppUi.secondary(this) else AppUi.ghost(this)
            setOnClickListener {
                filter = item
                selectedTag = null
                renderWords()
                refreshFilterStyles()
            }
        }
    }

    private fun refreshFilterStyles() {
        filterButtons.forEach { (item, button) ->
            if (filter == item) AppUi.secondary(button) else AppUi.ghost(button)
        }
    }

    private fun refreshJlptFilterStyles() {
        jlptFilterButtons.forEach { (item, button) ->
            if (jlptFilter == item) AppUi.secondary(button) else AppUi.ghost(button)
        }
    }

    private fun renderWords() {
        if (!::list.isInitialized) return
        val words = repository.getWords()
        val now = System.currentTimeMillis()
        val dueCount = ReviewScheduler.dueCount(words, now)
        dueText.text = if (dueCount > 0) {
            "$dueCount 个词等你验收"
        } else {
            "今日任务清零，干得不错"
        }
        reviewButton.isEnabled = dueCount > 0
        reviewButton.alpha = if (dueCount > 0) 1f else 0.58f
        reviewButton.text = if (dueCount > 0) "开始填空" else "今天已完成"
        renderTagFilters(words)
        val readingPairs = words.map { it.surface to it.reading }
        val variants = JapaneseSearch.expandQuery(searchQuery, readingPairs)
        val filtered = words
            .asSequence()
            .filter { word -> jlptFilter.matches(word.jlptLevel) }
            .filter { word -> matchesFilter(word, now) }
            .filter { word -> selectedTag == null || selectedTag in word.tags }
            .filter { word ->
                JapaneseSearch.matches(
                    variants,
                    JapaneseSearch.normalize(
                        listOf(
                            word.surface,
                            word.reading,
                            word.meaning,
                            word.sourceText,
                            word.tags.joinToString(" ")
                        ).joinToString("\n")
                    )
                )
            }
            .sortedWith(sort.comparator)
            .toList()
        countText.text = if (jlptFilter == JlptLevelFilter.ALL) {
            "显示 ${filtered.size} / ${words.size} 个词"
        } else {
            "${jlptFilter.label} · 显示 ${filtered.size} / ${words.size} 个词"
        }
        list.removeAllViews()
        if (filtered.isEmpty()) {
            list.addView(emptyState(words.isEmpty()))
        } else {
            filtered.forEach { list.addView(wordRow(it), matchWrap(bottom = 8)) }
        }
    }

    private fun matchesFilter(word: WordbookEntry, now: Long): Boolean {
        return when (filter) {
            Filter.ALL -> true
            Filter.DUE -> word.dueAt <= now
            Filter.FAVORITE -> word.isFavorite
            Filter.INCOMPLETE -> word.reading.isBlank() || word.meaning.isBlank() ||
                word.partOfSpeech.isBlank() || word.jlptLevel.isBlank()
        }
    }

    private fun renderTagFilters(words: List<WordbookEntry>) {
        tagFilters.removeAllViews()
        val tags = words.flatMap(WordbookEntry::tags).distinct().sorted()
        if (tags.isEmpty()) {
            tagFilters.visibility = View.GONE
            return
        }
        tagFilters.visibility = View.VISIBLE
        tagFilters.addView(TextView(this).apply {
            text = "标签"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(AppUi.HAIR_SOFT)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(10), 0)
        }, wrapHeight(36))
        tags.forEach { tag ->
            tagFilters.addView(Button(this).apply {
                text = tag
                if (selectedTag == tag) AppUi.secondary(this) else AppUi.ghost(this)
                textSize = 12f
                setOnClickListener {
                    selectedTag = if (selectedTag == tag) null else tag
                    renderWords()
                }
            }, wrapHeight(36, end = 7))
        }
    }

    private fun wordRow(word: WordbookEntry): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(10), dp(12))
            background = AppUi.sectionBackground(this@WordbookActivity)
            isClickable = true
            isFocusable = true
            contentDescription = "${word.surface}，${word.reading}，查看详情"
            setOnClickListener { openDetail(word.id, this) }
            addView(LinearLayout(this@WordbookActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(this@WordbookActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(this@WordbookActivity).apply {
                        text = word.surface
                        textSize = 20f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(AppUi.INK)
                    })
                    if (word.reading.isNotBlank()) addView(TextView(this@WordbookActivity).apply {
                        text = word.reading
                        textSize = 13f
                        setTextColor(AppUi.HAIR_SOFT)
                        setPadding(dp(9), dp(3), 0, 0)
                    })
                })
                addView(TextView(this@WordbookActivity).apply {
                    text = word.meaning.ifBlank { "词汇信息待补全" }
                    textSize = 13f
                    setTextColor(if (word.meaning.isBlank()) AppUi.MUTED else AppUi.INK)
                    maxLines = 2
                    setPadding(0, dp(4), 0, 0)
                })
                val metadata = buildList {
                    if (word.jlptLevel.isNotBlank()) add(word.jlptLevel)
                    if (word.partOfSpeech.isNotBlank()) add(word.partOfSpeech)
                    add(dueLabel(word))
                    if (word.tags.isNotEmpty()) add(word.tags.take(2).joinToString(" · "))
                }.joinToString("  ·  ")
                addView(TextView(this@WordbookActivity).apply {
                    text = metadata
                    textSize = 11f
                    setTextColor(AppUi.MUTED)
                    setPadding(0, dp(6), 0, 0)
                    maxLines = 1
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@WordbookActivity).apply {
                text = if (word.isFavorite) "★" else "☆"
                textSize = 22f
                contentDescription = if (word.isFavorite) "取消收藏" else "收藏"
                if (word.isFavorite) AppUi.secondary(this) else AppUi.ghost(this)
                setOnClickListener {
                    repository.setFavorite(word.id, !word.isFavorite)
                    renderWords()
                }
            }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) })
        }
    }

    private fun emptyState(isLibraryEmpty: Boolean): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(34), dp(20), dp(34))
            background = AppUi.sectionBackground(this@WordbookActivity, tinted = true)
            addView(TextView(this@WordbookActivity).apply {
                text = if (isLibraryEmpty) "这里还没有团员" else "没有符合条件的词"
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(AppUi.INK)
                gravity = Gravity.CENTER
            })
            addView(TextView(this@WordbookActivity).apply {
                text = if (isLibraryEmpty) {
                    "在注音结果里选词加入，释义和复习任务会在这里接班。"
                } else {
                    "换个搜索词或筛选条件，本团长可没把它们藏起来。"
                }
                textSize = 13f
                setTextColor(AppUi.MUTED)
                gravity = Gravity.CENTER
                setPadding(0, dp(7), 0, 0)
            })
        }
    }

    private fun openDetail(id: String, source: View) {
        AppMotion.startContainer(
            this,
            source,
            Intent(this, WordDetailActivity::class.java)
                .putExtra(WordDetailActivity.EXTRA_WORD_ID, id)
        )
    }

    private fun showSortMenu(button: Button) {
        val items = Sort.entries.map(Sort::label).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("单词排序")
            .setSingleChoiceItems(items, sort.ordinal) { dialog, which ->
                sort = Sort.entries[which]
                button.text = "排序：${sort.label}"
                dialog.dismiss()
                renderWords()
            }
            .show()
    }

    private fun showManageMenu() {
        AlertDialog.Builder(this)
            .setTitle("管理单词本")
            .setItems(arrayOf("导出 Anki TSV", "补全缺失词汇信息", "清空单词本")) { _, which ->
                when (which) {
                    0 -> exportToAnki()
                    1 -> {
                        repository.getWords().filter { it.meaning.isBlank() }.forEach {
                            WordMeaningFetcher.fetchIfMissing(applicationContext, it.id)
                        }
                        Toast.makeText(this, "已让模型在后台补全缺失信息。", Toast.LENGTH_SHORT).show()
                    }
                    2 -> confirmClear()
                }
            }
            .show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("清空整个单词本？")
            .setMessage("收藏、标签和复习进度也会一起删除，而且无法撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认清空") { _, _ ->
                repository.clear()
                renderWords()
            }
            .show()
    }

    private fun dueLabel(word: WordbookEntry): String {
        return if (word.dueAt <= System.currentTimeMillis()) {
            "待复习"
        } else {
            "下次 ${SimpleDateFormat("MM.dd", Locale.getDefault()).format(Date(word.dueAt))}"
        }
    }

    private fun exportToAnki() {
        if (repository.getWords().isEmpty()) {
            Toast.makeText(this, "单词本还是空的，先收几个词再导出。", Toast.LENGTH_SHORT).show()
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
            val stream = contentResolver.openOutputStream(uri) ?: error("无法打开导出文件")
            stream.use { it.write(buildAnkiTsv(words).toByteArray(Charsets.UTF_8)) }
        }.onSuccess {
            Toast.makeText(this, "已导出 ${words.size} 个词的 Anki TSV 文件。", Toast.LENGTH_LONG).show()
        }.onFailure { throwable ->
            Toast.makeText(this, "导出失败：${throwable.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildAnkiTsv(words: List<WordbookEntry>): String {
        return buildString {
            append("#separator:tab\n#html:false\n#columns:词面\t读音\t释义\t原句\n")
            words.forEach { word ->
                val meaning = listOf(word.jlptLevel, word.meaning).filter(String::isNotBlank).joinToString(" ")
                append(tsvField(word.surface)).append('\t')
                append(tsvField(word.reading)).append('\t')
                append(tsvField(meaning)).append('\t')
                append(tsvField(word.sourceText)).append('\n')
            }
        }
    }

    private fun tsvField(value: String): String = value
        .replace('\t', ' ')
        .replace('\r', ' ')
        .replace('\n', ' ')
        .trim()

    private fun loadHaruhi(imageView: ImageView, drawableRes: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            imageView.setImageResource(drawableRes)
            return
        }
        runCatching {
            val source = ImageDecoder.createSource(resources, drawableRes)
            ImageDecoder.decodeDrawable(source)
        }.onSuccess { drawable ->
            imageView.setImageDrawable(drawable)
            (drawable as? AnimatedImageDrawable)?.start()
        }.onFailure {
            imageView.setImageResource(drawableRes)
        }
    }

    private fun matchWrap(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, dp(top), 0, dp(bottom)) }

    private fun matchHeight(height: Int, top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        dp(height)
    ).apply { topMargin = dp(top) }

    private fun wrapHeight(height: Int, end: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        dp(height)
    ).apply { marginEnd = dp(end) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private enum class Filter(val label: String) {
        ALL("全部"), DUE("待复习"), FAVORITE("收藏"), INCOMPLETE("待补全")
    }

    private enum class Sort(
        val label: String,
        val comparator: Comparator<WordbookEntry>
    ) {
        RECENT("最近收录", compareByDescending { it.updatedAt }),
        DUE("下次复习", compareBy { it.dueAt }),
        JLPT("JLPT", compareBy<WordbookEntry> { jlptRank(it.jlptLevel) }.thenByDescending { it.updatedAt });

        companion object {
            private fun jlptRank(level: String): Int = when (level) {
                "N1" -> 1
                "N2" -> 2
                "N3" -> 3
                "N4" -> 4
                "N5" -> 5
                else -> 6
            }
        }
    }

    companion object {
        private const val REQUEST_EXPORT_ANKI = 1001
    }
}
