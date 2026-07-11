package com.sosdanfurigana

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.sosdanfurigana.data.FuriganaNote
import com.sosdanfurigana.data.NoteRepository
import com.sosdanfurigana.furigana.FuriganaAnnotation
import com.sosdanfurigana.furigana.FuriganaAnnotationCodec
import com.sosdanfurigana.furigana.GrammarAnalysisClient
import com.sosdanfurigana.furigana.GrammarHtmlRenderer
import com.sosdanfurigana.furigana.GrammarToken
import com.sosdanfurigana.furigana.GrammarTokenCodec
import com.sosdanfurigana.furigana.RubyAnnotationExtractor
import com.sosdanfurigana.furigana.RubyHtmlRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NoteDetailActivity : Activity() {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repository: NoteRepository
    private lateinit var webView: WebView
    private lateinit var grammarButton: Button
    private var note: FuriganaNote? = null
    private var annotations: List<FuriganaAnnotation> = emptyList()
    private var grammarTokens: List<GrammarToken> = emptyList()
    private var grammarShown = false
    private var analyzing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = NoteRepository(applicationContext)
        val loaded = intent.getStringExtra(EXTRA_NOTE_ID)?.let { repository.getNote(it) }
        if (loaded == null) {
            Toast.makeText(this, "这条笔记找不到了，也许被谁删了。", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        note = loaded
        annotations = FuriganaAnnotationCodec.decode(loaded.annotationHintsJson)
            .ifEmpty { RubyAnnotationExtractor.fromRubyHtml(loaded.originalText, loaded.rubyHtml) }
        grammarTokens = GrammarTokenCodec.decode(loaded.grammarJson)
        setContentView(createContentView(loaded))
        renderPlain()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
    }

    private fun createContentView(note: FuriganaNote): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = AppUi.appBackground()
        }

        root.addView(TextView(this).apply {
            text = "笔记详情"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(AppUi.INK)
        })
        root.addView(TextView(this).apply {
            text = formatTime(note.updatedAt)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(AppUi.HAIR_SOFT)
            setPadding(0, dp(4), 0, 0)
        })

        webView = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = false
            settings.domStorageEnabled = false
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = AppUi.sectionBackground(this@NoteDetailActivity)
            elevation = dp(1).toFloat()
            addView(
                webView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        root.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                setMargins(0, dp(12), 0, 0)
            }
        )

        grammarButton = Button(this).apply {
            text = if (grammarTokens.isEmpty()) "语法分析" else "显示语法"
            AppUi.primary(this)
            setOnClickListener { onGrammarClick() }
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                grammarButton,
                LinearLayout.LayoutParams(0, dp(50), 1f)
            )
            addView(
                Button(this@NoteDetailActivity).apply {
                    text = "分词/加词"
                    AppUi.ghost(this)
                    setOnClickListener { openAddWord() }
                },
                LinearLayout.LayoutParams(0, dp(50), 1f).apply {
                    marginStart = dp(8)
                }
            )
        }
        root.addView(
            actions,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dp(12), 0, 0)
            }
        )
        return root
    }

    private fun renderPlain() {
        val current = note ?: return
        grammarShown = false
        loadHtml(
            RubyHtmlRenderer.renderHtml(
                current.originalText,
                annotations,
                RubyHtmlRenderer.Theme.LIGHT
            )
        )
        if (!analyzing) {
            grammarButton.text = if (grammarTokens.isEmpty()) "语法分析" else "显示语法"
        }
    }

    private fun renderGrammar() {
        val current = note ?: return
        if (grammarTokens.isEmpty()) return
        grammarShown = true
        loadHtml(GrammarHtmlRenderer.renderHtml(current.originalText, annotations, grammarTokens))
        grammarButton.text = "隐藏语法"
    }

    private fun loadHtml(html: String) {
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    private fun onGrammarClick() {
        if (analyzing) return
        if (grammarShown) {
            renderPlain()
            return
        }
        if (grammarTokens.isNotEmpty()) {
            renderGrammar()
            return
        }
        val current = note ?: return
        val client = GrammarAnalysisClient(applicationContext)
        if (!client.isConfigured()) {
            Toast.makeText(
                this,
                "没配 API 就想让模型干活？先去首页把 API 填好，这是团长命令。",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        analyzing = true
        grammarButton.isEnabled = false
        grammarButton.text = "团长正在拆解句子…"
        uiScope.launch {
            val result = client.analyze(current.originalText)
            analyzing = false
            grammarButton.isEnabled = true
            result.fold(
                onSuccess = { tokens ->
                    grammarTokens = tokens
                    repository.updateGrammar(current.id, GrammarTokenCodec.encode(tokens))
                    renderGrammar()
                    Toast.makeText(
                        this@NoteDetailActivity,
                        "句子结构拆完了，以后打开这条笔记不再重复分析。",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onFailure = { throwable ->
                    grammarButton.text = "语法分析"
                    Toast.makeText(
                        this@NoteDetailActivity,
                        "模型这次没拆出来（${throwable.message?.take(60)}）。笔记还好好的，稍后再试。",
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    private fun openAddWord() {
        val current = note ?: return
        val hints = JSONArray()
        annotations.forEach { annotation ->
            hints.put(
                JSONObject()
                    .put("s", annotation.surface)
                    .put("r", annotation.reading)
                    .put("b", annotation.start)
                    .put("e", annotation.end)
            )
        }
        startActivity(
            Intent(this, AddWordActivity::class.java)
                .putExtra(AddWordActivity.EXTRA_SOURCE_TEXT, current.originalText)
                .putExtra(AddWordActivity.EXTRA_READING_HINTS, hints.toString())
        )
    }

    private fun formatTime(timeMillis: Long): String {
        if (timeMillis <= 0L) return ""
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timeMillis))
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        const val EXTRA_NOTE_ID = "note_id"
    }
}
