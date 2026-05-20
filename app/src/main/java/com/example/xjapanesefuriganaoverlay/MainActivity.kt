package com.example.xjapanesefuriganaoverlay

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.example.xjapanesefuriganaoverlay.data.ApiProfile
import com.example.xjapanesefuriganaoverlay.data.SettingsRepository
import com.example.xjapanesefuriganaoverlay.overlay.OverlayController

class MainActivity : Activity() {
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var accessibilityStatus: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var apiProfileSpinner: Spinner
    private lateinit var apiProfileName: EditText
    private lateinit var apiBaseUrl: EditText
    private lateinit var apiKey: EditText
    private lateinit var model: EditText
    private lateinit var targetPackages: EditText
    private lateinit var enabledSwitch: Switch
    private lateinit var deleteApiProfileButton: Button
    private var apiProfiles: List<ApiProfile> = emptyList()
    private var suppressProfileSelection = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settingsRepository = SettingsRepository(applicationContext)
        bindViews()
        loadSettingsIntoUi()
        bindActions()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun bindViews() {
        accessibilityStatus = findViewById(R.id.text_accessibility_status)
        overlayStatus = findViewById(R.id.text_overlay_status)
        apiProfileSpinner = findViewById(R.id.spinner_api_profiles)
        apiProfileName = findViewById(R.id.edit_api_profile_name)
        apiBaseUrl = findViewById(R.id.edit_api_base_url)
        apiKey = findViewById(R.id.edit_api_key)
        model = findViewById(R.id.edit_model)
        targetPackages = findViewById(R.id.edit_target_packages)
        enabledSwitch = findViewById(R.id.switch_enabled)
        deleteApiProfileButton = findViewById(R.id.button_delete_api_profile)
    }

    private fun loadSettingsIntoUi() {
        refreshApiProfileSpinner(settingsRepository.selectedApiProfileId())
        loadSelectedApiProfileIntoUi()
        targetPackages.setText(settingsRepository.targetPackages.joinToString("\n"))
        enabledSwitch.isChecked = settingsRepository.enabled
    }

    private fun bindActions() {
        findViewById<Button>(R.id.button_accessibility_settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.button_overlay_settings).setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        findViewById<Button>(R.id.button_save_settings).setOnClickListener {
            saveSettings()
            refreshApiProfileSpinner(settingsRepository.selectedApiProfileId())
            Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
        }

        apiProfileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                if (suppressProfileSelection) return
                val selected = apiProfiles.getOrNull(position) ?: return
                if (selected.id == settingsRepository.selectedApiProfileId()) return

                saveCurrentApiProfile()
                settingsRepository.selectApiProfile(selected.id)
                loadSelectedApiProfileIntoUi()
                refreshApiProfileSpinner(selected.id)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        findViewById<Button>(R.id.button_add_api_profile).setOnClickListener {
            saveSettings()
            val newProfile = settingsRepository.newApiProfile()
            settingsRepository.saveApiProfile(newProfile)
            refreshApiProfileSpinner(newProfile.id)
            loadSelectedApiProfileIntoUi()
            Toast.makeText(this, "已新增 API 配置", Toast.LENGTH_SHORT).show()
        }

        deleteApiProfileButton.setOnClickListener {
            val current = settingsRepository.selectedApiProfile()
            if (!settingsRepository.deleteApiProfile(current.id)) {
                Toast.makeText(this, "至少保留一个 API 配置", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            refreshApiProfileSpinner(settingsRepository.selectedApiProfileId())
            loadSelectedApiProfileIntoUi()
            Toast.makeText(this, "已删除 API 配置", Toast.LENGTH_SHORT).show()
        }

        enabledSwitch.setOnCheckedChangeListener { _, checked ->
            settingsRepository.enabled = checked
            if (!checked) {
                OverlayController.hideAll()
            } else if (Settings.canDrawOverlays(this)) {
                OverlayController.showButton(applicationContext)
            }
        }

        findViewById<Button>(R.id.button_show_overlay).setOnClickListener {
            saveSettings()
            if (!settingsRepository.enabled) {
                Toast.makeText(this, "请先启用服务开关", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先开启悬浮窗权限", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            OverlayController.showButton(applicationContext)
        }

        findViewById<Button>(R.id.button_open_notes).setOnClickListener {
            startActivity(Intent(this, NotesActivity::class.java))
        }

        findViewById<Button>(R.id.button_open_wordbook).setOnClickListener {
            startActivity(Intent(this, WordbookActivity::class.java))
        }
    }

    private fun saveSettings() {
        saveCurrentApiProfile()
        settingsRepository.targetPackages = targetPackages.text.toString()
            .split('\n', ',', ';', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun saveCurrentApiProfile() {
        val current = settingsRepository.selectedApiProfile()
        settingsRepository.saveApiProfile(
            current.copy(
                name = apiProfileName.text.toString(),
                apiBaseUrl = apiBaseUrl.text.toString(),
                apiKey = apiKey.text.toString(),
                model = model.text.toString()
            )
        )
    }

    private fun loadSelectedApiProfileIntoUi() {
        val selected = settingsRepository.selectedApiProfile()
        apiProfileName.setText(selected.name)
        apiBaseUrl.setText(selected.apiBaseUrl)
        apiKey.setText(selected.apiKey)
        model.setText(selected.model)
        deleteApiProfileButton.isEnabled = settingsRepository.apiProfiles().size > 1
    }

    private fun refreshApiProfileSpinner(selectedId: String) {
        apiProfiles = settingsRepository.apiProfiles()
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            apiProfiles.map { it.name }
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        val selectedIndex = apiProfiles.indexOfFirst { it.id == selectedId }
            .takeIf { it >= 0 }
            ?: 0
        suppressProfileSelection = true
        apiProfileSpinner.adapter = adapter
        apiProfileSpinner.setSelection(selectedIndex, false)
        suppressProfileSelection = false
        deleteApiProfileButton.isEnabled = apiProfiles.size > 1
    }

    private fun updatePermissionStatus() {
        val accessibilityEnabled = isAccessibilityServiceEnabled()
        val overlayEnabled = Settings.canDrawOverlays(this)
        accessibilityStatus.text = "无障碍服务：${if (accessibilityEnabled) "已开启" else "未开启"}"
        overlayStatus.text = "悬浮窗权限：${if (overlayEnabled) "已开启" else "未开启"}"
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = manager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        val expectedId = "$packageName/.accessibility.XTextAccessibilityService"
        return enabledServices.any { serviceInfo ->
            serviceInfo.id == expectedId || serviceInfo.resolveInfo.serviceInfo.name ==
                "com.example.xjapanesefuriganaoverlay.accessibility.XTextAccessibilityService"
        }
    }
}
