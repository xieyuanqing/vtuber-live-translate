package com.xyq.livetranslate

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.navigation.NavigationView
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.MaterialAutoCompleteTextView

class MainActivity : AppCompatActivity() {

    // 壳子
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navView: NavigationView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var pageContainer: View
    private lateinit var pageLive: View
    private lateinit var pageStreamer: View
    private lateinit var pageHistory: View
    private lateinit var pageSettings: View
    private lateinit var pageDiagnostics: View

    // 实时翻译页
    private lateinit var tvHeroStatus: TextView
    private lateinit var tvHeroSubStatus: TextView
    private lateinit var tvAudioLevel: TextView
    private lateinit var pbAudio: ProgressBar
    private lateinit var btnToggle: Button
    private lateinit var tvCurrentProfile: TextView
    private lateinit var tvLiveZh: TextView
    private lateinit var tvLiveJa: TextView
    private lateinit var tvTranscriptPath: TextView

    // 主播资料页
    private lateinit var acStreamerProfile: MaterialAutoCompleteTextView
    private lateinit var btnNewStreamer: Button
    private lateinit var btnSaveStreamer: Button
    private lateinit var etStreamerKey: EditText
    private lateinit var etStreamerNameJp: EditText
    private lateinit var etStreamerNameZh: EditText
    private lateinit var acStreamerCategory: MaterialAutoCompleteTextView
    private lateinit var etStreamerAffiliation: EditText
    private lateinit var etStreamerAliases: EditText
    private lateinit var etStreamerTerms: EditText
    private lateinit var etStreamerMisheard: EditText
    private lateinit var etStreamerStyle: EditText
    private lateinit var etYoutubeUrl: EditText
    private lateinit var btnFetchYoutube: Button
    private lateinit var tvYoutubeInfo: TextView
    private lateinit var etTempContext: EditText
    private lateinit var btnPreviewPrompt: Button
    private lateinit var tvPromptPreview: TextView

    // 历史页
    private lateinit var btnRefreshHistory: Button
    private lateinit var tvHistoryEmpty: TextView
    private lateinit var historyList: LinearLayout
    private lateinit var cardHistoryDetail: View
    private lateinit var tvHistoryTitle: TextView
    private lateinit var btnCopyHistory: Button
    private lateinit var tvHistoryDetail: TextView

    // 设置页
    private lateinit var etApiKeys: EditText
    private lateinit var etBaseUrl: EditText
    private lateinit var slFont: Slider
    private lateinit var slOpacity: Slider
    private lateinit var slLines: Slider
    private lateinit var tvFontVal: TextView
    private lateinit var tvOpacityVal: TextView
    private lateinit var tvLinesVal: TextView

    // 诊断页
    private lateinit var btnBattery: Button
    private lateinit var tvStatus: TextView

    private var streamerProfiles: List<StreamerProfile> = emptyList()
    private var currentVideoInfo: YouTubeVideoInfo? = null
    private var permRequested = false

    private val categorySuggestions = arrayOf("hololive", "Nijisanji / 彩虹社", "个人勢", "VSPO", "其他")

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
        applyWindowInsets()
        setupDrawer()
        setupStreamerPage()
        setupHistoryPage()
        setupSettings()
        setupStyleSliders()

        btnBattery.setOnClickListener { requestBatteryWhitelist() }
        btnToggle.setOnClickListener {
            if (StatusBus.serviceRunning) {
                startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
            } else {
                onStartClicked()
            }
        }

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

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawers()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ---------- 绑定 / 壳子 ----------

