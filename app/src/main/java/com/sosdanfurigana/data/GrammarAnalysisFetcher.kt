package com.sosdanfurigana.data

import com.sosdanfurigana.furigana.GrammarAnalysisClient
import com.sosdanfurigana.furigana.GrammarTokenCodec
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 保存笔记时在后台自动做语法分析（设置里可关闭）。
 *
 * - 进程级 scope：悬浮面板关掉也不影响写入。
 * - 已有缓存 / 未配 API / 开关关闭时直接跳过，同一条笔记不并发重复请求。
 * - 失败静默：等用户点开笔记时还可以手动再试。
 */
object GrammarAnalysisFetcher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightIds = mutableSetOf<String>()

    fun autoAnalyze(context: Context, noteId: String) {
        val appContext = context.applicationContext
        scope.launch {
            if (!SettingsRepository(appContext).autoGrammarAnalysis) return@launch
            val repository = NoteRepository(appContext)
            val note = repository.getNote(noteId) ?: return@launch
            if (note.grammarJson.isNotBlank()) return@launch
            val client = GrammarAnalysisClient(appContext)
            if (!client.isConfigured()) return@launch
            synchronized(inFlightIds) {
                if (!inFlightIds.add(noteId)) return@launch
            }
            try {
                client.analyze(note.originalText).onSuccess { tokens ->
                    val current = repository.getNote(noteId) ?: return@onSuccess
                    if (current.grammarJson.isBlank()) {
                        repository.updateGrammar(noteId, GrammarTokenCodec.encode(tokens))
                    }
                }
            } finally {
                synchronized(inFlightIds) { inFlightIds.remove(noteId) }
            }
        }
    }
}
