package com.sosdanfurigana.data

import android.content.Context
import com.sosdanfurigana.furigana.WordMeaningClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 加词后在后台补取释义。
 *
 * - 进程级 scope：加词页面立刻关闭也不影响写入。
 * - 只在 meaning 为空时才发请求；成功后写回，同一个词不重复查询。
 * - 失败静默：释义是锦上添花，保存单词从不依赖它。
 */
object WordMeaningFetcher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightIds = mutableSetOf<String>()

    fun fetchIfMissing(context: Context, wordId: String) {
        val appContext = context.applicationContext
        scope.launch {
            val repository = WordbookRepository(appContext)
            val entry = repository.getWord(wordId) ?: return@launch
            if (entry.meaning.isNotBlank()) return@launch
            synchronized(inFlightIds) {
                if (!inFlightIds.add(wordId)) return@launch
            }
            try {
                WordMeaningClient(appContext)
                    .requestMeaning(entry.surface, entry.reading, entry.sourceText)
                    .onSuccess { meaning ->
                        val current = repository.getWord(wordId) ?: return@onSuccess
                        if (current.meaning.isBlank()) {
                            repository.updateWord(
                                current.copy(
                                    meaning = meaning.meaning,
                                    jlptLevel = meaning.jlptLevel
                                )
                            )
                        }
                    }
            } finally {
                synchronized(inFlightIds) { inFlightIds.remove(wordId) }
            }
        }
    }
}
