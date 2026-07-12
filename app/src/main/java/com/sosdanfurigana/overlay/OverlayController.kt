package com.sosdanfurigana.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.sosdanfurigana.AddWordActivity
import com.sosdanfurigana.AppUi
import com.sosdanfurigana.R
import com.sosdanfurigana.accessibility.ScanMetrics
import com.sosdanfurigana.accessibility.XFuriganaPerf
import com.sosdanfurigana.accessibility.XTextAccessibilityService
import com.sosdanfurigana.data.CurrentPostRepository
import com.sosdanfurigana.data.DetectedPost
import com.sosdanfurigana.data.FuriganaCache
import com.sosdanfurigana.data.GrammarAnalysisFetcher
import com.sosdanfurigana.data.NoteRepository
import com.sosdanfurigana.data.SettingsRepository
import com.sosdanfurigana.furigana.FuriganaClient
import com.sosdanfurigana.furigana.FuriganaAnnotation
import com.sosdanfurigana.furigana.FuriganaAnnotationCodec
import com.sosdanfurigana.furigana.RubyAnnotationExtractor
import com.sosdanfurigana.furigana.RubyHtmlRenderer
import com.sosdanfurigana.japanese.JapaneseTextDetector
import com.sosdanfurigana.util.TextHash
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
            contentDescription = "SOS 日文注音按钮"
            setPadding(
                dp(appContext, 7),
                dp(appContext, 7),
                dp(appContext, 7),
                dp(appContext, 7)
            )
            background = ovalDrawable(Color.WHITE)
            clipToOutline = true
            elevation = dp(appContext, 5).toFloat()
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
        val view = panelView ?: run {
            buttonView?.visibility = View.VISIBLE
            return
        }
        if (animationsEnabled() && view.visibility == View.VISIBLE) {
            animatePanelClosed(view)
            return
        }
        removePanel(view)
    }

    private fun removePanel(view: View) {
        val params = panelParams
        panelView = null
        panelContentView = null
        panelParams = null
        params?.let { rememberPanelBounds(it) }
        destroyWebViews(view)
        runCatching { windowManager?.removeView(view) }
        currentCopyHtml = null
        currentCopyText = null
        buttonView?.visibility = View.VISIBLE
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
        val defaultWidth = (metrics.widthPixels * 0.84f).toInt()
        val defaultHeight = (metrics.heightPixels * 0.4f).toInt()
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
            metrics.widthPixels - width - dp(appContext, 12)
        }
        val y = if (lastPanelY != UNSET_POSITION) {
            clamp(lastPanelY, 0, metrics.heightPixels - height)
        } else {
            ((metrics.heightPixels - height) * 0.22f).toInt()
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
                dp(appContext, 14),
                dp(appContext, 8),
                dp(appContext, 14),
                dp(appContext, 14)
            )
            background = roundedDrawable(
                AppUi.HAIR_DEEP,
                dp(appContext, 16).toFloat()
            )
            elevation = dp(appContext, 6).toFloat()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                accessibilityPaneTitle = "SOS 注音面板"
            }
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
                dp(appContext, 48),
                dp(appContext, 48),
                Gravity.BOTTOM or Gravity.END
            )
        )
        panelContentView = root
        renderLoading(appContext, root, "正在识别当前屏幕...")

        runCatching {
            wm.addView(container, params)
            panelView = container
            if (animationsEnabled()) {
                animatePanelOpened(container, params)
            } else {
                buttonView?.visibility = View.GONE
            }
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
        root.addView(headerRow(context, "屏幕日文") {
            smallButton(context, "×", ButtonStyle.ICON).apply {
                contentDescription = "关闭"
                setOnClickListener { hidePanel() }
            }
        })
        root.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(ProgressBar(context).apply {
                    indeterminateTintList = android.content.res.ColorStateList.valueOf(AppUi.HEADBAND)
                })
                addView(emptyText(context, message))
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        animateContentChange(root)
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

        root.addView(headerRow(context, "屏幕日文") {
            smallButton(context, "×", ButtonStyle.ICON).apply {
                contentDescription = "关闭"
                setOnClickListener { hidePanel() }
            }
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
            list.addView(statusNotice(context, message))
        }
        val visiblePosts = posts.take(MAX_DISPLAYED_POSTS)
        if (visiblePosts.isEmpty()) {
            val message = buildString {
                append("当前屏幕没有识别到含汉字和假名的日文文本\n")
                append("请确认当前 App 包名在目标 App 包名列表中\n")
                append("可以尝试滚动页面后重新点击")
                metrics?.failure?.let { append("\n").append(it) }
            }
            list.addView(statusText(context, "还没发现日文", message))
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
        animateContentChange(root)
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

        val copyButton = smallButton(context, "复制", ButtonStyle.GHOST).apply {
            isEnabled = false
            alpha = DISABLED_ALPHA
            setOnClickListener { copyCurrentResult(context) }
        }
        val wordButton = smallButton(context, "加词", ButtonStyle.PRIMARY).apply {
            isEnabled = false
            alpha = DISABLED_ALPHA
        }
        root.addView(headerRow(context, "注音结果") {
            smallButton(context, "×", ButtonStyle.ICON).apply {
                contentDescription = "关闭"
                setOnClickListener { hidePanel() }
            }
        })
        root.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(context, 8))
                addView(
                    smallButton(context, "返回", ButtonStyle.GHOST).apply {
                        setOnClickListener { renderList(context, root) }
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        dp(context, 44)
                    )
                )
                addView(
                    View(context),
                    LinearLayout.LayoutParams(0, 1, 1f)
                )
                addView(copyButton)
                addView(wordButton)
            }
        )

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
            addView(ProgressBar(context).apply {
                indeterminateTintList = android.content.res.ColorStateList.valueOf(AppUi.HEADBAND)
            })
            addView(emptyText(context, "正在生成注音..."))
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
        animateContentChange(root)

        if (!JapaneseTextDetector.isLikelyJapanesePost(post.text)) {
            showError(progress, "这条文本未通过日文内容筛选，已取消请求。")
            return
        }

        val settingsRepository = SettingsRepository(context)
        val cache = FuriganaCache(context)
        val cached = cache.get(post.text, settingsRepository.model)
        if (cached != null) {
            val cachedAnnotations = FuriganaAnnotationCodec.decode(cached.annotationHintsJson)
                .ifEmpty { RubyAnnotationExtractor.fromRubyHtml(post.text, cached.rubyHtml) }
            val cachedPlain = cached.plainText.takeIf { it.isNotBlank() }
                ?: RubyHtmlRenderer.renderPlainText(post.text, cachedAnnotations)
                    .takeIf { cachedAnnotations.isNotEmpty() }
                ?: post.text
            val renderStart = SystemClock.elapsedRealtime()
            showHtml(webView, progress, cached.rubyHtml, cachedPlain)
            XFuriganaPerf.d(
                "result cache_hit clickToRenderMs=${SystemClock.elapsedRealtime() - clickedAt} " +
                    "webViewLoadMs=${SystemClock.elapsedRealtime() - renderStart}"
            )
            val cachedNoteId = NoteRepository(context).saveNote(
                originalText = post.text,
                rubyHtml = cached.rubyHtml,
                plainText = cachedPlain,
                modelName = settingsRepository.model,
                annotationHintsJson = cached.annotationHintsJson
            )
            GrammarAnalysisFetcher.autoAnalyze(context, cachedNoteId)
            copyButton.isEnabled = true
            copyButton.alpha = 1f
            wordButton.isEnabled = true
            wordButton.alpha = 1f
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
                    cache.put(post.text, settingsRepository.model, html, plain, annotationHintsJson)
                    val savedNoteId = NoteRepository(context).saveNote(
                        originalText = post.text,
                        rubyHtml = html,
                        plainText = plain,
                        modelName = settingsRepository.model,
                        annotationHintsJson = annotationHintsJson
                    )
                    GrammarAnalysisFetcher.autoAnalyze(context, savedNoteId)
                    val renderStart = SystemClock.elapsedRealtime()
                    showHtml(webView, progress, html, plain)
                    XFuriganaPerf.d(
                        "result webViewLoadMs=${SystemClock.elapsedRealtime() - renderStart} " +
                            "annotations=${annotations.size}"
                    )
                    copyButton.isEnabled = true
                    copyButton.alpha = 1f
                    wordButton.isEnabled = true
                    wordButton.alpha = 1f
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
            setPadding(0, 0, 0, dp(context, 10))

            addView(
                dragHandle(context),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(context, 28)
                ).apply {
                    setMargins(0, 0, 0, dp(context, 2))
                }
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        TouchTextView(context).apply {
                            text = title
                            textSize = 17f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(AppUi.WARM_WHITE)
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

    private fun dragHandle(context: Context): FrameLayout {
        return FrameLayout(context).apply {
            contentDescription = "拖动面板"
            setOnTouchListener(panelMoveTouchListener(context))
            addView(
                View(context).apply {
                    background = roundedDrawable(
                        Color.argb(120, 246, 235, 224),
                        dp(context, 2).toFloat()
                    )
                },
                FrameLayout.LayoutParams(dp(context, 42), dp(context, 4), Gravity.CENTER)
            )
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
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.argb(180, 246, 235, 224))
            contentDescription = "调整面板大小"
            background = roundedDrawable(
                Color.argb(28, 255, 255, 255),
                dp(context, 12).toFloat()
            )
            setOnTouchListener(PanelResizeTouchListener(wm, params, minWidth, minHeight))
        }
    }

    private fun postRow(context: Context, post: DetectedPost, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = post.text
            textSize = 15f
            setTextColor(AppUi.INK)
            setLineSpacing(dp(context, 3).toFloat(), 1f)
            setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            background = AppUi.ripple(
                AppUi.rounded(
                    context,
                    AppUi.SURFACE,
                    12
                ),
                0x33E59B2D
            )
            setOnClickListener { onClick() }
            val margin = dp(context, 8)
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
            setTextColor(AppUi.WARM_WHITE)
            setLineSpacing(dp(context, 4).toFloat(), 1f)
            gravity = Gravity.CENTER
            setPadding(dp(context, 12), dp(context, 20), dp(context, 12), dp(context, 20))
        }
    }

    private fun statusText(context: Context, title: String, message: String): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 16))
            addView(TextView(context).apply {
                text = title
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(AppUi.HEADBAND)
                gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                text = message
                textSize = 14f
                setTextColor(AppUi.WARM_WHITE)
                setLineSpacing(dp(context, 4).toFloat(), 1f)
                gravity = Gravity.CENTER
                setPadding(0, dp(context, 6), 0, 0)
            })
        }
    }

    private fun statusNotice(context: Context, message: String): TextView {
        return TextView(context).apply {
            text = message
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(AppUi.HEADBAND)
            gravity = Gravity.CENTER
            setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
            background = roundedDrawable(
                Color.argb(24, 255, 212, 77),
                dp(context, 10).toFloat()
            )
        }
    }

    private fun smallButton(
        context: Context,
        label: String,
        style: ButtonStyle = ButtonStyle.GHOST
    ): Button {
        return Button(context).apply {
            text = label
            textSize = 13f
            minHeight = 0
            minWidth = 0
            minimumHeight = 0
            minimumWidth = 0
            stateListAnimator = null
            setPadding(dp(context, 12), 0, dp(context, 12), 0)
            isAllCaps = false
            when (style) {
                ButtonStyle.GHOST -> {
                    setTextColor(AppUi.WARM_WHITE)
                    background = AppUi.ripple(
                        AppUi.rounded(context, Color.argb(28, 255, 255, 255), 10),
                        0x33FFFFFF
                    )
                }

                ButtonStyle.PRIMARY -> {
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(AppUi.HAIR_DEEP)
                    background = AppUi.ripple(
                        AppUi.rounded(context, AppUi.HEADBAND, 10),
                        0x33241611
                    )
                }
                ButtonStyle.ICON -> {
                    textSize = 22f
                    setPadding(0, 0, 0, dp(context, 2))
                    setTextColor(AppUi.WARM_WHITE)
                    background = AppUi.ripple(
                        AppUi.rounded(context, Color.argb(28, 255, 255, 255), 999),
                        0x33FFFFFF
                    )
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                if (style == ButtonStyle.ICON) {
                    dp(context, 44)
                } else {
                    LinearLayout.LayoutParams.WRAP_CONTENT
                },
                dp(context, 44)
            ).apply {
                marginStart = dp(context, 6)
            }
        }
    }

    private fun animatePanelOpened(view: View, params: WindowManager.LayoutParams) {
        val buttonX = lastButtonX.takeIf { it != UNSET_POSITION } ?: params.x
        val buttonY = lastButtonY.takeIf { it != UNSET_POSITION } ?: params.y
        val pivotX = (buttonX - params.x + dp(view.context, 28)).toFloat()
            .coerceIn(0f, params.width.toFloat())
        val pivotY = (buttonY - params.y + dp(view.context, 28)).toFloat()
            .coerceIn(0f, params.height.toFloat())
        view.pivotX = pivotX
        view.pivotY = pivotY
        view.scaleX = 0.18f
        view.scaleY = 0.18f
        view.alpha = 0f
        buttonView?.animate()
            ?.alpha(0f)
            ?.scaleX(0.82f)
            ?.scaleY(0.82f)
            ?.setDuration(120L)
            ?.start()
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(260L)
            .setInterpolator(MOTION_EASING)
            .withEndAction { buttonView?.visibility = View.GONE }
            .start()
    }

    private fun animatePanelClosed(view: View) {
        buttonView?.apply {
            visibility = View.VISIBLE
            alpha = 0f
            scaleX = 0.82f
            scaleY = 0.82f
        }
        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .scaleX(0.18f)
            .scaleY(0.18f)
            .setDuration(190L)
            .setInterpolator(MOTION_EASING)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.animate().setListener(null)
                    removePanel(view)
                    buttonView?.apply {
                        animate()
                            .alpha(1f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(150L)
                            .setInterpolator(MOTION_EASING)
                            .start()
                    }
                }
            })
            .start()
    }

    private fun animateContentChange(view: View) {
        if (!animationsEnabled()) return
        view.animate().cancel()
        view.alpha = 0.65f
        view.translationX = dp(view.context, 18).toFloat()
        view.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(190L)
            .setInterpolator(MOTION_EASING)
            .start()
    }

    private fun animationsEnabled(): Boolean = ValueAnimator.areAnimatorsEnabled()

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

    private fun ovalDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
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

    private enum class ButtonStyle {
        GHOST,
        PRIMARY,
        ICON
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
    private const val DISABLED_ALPHA = 0.48f
    private val MOTION_EASING = PathInterpolator(0.2f, 0f, 0f, 1f)
}
