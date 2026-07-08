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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.navigation.NavigationView
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.MaterialAutoCompleteTextView

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navView: NavigationView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var pageLive: View
    private lateinit var pageStreamer: View
    private lateinit var pageSettings: View
    private lateinit var pageDiagnostics: View

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
    private lateinit var tvHeroStatus: TextView
    private lateinit var tvHeroSubStatus: TextView
    private lateinit var tvAudioLevel: TextView
    private lateinit var pbAudio: ProgressBar
    private lateinit var tvLiveZh: TextView
    private lateinit var tvLiveJa: TextView
    private lateinit var tvTranscriptPath: TextView
    private lateinit var tvStatus: TextView

    private lateinit var acStreamerProfile: MaterialAutoCompleteTextView
    private lateinit var etStreamerKey: EditText
    private lateinit var etStreamerNameJp: EditText
    private lateinit var etStreamerNameZh: EditText
    private lateinit var etStreamerAffiliation: EditText
    private lateinit var etStreamerAliases: EditText
    private lateinit var etStreamerTerms: EditText
    private lateinit var etStreamerMisheard: EditText
    private lateinit var etStreamerStyle: EditText
    private lateinit var btnNewStreamer: Button
    private lateinit var btnSaveStreamer: Button
    private lateinit var etYoutubeUrl: EditText
    private lateinit var btnFetchYoutube: Button
    private lateinit var tvYoutubeInfo: TextView
    private lateinit var etVideoContext: EditText
    private lateinit var btnGeneratePrompt: Button
    private lateinit var etGeneratedPrompt: EditText
    private lateinit var btnApplyPrompt: Button

    private var presetNames: List<String> = emptyList()
    private var streamerProfiles: List<StreamerProfile> = emptyList()
    private var currentVideoInfo: YouTubeVideoInfo? = null
    private var permRequested = false

    private val ui = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            renderStatus()
            ui.postDelayed(this, 300)
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

        bindViews()
        setupDrawer()
        setupSettings()
        setupStreamerPage()
        setupStyleSliders()

        btnBattery.setOnClickListener { requestBatteryWhitelist() }
        btnToggle.setOnClickListener {
            if (StatusBus.serviceRunning) {
                startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
            } else {
                onStartClicked()
            }
        }

        navView.setCheckedItem(R.id.nav_live)
        showPage(R.id.nav_live)
    }

    override fun onResume() {
        super.onResume()
        ui.removeCallbacks(refresh)
        ui.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(refresh)
    }

    private fun bindViews() {
        drawerLayout = findViewById(R.id.drawerLayout)
        navView = findViewById(R.id.navView)
        toolbar = findViewById(R.id.toolbar)
        pageLive = findViewById(R.id.pageLive)
        pageStreamer = findViewById(R.id.pageStreamer)
        pageSettings = findViewById(R.id.pageSettings)
        pageDiagnostics = findViewById(R.id.pageDiagnostics)

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
        tvHeroStatus = findViewById(R.id.tvHeroStatus)
        tvHeroSubStatus = findViewById(R.id.tvHeroSubStatus)
        tvAudioLevel = findViewById(R.id.tvAudioLevel)
        pbAudio = findViewById(R.id.pbAudio)
        tvLiveZh = findViewById(R.id.tvLiveZh)
        tvLiveJa = findViewById(R.id.tvLiveJa)
        tvTranscriptPath = findViewById(R.id.tvTranscriptPath)
        tvStatus = findViewById(R.id.tvStatus)

        acStreamerProfile = findViewById(R.id.acStreamerProfile)
        etStreamerKey = findViewById(R.id.etStreamerKey)
        etStreamerNameJp = findViewById(R.id.etStreamerNameJp)
        etStreamerNameZh = findViewById(R.id.etStreamerNameZh)
        etStreamerAffiliation = findViewById(R.id.etStreamerAffiliation)
        etStreamerAliases = findViewById(R.id.etStreamerAliases)
        etStreamerTerms = findViewById(R.id.etStreamerTerms)
        etStreamerMisheard = findViewById(R.id.etStreamerMisheard)
        etStreamerStyle = findViewById(R.id.etStreamerStyle)
        btnNewStreamer = findViewById(R.id.btnNewStreamer)
        btnSaveStreamer = findViewById(R.id.btnSaveStreamer)
        etYoutubeUrl = findViewById(R.id.etYoutubeUrl)
        btnFetchYoutube = findViewById(R.id.btnFetchYoutube)
        tvYoutubeInfo = findViewById(R.id.tvYoutubeInfo)
        etVideoContext = findViewById(R.id.etVideoContext)
        btnGeneratePrompt = findViewById(R.id.btnGeneratePrompt)
        etGeneratedPrompt = findViewById(R.id.etGeneratedPrompt)
        btnApplyPrompt = findViewById(R.id.btnApplyPrompt)
    }

    private fun setupDrawer() {
        toolbar.setNavigationOnClickListener { drawerLayout.openDrawer(GravityCompat.START) }
        navView.setNavigationItemSelectedListener { item ->
            showPage(item.itemId)
            drawerLayout.closeDrawers()
            true
        }
    }

    private fun showPage(itemId: Int) {
        pageLive.visibility = if (itemId == R.id.nav_live) View.VISIBLE else View.GONE
        pageStreamer.visibility = if (itemId == R.id.nav_streamer) View.VISIBLE else View.GONE
        pageSettings.visibility = if (itemId == R.id.nav_settings) View.VISIBLE else View.GONE
        pageDiagnostics.visibility = if (itemId == R.id.nav_diagnostics) View.VISIBLE else View.GONE
        toolbar.title = when (itemId) {
            R.id.nav_streamer -> "主播资料"
            R.id.nav_settings -> "设置"
            R.id.nav_diagnostics -> "诊断"
            else -> "实时翻译"
        }
        navView.setCheckedItem(itemId)
    }

    // ---------- 设置 / prompt 预设 ----------

    private fun setupSettings() {
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
    }

    private fun reloadPresets(selectName: String) {
        presetNames = SettingsStore.presetNames(this)
        acPreset.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, presetNames))
        val name = if (selectName in presetNames) selectName else presetNames.first()
        acPreset.setText(name, false)
        etPresetName.setText(name)
        etPrompt.setText(SettingsStore.presetText(this, name))
    }

    // ---------- 主播资料 / prompt 后端 ----------

    private fun setupStreamerPage() {
        reloadStreamerProfiles(StreamerProfileStore.selectedKey(this))

        acStreamerProfile.setOnItemClickListener { _, _, pos, _ ->
            val profile = streamerProfiles.getOrNull(pos) ?: return@setOnItemClickListener
            StreamerProfileStore.setSelected(this, profile.key)
            renderStreamerProfile(profile)
        }

        btnNewStreamer.setOnClickListener {
            renderStreamerProfile(StreamerProfileStore.DEFAULT_PROFILE.copy(key = "", nameZh = ""))
            acStreamerProfile.setText("", false)
            toast("填好资料后点“保存资料”")
        }

        btnSaveStreamer.setOnClickListener {
            val profile = collectStreamerProfile()
            if (profile.key.isBlank()) {
                toast("资料名不能为空")
                return@setOnClickListener
            }
            StreamerProfileStore.save(this, profile)
            reloadStreamerProfiles(profile.key)
            toast("已保存主播资料：${profile.key}")
        }

        btnFetchYoutube.setOnClickListener { fetchYoutubeInfo() }
        btnGeneratePrompt.setOnClickListener { generatePromptFromForm() }
        btnApplyPrompt.setOnClickListener { applyGeneratedPrompt() }
    }

    private fun reloadStreamerProfiles(selectKey: String) {
        streamerProfiles = StreamerProfileStore.all(this)
        val keys = streamerProfiles.map { it.key }
        acStreamerProfile.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, keys))
        val key = if (selectKey in keys) selectKey else keys.first()
        acStreamerProfile.setText(key, false)
        renderStreamerProfile(StreamerProfileStore.get(this, key))
    }

    private fun renderStreamerProfile(p: StreamerProfile) {
        etStreamerKey.setText(p.key)
        etStreamerNameJp.setText(p.nameJp)
        etStreamerNameZh.setText(p.nameZh)
        etStreamerAffiliation.setText(p.affiliation)
        etStreamerAliases.setText(p.aliases.joinToString("\n"))
        etStreamerTerms.setText(p.terms.joinToString("\n"))
        etStreamerMisheard.setText(p.misheard.joinToString("\n"))
        etStreamerStyle.setText(p.style)
    }

    private fun collectStreamerProfile(): StreamerProfile {
        val key = etStreamerKey.text.toString().trim()
            .ifEmpty { etStreamerNameJp.text.toString().trim() }
            .ifEmpty { etStreamerNameZh.text.toString().trim() }
        return StreamerProfile(
            key = key,
            nameJp = etStreamerNameJp.text.toString().trim(),
            nameZh = etStreamerNameZh.text.toString().trim(),
            affiliation = etStreamerAffiliation.text.toString().trim(),
            aliases = parseLines(etStreamerAliases.text.toString()),
            terms = parseLines(etStreamerTerms.text.toString()),
            misheard = parseLines(etStreamerMisheard.text.toString()),
            style = etStreamerStyle.text.toString().trim(),
        )
    }

    private fun parseLines(raw: String): List<String> = raw
        .split('\n', ',', '，')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

    private fun fetchYoutubeInfo() {
        val url = etYoutubeUrl.text.toString().trim()
        if (url.isEmpty()) {
            toast("请先粘贴 YouTube URL")
            return
        }
        btnFetchYoutube.isEnabled = false
        btnFetchYoutube.text = "获取中…"
        tvYoutubeInfo.text = "正在请求 YouTube oEmbed…"
        Thread({
            val result = runCatching { YouTubeOEmbedClient.fetch(url) }
            runOnUiThread {
                btnFetchYoutube.isEnabled = true
                btnFetchYoutube.text = "获取标题和频道（oEmbed）"
                result.onSuccess { info ->
                    currentVideoInfo = info
                    tvYoutubeInfo.text = "标题：${info.title}\n频道：${info.authorName}\nURL：${info.url}"
                }.onFailure { e ->
                    tvYoutubeInfo.text = "获取失败：${e.message}"
                    toast("获取 YouTube 信息失败：${e.message}")
                }
            }
        }, "youtube-oembed").start()
    }

    private fun generatePromptFromForm() {
        val profile = collectStreamerProfile()
        if (profile.key.isBlank()) {
            toast("请先填写主播资料名")
            return
        }
        val prompt = PromptBuilder.build(
            profile = profile,
            video = currentVideoInfo,
            extraContext = etVideoContext.text.toString(),
        )
        etGeneratedPrompt.setText(prompt)
        toast("已生成 prompt")
    }

    private fun applyGeneratedPrompt() {
        val profile = collectStreamerProfile()
        val prompt = etGeneratedPrompt.text.toString().ifBlank {
            PromptBuilder.build(profile, currentVideoInfo, etVideoContext.text.toString())
        }
        if (profile.key.isBlank()) {
            toast("请先填写主播资料名")
            return
        }
        StreamerProfileStore.save(this, profile)
        SettingsStore.savePreset(this, profile.key, prompt)
        SettingsStore.setSelectedPreset(this, profile.key)
        reloadStreamerProfiles(profile.key)
        reloadPresets(profile.key)
        showPage(R.id.nav_settings)
        toast("已应用到提示词预设：${profile.key}")
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
                    slLines.value.toInt(),
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
                    Uri.parse("package:$packageName"),
                ),
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
            etBaseUrl.text.toString().trim().ifEmpty { SettingsStore.DEFAULT_BASE_URL },
        )
        val pn = etPresetName.text.toString().trim().ifEmpty { SettingsStore.DEFAULT_PRESET_NAME }
        SettingsStore.savePreset(this, pn, etPrompt.text.toString())
        SettingsStore.setSelectedPreset(this, pn)

        if (SettingsStore.apiKeyList(this).isEmpty()) {
            toast("请先在设置页填入 Gemini API Key")
            showPage(R.id.nav_settings)
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
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        projLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun renderStatus() {
        val s = StatusBus
        val running = s.serviceRunning
        val conn = s.connState
        val level = s.audioLevelPct.coerceIn(0, 100)

        tvHeroStatus.text = when {
            !running -> "○ 待开始"
            conn == "ready" -> "● 翻译中"
            conn.startsWith("error") -> "● 出错"
            else -> "● 准备连接"
        }
        tvHeroSubStatus.text = buildString {
            append("连接: ").append(conn)
            if (s.currentKeyLabel.isNotEmpty()) append(" · ").append(s.currentKeyLabel)
            append(" · 已发送 ").append(s.chunksSent.get()).append(" 块")
        }
        tvAudioLevel.text = "$level%"
        pbAudio.progress = level

        tvLiveZh.text = s.zhTail.ifBlank { "等待中文字幕…" }
        tvLiveJa.text = s.jaTail.ifBlank { "等待日文输入…" }
        tvTranscriptPath.text = if (s.transcriptPath.isNotEmpty()) {
            "记录：${s.transcriptPath}"
        } else {
            "记录：开始后自动保存到 下载/LiveTranslate/"
        }

        tvStatus.text = buildString {
            append(if (running) "● 运行中" else "○ 未运行")
            append("  连接: ").append(conn)
            if (s.currentKeyLabel.isNotEmpty()) append("  ").append(s.currentKeyLabel)
            append("  音量: ").append(level).append("%")
            append("  已发送: ").append(s.chunksSent.get()).append(" 块\n")
            if (s.transcriptPath.isNotEmpty()) {
                append("记录: ").append(s.transcriptPath).append("\n")
            }
            if (s.jaTail.isNotEmpty()) append("ja: …").append(s.jaTail).append("\n")
            if (s.zhTail.isNotEmpty()) append("zh: …").append(s.zhTail)
        }
        btnToggle.text = if (running) "停止翻译" else "开始翻译"
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
}
