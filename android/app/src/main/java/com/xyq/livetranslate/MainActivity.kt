package com.xyq.livetranslate

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.MaterialAutoCompleteTextView

class MainActivity : AppCompatActivity() {

    private lateinit var etApiKeys: EditText
    private lateinit var etBaseUrl: EditText
    private lateinit var acPreset: MaterialAutoCompleteTextView
    private lateinit var etPresetName: EditText
    private lateinit var etPrompt: EditText
    private lateinit var btnNewPreset: Button
    private lateinit var btnDeletePreset: Button
    private lateinit var btnSavePreset: Button
    private lateinit var slFont: Slider
    private lateinit var slOpacity: Slider
    private lateinit var slLines: Slider
    private lateinit var tvFontVal: TextView
    private lateinit var tvOpacityVal: TextView
    private lateinit var tvLinesVal: TextView
    private lateinit var btnBattery: Button
    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView

    private var presetNames: List<String> = emptyList()
    private var permRequested = false

    private val ui = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            renderStatus()
            ui.postDelayed(this, 1000)
        }
    }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            onStartClicked()
        }

    private val projLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK && res.data != null) {
                val i = Intent(this, CaptureService::class.java)
                    .setAction(CaptureService.ACTION_START)
                    .putExtra(CaptureService.EXTRA_RESULT_CODE, res.resultCode)
                    .putExtra(CaptureService.EXTRA_RESULT_DATA, res.data)
                startForegroundService(i)
            } else {
                toast("未获得屏幕捕获授权，无法内录")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etApiKeys = findViewById(R.id.etApiKeys)
        etBaseUrl = findViewById(R.id.etBaseUrl)
        acPreset = findViewById(R.id.acPreset)
        etPresetName = findViewById(R.id.etPresetName)
        etPrompt = findViewById(R.id.etPrompt)
        btnNewPreset = findViewById(R.id.btnNewPreset)
        btnDeletePreset = findViewById(R.id.btnDeletePreset)
        btnSavePreset = findViewById(R.id.btnSavePreset)
        slFont = findViewById(R.id.slFont)
        slOpacity = findViewById(R.id.slOpacity)
        slLines = findViewById(R.id.slLines)
        tvFontVal = findViewById(R.id.tvFontVal)
        tvOpacityVal = findViewById(R.id.tvOpacityVal)
        tvLinesVal = findViewById(R.id.tvLinesVal)
        btnBattery = findViewById(R.id.btnBattery)
        btnToggle = findViewById(R.id.btnToggle)
        tvStatus = findViewById(R.id.tvStatus)

        etApiKeys.setText(SettingsStore.apiKeysRaw(this))
        etBaseUrl.setText(SettingsStore.baseUrl(this))
        reloadPresets(SettingsStore.selectedPreset(this))

        acPreset.setOnItemClickListener { _, _, pos, _ ->
            val name = presetNames.getOrNull(pos) ?: return@setOnItemClickListener
            SettingsStore.setSelectedPreset(this, name)
            etPresetName.setText(name)
            etPrompt.setText(SettingsStore.presetText(this, name))
        }

        btnNewPreset.setOnClickListener {
            etPresetName.setText("")
            etPrompt.setText(SettingsStore.DEFAULT_PROMPT)
            etPresetName.requestFocus()
            toast("填好预设名和提示词后，点“保存”")
        }

        btnSavePreset.setOnClickListener {
            val name = etPresetName.text.toString().trim()
            if (name.isEmpty()) {
                toast("预设名不能为空")
                return@setOnClickListener
            }
            SettingsStore.savePreset(this, name, etPrompt.text.toString())
            SettingsStore.setSelectedPreset(this, name)
            reloadPresets(name)
            toast("已保存预设：$name")
        }

        btnDeletePreset.setOnClickListener {
            val name = acPreset.text.toString()
            if (!SettingsStore.deletePreset(this, name)) {
                toast("至少保留一个预设")
                return@setOnClickListener
            }
            reloadPresets(SettingsStore.selectedPreset(this))
            toast("已删除：$name")
        }

        setupStyleSliders()

        btnBattery.setOnClickListener { requestBatteryWhitelist() }

        btnToggle.setOnClickListener {
            if (StatusBus.serviceRunning) {
                startService(
                    Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP)
                )
            } else {
                onStartClicked()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(refresh)
    }

    // ---------- 预设 ----------

    private fun reloadPresets(selectName: String) {
        presetNames = SettingsStore.presetNames(this)
        acPreset.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, presetNames)
        )
        val name = if (selectName in presetNames) selectName else presetNames.first()
        acPreset.setText(name, false)
        etPresetName.setText(name)
        etPrompt.setText(SettingsStore.presetText(this, name))
    }

    // ---------- 样式 ----------

    private fun setupStyleSliders() {
        slFont.value = SettingsStore.fontSizeSp(this).toFloat().coerceIn(12f, 26f)
        slOpacity.value = (SettingsStore.bgOpacityPct(this) / 5 * 5).toFloat().coerceIn(20f, 95f)
        slLines.value = SettingsStore.overlayMaxLines(this).toFloat().coerceIn(1f, 3f)
        updateStyleLabels()

        val change = Slider.OnChangeListener { _, _, _ -> updateStyleLabels() }
        val touch = object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                SettingsStore.saveStyle(
                    this@MainActivity,
                    slFont.value.toInt(),
                    slOpacity.value.toInt(),
                    slLines.value.toInt()
                )
            }
        }
        listOf(slFont, slOpacity, slLines).forEach {
            it.addOnChangeListener(change)
            it.addOnSliderTouchListener(touch)
        }
    }

    private fun updateStyleLabels() {
        tvFontVal.text = "字号 ${slFont.value.toInt()}sp"
        tvOpacityVal.text = "背景不透明度 ${slOpacity.value.toInt()}%"
        tvLinesVal.text = "最多行数 ${slLines.value.toInt()}"
    }

    // ---------- 电池白名单 ----------

    @SuppressLint("BatteryLife")
    private fun requestBatteryWhitelist() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("已在电池白名单里")
            return
        }
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    // ---------- 启动流程 ----------

    private fun onStartClicked() {
        SettingsStore.saveApiKeys(this, etApiKeys.text.toString())
        SettingsStore.saveBaseUrl(
            this,
            etBaseUrl.text.toString().trim().ifEmpty { SettingsStore.DEFAULT_BASE_URL }
        )
        val pn = etPresetName.text.toString().trim()
            .ifEmpty { SettingsStore.DEFAULT_PRESET_NAME }
        SettingsStore.savePreset(this, pn, etPrompt.text.toString())
        SettingsStore.setSelectedPreset(this, pn)

        if (SettingsStore.apiKeyList(this).isEmpty()) {
            toast("请先填入 Gemini API Key")
            return
        }

        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            need += Manifest.permission.POST_NOTIFICATIONS
        }
        if (need.isNotEmpty()) {
            if (permRequested) {
                permRequested = false
                toast("缺少录音/通知权限，请在系统设置中授予")
                return
            }
            permRequested = true
            permLauncher.launch(need.toTypedArray())
            return
        }
        permRequested = false

        if (!Settings.canDrawOverlays(this)) {
            toast("请开启悬浮窗权限，开启后回到本页再点开始")
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        projLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun renderStatus() {
        val s = StatusBus
        tvStatus.text = buildString {
            append(if (s.serviceRunning) "● 运行中" else "○ 未运行")
            append("  连接: ").append(s.connState)
            if (s.currentKeyLabel.isNotEmpty()) append("  ").append(s.currentKeyLabel)
            append("  已发送: ").append(s.chunksSent.get()).append(" 块\n")
            if (s.transcriptPath.isNotEmpty()) {
                append("记录: ").append(s.transcriptPath).append("\n")
            }
            if (s.jaTail.isNotEmpty()) append("ja: …").append(s.jaTail).append("\n")
            if (s.zhTail.isNotEmpty()) append("zh: …").append(s.zhTail)
        }
        btnToggle.text = if (s.serviceRunning) "停止翻译" else "开始翻译"
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
}
