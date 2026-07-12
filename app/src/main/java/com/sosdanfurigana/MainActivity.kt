package com.sosdanfurigana

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.sosdanfurigana.data.NoteRepository
import com.sosdanfurigana.data.WordbookRepository
import com.sosdanfurigana.overlay.OverlayController
import kotlin.random.Random

class MainActivity : Activity() {
    private lateinit var noteRepository: NoteRepository
    private lateinit var wordbookRepository: WordbookRepository
    private lateinit var accessibilityStatus: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var missionStatus: TextView
    private lateinit var dueCount: TextView
    private lateinit var noteCount: TextView
    private lateinit var wordCount: TextView
    private lateinit var reviewButton: Button
    private lateinit var haruhiImage: ImageView
    private val haruhiTapTimestamps = ArrayDeque<Long>()
    private var currentHaruhiGifIndex = 0
    private var lastHaruhiScoldAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = layoutInflater.inflate(R.layout.activity_main, null, false)
        setContentView(AppBottomNavigation.wrap(this, content, BottomDestination.HOME))

        noteRepository = NoteRepository(applicationContext)
        wordbookRepository = WordbookRepository(applicationContext)
        bindViews(content)
        startHeroGifIfSupported()
        bindActions(content)
    }

    override fun onResume() {
        super.onResume()
        updateDashboard()
    }

    private fun bindViews(content: android.view.View) {
        accessibilityStatus = content.findViewById(R.id.text_accessibility_status)
        overlayStatus = content.findViewById(R.id.text_overlay_status)
        missionStatus = content.findViewById(R.id.text_mission_status)
        dueCount = content.findViewById(R.id.text_due_count)
        noteCount = content.findViewById(R.id.text_note_count)
        wordCount = content.findViewById(R.id.text_word_count)
        reviewButton = content.findViewById(R.id.button_start_review)
        haruhiImage = content.findViewById(R.id.image_haruhi)
    }

    private fun bindActions(content: android.view.View) {
        bindHaruhiInteraction()

        content.findViewById<Button>(R.id.button_show_overlay).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "先把悬浮窗权限交出来，团长才能出动。", Toast.LENGTH_SHORT).show()
                openOverlaySettings()
                return@setOnClickListener
            }
            OverlayController.showButton(applicationContext)
            Toast.makeText(this, "SOS 已就位。现在去 X 找日文！", Toast.LENGTH_SHORT).show()
        }

        content.findViewById<Button>(R.id.button_accessibility_settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        content.findViewById<Button>(R.id.button_overlay_settings).setOnClickListener {
            openOverlaySettings()
        }
        content.findViewById<Button>(R.id.button_open_settings).setOnClickListener {
            AppMotion.startContainer(this, it, Intent(this, SettingsActivity::class.java))
        }
        content.findViewById<android.view.View>(R.id.action_due).setOnClickListener {
            AppMotion.startContainer(this, it, Intent(this, ReviewActivity::class.java))
        }
        content.findViewById<android.view.View>(R.id.action_notes).setOnClickListener {
            AppMotion.startContainer(this, it, Intent(this, NotesActivity::class.java))
        }
        content.findViewById<android.view.View>(R.id.action_words).setOnClickListener {
            AppMotion.startContainer(this, it, Intent(this, WordbookActivity::class.java))
        }
        reviewButton.setOnClickListener {
            AppMotion.startContainer(this, it, Intent(this, ReviewActivity::class.java))
        }
    }

    private fun updateDashboard() {
        val accessibilityEnabled = isAccessibilityServiceEnabled()
        val overlayEnabled = Settings.canDrawOverlays(this)
        applyStatusChip(accessibilityStatus, accessibilityEnabled)
        applyStatusChip(overlayStatus, overlayEnabled)

        missionStatus.text = when {
            accessibilityEnabled && overlayEnabled -> "装备齐全。打开 X，召唤 SOS 开始搜查。"
            !accessibilityEnabled && !overlayEnabled -> "还缺两项权限。先完成整备，不许偷懒。"
            else -> "只差一项权限，马上就能出动。"
        }

        val words = wordbookRepository.getWords()
        val now = System.currentTimeMillis()
        val due = words.count { it.dueAt <= now }
        dueCount.text = due.toString()
        noteCount.text = noteRepository.getNotes().size.toString()
        wordCount.text = words.size.toString()
        reviewButton.text = if (due > 0) "开始读音填空 · $due 题" else "今日复习已完成"
        reviewButton.isEnabled = due > 0
    }

    private fun applyStatusChip(chip: TextView, enabled: Boolean) {
        chip.text = if (enabled) "已开启" else "待开启"
        chip.setBackgroundResource(
            if (enabled) R.drawable.bg_status_chip_on else R.drawable.bg_status_chip_off
        )
        chip.setTextColor(getColor(if (enabled) R.color.haruhi_hair else R.color.haruhi_danger))
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
        )
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = manager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        val expectedId = "$packageName/.accessibility.XTextAccessibilityService"
        return enabledServices.any { serviceInfo ->
            serviceInfo.id == expectedId || serviceInfo.resolveInfo.serviceInfo.name ==
                "com.sosdanfurigana.accessibility.XTextAccessibilityService"
        }
    }

    private fun startHeroGifIfSupported() {
        playHaruhiGif(HARUHI_GIFS[currentHaruhiGifIndex])
    }

    private fun playHaruhiGif(drawableRes: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            haruhiImage.setImageResource(drawableRes)
            return
        }
        runCatching {
            val source = ImageDecoder.createSource(resources, drawableRes)
            val drawable = ImageDecoder.decodeDrawable(source)
            haruhiImage.setImageDrawable(drawable)
            (drawable as? AnimatedImageDrawable)?.start()
        }.onFailure {
            haruhiImage.setImageResource(drawableRes)
        }
    }

    private fun bindHaruhiInteraction() {
        haruhiImage.setOnClickListener {
            recordHaruhiTap()
            currentHaruhiGifIndex = nextHaruhiGifIndex()
            playHaruhiGif(HARUHI_GIFS[currentHaruhiGifIndex])
            maybeShowHaruhiScoldDialog()
        }
    }

    private fun recordHaruhiTap() {
        val now = System.currentTimeMillis()
        haruhiTapTimestamps.addLast(now)
        while (haruhiTapTimestamps.isNotEmpty() &&
            now - haruhiTapTimestamps.first() > HARUHI_TAP_WINDOW_MS
        ) {
            haruhiTapTimestamps.removeFirst()
        }
    }

    private fun nextHaruhiGifIndex(): Int {
        if (HARUHI_GIFS.size <= 1) return 0
        var next = Random.nextInt(HARUHI_GIFS.size)
        if (next == currentHaruhiGifIndex) next = (next + 1) % HARUHI_GIFS.size
        return next
    }

    private fun maybeShowHaruhiScoldDialog() {
        val now = System.currentTimeMillis()
        if (haruhiTapTimestamps.size < HARUHI_SCOLD_TAP_COUNT) return
        if (now - lastHaruhiScoldAt < HARUHI_SCOLD_COOLDOWN_MS) return
        lastHaruhiScoldAt = now
        haruhiTapTimestamps.clear()
        AlertDialog.Builder(this)
            .setTitle("团长警告")
            .setMessage(HARUHI_SCOLD_LINES.random())
            .setPositiveButton("知道了，团长") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    companion object {
        private const val HARUHI_TAP_WINDOW_MS = 6_000L
        private const val HARUHI_SCOLD_TAP_COUNT = 4
        private const val HARUHI_SCOLD_COOLDOWN_MS = 7_000L
        private val HARUHI_GIFS = intArrayOf(
            R.drawable.haruhi_start,
            R.drawable.haruhi_hmph,
            R.drawable.haruhi_angry,
            R.drawable.haruhi_confused,
            R.drawable.haruhi_clap,
            R.drawable.haruhi_poke
        )
        private val HARUHI_SCOLD_LINES = listOf(
            "阿虚，你戳我干嘛？作战还没完成呢。",
            "团长不是按钮！SOS 才是。",
            "再戳也不会凭空多记住一个单词。"
        )
    }
}
