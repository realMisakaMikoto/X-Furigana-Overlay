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
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(0xFFF7F8FA.toInt())
        }

        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    TextView(this@NotesActivity).apply {
                        text = "笔记"
                        textSize = 22f
                        setTextColor(0xFF111111.toInt())
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                )
                addView(Button(this@NotesActivity).apply {
                    text = "清空"
                    setOnClickListener {
                        repository.clear()
                        renderNotes()
                        Toast.makeText(this@NotesActivity, "已清空笔记", Toast.LENGTH_SHORT).show()
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
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedBackground(0xFFFFFFFF.toInt())
            addView(TextView(this@NotesActivity).apply {
                text = formatTime(note.updatedAt)
                textSize = 12f
                setTextColor(0xFF666666.toInt())
            })
            addView(TextView(this@NotesActivity).apply {
                text = note.originalText
                textSize = 15f
                setTextColor(0xFF111111.toInt())
                setPadding(0, dp(8), 0, 0)
            })
            addView(TextView(this@NotesActivity).apply {
                text = note.plainText
                textSize = 15f
                setTextColor(0xFF333333.toInt())
                setPadding(0, dp(8), 0, 0)
            })
            addView(
                LinearLayout(this@NotesActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(10), 0, 0)
                    addView(
                        Button(this@NotesActivity).apply {
                            text = "分词/加词"
                            setOnClickListener { openAddWord(note) }
                        },
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    )
                    addView(
                        Button(this@NotesActivity).apply {
                            text = "删除"
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
            setTextColor(0xFF333333.toInt())
            gravity = Gravity.CENTER
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
