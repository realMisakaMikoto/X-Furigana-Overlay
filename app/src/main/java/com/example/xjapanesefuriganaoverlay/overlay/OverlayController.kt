package com.example.xjapanesefuriganaoverlay.overlay

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.xjapanesefuriganaoverlay.AddWordActivity
import com.example.xjapanesefuriganaoverlay.AppUi
import com.example.xjapanesefuriganaoverlay.R
import com.example.xjapanesefuriganaoverlay.accessibility.ScanMetrics
import com.example.xjapanesefuriganaoverlay.accessibility.XFuriganaPerf
import com.example.xjapanesefuriganaoverlay.accessibility.XTextAccessibilityService
import com.example.xjapanesefuriganaoverlay.data.CurrentPostRepository
import com.example.xjapanesefuriganaoverlay.data.DetectedPost
import com.example.xjapanesefuriganaoverlay.data.FuriganaCache
import com.example.xjapanesefuriganaoverlay.data.NoteRepository
import com.example.xjapanesefuriganaoverlay.data.SettingsRepository
import com.example.xjapanesefuriganaoverlay.furigana.FuriganaClient
import com.example.xjapanesefuriganaoverlay.furigana.FuriganaAnnotation
import com.example.xjapanesefuriganaoverlay.furigana.FuriganaAnnotationCodec
import com.example.xjapanesefuriganaoverlay.furigana.RubyAnnotationExtractor
import com.example.xjapanesefuriganaoverlay.furigana.RubyHtmlRenderer
import com.example.xjapanesefuriganaoverlay.japanese.JapaneseTextDetector
import com.example.xjapanesefuriganaoverlay.util.TextHash
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

