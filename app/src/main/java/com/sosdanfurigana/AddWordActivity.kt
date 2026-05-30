package com.sosdanfurigana

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.sosdanfurigana.data.WordbookRepository
import com.sosdanfurigana.furigana.FuriganaClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.text.Normalizer

class AddWordActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var sourceText: EditText
    private lateinit var surfaceInput: EditText
    private lateinit var readingInput: EditText
    private lateinit var repository: WordbookRepository
    private var source: String = ""
    private var readingHints: List<ReadingHint> = emptyList()
    private var readingRequestId: Int = 0
    private var readingLoading: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = WordbookRepository(applicationContext)
        source = intent.getStringExtra(EXTRA_SOURCE_TEXT).orEmpty()
        readingHints = parseReadingHints(intent.getStringExtra(EXTRA_READING_HINTS).orEmpty())
        setContentView(createContentView())
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
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
                background = AppUi.heroBackground(this@AddWordActivity)
                elevation = dp(3).toFloat()
                addView(
                    View(this@AddWordActivity).apply {
                        background = AppUi.headbandRule(this@AddWordActivity)
                    },
                    LinearLayout.LayoutParams(dp(86), dp(5))
                )
                addView(TextView(this@AddWordActivity).apply {
                    text = "手动加词"
                    textSize = 24f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(0xFFFFFFFF.toInt())
                    setPadding(0, dp(14), 0, 0)
                })
                addView(TextView(this@AddWordActivity).apply {
                    text = "从注音原文里选一段，读音能本地判断就不麻烦模型。"
                    textSize = 14f
                    setTextColor(0xFFEAF8FC.toInt())
                    setPadding(0, dp(6), 0, 0)
                })
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(14))
            }
        )

        sourceText = EditText(this).apply {
            setText(source)
            setTextIsSelectable(true)
            keyListener = null
            isCursorVisible = false
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
            textSize = 16f
            setTextColor(AppUi.INK)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = AppUi.sectionBackground(this@AddWordActivity)
        }
        root.addView(
            sourceText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(150)
            )
        )

        root.addView(Button(this).apply {
            text = "使用选中文本"
            AppUi.primary(this)
            setOnClickListener { useSelectedText() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(50)
        ).apply {
            setMargins(0, dp(10), 0, 0)
        })

        root.addView(label("词面"))
        surfaceInput = EditText(this).apply {
            hint = "例：人気"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setTextColor(AppUi.INK)
            setHintTextColor(0xFF8B9AA0.toInt())
            background = AppUi.inputBackground(this@AddWordActivity)
            setPadding(dp(12), 0, dp(12), 0)
        }
        root.addView(surfaceInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48)
        ))

        root.addView(label("读音"))
        readingInput = EditText(this).apply {
            hint = "例：ひとけ"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setTextColor(AppUi.INK)
            setHintTextColor(0xFF8B9AA0.toInt())
            background = AppUi.inputBackground(this@AddWordActivity)
            setPadding(dp(12), 0, dp(12), 0)
        }
        root.addView(readingInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48)
        ))

        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(16), 0, 0)
                addView(
                    Button(this@AddWordActivity).apply {
                        text = "保存"
                        AppUi.primary(this)
                        setOnClickListener { saveWord() }
                    },
                    LinearLayout.LayoutParams(0, dp(50), 1f)
                )
                addView(
                    Button(this@AddWordActivity).apply {
                        text = "取消"
                        AppUi.ghost(this)
                        setOnClickListener { finish() }
                    },
                    LinearLayout.LayoutParams(0, dp(50), 1f).apply {
                        setMargins(dp(8), 0, 0, 0)
                    }
                )
            }
        )

        return ScrollView(this).apply {
            addView(root)
        }
    }

    private fun useSelectedText() {
        var start = sourceText.selectionStart.coerceAtMost(sourceText.selectionEnd)
        var end = sourceText.selectionStart.coerceAtLeast(sourceText.selectionEnd)
        if (start < 0 || end <= start || end > source.length) {
            Toast.makeText(this, "请先在原文中选择词", Toast.LENGTH_SHORT).show()
            return
        }
        while (start < end && source[start].isWhitespace()) start++
        while (end > start && source[end - 1].isWhitespace()) end--
        val selected = source.substring(start, end)
        if (selected.isBlank()) {
            Toast.makeText(this, "选中文本为空", Toast.LENGTH_SHORT).show()
            return
        }
        surfaceInput.setText(selected)
        requestSelectedReading(selected, start, end)
    }

    private fun requestSelectedReading(selected: String, start: Int, end: Int) {
        val requestId = ++readingRequestId
        val resolveResult = SelectedReadingResolver(source, readingHints).resolve(start, end)
        if (!resolveResult.shouldUseLlm && !resolveResult.reading.isNullOrBlank()) {
            readingLoading = false
            readingInput.isEnabled = true
            readingInput.setText(resolveResult.reading)
            logResolveResult(
                selected = selected,
                start = start,
                end = end,
                result = resolveResult,
                willCallLlm = false,
                finalReading = resolveResult.reading
            )
            Toast.makeText(this, "已使用本地注音索引：${resolveResult.hitType}", Toast.LENGTH_SHORT).show()
            focusReadingInput()
            return
        }

        logResolveResult(
            selected = selected,
            start = start,
            end = end,
            result = resolveResult,
            willCallLlm = true,
            finalReading = resolveResult.reading
        )

        readingLoading = true
        readingInput.isEnabled = false
        readingInput.setText("识别中...")
        Toast.makeText(this, "正在让模型识别选区读音", Toast.LENGTH_SHORT).show()

        scope.launch {
            val result = FuriganaClient(this@AddWordActivity)
                .requestSelectionReading(source, selected, start, end)
            if (requestId != readingRequestId) return@launch

            readingLoading = false
            readingInput.isEnabled = true
            result.fold(
                onSuccess = { reading ->
                    readingInput.setText(reading)
                    Log.d(
                        TAG,
                        "selection LLM success finalReading=$reading hitType=${resolveResult.hitType}"
                    )
                    Toast.makeText(this@AddWordActivity, "已由模型识别读音，可手动修改", Toast.LENGTH_SHORT).show()
                },
                onFailure = { throwable ->
                    val fallbackReading = resolveResult.reading
                        ?.takeIf { it.isNotBlank() }
                        ?: inferReading(selected, start, end).takeIf { it.isNotBlank() }
                    Log.d(
                        TAG,
                        "selection LLM failed hitType=${resolveResult.hitType} fallback=$fallbackReading error=${throwable.message ?: throwable.javaClass.simpleName}"
                    )
                    readingInput.setText(fallbackReading.orEmpty())
                    Toast.makeText(
                        this@AddWordActivity,
                        if (fallbackReading.isNullOrBlank()) {
                            "模型识别失败，请手动填写：${throwable.message ?: throwable.javaClass.simpleName}"
                        } else {
                            "模型识别失败，已使用本地兜底：${throwable.message ?: throwable.javaClass.simpleName}"
                        },
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
            focusReadingInput()
        }
    }

    private fun focusReadingInput() {
        readingInput.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(readingInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun logResolveResult(
        selected: String,
        start: Int,
        end: Int,
        result: ResolveResult,
        willCallLlm: Boolean,
        finalReading: String?
    ) {
        val matchedHints = result.usedHints.joinToString(
            separator = "; ",
            prefix = "[",
            postfix = "]"
        ) { hint ->
            "${hint.start}-${hint.end}:${hint.surface}/${hint.reading}"
        }
        Log.d(
            TAG,
            "selection sourceLength=${source.length}, start=$start, end=$end, selected=$selected, " +
                "hintsCount=${readingHints.size}, matchedHints=$matchedHints, " +
                "hitType=${result.hitType}, finalReading=${finalReading.orEmpty()}, " +
                "shouldUseLlm=${result.shouldUseLlm}, willCallLlm=$willCallLlm, reason=${result.reason}"
        )
    }

    private fun saveWord() {
        val surface = surfaceInput.text.toString().trim()
        val reading = readingInput.text.toString().trim()
        if (readingLoading) {
            Toast.makeText(this, "读音识别中，请稍等", Toast.LENGTH_SHORT).show()
            return
        }
        if (surface.isBlank()) {
            Toast.makeText(this, "请填写词面", Toast.LENGTH_SHORT).show()
            return
        }
        if (reading.isBlank()) {
            Toast.makeText(this, "请填写读音", Toast.LENGTH_SHORT).show()
            return
        }
        repository.saveWord(surface, reading, source)
        Toast.makeText(this, "已加入单词本", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun label(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(AppUi.HAIR)
            setPadding(0, dp(14), 0, dp(6))
        }
    }

    private fun inferReading(selected: String, start: Int, end: Int): String {
        contextualSingleKanjiReading(selected, start)?.let { return it }
        exactHintByRange(start, end)?.let { return it }
        inferReadingBySourceRange(start, end)?.let { return it }
        uniqueHintBySurface(selected)?.let { return it }
        inferReadingBySurfaceScan(selected)?.let { return it }

        return ""
    }

    private fun inferReadingBySourceRange(start: Int, end: Int): String? {
        if (start < 0 || end > source.length || start >= end) return null
        val builder = StringBuilder()
        var cursor = start
        while (cursor < end) {
            val numberToken = readNumberWithSuffix(cursor, end)
            if (numberToken != null) {
                builder.append(numberToken.reading)
                cursor = numberToken.end
                continue
            }

            val hint = bestHintStartingAt(cursor, end)
            if (hint != null) {
                builder.append(hint.reading)
                cursor = hint.end
                continue
            }

            val literalEnd = literalRunEnd(cursor, end)
            if (literalEnd > cursor) {
                builder.append(literalSegmentReading(source.substring(cursor, literalEnd)) ?: return null)
                cursor = literalEnd
                continue
            }

            val char = source[cursor]
            builder.append(charReading(normalizeSingleChar(char)) ?: return null)
            cursor++
        }
        return builder.toString().takeIf { it.isNotBlank() }
    }

    private fun inferReadingBySurfaceScan(selected: String): String? {
        val normalizedSelected = normalizeJapaneseWidth(selected)
        val hints = readingHints
            .filter { it.surface.isNotBlank() }
            .sortedByDescending { it.surface.length }
        if (hints.isEmpty()) return literalSegmentReading(normalizedSelected)

        val builder = StringBuilder()
        var index = 0
        while (index < normalizedSelected.length) {
            val hint = hints.firstOrNull { normalizedSelected.startsWith(it.surface, index) }
            if (hint != null) {
                builder.append(hint.reading)
                index += hint.surface.length
                continue
            }
            builder.append(charReading(normalizedSelected[index]) ?: return null)
            index++
        }
        return builder.toString().takeIf { it.isNotBlank() }
    }

    private fun exactHintByRange(start: Int, end: Int): String? {
        return readingHints.firstOrNull { it.start == start && it.end == end }?.reading
    }

    private fun bestHintStartingAt(start: Int, rangeEnd: Int): ReadingHint? {
        return readingHints
            .asSequence()
            .filter { it.hasValidRange() && it.start == start && it.end <= rangeEnd }
            .sortedWith(
                compareByDescending<ReadingHint> { it.end - it.start }
                    .thenByDescending { it.confidence }
            )
            .firstOrNull()
    }

    private fun uniqueHintBySurface(surface: String): String? {
        val readings = readingHints
            .filter { it.surface == surface }
            .map { it.reading }
            .distinct()
        return readings.singleOrNull()
    }

    private fun literalSegmentReading(segment: String): String? {
        val builder = StringBuilder()
        normalizeJapaneseWidth(segment).forEach { char ->
            builder.append(charReading(char) ?: return null)
        }
        return builder.toString()
    }

    private fun literalSegmentReading(start: Int, end: Int): String? {
        if (start < 0 || end > source.length || start > end) return null
        val builder = StringBuilder()
        var cursor = start
        while (cursor < end) {
            val numberToken = readNumberWithSuffix(cursor, end)
            if (numberToken != null) {
                builder.append(numberToken.reading)
                cursor = numberToken.end
                continue
            }
            val literalStart = cursor
            while (cursor < end && !isDigitLike(source[cursor])) {
                cursor++
            }
            builder.append(literalSegmentReading(source.substring(literalStart, cursor)) ?: return null)
        }
        return builder.toString()
    }

    private fun charReading(char: Char): String? {
        return when {
            isHiragana(char) -> char.toString()
            isKatakana(char) -> (char.code - KATAKANA_TO_HIRAGANA_OFFSET).toChar().toString()
            char == 'ー' -> "ー"
            isSkippableForReading(char) -> ""
            isIgnorableLatin(char) -> ""
            isKanjiLike(char) -> null
            else -> null
        }
    }

    private fun isHiragana(char: Char): Boolean {
        return char in '\u3041'..'\u3096' || char in '\u309D'..'\u309E'
    }

    private fun isKatakana(char: Char): Boolean {
        return char in '\u30A1'..'\u30F6' || char in '\u30FD'..'\u30FE'
    }

    private fun isKanjiLike(char: Char): Boolean {
        return char in '\u3400'..'\u4DBF' ||
            char in '\u4E00'..'\u9FFF' ||
            char in '\uF900'..'\uFAFF' ||
            char == '々' ||
            char == '〆'
    }

    private fun isSkippableForReading(char: Char): Boolean {
        return char.isWhitespace() || char in SKIPPABLE_READING_CHARS
    }

    private fun contextualSingleKanjiReading(selected: String, start: Int): String? {
        if (selected.length != 1) return null
        val previous = previousNonWhitespaceChar(start)
        return when {
            selected == "月" && previous != null && isDigitLike(previous) -> "がつ"
            selected == "年" && previous != null && isDigitLike(previous) -> "ねん"
            selected == "日" -> previousNumberValueBefore(start)?.let { dateDayReading(it) }
            else -> null
        }
    }

    private fun numberRunReading(start: Int, end: Int): String? {
        readNumberWithSuffix(start, source.length)?.takeIf { it.end >= end }?.let { return it.reading }
        return japaneseNumberReading(numberValue(start, end) ?: return null)
    }

    private fun readNumberWithSuffix(start: Int, rangeEnd: Int): ReadingToken? {
        if (start >= rangeEnd || !isDigitLike(source[start])) return null
        val digitEnd = digitRunEnd(start, rangeEnd)
        val value = numberValue(start, digitEnd) ?: return null
        if (digitEnd >= rangeEnd) {
            return ReadingToken(japaneseNumberReading(value) ?: return null, digitEnd)
        }
        return when (source[digitEnd]) {
            '年' -> ReadingToken("${japaneseNumberReading(value) ?: return null}ねん", digitEnd + 1)
            '月' -> ReadingToken("${monthNumberReading(value) ?: return null}がつ", digitEnd + 1)
            '日' -> ReadingToken("${dateDayReading(value) ?: return null}", digitEnd + 1)
            else -> ReadingToken(japaneseNumberReading(value) ?: return null, digitEnd)
        }
    }

    private fun digitRunEnd(start: Int, rangeEnd: Int): Int {
        var cursor = start
        while (cursor < rangeEnd && isDigitLike(source[cursor])) {
            cursor++
        }
        return cursor
    }

    private fun literalRunEnd(start: Int, rangeEnd: Int): Int {
        var cursor = start
        while (cursor < rangeEnd) {
            val char = normalizeSingleChar(source[cursor])
            if (isDigitLike(char)) break
            if (isKanjiLike(char)) break
            if (bestHintStartingAt(cursor, rangeEnd) != null) break
            cursor++
        }
        return cursor
    }

    private fun numberValue(start: Int, end: Int): Int? {
        if (start >= end || end > source.length) return null
        return normalizeDigits(source.substring(start, end)).toIntOrNull()
    }

    private fun monthNumberReading(value: Int): String? {
        return when (value) {
            1 -> "いち"
            2 -> "に"
            3 -> "さん"
            4 -> "し"
            5 -> "ご"
            6 -> "ろく"
            7 -> "しち"
            8 -> "はち"
            9 -> "く"
            10 -> "じゅう"
            11 -> "じゅういち"
            12 -> "じゅうに"
            else -> null
        }
    }

    private fun dateDayReading(value: Int): String? {
        return when (value) {
            1 -> "ついたち"
            2 -> "ふつか"
            3 -> "みっか"
            4 -> "よっか"
            5 -> "いつか"
            6 -> "むいか"
            7 -> "なのか"
            8 -> "ようか"
            9 -> "ここのか"
            10 -> "とおか"
            11 -> "じゅういちにち"
            12 -> "じゅうににち"
            13 -> "じゅうさんにち"
            14 -> "じゅうよっか"
            15 -> "じゅうごにち"
            16 -> "じゅうろくにち"
            17 -> "じゅうしちにち"
            18 -> "じゅうはちにち"
            19 -> "じゅうくにち"
            20 -> "はつか"
            21 -> "にじゅういちにち"
            22 -> "にじゅうににち"
            23 -> "にじゅうさんにち"
            24 -> "にじゅうよっか"
            25 -> "にじゅうごにち"
            26 -> "にじゅうろくにち"
            27 -> "にじゅうしちにち"
            28 -> "にじゅうはちにち"
            29 -> "にじゅうくにち"
            30 -> "さんじゅうにち"
            31 -> "さんじゅういちにち"
            else -> null
        }
    }

    private fun japaneseNumberReading(value: Int): String? {
        if (value < 0 || value > 9999) return null
        if (value == 0) return "ぜろ"
        val thousands = value / 1000
        val hundreds = (value / 100) % 10
        val tens = (value / 10) % 10
        val ones = value % 10
        return buildString {
            append(thousandReading(thousands))
            append(hundredReading(hundreds))
            append(tenReading(tens))
            append(oneReading(ones))
        }.takeIf { it.isNotBlank() }
    }

    private fun thousandReading(value: Int): String {
        return when (value) {
            0 -> ""
            1 -> "せん"
            2 -> "にせん"
            3 -> "さんぜん"
            4 -> "よんせん"
            5 -> "ごせん"
            6 -> "ろくせん"
            7 -> "ななせん"
            8 -> "はっせん"
            9 -> "きゅうせん"
            else -> ""
        }
    }

    private fun hundredReading(value: Int): String {
        return when (value) {
            0 -> ""
            1 -> "ひゃく"
            2 -> "にひゃく"
            3 -> "さんびゃく"
            4 -> "よんひゃく"
            5 -> "ごひゃく"
            6 -> "ろっぴゃく"
            7 -> "ななひゃく"
            8 -> "はっぴゃく"
            9 -> "きゅうひゃく"
            else -> ""
        }
    }

    private fun tenReading(value: Int): String {
        return when (value) {
            0 -> ""
            1 -> "じゅう"
            2 -> "にじゅう"
            3 -> "さんじゅう"
            4 -> "よんじゅう"
            5 -> "ごじゅう"
            6 -> "ろくじゅう"
            7 -> "ななじゅう"
            8 -> "はちじゅう"
            9 -> "きゅうじゅう"
            else -> ""
        }
    }

    private fun oneReading(value: Int): String {
        return when (value) {
            0 -> ""
            1 -> "いち"
            2 -> "に"
            3 -> "さん"
            4 -> "よん"
            5 -> "ご"
            6 -> "ろく"
            7 -> "なな"
            8 -> "はち"
            9 -> "きゅう"
            else -> ""
        }
    }

    private fun previousNonWhitespaceChar(index: Int): Char? {
        var cursor = index - 1
        while (cursor >= 0) {
            val char = source[cursor]
            if (!char.isWhitespace()) return char
            cursor--
        }
        return null
    }

    private fun previousNumberValueBefore(index: Int): Int? {
        var end = index
        while (end > 0 && source[end - 1].isWhitespace()) {
            end--
        }
        var start = end
        while (start > 0 && isDigitLike(source[start - 1])) {
            start--
        }
        if (start == end) return null
        return numberValue(start, end)
    }

    private fun nextNonWhitespaceChar(index: Int): Char? {
        var cursor = index
        while (cursor < source.length) {
            val char = source[cursor]
            if (!char.isWhitespace()) return char
            cursor++
        }
        return null
    }

    private fun isDigitLike(char: Char): Boolean {
        return char in '0'..'9' || char in '０'..'９'
    }

    private fun normalizeDigit(char: Char): Char {
        return when (char) {
            in '0'..'9' -> char
            in '０'..'９' -> ('0'.code + (char.code - '０'.code)).toChar()
            else -> char
        }
    }

    private fun isIgnorableLatin(char: Char): Boolean {
        return char in 'A'..'Z' || char in 'a'..'z'
    }

    private fun normalizeJapaneseWidth(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
    }

    private fun normalizeSingleChar(char: Char): Char {
        return normalizeJapaneseWidth(char.toString()).firstOrNull() ?: char
    }

    private fun normalizeDigits(value: String): String {
        return buildString {
            value.forEach { char ->
                append(
                    when (char) {
                        in '0'..'9' -> char
                        in '０'..'９' -> ('0'.code + (char.code - '０'.code)).toChar()
                        else -> return@forEach
                    }
                )
            }
        }
    }

    private fun parseReadingHints(raw: String): List<ReadingHint> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            val parsed = buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val surface = item.optString("s").trim()
                    val rawReading = item.optString("r").trim()
                    val start = item.optInt("b", -1)
                    val end = item.optInt("e", -1)
                    val confidence = item.optDouble("c", 0.5).coerceIn(0.0, 1.0)
                    val reading = contextualHintReading(surface, start, rawReading)
                    if (surface.isNotBlank() && reading.isNotBlank()) {
                        add(
                            ReadingHint(
                                surface = surface,
                                reading = reading,
                                start = start,
                                end = end,
                                confidence = confidence
                            )
                        )
                    }
                }
            }
            expandReadingHints(parsed)
        }.getOrDefault(emptyList())
    }

    private fun contextualHintReading(surface: String, start: Int, rawReading: String): String {
        contextualSingleKanjiReading(surface, start)?.let { return it }
        val previous = previousNonWhitespaceChar(start)
        return when {
            surface.startsWith("月") && previous != null && isDigitLike(previous) ->
                rawReading.replacePrefix("げつ", "がつ")
                    .replacePrefix("つき", "がつ")
            surface.startsWith("年") && previous != null && isDigitLike(previous) ->
                rawReading.replacePrefix("とし", "ねん")
            surface.startsWith("日") -> {
                val dayReading = previousNumberValueBefore(start)?.let { dateDayReading(it) }
                if (dayReading == null) {
                    rawReading
                } else {
                    rawReading.replacePrefix("にち", dayReading)
                        .replacePrefix("じつ", dayReading)
                        .replacePrefix("ひ", dayReading)
                }
            }
            else -> rawReading
        }
    }

    private fun expandReadingHints(hints: List<ReadingHint>): List<ReadingHint> {
        val expanded = mutableListOf<ReadingHint>()
        hints.forEach { hint ->
            expanded.add(hint)
            splitHintIntoSingleKanjiHints(hint).forEach { expanded.add(it) }
        }
        return expanded.distinctBy { "${it.start}:${it.end}:${it.surface}:${it.reading}" }
    }

    private fun splitHintIntoSingleKanjiHints(hint: ReadingHint): List<ReadingHint> {
        if (!hint.hasValidRange()) return emptyList()
        if (hint.surface.length <= 1) return emptyList()
        if (hint.end - hint.start != hint.surface.length) return emptyList()
        if (hint.surface.any { !isKanjiLike(it) }) return emptyList()
        val parts = splitReadingByKanji(hint.surface, hint.reading) ?: return emptyList()
        return parts.mapIndexed { index, reading ->
            ReadingHint(
                surface = hint.surface[index].toString(),
                reading = reading,
                start = hint.start + index,
                end = hint.start + index + 1,
                confidence = hint.confidence * 0.95
            )
        }
    }

    private fun splitReadingByKanji(surface: String, reading: String): List<String>? {
        fun search(index: Int, cursor: Int): List<String>? {
            if (index == surface.length) {
                return if (cursor == reading.length) emptyList() else null
            }
            val options = KANJI_READING_PARTS[surface[index]].orEmpty()
                .sortedByDescending { it.length }
            options.forEach { option ->
                if (reading.startsWith(option, cursor)) {
                    val rest = search(index + 1, cursor + option.length)
                    if (rest != null) return listOf(option) + rest
                }
            }
            return null
        }
        return search(0, 0)
    }

    private fun String.replacePrefix(prefix: String, replacement: String): String {
        return if (startsWith(prefix)) replacement + drop(prefix.length) else this
    }

    private fun roundedBackground(color: Int): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(8).toFloat()
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        const val EXTRA_SOURCE_TEXT = "source_text"
        const val EXTRA_READING_HINTS = "reading_hints"
        private const val TAG = "AddWordActivity"
        private const val KATAKANA_TO_HIRAGANA_OFFSET = 0x60
        private const val SKIPPABLE_READING_CHARS =
            "、。，．・･!！?？「」『』（）()[]【】<>《》〈〉…‥〜～-—―/／\\|｜:：;；,.\"'“”‘’"
        private val KANJI_READING_PARTS = mapOf(
            '一' to listOf("いち", "ひと"),
            '二' to listOf("に", "ふた"),
            '三' to listOf("さん", "み"),
            '四' to listOf("よん", "し", "よ"),
            '五' to listOf("ご", "いつ"),
            '六' to listOf("ろく", "む"),
            '七' to listOf("なな", "しち"),
            '八' to listOf("はち", "や"),
            '九' to listOf("きゅう", "く"),
            '十' to listOf("じゅう", "とお"),
            '年' to listOf("ねん", "とし"),
            '月' to listOf("がつ", "げつ", "つき"),
            '日' to listOf("にち", "じつ", "ひ", "び", "か"),
            '時' to listOf("じ", "とき"),
            '分' to listOf("ふん", "ぷん", "ぶん", "わ"),
            '発' to listOf("はつ", "はっ"),
            '売' to listOf("ばい", "う"),
            '最' to listOf("さい"),
            '新' to listOf("しん", "あら", "にい"),
            '人' to listOf("ひと", "にん", "じん"),
            '気' to listOf("け", "き"),
            '道' to listOf("みち", "どう")
        )
    }

    private data class ReadingToken(
        val reading: String,
        val end: Int
    )
}