    private fun bindViews() {
        drawerLayout = findViewById(R.id.drawerLayout)
        navView = findViewById(R.id.navView)
        toolbar = findViewById(R.id.toolbar)
        pageContainer = findViewById(R.id.pageContainer)
        pageLive = findViewById(R.id.pageLive)
        pageStreamer = findViewById(R.id.pageStreamer)
        pageHistory = findViewById(R.id.pageHistory)
        pageSettings = findViewById(R.id.pageSettings)
        pageDiagnostics = findViewById(R.id.pageDiagnostics)

        tvHeroStatus = findViewById(R.id.tvHeroStatus)
        tvHeroSubStatus = findViewById(R.id.tvHeroSubStatus)
        tvAudioLevel = findViewById(R.id.tvAudioLevel)
        pbAudio = findViewById(R.id.pbAudio)
        btnToggle = findViewById(R.id.btnToggle)
        tvCurrentProfile = findViewById(R.id.tvCurrentProfile)
        tvLiveZh = findViewById(R.id.tvLiveZh)
        tvLiveJa = findViewById(R.id.tvLiveJa)
        tvTranscriptPath = findViewById(R.id.tvTranscriptPath)

        acStreamerProfile = findViewById(R.id.acStreamerProfile)
        btnNewStreamer = findViewById(R.id.btnNewStreamer)
        btnSaveStreamer = findViewById(R.id.btnSaveStreamer)
        etStreamerKey = findViewById(R.id.etStreamerKey)
        etStreamerNameJp = findViewById(R.id.etStreamerNameJp)
        etStreamerNameZh = findViewById(R.id.etStreamerNameZh)
        acStreamerCategory = findViewById(R.id.acStreamerCategory)
        etStreamerAffiliation = findViewById(R.id.etStreamerAffiliation)
        etStreamerAliases = findViewById(R.id.etStreamerAliases)
        etStreamerTerms = findViewById(R.id.etStreamerTerms)
        etStreamerMisheard = findViewById(R.id.etStreamerMisheard)
        etStreamerStyle = findViewById(R.id.etStreamerStyle)
        etYoutubeUrl = findViewById(R.id.etYoutubeUrl)
        btnFetchYoutube = findViewById(R.id.btnFetchYoutube)
        tvYoutubeInfo = findViewById(R.id.tvYoutubeInfo)
        etTempContext = findViewById(R.id.etTempContext)
        btnPreviewPrompt = findViewById(R.id.btnPreviewPrompt)
        tvPromptPreview = findViewById(R.id.tvPromptPreview)

        btnRefreshHistory = findViewById(R.id.btnRefreshHistory)
        tvHistoryEmpty = findViewById(R.id.tvHistoryEmpty)
        historyList = findViewById(R.id.historyList)
        cardHistoryDetail = findViewById(R.id.cardHistoryDetail)
        tvHistoryTitle = findViewById(R.id.tvHistoryTitle)
        btnCopyHistory = findViewById(R.id.btnCopyHistory)
        tvHistoryDetail = findViewById(R.id.tvHistoryDetail)

        etApiKeys = findViewById(R.id.etApiKeys)
        etBaseUrl = findViewById(R.id.etBaseUrl)
        slFont = findViewById(R.id.slFont)
        slOpacity = findViewById(R.id.slOpacity)
        slLines = findViewById(R.id.slLines)
        tvFontVal = findViewById(R.id.tvFontVal)
        tvOpacityVal = findViewById(R.id.tvOpacityVal)
        tvLinesVal = findViewById(R.id.tvLinesVal)

        btnBattery = findViewById(R.id.btnBattery)
        tvStatus = findViewById(R.id.tvStatus)
    }

