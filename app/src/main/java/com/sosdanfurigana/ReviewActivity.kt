package com.sosdanfurigana

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.sosdanfurigana.data.ReadingAnswerNormalizer
import com.sosdanfurigana.data.ReadingReviewPolicy
import com.sosdanfurigana.data.ReviewScheduler
import com.sosdanfurigana.data.ReviewSentenceMatcher
import com.sosdanfurigana.data.WordbookEntry
import com.sosdanfurigana.data.WordbookRepository
import com.sosdanfurigana.furigana.ReadingAnswerVerifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ReviewActivity : Activity() {
    private lateinit var repository: WordbookRepository
    private lateinit var verifier: ReadingAnswerVerifier
    private lateinit var root: LinearLayout
    private lateinit var progressChip: TextView
    private lateinit var cardArea: LinearLayout
    private lateinit var actionArea: LinearLayout
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val queue = ArrayDeque<WordbookEntry>()
    private var passedCount = 0
    private var deferredCount = 0
    private var failedAttempts = 0
    private var verifyingJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppMotion.prepareContainerActivity(this, savedInstanceState)
        repository = WordbookRepository(applicationContext)
        verifier = ReadingAnswerVerifier(applicationContext)
        repository.dueWords().forEach { queue.addLast(it) }
        setContentView(createContentView().also {
            AppWindowInsets.apply(it, includeIme = true)
            AppMotion.bindContainerTarget(this, it)
        })
        if (queue.isEmpty()) {
            renderAllDone(nothingWasDue = true)
        } else {
            renderCard()
        }
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
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
                    LinearLayout(this@ReviewActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(TextView(this@ReviewActivity).apply {
                            text = "SOS 读音验收"
                            textSize = 24f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(AppUi.INK)
                        })
                        addView(TextView(this@ReviewActivity).apply {
                            text = "看原句，写出荧光标记词的读音"
                            textSize = 12f
                            setTextColor(AppUi.MUTED)
                            setPadding(0, dp(3), 0, 0)
                        })
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
        verifyingJob?.cancel()
        failedAttempts = 0
        progressChip.text = "待验收 ${queue.size} 个"
        cardArea.removeAllViews()
        actionArea.removeAllViews()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(dp(24), dp(28), dp(24), dp(28))
            background = AppUi.sectionBackground(this@ReviewActivity)
            elevation = dp(2).toFloat()
        }
        card.addView(TextView(this).apply {
            text = "原句任务"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(AppUi.MUTED)
        })
        val sentence = entry.sourceText.ifBlank { entry.surface }
        val ranges = ReviewSentenceMatcher.targetRanges(sentence, entry.surface)
        card.addView(TextView(this).apply {
            text = highlightedSentence(sentence, ranges)
            textSize = 23f
            setTextColor(AppUi.INK)
            setLineSpacing(dp(8).toFloat(), 1f)
            setPadding(0, dp(14), 0, dp(6))
            contentDescription = if (ranges.isEmpty()) {
                "原句：$sentence。目标词：${entry.surface}"
            } else {
                "原句：$sentence。荧光标记目标词：${entry.surface}"
            }
        })
        if (ranges.isEmpty() && entry.sourceText.isNotBlank()) {
            card.addView(TextView(this).apply {
                text = "目标词：${entry.surface}"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(AppUi.HAIR)
                background = AppUi.highlightedBackground(this@ReviewActivity)
                setPadding(dp(10), dp(6), dp(10), dp(6))
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(10), 0, 0) })
        }

        val answerInput = EditText(this).apply {
            hint = "输入符合语境的读音"
            textSize = 18f
            setTextColor(AppUi.INK)
            setHintTextColor(AppUi.MUTED)
            background = AppUi.inputBackground(this@ReviewActivity)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_NORMAL
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        card.addView(answerInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(54)
        ).apply { setMargins(0, dp(22), 0, 0) })

        val feedback = TextView(this).apply {
            text = "平假名、片假名都可以，空格和常见标点会自动忽略。"
            textSize = 13f
            setTextColor(AppUi.MUTED)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(10), 0, 0)
            announceForAccessibility(text)
        }
        card.addView(feedback)

        val verifyProgress = ProgressBar(this).apply {
            visibility = View.GONE
            contentDescription = "正在请求模型验证读音"
        }
        card.addView(verifyProgress, LinearLayout.LayoutParams(dp(32), dp(32)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setMargins(0, dp(12), 0, 0)
        })
        cardArea.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val submitButton = Button(this).apply {
            text = "提交读音"
            AppUi.primary(this)
        }
        val revealButton = Button(this).apply {
            text = "不会，看答案"
            AppUi.ghost(this)
        }
        actionArea.orientation = LinearLayout.VERTICAL
        actionArea.addView(submitButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(50)
        ))
        actionArea.addView(revealButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(50)
        ).apply { setMargins(0, dp(8), 0, 0) })

        fun submit() {
            val rawAnswer = answerInput.text?.toString().orEmpty()
            if (ReadingAnswerNormalizer.normalize(rawAnswer).isBlank()) {
                feedback.showFeedback("先填读音，团长不接受空白答卷。", AppUi.DANGER)
                answerInput.requestFocus()
                return
            }
            if (ReadingAnswerNormalizer.isAccepted(
                    answer = rawAnswer,
                    savedReading = entry.reading,
                    acceptedReadings = entry.acceptedReadings
                )
            ) {
                hideKeyboard(answerInput)
                val rating = ReadingReviewPolicy.ratingForLocalMatch(entry.streak, failedAttempts)
                completeAnswer(entry, rating, "读音正确，验收通过。")
                return
            }
            verifyWithModel(
                entry = entry,
                rawAnswer = rawAnswer,
                answerInput = answerInput,
                feedback = feedback,
                progress = verifyProgress,
                submitButton = submitButton,
                revealButton = revealButton
            )
        }
        submitButton.setOnClickListener { submit() }
        revealButton.setOnClickListener { revealAnswer(entry, card, answerInput) }
        answerInput.setOnEditorActionListener { _, actionId, event ->
            val done = actionId == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)
            if (done) submit()
            done
        }
        answerInput.requestFocus()
        answerInput.postDelayed({
            if (!isFinishing && queue.firstOrNull()?.id == entry.id) {
                (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(answerInput, InputMethodManager.SHOW_IMPLICIT)
            }
        }, 180L)
    }

    private fun verifyWithModel(
        entry: WordbookEntry,
        rawAnswer: String,
        answerInput: EditText,
        feedback: TextView,
        progress: ProgressBar,
        submitButton: Button,
        revealButton: Button
    ) {
        if (verifyingJob?.isActive == true) return
        answerInput.isEnabled = false
        submitButton.isEnabled = false
        revealButton.isEnabled = false
        progress.visibility = View.VISIBLE
        feedback.showFeedback("已与保存读音不同，正在请模型结合原句判断……", AppUi.WARNING)
        verifyingJob = activityScope.launch {
            val result = verifier.verify(
                sourceText = entry.sourceText.ifBlank { entry.surface },
                target = entry.surface,
                savedReading = entry.reading,
                userReading = rawAnswer
            )
            if (isFinishing || isDestroyed) return@launch
            answerInput.isEnabled = true
            submitButton.isEnabled = true
            revealButton.isEnabled = true
            progress.visibility = View.GONE
            result.fold(
                onSuccess = { verification ->
                    if (verification.accepted) {
                        val normalized = ReadingAnswerNormalizer.normalize(rawAnswer)
                        withContext(Dispatchers.IO) {
                            reviewWriteMutex.withLock {
                                repository.addAcceptedReading(entry.id, normalized)
                            }
                        }
                        completeAnswer(
                            entry,
                            ReadingReviewPolicy.ratingForModelMatch(),
                            "模型已确认这个读音符合原句，以后会直接通过。"
                        )
                    } else {
                        failedAttempts++
                        if (ReadingReviewPolicy.hasReachedFailureLimit(failedAttempts)) {
                            hideKeyboard(answerInput)
                            finishAsAgain(entry, "连续两次不符合语境，这题等会儿再来。")
                        } else {
                            feedback.showFeedback(
                                "这个读音不符合当前语境，再试一次。正确答案只会在你主动查看后显示。",
                                AppUi.DANGER
                            )
                            answerInput.selectAll()
                            answerInput.requestFocus()
                        }
                    }
                },
                onFailure = {
                    feedback.showFeedback(
                        "暂时无法确认这个读音。请重试，或跳过并查看答案；本次不计错。",
                        AppUi.WARNING
                    )
                    answerInput.requestFocus()
                }
            )
        }
    }

    private fun revealAnswer(entry: WordbookEntry, card: LinearLayout, answerInput: EditText) {
        verifyingJob?.cancel()
        hideKeyboard(answerInput)
        answerInput.isEnabled = false
        card.addView(View(this).apply {
            background = AppUi.headbandRule(this@ReviewActivity)
        }, LinearLayout.LayoutParams(dp(56), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setMargins(0, dp(18), 0, dp(12))
        })
        card.addView(TextView(this).apply {
            text = "${entry.surface}：${entry.reading}"
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(AppUi.HAIR)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        if (entry.meaning.isNotBlank()) {
            card.addView(TextView(this).apply {
                text = entry.meaning
                textSize = 14f
                setTextColor(AppUi.MUTED)
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        actionArea.removeAllViews()
        actionArea.addView(Button(this).apply {
            text = "继续下一题"
            AppUi.primary(this)
            setOnClickListener { finishAsAgain(entry, "已查看答案，稍后再验收一次。") }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(50)
        ))
    }

    private fun completeAnswer(
        expectedEntry: WordbookEntry,
        rating: ReviewScheduler.Rating,
        message: String
    ) {
        rate(expectedEntry, rating, message)
    }

    private fun finishAsAgain(entry: WordbookEntry, message: String) {
        rate(entry, ReviewScheduler.Rating.AGAIN, message)
    }

    private fun rate(
        expectedEntry: WordbookEntry,
        rating: ReviewScheduler.Rating,
        message: String
    ) {
        if (queue.firstOrNull()?.id != expectedEntry.id) return
        val entry = queue.removeFirstOrNull() ?: return
        if (rating != ReviewScheduler.Rating.AGAIN) {
            passedCount++
        } else {
            deferredCount++
        }
        renderCard()
        val wordRepository = repository
        reviewWriteScope.launch {
            val reviewed = reviewWriteMutex.withLock {
                ReviewScheduler.review(wordRepository.getWord(entry.id) ?: entry, rating).also {
                    wordRepository.updateWord(it)
                }
            }
            withContext(Dispatchers.Main) {
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(
                        this@ReviewActivity,
                        "$message ${ReviewScheduler.intervalDescription(reviewed)}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun renderAllDone(nothingWasDue: Boolean) {
        progressChip.text = "待验收 0 个"
        cardArea.removeAllViews()
        actionArea.removeAllViews()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(36), dp(24), dp(36))
            background = AppUi.sectionBackground(this@ReviewActivity, tinted = true)
        }
        card.addView(TextView(this).apply {
            text = if (nothingWasDue) "今日无任务" else "验收完毕！"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(AppUi.INK)
            gravity = Gravity.CENTER
        })
        card.addView(TextView(this).apply {
            text = if (nothingWasDue) {
                "今天没有该复习的词。想偷懒？正好，去 X 上再抓几条日文内容回来充实单词本！"
            } else if (deferredCount == 0) {
                "$passedCount 个词全部过关。SOS 团的字典里可没有『遗忘』这两个字——下次到期也要来报到，这是团长命令！"
            } else {
                "$passedCount 个词通过，$deferredCount 个词已安排 10 分钟后重验。今天这轮结束，但任务还没彻底清零。"
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
                setOnClickListener { AppMotion.finishContainer(this@ReviewActivity) }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(50)
            )
        )
    }

    private fun highlightedSentence(
        sentence: String,
        ranges: List<com.sosdanfurigana.data.ReviewTargetRange>
    ): CharSequence {
        val spannable = SpannableString(sentence)
        ranges.forEach { range ->
            spannable.setSpan(
                BackgroundColorSpan(AppUi.HEADBAND),
                range.start,
                range.endExclusive,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannable.setSpan(
                StyleSpan(Typeface.BOLD),
                range.start,
                range.endExclusive,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return spannable
    }

    private fun TextView.showFeedback(message: String, color: Int) {
        text = message
        setTextColor(color)
        announceForAccessibility(message)
    }

    private fun hideKeyboard(view: View) {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(view.windowToken, 0)
        view.clearFocus()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        private val reviewWriteMutex = Mutex()
        private val reviewWriteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

}
