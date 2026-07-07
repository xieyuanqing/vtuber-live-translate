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
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var etApiKeys: EditText
    private lateinit var etBaseUrl: EditText
    private lateinit var spPreset: Spinner
    private lateinit var etPresetName: EditText
    private lateinit var etPrompt: EditText
    private lateinit var btnNewPreset: Button
    private lateinit var btnDeletePreset: Button
    private lateinit var btnSavePreset: Button
    private lateinit var sbFont: SeekBar
    private lateinit var sbOpacity: SeekBar
    private lateinit var sbLines: SeekBar
    private lateinit var tvFontVal: TextView
    private lateinit var tvOpacityVal: TextView
    private lateinit var tvLinesVal: TextView
    private lateinit var btnBattery: Button
    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView

    private var presetNames: List<String> = emptyList()
    private var suppressSpinner = false
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
        spPreset = findViewById(R.id.spPreset)
        etPresetName = findViewById(R.id.etPresetName)
        etPrompt = findViewById(R.id.etPrompt)
        btnNewPreset = findViewById(R.id.btnNewPreset)
        btnDeletePreset = findViewById(R.id.btnDeletePreset)
        btnSavePreset = findViewById(R.id.btnSavePreset)
        sbFont = findViewById(R.id.sbFont)
        sbOpacity = findViewById(R.id.sbOpacity)
        sbLines = findViewById(R.id.sbLines)
        tvFontVal = findViewById(R.id.tvFontVal)
        tvOpacityVal = findViewById(R.id.tvOpacityVal)
        tvLinesVal = findViewById(R.id.tvLinesVal)
        btnBattery = findViewById(R.id.btnBattery)
        btnToggle = findViewById(R.id.btnToggle)
        tvStatus = findViewById(R.id.tvStatus)

        etApiKeys.setText(SettingsStore.apiKeysRaw(this))
        etBaseUrl.setText(SettingsStore.baseUrl(this))
        reloadPresets(SettingsStore.selectedPreset(this))

        spPreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (suppressSpinner) return
                val name = presetNames.getOrNull(pos) ?: return
                SettingsStore.setSelectedPreset(this@MainActivity, name)
                etPresetName.setText(name)
                etPrompt.setText(SettingsStore.presetText(this@MainActivity, name))
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        btnNewPreset.setOnClickListener {
            etPresetName.setText("")
            etPrompt.setText(SettingsStore.DEFAULT_PROMPT)
            etPresetName.requestFocus()
            toast("填好预设名和提示词后，点“保存当前预设”")
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
            val name = presetNames.getOrNull(spPreset.selectedItemPosition) ?: return@setOnClickListener
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
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, presetNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        suppressSpinner = true
        spPreset.adapter = adapter
        val idx = presetNames.indexOf(selectName).coerceAtLeast(0)
        spPreset.setSelection(idx)
        suppressSpinner = false
        val name = presetNames[idx]
        etPresetName.setText(name)
        etPrompt.setText(SettingsStore.presetText(this, name))
    }

    // ---------- 样式 ----------

    private fun setupStyleSliders() {
        sbFont.progress = SettingsStore.fontSizeSp(this)
        sbOpacity.progress = SettingsStore.bgOpacityPct(this)
        sbLines.progress = SettingsStore.overlayMaxLines(this)
        updateStyleLabels()

        val l = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                updateStyleLabels()
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}

            override fun onStopTrackingTouch(s: SeekBar?) {
                SettingsStore.saveStyle(
                    this@MainActivity, sbFont.progress, sbOpacity.progress, sbLines.progress
                )
            }
        }
        sbFont.setOnSeekBarChangeListener(l)
        sbOpacity.setOnSeekBarChangeListener(l)
        sbLines.setOnSeekBarChangeListener(l)
    }

    private fun updateStyleLabels() {
        tvFontVal.text = "字号 ${sbFont.progress}sp"
        tvOpacityVal.text = "背景不透明度 ${sbOpacity.progress}%"
        tvLinesVal.text = "最多行数 ${sbLines.progress}"
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
            // 个别 ROM 不支持该 intent，退而求其次跳电池优化列表
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    // ---------- 启动流程 ----------

    private fun onStartClicked() {
        // 保存所有设置（key 加密入库；当前编辑的预设顺手保存并选中）
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

        // 1) 运行时权限
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

        // 2) 悬浮窗权限
        if (!Settings.canDrawOverlays(this)) {
            toast("请开启悬浮窗权限，开启后回到本页再点开始")
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }

        // 3) MediaProjection（系统录制确认框）
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