@SuppressLint("StaticFieldLeak")
object OverlayController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var windowManager: WindowManager? = null
    private var buttonView: View? = null
    private var panelView: FrameLayout? = null
    private var panelContentView: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var lastPanelWidth = 0
    private var lastPanelHeight = 0
    private var lastPanelX = UNSET_POSITION
    private var lastPanelY = UNSET_POSITION
    private var lastButtonX = UNSET_POSITION
    private var lastButtonY = UNSET_POSITION
    private var currentCopyHtml: String? = null
    private var currentCopyText: String? = null
    private var panelScanGeneration = 0
    private val inFlightAnnotationRequests =
        mutableMapOf<String, Deferred<Result<List<FuriganaAnnotation>>>>()

    fun showButton(context: Context) {
        val appContext = context.applicationContext
        if (!Settings.canDrawOverlays(appContext)) return
        if (buttonView != null) return

        val wm = appContext.windowManager()
        windowManager = wm
        val button = OverlayButtonView(appContext).apply {
            setImageResource(R.drawable.sos)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "SOS Furigana"
            setPadding(
                dp(appContext, 2),
                dp(appContext, 2),
                dp(appContext, 2),
                dp(appContext, 2)
            )
            background = ovalDrawable(Color.WHITE, AppUi.HEADBAND)
            elevation = dp(appContext, 10).toFloat()
        }

        val buttonSize = dp(appContext, 56)
        val metrics = appContext.resources.displayMetrics
        val params = WindowManager.LayoutParams(
            buttonSize,
            buttonSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (lastButtonX != UNSET_POSITION) {
                clamp(lastButtonX, 0, metrics.widthPixels - buttonSize)
            } else {
                dp(appContext, 16)
            }
            y = if (lastButtonY != UNSET_POSITION) {
                clamp(lastButtonY, 0, metrics.heightPixels - buttonSize)
            } else {
                dp(appContext, 120)
            }
        }

        button.setOnClickListener { togglePanel(appContext) }
        button.setOnTouchListener(
            DraggableTouchListener(wm, params) { x, y ->
                lastButtonX = x
                lastButtonY = y
            }
        )

        runCatching {
            wm.addView(button, params)
            buttonView = button
        }
    }

    fun hideButton() {
        val view = buttonView ?: return
        runCatching { windowManager?.removeView(view) }
        buttonView = null
    }

    fun hidePanel() {
        val view = panelView ?: return
        panelParams?.let { rememberPanelBounds(it) }
        destroyWebViews(view)
        runCatching { windowManager?.removeView(view) }
        panelView = null
        panelContentView = null
        panelParams = null
        currentCopyHtml = null
        currentCopyText = null
    }

    fun hideAll() {
        hidePanel()
        hideButton()
    }

    fun isButtonVisible(): Boolean = buttonView != null

    fun isPanelVisible(): Boolean = panelView != null

    private fun togglePanel(context: Context) {
        if (panelView != null) {
            hidePanel()
        } else {
            showPanel(context)
        }
    }

    private fun showPanel(context: Context) {
        val appContext = context.applicationContext
        if (!Settings.canDrawOverlays(appContext)) return
        if (panelView != null) return
        val wm = appContext.windowManager()
        windowManager = wm

        val metrics = appContext.resources.displayMetrics
        val defaultWidth = (metrics.widthPixels * 0.9f).toInt()
        val defaultHeight = (metrics.heightPixels * 0.6f).toInt()
        val minWidth = dp(appContext, 280)
        val minHeight = dp(appContext, 260)
        val width = clamp(
            if (lastPanelWidth > 0) lastPanelWidth else defaultWidth,
            minWidth,
            metrics.widthPixels
        )
        val height = clamp(
            if (lastPanelHeight > 0) lastPanelHeight else defaultHeight,
            minHeight,
            metrics.heightPixels
        )
        val x = if (lastPanelX != UNSET_POSITION) {
            clamp(lastPanelX, 0, metrics.widthPixels - width)
        } else {
            (metrics.widthPixels - width) / 2
        }
        val y = if (lastPanelY != UNSET_POSITION) {
            clamp(lastPanelY, 0, metrics.heightPixels - height)
        } else {
            ((metrics.heightPixels - height) * 0.35f).toInt()
        }

        val params = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        panelParams = params

        val container = FrameLayout(appContext)
        val root = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(appContext, 12),
                dp(appContext, 12),
                dp(appContext, 12),
                dp(appContext, 12)
            )
            background = roundedGradientDrawable(
                AppUi.HAIR_DEEP,
                Color.rgb(58, 36, 28),
                dp(appContext, 14).toFloat(),
                Color.argb(160, 169, 219, 234)
            )
            elevation = dp(appContext, 8).toFloat()
        }
        container.addView(
            root,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        container.addView(
            resizeHandle(appContext, wm, params, minWidth, minHeight),
            FrameLayout.LayoutParams(
                dp(appContext, 36),
                dp(appContext, 36),
                Gravity.BOTTOM or Gravity.END
            )
        )
        panelContentView = root
        renderLoading(appContext, root, "正在识别当前屏幕...")

        runCatching {
            wm.addView(container, params)
            panelView = container
            requestPanelScan(appContext, root, SystemClock.elapsedRealtime())
        }.onFailure {
            panelContentView = null
            panelParams = null
            Toast.makeText(appContext, "无法显示悬浮面板：${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestPanelScan(context: Context, root: LinearLayout, openedAt: Long) {
        val generation = ++panelScanGeneration
        var scanReturned = false
        root.postDelayed(
            {
                if (panelContentView !== root || generation != panelScanGeneration || scanReturned) {
                    return@postDelayed
                }
                val fallbackPosts = CurrentPostRepository.getPosts()
                XFuriganaPerf.d(
                    "panel scan timeout openToFallbackMs=${SystemClock.elapsedRealtime() - openedAt} " +
                        "fallbackPosts=${fallbackPosts.size}"
                )
                renderList(
                    context = context,
                    root = root,
                    posts = fallbackPosts,
                    statusMessage = "使用最近一次识别结果"
                )
            },
            PANEL_SCAN_FALLBACK_MS
        )

        val requested = XTextAccessibilityService.requestScanNow { posts, metrics ->
            scope.launch {
                if (panelContentView !== root || generation != panelScanGeneration) return@launch
                scanReturned = true
                val openToListMs = SystemClock.elapsedRealtime() - openedAt
                XFuriganaPerf.d(
                    "panel scan result openToListMs=$openToListMs posts=${posts.size} " +
                        "scanMs=${metrics.totalDurationMs} rawCache=${metrics.rawHashCacheHit} " +
                        "fallback=${metrics.usedRepositoryFallback}"
                )
                renderList(
                    context = context,
                    root = root,
                    posts = posts,
                    statusMessage = if (metrics.usedRepositoryFallback) {
                        "使用最近一次识别结果"
                    } else {
                        null
                    },
                    metrics = metrics
                )
            }
        }

        if (!requested) {
            scanReturned = true
            val fallbackPosts = CurrentPostRepository.getPosts()
            XFuriganaPerf.d(
                "panel scan unavailable openToFallbackMs=${SystemClock.elapsedRealtime() - openedAt} " +
                    "fallbackPosts=${fallbackPosts.size}"
            )
            renderList(
                context = context,
                root = root,
                posts = fallbackPosts,
                statusMessage = "无障碍服务未连接，使用最近一次识别结果"
            )
        }
    }

    private fun renderLoading(context: Context, root: LinearLayout, message: String) {
        currentCopyHtml = null
        currentCopyText = null
        root.removeAllViews()
        root.addView(headerRow(context, "当前屏幕日文 post") {
            smallButton(context, "关闭").apply { setOnClickListener { hidePanel() } }
        })
        root.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(ProgressBar(context))
                addView(emptyText(context, message))
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
    }

    private fun renderList(
        context: Context,
        root: LinearLayout,
        posts: List<DetectedPost> = CurrentPostRepository.getPosts(),
        statusMessage: String? = null,
        metrics: ScanMetrics? = null
    ) {
        currentCopyHtml = null
        currentCopyText = null
        root.removeAllViews()

        root.addView(headerRow(context, "当前屏幕日文 post") {
            smallButton(context, "关闭").apply { setOnClickListener { hidePanel() } }
        })

        val scrollView = ScrollView(context)
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(
            list,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        statusMessage?.let { message ->
            list.addView(emptyText(context, message))
        }
        val visiblePosts = posts.take(MAX_DISPLAYED_POSTS)
        if (visiblePosts.isEmpty()) {
            val message = buildString {
                append("当前屏幕没有识别到含汉字和假名的日文文本\n")
                append("请确认当前 App 包名在目标包名列表中\n")
                append("可以尝试滚动页面后重新点击")
                metrics?.failure?.let { append("\n").append(it) }
            }
            list.addView(emptyText(context, message))
        } else {
            visiblePosts.forEach { post ->
                list.addView(postRow(context, post) {
                    renderResult(context, root, post, SystemClock.elapsedRealtime())
                })
            }
        }

        root.addView(
            scrollView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
    }

    private fun renderResult(
        context: Context,
        root: LinearLayout,
        post: DetectedPost,
        clickedAt: Long
    ) {
        currentCopyHtml = null
        currentCopyText = null
        root.removeAllViews()

        val copyButton = smallButton(context, "复制").apply {
            isEnabled = false
            setOnClickListener { copyCurrentResult(context) }
        }
        val wordButton = smallButton(context, "加词").apply {
            isEnabled = false
        }
        root.addView(headerRow(context, "注音结果") {
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(smallButton(context, "返回列表").apply {
                    setOnClickListener { renderList(context, root) }
                })
                addView(copyButton)
                addView(wordButton)
                addView(smallButton(context, "关闭").apply {
                    setOnClickListener { hidePanel() }
                })
            }
        })

        val contentFrame = FrameLayout(context)
        val webView = WebView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = false
            settings.domStorageEnabled = false
            visibility = View.INVISIBLE
        }
        val progress = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(ProgressBar(context))
            addView(emptyText(context, "生成中..."))
        }
        contentFrame.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        contentFrame.addView(
            progress,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            contentFrame,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        if (!JapaneseTextDetector.isLikelyJapanesePost(post.text)) {
            showError(progress, "这条文本未通过日文 post 过滤，已取消请求。")
            return
        }

        val settingsRepository = SettingsRepository(context)
        val cache = FuriganaCache(context)
        val cached = cache.get(post.text, settingsRepository.model)
        if (cached != null) {
            val renderStart = SystemClock.elapsedRealtime()
            showHtml(webView, progress, cached.rubyHtml, post.text)
            XFuriganaPerf.d(
                "result cache_hit clickToRenderMs=${SystemClock.elapsedRealtime() - clickedAt} " +
                    "webViewLoadMs=${SystemClock.elapsedRealtime() - renderStart}"
            )
            val cachedAnnotations = FuriganaAnnotationCodec.decode(cached.annotationHintsJson)
                .ifEmpty { RubyAnnotationExtractor.fromRubyHtml(post.text, cached.rubyHtml) }
            NoteRepository(context).saveNote(
                originalText = post.text,
                rubyHtml = cached.rubyHtml,
                plainText = post.text,
                modelName = settingsRepository.model,
                annotationHintsJson = cached.annotationHintsJson
            )
            copyButton.isEnabled = true
            wordButton.isEnabled = true
            wordButton.setOnClickListener {
                openManualAddWord(context, post.text, cachedAnnotations)
            }
            return
        }

        scope.launch {
            val llmStart = SystemClock.elapsedRealtime()
            XFuriganaPerf.d("result clickToLlmStartMs=${llmStart - clickedAt} textLen=${post.text.length}")
            val requestKey = annotationRequestKey(settingsRepository, post)
            val deferred = annotationRequest(context, requestKey, post.text)
            val result = deferred.await()
            val llmDurationMs = SystemClock.elapsedRealtime() - llmStart
            XFuriganaPerf.d(
                "result llmDurationMs=$llmDurationMs success=${result.isSuccess} " +
                    "error=${result.exceptionOrNull()?.message}"
            )
            if (panelContentView !== root) return@launch

            result.fold(
                onSuccess = { annotations ->
                    val html = RubyHtmlRenderer.renderHtml(post.text, annotations)
                    val plain = RubyHtmlRenderer.renderPlainText(post.text, annotations)
                    val annotationHintsJson = FuriganaAnnotationCodec.encode(annotations)
                    cache.put(post.text, settingsRepository.model, html, annotationHintsJson)
                    NoteRepository(context).saveNote(
                        originalText = post.text,
                        rubyHtml = html,
                        plainText = plain,
                        modelName = settingsRepository.model,
                        annotationHintsJson = annotationHintsJson
                    )
                    val renderStart = SystemClock.elapsedRealtime()
                    showHtml(webView, progress, html, plain)
                    XFuriganaPerf.d(
                        "result webViewLoadMs=${SystemClock.elapsedRealtime() - renderStart} " +
                            "annotations=${annotations.size}"
                    )
                    copyButton.isEnabled = true
                    wordButton.isEnabled = true
                    wordButton.setOnClickListener {
                        openManualAddWord(context, post.text, annotations)
                    }
                },
                onFailure = { throwable ->
                    showError(progress, "生成失败：${throwable.message ?: throwable.javaClass.simpleName}")
                }
            )
        }
    }

    private fun openManualAddWord(
        context: Context,
        sourceText: String,
        annotations: List<FuriganaAnnotation>
    ) {
        val hints = org.json.JSONArray()
        annotations
            .distinctBy { "${it.start}:${it.end}:${it.surface}:${it.reading}" }
            .forEach { annotation ->
                hints.put(
                    org.json.JSONObject()
                        .put("s", annotation.surface)
                        .put("r", annotation.reading)
                        .put("b", annotation.start)
                        .put("e", annotation.end)
                )
            }
        val intent = Intent(context.applicationContext, AddWordActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(AddWordActivity.EXTRA_SOURCE_TEXT, sourceText)
            .putExtra(AddWordActivity.EXTRA_READING_HINTS, hints.toString())
        hidePanel()
        context.applicationContext.startActivity(intent)
    }

    private fun annotationRequest(
        context: Context,
        requestKey: String,
        text: String
    ): Deferred<Result<List<FuriganaAnnotation>>> {
        inFlightAnnotationRequests[requestKey]?.let { existing ->
            XFuriganaPerf.d("result reuse_in_flight_request key=$requestKey")
            return existing
        }

        val appContext = context.applicationContext
        return scope.async {
            FuriganaClient(appContext).requestAnnotations(text)
        }.also { deferred ->
            inFlightAnnotationRequests[requestKey] = deferred
            deferred.invokeOnCompletion {
                scope.launch {
                    if (inFlightAnnotationRequests[requestKey] === deferred) {
                        inFlightAnnotationRequests.remove(requestKey)
                    }
                }
            }
        }
    }

    private fun annotationRequestKey(
        settingsRepository: SettingsRepository,
        post: DetectedPost
    ): String {
        return TextHash.sha256Short(
            "${settingsRepository.apiBaseUrl}\n${settingsRepository.model}\n${post.text}"
        )
    }

    private fun showHtml(webView: WebView, progress: View, html: String, plainText: String) {
        currentCopyHtml = html
        currentCopyText = plainText
        progress.visibility = View.GONE
        webView.visibility = View.VISIBLE
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    private fun showError(progress: LinearLayout, message: String) {
        progress.removeAllViews()
        progress.addView(emptyText(progress.context, message))
        progress.visibility = View.VISIBLE
    }

    private fun copyCurrentResult(context: Context) {
        val html = currentCopyHtml
        val text = currentCopyText ?: html
        if (html.isNullOrBlank() || text.isNullOrBlank()) {
            Toast.makeText(context, "暂无可复制内容", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newHtmlText("furigana", text, html))
        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
    }

    private fun headerRow(context: Context, title: String, trailingFactory: () -> View): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(context, 8))

            addView(
                dragHandleBar(context),
                LinearLayout.LayoutParams(
                    dp(context, 72),
                    dp(context, 5)
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    setMargins(0, 0, 0, dp(context, 8))
                }
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        TouchTextView(context).apply {
                            text = title
                            textSize = 16f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(0xFFFFF5D3.toInt())
                            maxLines = 1
                            setOnTouchListener(panelMoveTouchListener(context))
                        },
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    )
                    addView(trailingFactory())
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun dragHandleBar(context: Context): View {
        return View(context).apply {
            background = roundedGradientDrawable(
                AppUi.HEADBAND,
                AppUi.UNIFORM_BLUE,
                dp(context, 3).toFloat(),
                null
            )
            setOnTouchListener(panelMoveTouchListener(context))
        }
    }

    private fun panelMoveTouchListener(context: Context): View.OnTouchListener {
        return PanelMoveTouchListener(
            context.windowManager(),
            requireNotNull(panelParams)
        )
    }

    private fun resizeHandle(
        context: Context,
        wm: WindowManager,
        params: WindowManager.LayoutParams,
        minWidth: Int,
        minHeight: Int
    ): TextView {
        return TouchTextView(context).apply {
            text = "↘"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(AppUi.HAIR_DEEP)
            background = roundedGradientDrawable(
                AppUi.HEADBAND,
                AppUi.UNIFORM_BLUE,
                dp(context, 8).toFloat(),
                Color.WHITE
            )
            setOnTouchListener(PanelResizeTouchListener(wm, params, minWidth, minHeight))
        }
    }

    private fun postRow(context: Context, post: DetectedPost, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = post.text.take(100)
            textSize = 15f
            setTextColor(AppUi.INK)
            setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
            background = roundedDrawable(
                Color.argb(238, 248, 251, 252),
                dp(context, 10).toFloat(),
                Color.argb(190, 169, 219, 234)
            )
            setOnClickListener { onClick() }
            val margin = dp(context, 6)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, margin)
            }
        }
    }

    private fun emptyText(context: Context, message: String): TextView {
        return TextView(context).apply {
            text = message
            textSize = 14f
            setTextColor(0xFFEAF8FC.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(context, 12), dp(context, 20), dp(context, 12), dp(context, 20))
        }
    }

    private fun smallButton(context: Context, label: String): Button {
        return Button(context).apply {
            text = label
            textSize = 12f
            minHeight = 0
            minWidth = 0
            minimumHeight = 0
            minimumWidth = 0
            setPadding(dp(context, 8), 0, dp(context, 8), 0)
            isAllCaps = false
            setTextColor(AppUi.HAIR)
            background = AppUi.headbandButton(context)
        }
    }

    private fun destroyWebViews(view: View) {
        if (view is WebView) {
            view.stopLoading()
            view.destroy()
            return
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                destroyWebViews(view.getChildAt(index))
            }
        }
    }

    private fun Context.windowManager(): WindowManager {
        return getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    private fun roundedDrawable(color: Int, radius: Float, strokeColor: Int? = null): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            strokeColor?.let { setStroke(1, it) }
        }
    }

    private fun ovalDrawable(color: Int, strokeColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(2, strokeColor)
        }
    }

    private fun ovalGradientDrawable(startColor: Int, endColor: Int, strokeColor: Int): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(startColor, endColor)
        ).apply {
            shape = GradientDrawable.OVAL
            setStroke(2, strokeColor)
        }
    }

    private fun roundedGradientDrawable(
        startColor: Int,
        endColor: Int,
        radius: Float,
        strokeColor: Int?
    ): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(startColor, endColor)
        ).apply {
            cornerRadius = radius
            strokeColor?.let { setStroke(1, it) }
        }
    }

    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }

    private fun rememberPanelBounds(params: WindowManager.LayoutParams) {
        lastPanelWidth = params.width
        lastPanelHeight = params.height
        lastPanelX = params.x
        lastPanelY = params.y
    }

    private fun clamp(value: Int, minValue: Int, maxValue: Int): Int {
        if (maxValue < minValue) return minValue
        return value.coerceIn(minValue, maxValue)
    }

    private class DraggableTouchListener(
        private val windowManager: WindowManager,
        private val params: WindowManager.LayoutParams,
        private val onPositionChanged: (Int, Int) -> Unit
    ) : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false
        private var touchSlop = 0

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (!moved &&
                        kotlin.math.abs(dx) <= touchSlop &&
                        kotlin.math.abs(dy) <= touchSlop
                    ) {
                        return true
                    }
                    moved = true
                    val metrics = view.context.resources.displayMetrics
                    val maxX = metrics.widthPixels - params.width
                    val maxY = metrics.heightPixels - params.height
                    val nextX = clamp(startX + dx, 0, maxX)
                    val nextY = clamp(startY + dy, 0, maxY)
                    if (nextX != params.x || nextY != params.y) {
                        params.x = nextX
                        params.y = nextY
                        onPositionChanged(params.x, params.y)
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) view.performClick()
                    onPositionChanged(params.x, params.y)
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    moved = false
                    return true
                }
            }
            return false
        }
    }

    private class PanelMoveTouchListener(
        private val windowManager: WindowManager,
        private val params: WindowManager.LayoutParams
    ) : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false
        private var touchSlop = 0

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (!moved &&
                        kotlin.math.abs(dx) <= touchSlop &&
                        kotlin.math.abs(dy) <= touchSlop
                    ) {
                        return true
                    }
                    moved = true
                    val metrics = view.context.resources.displayMetrics
                    params.x = clamp(startX + dx, 0, metrics.widthPixels - params.width)
                    params.y = clamp(startY + dy, 0, metrics.heightPixels - params.height)
                    updatePanelLayout(windowManager, params)
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) view.performClick()
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    moved = false
                    return true
                }
            }
            return false
        }
    }

    private class PanelResizeTouchListener(
        private val windowManager: WindowManager,
        private val params: WindowManager.LayoutParams,
        private val minWidth: Int,
        private val minHeight: Int
    ) : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startWidth = 0
        private var startHeight = 0
        private var moved = false
        private var touchSlop = 0

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startWidth = params.width
                    startHeight = params.height
                    moved = false
                    touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (!moved &&
                        kotlin.math.abs(dx) <= touchSlop &&
                        kotlin.math.abs(dy) <= touchSlop
                    ) {
                        return true
                    }
                    moved = true
                    val metrics = view.context.resources.displayMetrics
                    val maxWidth = kotlin.math.max(minWidth, metrics.widthPixels - params.x)
                    val maxHeight = kotlin.math.max(minHeight, metrics.heightPixels - params.y)
                    params.width = clamp(startWidth + dx, minWidth, maxWidth)
                    params.height = clamp(startHeight + dy, minHeight, maxHeight)
                    updatePanelLayout(windowManager, params)
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) view.performClick()
                    moved = false
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    moved = false
                    return true
                }
            }
            return false
        }
    }

    private fun updatePanelLayout(
        windowManager: WindowManager,
        params: WindowManager.LayoutParams
    ) {
        panelView?.let { view ->
            rememberPanelBounds(params)
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    private class OverlayButtonView(context: Context) : ImageView(context) {
        override fun performClick(): Boolean {
            return super.performClick()
        }
    }

    private open class TouchTextView(context: Context) : TextView(context) {
        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    private const val UNSET_POSITION = Int.MIN_VALUE
    private const val PANEL_SCAN_FALLBACK_MS = 1500L
    private const val MAX_DISPLAYED_POSTS = 20
}
