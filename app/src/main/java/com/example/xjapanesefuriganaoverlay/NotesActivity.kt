package com.example.xjapanesefuriganaoverlay

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.xjapanesefuriganaoverlay.data.FuriganaNote
import com.example.xjapanesefuriganaoverlay.data.NoteRepository
import com.example.xjapanesefuriganaoverlay.furigana.FuriganaAnnotationCodec
import com.example.xjapanesefuriganaoverlay.furigana.RubyAnnotationExtractor
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotesActivity : Activity() {
    private lateinit var repository: NoteRepository
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = NoteRepository(applicationContext)
        setContentView(createContentView())
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
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(18), dp(18), dp(18))
                background = AppUi.heroBackground(this@NotesActivity)
                elevation = dp(3).toFloat()
                addView(
                    View(this@NotesActivity).apply {
                        background = AppUi.headbandRule(this@NotesActivity)
                    },
                    LinearLayout.LayoutParams(dp(86), dp(5))
                )
                addView(
                    TextView(this@NotesActivity).apply {
                        text = "笔记"
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
                addView(TextView(this@NotesActivity).apply {
                    text = "自动保存注音过的 post，之后可以继续分词加词。"
                    textSize = 14f
                    setTextColor(0xFFEAF8FC.toInt())
                    setPadding(0, dp(6), 0, dp(12))
                })
                addView(Button(this@NotesActivity).apply {
                    text = "清空笔记"
                    AppUi.secondary(this)
                    setOnClickListener {
                        repository.clear()
                        renderNotes()
                        Toast.makeText(this@NotesActivity, "已清空笔记", Toast.LENGTH_SHORT).show()
                    }
                })
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
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

    private fun renderNotes() {
        list.removeAllViews()
        val notes = repository.getNotes()
        if (notes.isEmpty()) {
            list.addView(emptyText("暂无笔记。成功注音的 post 会自动保存在这里。"))
            return
        }
        notes.forEach { note ->
            list.addView(noteView(note))
        }
    }

    private fun noteView(note: FuriganaNote): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = AppUi.sectionBackground(this@NotesActivity)
            elevation = dp(1).toFloat()
            addView(TextView(this@NotesActivity).apply {
                text = formatTime(note.updatedAt)
                textSize = 12f
                setTextColor(AppUi.HAIR_SOFT)
            })
            addView(TextView(this@NotesActivity).apply {
                text = note.originalText
                textSize = 15f
                setTextColor(AppUi.INK)
                setPadding(0, dp(8), 0, 0)
            })
            addView(TextView(this@NotesActivity).apply {
                text = note.plainText
                textSize = 15f
                setTextColor(AppUi.MUTED)
                setPadding(0, dp(8), 0, 0)
            })
            addView(
                LinearLayout(this@NotesActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(10), 0, 0)
                    addView(
                        Button(this@NotesActivity).apply {
                            text = "分词/加词"
                            AppUi.primary(this)
                            setOnClickListener { openAddWord(note) }
                        },
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    )
                    addView(
                        Button(this@NotesActivity).apply {
                            text = "删除"
                            AppUi.danger(this)
                            setOnClickListener {
                                repository.deleteNote(note.id)
                                renderNotes()
                            }
                        },
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
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

    private fun openAddWord(note: FuriganaNote) {
        val hints = JSONArray()
        val annotations = FuriganaAnnotationCodec.decode(note.annotationHintsJson)
            .ifEmpty { RubyAnnotationExtractor.fromRubyHtml(note.originalText, note.rubyHtml) }
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
        startActivity(
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
            background = AppUi.sectionBackground(this@NotesActivity, tinted = true)
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