    /** targetSdk 35 起默认 edge-to-edge，需要手动把状态栏/导航栏的高度让出来。 */
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(pageContainer) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = bars.bottom)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(navView) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
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
        pageHistory.visibility = if (itemId == R.id.nav_history) View.VISIBLE else View.GONE
        pageSettings.visibility = if (itemId == R.id.nav_settings) View.VISIBLE else View.GONE
        pageDiagnostics.visibility = if (itemId == R.id.nav_diagnostics) View.VISIBLE else View.GONE
        toolbar.title = when (itemId) {
            R.id.nav_streamer -> "主播资料"
            R.id.nav_history -> "历史记录"
            R.id.nav_settings -> "设置"
            R.id.nav_diagnostics -> "诊断"
            else -> "实时翻译"
        }
        navView.setCheckedItem(itemId)
        if (itemId == R.id.nav_history) reloadHistory()
    }

    // ---------- 主播资料 + 提示词后端 ----------

    private fun setupStreamerPage() {
        acStreamerCategory.setSimpleItems(categorySuggestions)
        reloadStreamerProfiles(StreamerProfileStore.selectedKey(this))

        acStreamerProfile.setOnItemClickListener { _, _, pos, _ ->
            val profile = streamerProfiles.getOrNull(pos) ?: return@setOnItemClickListener
            StreamerProfileStore.setSelected(this, profile.key)
            renderStreamerProfile(profile)
            updateCurrentProfileLabel()
        }

        btnNewStreamer.setOnClickListener {
            renderStreamerProfile(StreamerProfileStore.DEFAULT_PROFILE.copy(key = "", nameZh = "", category = ""))
            acStreamerProfile.setText("", false)
            etStreamerKey.requestFocus()
            toast("填好资料名后点“保存资料”")
        }

        btnSaveStreamer.setOnClickListener {
            val profile = collectStreamerProfile()
            if (profile.key.isBlank()) {
                toast("资料名不能为空")
                return@setOnClickListener
            }
            StreamerProfileStore.save(this, profile)
            reloadStreamerProfiles(profile.key)
            updateCurrentProfileLabel()
            toast("已保存主播资料：${profile.key}")
        }

        btnFetchYoutube.setOnClickListener { fetchYoutubeInfo() }
        btnPreviewPrompt.setOnClickListener {
            tvPromptPreview.text = composeSessionPrompt()
        }
        updateCurrentProfileLabel()
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
        acStreamerCategory.setText(p.category, false)
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
            category = acStreamerCategory.text.toString().trim(),
            aliases = parseLines(etStreamerAliases.text.toString()),
            terms = parseLines(etStreamerTerms.text.toString()),
            misheard = parseLines(etStreamerMisheard.text.toString()),
            style = etStreamerStyle.text.toString().trim(),
        )
    }

    private fun parseLines(raw: String): List<String> = raw
        .split('\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

    /** 会话真正使用的固定资料：优先用表单里的内容（所见即所用），为空退回已保存的选中项。 */
    private fun currentSessionProfile(): StreamerProfile {
        val form = collectStreamerProfile()
        return if (form.key.isNotBlank()) form
        else StreamerProfileStore.get(this, StreamerProfileStore.selectedKey(this))
    }

    private fun currentSessionContext(): SessionPromptContext = SessionPromptContext(
        video = currentVideoInfo,
        manualContext = etTempContext.text.toString(),
    )

    private fun composeSessionPrompt(): String =
        PromptBuilder.build(currentSessionProfile(), currentSessionContext())

    private fun updateCurrentProfileLabel() {
        val name = currentSessionProfile().let { it.key.ifBlank { it.displayName() } }
        tvCurrentProfile.text = "当前主播：$name"
    }

    private fun fetchYoutubeInfo() {
        val url = etYoutubeUrl.text.toString().trim()
        if (url.isEmpty()) {
            toast("请先粘贴 YouTube 链接")
            return
        }
        btnFetchYoutube.isEnabled = false
        btnFetchYoutube.text = "获取中…"
        tvYoutubeInfo.text = "正在请求 YouTube…"
        Thread({
            val result = runCatching { YouTubeOEmbedClient.fetch(url) }
            runOnUiThread {
                btnFetchYoutube.isEnabled = true
                btnFetchYoutube.text = "从链接获取标题和频道"
                result.onSuccess { info ->
                    currentVideoInfo = info
                    tvYoutubeInfo.text = "已获取：\n标题：${info.title}\n频道：${info.authorName}"
                }.onFailure { e ->
                    tvYoutubeInfo.text = "获取失败：${e.message}（可手动在下面补一句）"
                    toast("获取 YouTube 信息失败：${e.message}")
                }
            }
        }, "youtube-oembed").start()
    }

    // ---------- 历史记录 ----------

    private fun setupHistoryPage() {
        btnRefreshHistory.setOnClickListener { reloadHistory() }
        btnCopyHistory.setOnClickListener {
            val text = tvHistoryDetail.text.toString()
            if (text.isBlank()) return@setOnClickListener
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("transcript", text))
            toast("已复制全文")
        }
    }

    private fun reloadHistory() {
        val items = HistoryStore.list(this)
        historyList.removeAllViews()
        tvHistoryEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        items.forEach { item ->
            val b = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                isAllCaps = false
                text = "${item.title}\n${HistoryStore.formatTime(item.updatedAt)} · ${item.sizeBytes / 1024}KB"
                setOnClickListener { showHistoryDetail(item) }
            }
            historyList.addView(b)
        }
    }

    private fun showHistoryDetail(item: HistoryStore.HistoryItem) {
        val content = HistoryStore.read(this, item.fileName)
        tvHistoryTitle.text = item.title
        tvHistoryDetail.text = content.ifBlank { "（这份记录是空的）" }
        cardHistoryDetail.visibility = View.VISIBLE
    }

    // ---------- 设置 ----------

    private fun setupSettings() {
        etApiKeys.setText(SettingsStore.apiKeysRaw(this))
        etBaseUrl.setText(SettingsStore.baseUrl(this))
    }

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
        // 固定资料若在表单里编辑过就顺手保存，避免开播用到没保存的内容时下次丢失
        val formProfile = collectStreamerProfile()
        if (formProfile.key.isNotBlank()) {
            StreamerProfileStore.save(this, formProfile)
        }
        // 组装本场提示词 = 固定资料 + 临时上下文，写给服务用
        SettingsStore.saveComposedPrompt(this, composeSessionPrompt())

        if (SettingsStore.apiKeyList(this).isEmpty()) {
            toast("请先到“设置”里填 Gemini API Key")
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
        tvHeroSubStatus.text = if (!running) {
            "尚未开始翻译"
        } else {
            buildString {
                append("连接 ").append(conn)
                if (s.currentKeyLabel.isNotEmpty()) append(" · ").append(s.currentKeyLabel)
                append(" · 已发送 ").append(s.chunksSent.get()).append(" 块")
            }
        }
        tvAudioLevel.text = "$level%"
        pbAudio.progress = level

        tvLiveZh.text = s.zhTail.ifBlank { "等待中文字幕…" }
        tvLiveJa.text = s.jaTail.ifBlank { "等待日文输入…" }
        if (s.transcriptPath.isNotEmpty()) {
            tvTranscriptPath.text = "本场记录：${s.transcriptPath}"
        }

        tvStatus.text = buildString {
            append(if (running) "● 运行中" else "○ 未运行")
            append("  连接: ").append(conn)
            if (s.currentKeyLabel.isNotEmpty()) append("  ").append(s.currentKeyLabel)
            append("  音量: ").append(level).append("%")
            append("  已发送: ").append(s.chunksSent.get()).append(" 块\n")
            if (s.transcriptPath.isNotEmpty()) append("记录: ").append(s.transcriptPath).append("\n")
            if (s.jaTail.isNotEmpty()) append("ja: …").append(s.jaTail).append("\n")
            if (s.zhTail.isNotEmpty()) append("zh: …").append(s.zhTail)
        }
        btnToggle.text = if (running) "停止翻译" else "开始翻译"
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
}
