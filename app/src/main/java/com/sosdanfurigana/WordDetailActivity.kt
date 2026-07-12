package com.sosdanfurigana

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.sosdanfurigana.data.WordbookEntry
import com.sosdanfurigana.data.WordbookRepository
import com.sosdanfurigana.furigana.WordVerificationClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WordDetailActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repository: WordbookRepository
    private lateinit var content: LinearLayout
    private lateinit var wordId: String
    private var pendingSuggestedTags: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppMotion.prepareContainerActivity(this, savedInstanceState)
        repository = WordbookRepository(applicationContext)
        wordId = intent.getStringExtra(EXTRA_WORD_ID).orEmpty()
        if (wordId.isBlank() || repository.getWord(wordId) == null) {
            Toast.makeText(this, "这个词条已经不在单词本里了。", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
            background = AppUi.appBackground()
        }
        setContentView(ScrollView(this).apply {
            addView(content)
            AppWindowInsets.apply(this, includeIme = true)
        })
        render()
        AppMotion.bindContainerTarget(this, content)
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) render()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render() {
        val word = repository.getWord(wordId) ?: run {
            finish()
            return
        }
        content.removeAllViews()
        content.addView(topBar(word))
        content.addView(hero(word), matchWrap(top = 12))
        content.addView(section("语境", contextContent(word)), matchWrap(top = 12))
        content.addView(section("收藏与标签", tagContent(word)), matchWrap(top = 12))
        content.addView(section("学习进度", progressContent(word)), matchWrap(top = 12))
        content.addView(actionRow(word), matchWrap(top = 14))
    }

    private fun topBar(word: WordbookEntry): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Button(this@WordDetailActivity).apply {
                text = "‹ 返回"
                contentDescription = "返回单词列表"
                AppUi.ghost(this)
                setOnClickListener { AppMotion.finishContainer(this@WordDetailActivity) }
            }, LinearLayout.LayoutParams(dp(88), dp(48)))
            addView(TextView(this@WordDetailActivity).apply {
                text = "词条档案"
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(AppUi.INK)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(Button(this@WordDetailActivity).apply {
                text = if (word.isFavorite) "★" else "☆"
                textSize = 22f
                contentDescription = if (word.isFavorite) "取消收藏" else "收藏"
                if (word.isFavorite) AppUi.secondary(this) else AppUi.ghost(this)
                setOnClickListener {
                    repository.setFavorite(word.id, !word.isFavorite)
                    render()
                }
            }, LinearLayout.LayoutParams(dp(52), dp(48)))
        }
    }

    private fun hero(word: WordbookEntry): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(17))
            background = AppUi.heroBackground(this@WordDetailActivity)
            addView(TextView(this@WordDetailActivity).apply {
                text = "SOS WORD FILE"
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.08f
                setTextColor(AppUi.HEADBAND)
            })
            addView(TextView(this@WordDetailActivity).apply {
                text = word.surface
                textSize = 34f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(AppUi.CREAM)
                setPadding(0, dp(7), 0, 0)
            })
            addView(TextView(this@WordDetailActivity).apply {
                text = word.reading
                textSize = 17f
                setTextColor(AppUi.WARM_WHITE)
                setPadding(0, dp(2), 0, 0)
            })
            addView(TextView(this@WordDetailActivity).apply {
                text = word.meaning.ifBlank { "还没有释义，别让档案一直空着。" }
                textSize = 15f
                setTextColor(AppUi.CREAM)
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(0, dp(10), 0, 0)
            })
            val facts = listOf(word.jlptLevel, word.partOfSpeech).filter(String::isNotBlank)
            if (facts.isNotEmpty()) addView(TextView(this@WordDetailActivity).apply {
                text = facts.joinToString("  ·  ")
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(AppUi.HEADBAND)
                setPadding(0, dp(9), 0, 0)
            })
        }
    }

    private fun contextContent(word: WordbookEntry): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@WordDetailActivity).apply {
                text = word.sourceText.ifBlank { "这个词没有保存原句。" }
                textSize = 15f
                setTextColor(AppUi.INK)
                setLineSpacing(dp(5).toFloat(), 1f)
                setTextIsSelectable(true)
            })
            if (word.acceptedReadings.isNotEmpty()) addView(TextView(this@WordDetailActivity).apply {
                text = "已验证的其他语境读音：${word.acceptedReadings.joinToString("、")}"
                textSize = 12f
                setTextColor(AppUi.HAIR_SOFT)
                setPadding(0, dp(10), 0, 0)
            })
        }
    }

    private fun tagContent(word: WordbookEntry): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@WordDetailActivity).apply {
                text = if (word.tags.isEmpty()) "还没有标签" else word.tags.joinToString("  #", prefix = "#")
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (word.tags.isEmpty()) AppUi.MUTED else AppUi.HAIR)
            })
            addView(LinearLayout(this@WordDetailActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, 0)
                addView(Button(this@WordDetailActivity).apply {
                    text = "编辑标签"
                    AppUi.ghost(this)
                    setOnClickListener { showEditTags(word) }
                }, LinearLayout.LayoutParams(0, dp(48), 1f))
                addView(Button(this@WordDetailActivity).apply {
                    text = "让模型推荐"
                    AppUi.secondary(this)
                    setOnClickListener { requestTagSuggestions(word, this) }
                }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
            })
            if (pendingSuggestedTags.isNotEmpty()) addView(suggestionContent(word))
        }
    }

    private fun suggestionContent(word: WordbookEntry): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(11), dp(12), dp(11))
            background = AppUi.sectionBackground(this@WordDetailActivity, tinted = true)
            addView(TextView(this@WordDetailActivity).apply {
                text = "模型建议（点选才会加入）"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(AppUi.HAIR_SOFT)
            })
            pendingSuggestedTags.forEach { tag ->
                addView(Button(this@WordDetailActivity).apply {
                    text = "+ $tag"
                    AppUi.ghost(this)
                    setOnClickListener {
                        repository.setTags(word.id, word.tags + tag)
                        pendingSuggestedTags = pendingSuggestedTags - tag
                        render()
                    }
                }, matchHeight(42, top = 7))
            }
        }.also { it.layoutParams = matchWrap(top = 10) }
    }

    private fun progressContent(word: WordbookEntry): View {
        val next = if (word.dueAt <= System.currentTimeMillis()) {
            "现在可以复习"
        } else {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(word.dueAt))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(infoRow("下次复习", next))
            addView(infoRow("连续答对", "${word.streak} 次"))
            addView(infoRow("累计复习", "${word.reviewCount} 次"))
            addView(infoRow("最近更新", formatTime(word.updatedAt)))
        }
    }

    private fun actionRow(word: WordbookEntry): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@WordDetailActivity).apply {
                text = "编辑并验证"
                AppUi.primary(this)
                setOnClickListener { showEditDialog(word) }
            }, LinearLayout.LayoutParams(0, dp(50), 1f))
            addView(Button(this@WordDetailActivity).apply {
                text = "删除"
                AppUi.danger(this)
                setOnClickListener { confirmDelete(word) }
            }, LinearLayout.LayoutParams(dp(92), dp(50)).apply { marginStart = dp(8) })
        }
    }

    private fun showEditTags(word: WordbookEntry) {
        val input = EditText(this).apply {
            hint = "用逗号分隔，例如：旅行、会话"
            setText(word.tags.joinToString("、"))
            setTextColor(AppUi.INK)
            setHintTextColor(AppUi.MUTED)
            background = AppUi.inputBackground(this@WordDetailActivity)
            setPadding(dp(12), 0, dp(12), 0)
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("编辑自定义标签")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                repository.setTags(word.id, parseTags(input.text.toString()))
                render()
            }
            .show()
        input.requestFocus()
    }

    private fun requestTagSuggestions(word: WordbookEntry, button: Button) {
        button.isEnabled = false
        button.text = "推荐中…"
        scope.launch {
            val result = WordVerificationClient(this@WordDetailActivity).suggestTags(
                word.surface,
                word.reading,
                word.meaning,
                word.sourceText,
                word.tags
            )
            button.isEnabled = true
            button.text = "让模型推荐"
            result.fold(
                onSuccess = { tags ->
                    pendingSuggestedTags = tags.filterNot { it in word.tags }
                    if (pendingSuggestedTags.isEmpty()) {
                        Toast.makeText(this@WordDetailActivity, "模型没有找到新的合适标签。", Toast.LENGTH_SHORT).show()
                    }
                    render()
                },
                onFailure = { error ->
                    Toast.makeText(
                        this@WordDetailActivity,
                        "标签推荐失败：${error.message?.take(80)}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    private fun showEditDialog(word: WordbookEntry) {
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(2), dp(20), 0)
        }
        val reading = editField(fields, "读音", word.reading)
        val meaning = editField(fields, "中文释义", word.meaning, singleLine = false)
        val pos = editField(fields, "词性", word.partOfSpeech)
        val jlpt = editField(fields, "JLPT（N1-N5，可留空）", word.jlptLevel)
        val dialog = AlertDialog.Builder(this)
            .setTitle("编辑词汇信息")
            .setMessage("保存前必须通过模型验证；验证失败时不会改动词条。")
            .setView(fields)
            .setNegativeButton("取消", null)
            .setPositiveButton("交给模型验证", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val proposal = WordVerificationClient.ProposedWord(
                    reading = reading.text.toString().trim(),
                    meaning = meaning.text.toString().trim(),
                    partOfSpeech = pos.text.toString().trim(),
                    jlptLevel = jlpt.text.toString().trim().uppercase()
                )
                if (proposal.reading.isBlank() || proposal.meaning.isBlank()) {
                    Toast.makeText(this, "读音和释义不能为空。", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val verifyButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                verifyButton.isEnabled = false
                verifyButton.text = "验证中…"
                scope.launch {
                    WordVerificationClient(this@WordDetailActivity)
                        .verifyEdit(word.surface, word.sourceText, proposal)
                        .fold(
                            onSuccess = { verification ->
                                verifyButton.isEnabled = true
                                verifyButton.text = "交给模型验证"
                                if (verification.approved) {
                                    saveVerifiedEdit(word, proposal)
                                    dialog.dismiss()
                                } else {
                                    dialog.dismiss()
                                    showRejectedEdit(word, proposal, verification)
                                }
                            },
                            onFailure = { error ->
                                verifyButton.isEnabled = true
                                verifyButton.text = "交给模型验证"
                                Toast.makeText(
                                    this@WordDetailActivity,
                                    "验证失败，未保存：${error.message?.take(100)}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        )
                }
            }
        }
        dialog.show()
    }

    private fun showRejectedEdit(
        word: WordbookEntry,
        proposal: WordVerificationClient.ProposedWord,
        verification: WordVerificationClient.Verification
    ) {
        val message = buildString {
            appendLine(verification.reason.ifBlank { "模型认为这组修改不适合当前语境。" })
            appendLine()
            appendLine("你的版本")
            appendLine("${proposal.reading} · ${proposal.partOfSpeech} · ${proposal.jlptLevel}")
            appendLine(proposal.meaning)
            appendLine()
            appendLine("模型建议")
            appendLine("${verification.suggestion.reading} · ${verification.suggestion.partOfSpeech} · ${verification.suggestion.jlptLevel}")
            append(verification.suggestion.meaning)
        }
        AlertDialog.Builder(this)
            .setTitle("这版没有通过验证")
            .setMessage(message)
            .setNegativeButton("返回修改") { _, _ -> showEditDialog(word) }
            .setPositiveButton("验证并采用建议") { _, _ ->
                verifySuggestedEdit(word, verification.suggestion)
            }
            .show()
    }

    private fun verifySuggestedEdit(word: WordbookEntry, suggestion: WordVerificationClient.ProposedWord) {
        Toast.makeText(this, "正在复核模型建议…", Toast.LENGTH_SHORT).show()
        scope.launch {
            WordVerificationClient(this@WordDetailActivity)
                .verifyEdit(word.surface, word.sourceText, suggestion)
                .fold(
                    onSuccess = { verification ->
                        if (verification.approved) {
                            saveVerifiedEdit(word, suggestion)
                        } else {
                            Toast.makeText(
                                this@WordDetailActivity,
                                "建议仍未通过复核，未保存。",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    },
                    onFailure = { error ->
                        Toast.makeText(
                            this@WordDetailActivity,
                            "复核失败，未保存：${error.message?.take(80)}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
        }
    }

    private fun saveVerifiedEdit(word: WordbookEntry, proposal: WordVerificationClient.ProposedWord) {
        repository.updateWord(
            word.copy(
                reading = proposal.reading,
                meaning = proposal.meaning,
                partOfSpeech = proposal.partOfSpeech,
                jlptLevel = proposal.jlptLevel,
                updatedAt = System.currentTimeMillis()
            )
        )
        Toast.makeText(this, "验证通过，词条已更新。", Toast.LENGTH_SHORT).show()
        render()
    }

    private fun confirmDelete(word: WordbookEntry) {
        AlertDialog.Builder(this)
            .setTitle("删除「${word.surface}」？")
            .setMessage("标签、收藏和复习记录都会一起删除。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                repository.deleteWord(word.id)
                finish()
            }
            .show()
    }

    private fun editField(
        parent: LinearLayout,
        label: String,
        value: String,
        singleLine: Boolean = true
    ): EditText {
        parent.addView(TextView(this).apply {
            text = label
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(AppUi.HAIR)
            setPadding(0, dp(10), 0, dp(5))
        })
        return EditText(this).apply {
            setText(value)
            setSingleLine(singleLine)
            if (!singleLine) {
                minLines = 2
                gravity = Gravity.TOP
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
            setTextColor(AppUi.INK)
            setHintTextColor(AppUi.MUTED)
            background = AppUi.inputBackground(this@WordDetailActivity)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            parent.addView(this, matchWrap())
        }
    }

    private fun section(title: String, child: View): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = AppUi.sectionBackground(this@WordDetailActivity)
            addView(TextView(this@WordDetailActivity).apply {
                text = title
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(AppUi.HAIR)
                setPadding(0, 0, 0, dp(10))
            })
            addView(child)
        }
    }

    private fun infoRow(label: String, value: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(5))
            addView(TextView(this@WordDetailActivity).apply {
                text = label
                textSize = 13f
                setTextColor(AppUi.MUTED)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@WordDetailActivity).apply {
                text = value
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(AppUi.INK)
            })
        }
    }

    private fun parseTags(raw: String): List<String> = raw
        .split(',', '，', '、', ';', '；', '\n')
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinctBy { it.lowercase() }

    private fun formatTime(time: Long): String {
        if (time <= 0L) return "未知"
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(time))
    }

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun matchHeight(height: Int, top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        dp(height)
    ).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_WORD_ID = "word_id"
    }
}
