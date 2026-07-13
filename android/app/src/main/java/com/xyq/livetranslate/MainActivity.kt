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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.MaterialAutoCompleteTextView

class MainActivity : AppCompatActivity() {

    // 壳子：底部 4 Tab
    private lateinit var rootLayout: View
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var pageContainer: View
    private lateinit var pageInterp: View
    private lateinit var pageVideo: View
    private lateinit var pageStreamer: View
    private lateinit var pageHistory: View
    private lateinit var pageSettings: View
    private var currentMainTabId = R.id.nav_interp

    // 设置二级页（0 = 设置首页）
    private var settingsSubId = 0
    private lateinit var pageSettingsTranslate: View
    private lateinit var pageSettingsSubtitle: View
    private lateinit var pageSettingsProfileAi: View
    private lateinit var pageSettingsDiagnostics: View
    private lateinit var pageSettingsAbout: View
    private lateinit var settingsSubViews: List<View>
    private lateinit var rowSetTranslate: View
    private lateinit var rowSetSubtitle: View
    private lateinit var rowSetScenes: View
    private lateinit var rowSetProfileAi: View
    private lateinit var rowSetDiagnostics: View
    private lateinit var rowSetAbout: View

    // 同传页（麦克风）
    private lateinit var tvInterpStatus: TextView
    private lateinit var tvInterpSubStatus: TextView
    private lateinit var tvInterpAudioLevel: TextView
    private lateinit var pbInterpAudio: ProgressBar
    private lateinit var btnInterpToggle: Button
    private lateinit var tvInterpProfile: TextView
    private lateinit var tvInterpZh: TextView
    private lateinit var tvInterpJa: TextView
    private lateinit var tvInterpTranscriptPath: TextView

    // 视频页（原实时翻译：系统内录 + 悬浮字幕）
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
    private lateinit var btnAiAnalyze: Button
    private lateinit var tvAiStatus: TextView
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
    private lateinit var etSecondAiKey: EditText
    private lateinit var etSecondAiUrl: EditText
    private lateinit var etSecondAiModel: EditText
    private lateinit var btnSecondAiFormat: Button

    // 翻译参数 / 断句参数（分属翻译服务、字幕与悬浮窗两个子页）
    private lateinit var acTargetLang: MaterialAutoCompleteTextView
    private lateinit var swEchoTarget: MaterialSwitch
    private lateinit var slRotate: Slider
    private lateinit var slIdle: Slider
    private lateinit var slMaxChars: Slider
    private lateinit var tvRotateVal: TextView
    private lateinit var tvIdleVal: TextView
    private lateinit var tvMaxCharsVal: TextView
    private lateinit var btnResetTranslate: Button
    private lateinit var btnResetSubtitle: Button

    // 诊断 / 关于子页
    private lateinit var btnBattery: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvAboutVersion: TextView
    private lateinit var btnAboutRepo: Button

    private var streamerProfiles: List<StreamerProfile> = emptyList()
    private var currentVideoInfo: YouTubeVideoInfo? = null
    private var permRequested = false
    /** 权限回调后要启动的模式：video / mic */
    private var pendingStartMode: String = StatusBus.MODE_VIDEO

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
            startCapture(pendingStartMode)
        }

    private val projLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK && res.data != null) {
                val i = Intent(this, CaptureService::class.java)
                    .setAction(CaptureService.ACTION_START)
                    .putExtra(CaptureService.EXTRA_MODE, StatusBus.MODE_VIDEO)
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
        setupBottomNav()
        setupStreamerPage()
        setupHistoryPage()
        setupSettings()
        setupStyleSliders()
        setupParamControls()

        btnBattery.setOnClickListener { requestBatteryWhitelist() }
        btnToggle.setOnClickListener { onModeToggle(StatusBus.MODE_VIDEO) }
        btnInterpToggle.setOnClickListener { onModeToggle(StatusBus.MODE_MIC) }

        showPage(R.id.nav_interp)
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
        when {
            settingsSubId != 0 -> closeSettingsSub()
            else -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    // ---------- 绑定 / 壳子 ----------

    private fun bindViews() {
        rootLayout = findViewById(R.id.rootLayout)
        bottomNav = findViewById(R.id.bottomNav)
        toolbar = findViewById(R.id.toolbar)
        pageContainer = findViewById(R.id.pageContainer)
        pageInterp = findViewById(R.id.pageInterp)
        pageVideo = findViewById(R.id.pageVideo)
        pageStreamer = findViewById(R.id.pageStreamer)
        pageHistory = findViewById(R.id.pageHistory)
        pageSettings = findViewById(R.id.pageSettings)
        pageSettingsTranslate = findViewById(R.id.pageSettingsTranslate)
        pageSettingsSubtitle = findViewById(R.id.pageSettingsSubtitle)
        pageSettingsProfileAi = findViewById(R.id.pageSettingsProfileAi)
        pageSettingsDiagnostics = findViewById(R.id.pageSettingsDiagnostics)
        pageSettingsAbout = findViewById(R.id.pageSettingsAbout)
        settingsSubViews = listOf(
            pageStreamer,
            pageSettingsTranslate, pageSettingsSubtitle, pageSettingsProfileAi,
            pageSettingsDiagnostics, pageSettingsAbout,
        )
        rowSetTranslate = findViewById(R.id.rowSetTranslate)
        rowSetSubtitle = findViewById(R.id.rowSetSubtitle)
        rowSetScenes = findViewById(R.id.rowSetScenes)
        rowSetProfileAi = findViewById(R.id.rowSetProfileAi)
        rowSetDiagnostics = findViewById(R.id.rowSetDiagnostics)
        rowSetAbout = findViewById(R.id.rowSetAbout)

        tvInterpStatus = findViewById(R.id.tvInterpStatus)
        tvInterpSubStatus = findViewById(R.id.tvInterpSubStatus)
        tvInterpAudioLevel = findViewById(R.id.tvInterpAudioLevel)
        pbInterpAudio = findViewById(R.id.pbInterpAudio)
        btnInterpToggle = findViewById(R.id.btnInterpToggle)
        tvInterpProfile = findViewById(R.id.tvInterpProfile)
        tvInterpZh = findViewById(R.id.tvInterpZh)
        tvInterpJa = findViewById(R.id.tvInterpJa)
        tvInterpTranscriptPath = findViewById(R.id.tvInterpTranscriptPath)

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
        btnAiAnalyze = findViewById(R.id.btnAiAnalyze)
        tvAiStatus = findViewById(R.id.tvAiStatus)
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
        etSecondAiKey = findViewById(R.id.etSecondAiKey)
        etSecondAiUrl = findViewById(R.id.etSecondAiUrl)
        etSecondAiModel = findViewById(R.id.etSecondAiModel)
        btnSecondAiFormat = findViewById(R.id.btnSecondAiFormat)

        acTargetLang = findViewById(R.id.acTargetLang)
        swEchoTarget = findViewById(R.id.swEchoTarget)
        slRotate = findViewById(R.id.slRotate)
        slIdle = findViewById(R.id.slIdle)
        slMaxChars = findViewById(R.id.slMaxChars)
        tvRotateVal = findViewById(R.id.tvRotateVal)
        tvIdleVal = findViewById(R.id.tvIdleVal)
        tvMaxCharsVal = findViewById(R.id.tvMaxCharsVal)
        btnResetTranslate = findViewById(R.id.btnResetTranslate)
        btnResetSubtitle = findViewById(R.id.btnResetSubtitle)

        btnBattery = findViewById(R.id.btnBattery)
        tvStatus = findViewById(R.id.tvStatus)
        tvAboutVersion = findViewById(R.id.tvAboutVersion)
        btnAboutRepo = findViewById(R.id.btnAboutRepo)
    }

    /** targetSdk 35 起默认 edge-to-edge，需要手动把状态栏/导航栏的高度让出来。 */
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top)
            insets
        }
        // 底部导航自己吃系统导航栏高度；内容区不再额外加 bottom padding，避免双重留白
        ViewCompat.setOnApplyWindowInsetsListener(bottomNav) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = bars.bottom)
            insets
        }
    }

    private fun setupBottomNav() {
        toolbar.setNavigationOnClickListener {
            if (settingsSubId != 0) closeSettingsSub()
        }
        // 主 Tab 不显示返回箭头
        toolbar.navigationIcon = null
        bottomNav.setOnItemSelectedListener { item ->
            showPage(item.itemId)
            true
        }
    }

    private fun showPage(itemId: Int) {
        settingsSubId = 0
        settingsSubViews.forEach { it.visibility = View.GONE }
        // 主播/场景页不再是底部 Tab，统一作为设置二级页隐藏
        pageInterp.visibility = if (itemId == R.id.nav_interp) View.VISIBLE else View.GONE
        pageVideo.visibility = if (itemId == R.id.nav_video) View.VISIBLE else View.GONE
        pageHistory.visibility = if (itemId == R.id.nav_history) View.VISIBLE else View.GONE
        pageSettings.visibility = if (itemId == R.id.nav_settings) View.VISIBLE else View.GONE
        pageStreamer.visibility = View.GONE

        toolbar.title = when (itemId) {
            R.id.nav_interp -> "同传"
            R.id.nav_video -> "视频"
            R.id.nav_history -> "历史"
            R.id.nav_settings -> "设置"
            else -> "同传"
        }
        toolbar.navigationIcon = null
        bottomNav.visibility = View.VISIBLE
        // 这里不能再写 bottomNav.selectedItemId：showPage 本身就在选中回调里，
        // Material 会在回调返回后才更新 selectedItemId；回调内重设会无限重入并栈溢出。
        currentMainTabId = itemId
        if (itemId == R.id.nav_history) reloadHistory()
    }

    /** 打开设置二级页：工具栏变返回箭头，标题换成子页名；隐藏底部导航。 */
    private fun openSettingsSub(pageId: Int, title: String) {
        settingsSubId = pageId
        pageInterp.visibility = View.GONE
        pageVideo.visibility = View.GONE
        pageHistory.visibility = View.GONE
        pageSettings.visibility = View.GONE
        settingsSubViews.forEach { it.visibility = if (it.id == pageId) View.VISIBLE else View.GONE }
        toolbar.title = title
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back_24)
        bottomNav.visibility = View.GONE
    }

    private fun closeSettingsSub() {
        settingsSubId = 0
        settingsSubViews.forEach { it.visibility = View.GONE }
        // 回到设置 Tab 首页
        pageSettings.visibility = View.VISIBLE
        pageInterp.visibility = View.GONE
        pageVideo.visibility = View.GONE
        pageHistory.visibility = View.GONE
        pageStreamer.visibility = View.GONE
        toolbar.title = "设置"
        toolbar.navigationIcon = null
        bottomNav.visibility = View.VISIBLE
        if (bottomNav.selectedItemId != R.id.nav_settings) {
            bottomNav.selectedItemId = R.id.nav_settings
        }
        currentMainTabId = R.id.nav_settings
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
            toast("填好资料名后点\"保存资料\"")
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
        btnAiAnalyze.setOnClickListener { onAiAnalyze() }
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
        val text = "当前场景：$name"
        tvCurrentProfile.text = text
        tvInterpProfile.text = text
    }


    private fun fetchYoutubeInfo() {
        val url = etYoutubeUrl.text.toString().trim()
        if (url.isEmpty()) {
            toast("请先粘贴 YouTube 链接")
            return
        }
        btnFetchYoutube.isEnabled = false
        btnFetchYoutube.text = "获取中…"
        tvYoutubeInfo.text = "正在请求…"
        Thread({
            val result = runCatching { YouTubeOEmbedClient.fetch(url) }
            runOnUiThread {
                btnFetchYoutube.isEnabled = true
                btnFetchYoutube.text = "获取视频信息"
                result.onSuccess { info ->
                    currentVideoInfo = info
                    tvYoutubeInfo.text = "${info.authorName} · ${info.title}"
                }.onFailure { e ->
                    tvYoutubeInfo.text = "获取失败：${e.message}"
                    toast("获取 YouTube 信息失败：${e.message}")
                }
            }
        }, "youtube-oembed").start()
    }

    private fun onAiAnalyze() {
        // 先确保第二 AI 设置已保存
        saveSecondAiSettings()

        val apiKey = SettingsStore.secondAiApiKey(this)
        if (apiKey.isEmpty()) {
            toast("请先到\"设置 → 资料 AI\"配置 Key")
            return
        }

        val url = etYoutubeUrl.text.toString().trim()
        if (url.isEmpty()) {
            toast("请先粘贴 YouTube 链接")
            return
        }

        // 如果还没获取 YouTube 信息，先获取
        val video = currentVideoInfo
        if (video == null || video.title.isEmpty()) {
            toast("请先点\"从链接获取标题和频道\"拿到视频信息再 AI 分析")
            return
        }

        btnAiAnalyze.isEnabled = false
        btnAiAnalyze.text = "分析中…"
        tvAiStatus.text = "正在分析 ${video.authorName}…"

        val baseUrl = SettingsStore.secondAiBaseUrl(this)
        val model = SettingsStore.secondAiModel(this)
        val format = secondAiFormat()

        Thread({
            val result = runCatching {
                ProfileGenerator.generate(
                    videoInfo = video,
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    model = model,
                    format = format,
                )
            }
            runOnUiThread {
                btnAiAnalyze.isEnabled = true
                btnAiAnalyze.text = "AI 自动分析生成"

                result.onSuccess { gen ->
                    tvAiStatus.text = gen.note.ifBlank { "已生成" }

                    // 把 AI 生成的主播资料回填到表单
                    gen.profile?.let { profile ->
                        renderStreamerProfile(profile)
                        acStreamerProfile.setText(profile.key, false)
                        toast("已生成主播资料：${profile.key}，请检查后保存")
                    } ?: run {
                        tvAiStatus.text = "资料解析失败，请重试或手动填写"
                        toast("AI 未能生成主播资料，请手动填写")
                    }

                    // 把 AI 生成的本场提示词自动填入临时上下文
                    if (gen.temporaryContext.isNotBlank()) {
                        etTempContext.setText(gen.temporaryContext)
                    }

                    // 自动预览
                    tvPromptPreview.text = composeSessionPrompt()
                }.onFailure { e ->
                    tvAiStatus.text = "分析失败：${e.message}"
                    toast("AI 分析失败：${e.message}")
                }
            }
        }, "ai-analyze").start()
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
        rowSetTranslate.setOnClickListener { openSettingsSub(R.id.pageSettingsTranslate, "翻译服务") }
        rowSetSubtitle.setOnClickListener { openSettingsSub(R.id.pageSettingsSubtitle, "字幕与悬浮窗") }
        rowSetScenes.setOnClickListener { openSettingsSub(R.id.pageStreamer, "场景 / 术语库") }
        rowSetProfileAi.setOnClickListener { openSettingsSub(R.id.pageSettingsProfileAi, "资料 AI") }
        rowSetDiagnostics.setOnClickListener { openSettingsSub(R.id.pageSettingsDiagnostics, "诊断") }
        rowSetAbout.setOnClickListener { openSettingsSub(R.id.pageSettingsAbout, "关于") }

        etApiKeys.setText(SettingsStore.apiKeysRaw(this))
        etBaseUrl.setText(SettingsStore.baseUrl(this))
        etSecondAiKey.setText(SettingsStore.secondAiApiKey(this))
        etSecondAiUrl.setText(SettingsStore.secondAiBaseUrl(this))
        etSecondAiModel.setText(SettingsStore.secondAiModel(this))
        updateSecondAiFormatLabel()
        btnSecondAiFormat.setOnClickListener { toggleSecondAiFormat() }

        val info = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        tvAboutVersion.text = if (info != null) {
            "版本 ${info.versionName}（${info.longVersionCode}）"
        } else {
            "版本未知"
        }
        btnAboutRepo.setOnClickListener {
            runCatching {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/xieyuanqing/vtuber-live-translate")),
                )
            }.onFailure { toast("没有可用的浏览器") }
        }
    }

    private fun secondAiFormat(): AiTextClient.Format =
        AiTextClient.Format.fromKey(SettingsStore.secondAiFormat(this))

    private fun updateSecondAiFormatLabel() {
        btnSecondAiFormat.text = when (secondAiFormat()) {
            AiTextClient.Format.GEMINI -> "Gemini 原生"
            AiTextClient.Format.OPENAI -> "OpenAI 兼容"
        }
    }

    private fun toggleSecondAiFormat() {
        val next = when (secondAiFormat()) {
            AiTextClient.Format.GEMINI -> AiTextClient.Format.OPENAI
            AiTextClient.Format.OPENAI -> AiTextClient.Format.GEMINI
        }
        SettingsStore.saveSecondAiFormat(this, next.key)
        updateSecondAiFormatLabel()
        toast("已切换到 ${next.key} 格式")
    }

    private fun saveSecondAiSettings() {
        SettingsStore.saveSecondAiApiKey(this, etSecondAiKey.text.toString())
        SettingsStore.saveSecondAiBaseUrl(this, etSecondAiUrl.text.toString().trim()
            .ifEmpty { SettingsStore.DEFAULT_BASE_URL })
        SettingsStore.saveSecondAiModel(this, etSecondAiModel.text.toString().trim()
            .ifEmpty { SettingsStore.secondAiModel(this) })
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

    // ---------- 翻译参数 / 断句参数 ----------

    private val targetLangSuggestions = arrayOf("zh", "zh-Hans", "zh-Hant", "en", "ja", "ko")

    private fun setupParamControls() {
        acTargetLang.setSimpleItems(targetLangSuggestions)
        renderParamValues()

        acTargetLang.setOnItemClickListener { _, _, _, _ ->
            SettingsStore.saveTargetLang(this, acTargetLang.text.toString())
        }
        swEchoTarget.setOnCheckedChangeListener { _, checked ->
            SettingsStore.saveEchoTargetLanguage(this, checked)
        }

        val change = Slider.OnChangeListener { _, _, _ -> updateParamLabels() }
        val touch = object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                SettingsStore.saveRotateSeconds(this@MainActivity, slRotate.value.toInt())
                SettingsStore.saveStabIdleMs(this@MainActivity, slIdle.value.toInt())
                SettingsStore.saveStabMaxChars(this@MainActivity, slMaxChars.value.toInt())
            }
        }
        listOf(slRotate, slIdle, slMaxChars).forEach {
            it.addOnChangeListener(change)
            it.addOnSliderTouchListener(touch)
        }

        btnResetTranslate.setOnClickListener {
            SettingsStore.saveTargetLang(this, SettingsStore.DEFAULT_TARGET_LANG)
            SettingsStore.saveEchoTargetLanguage(this, true)
            SettingsStore.saveRotateSeconds(this, SettingsStore.DEFAULT_ROTATE_SECONDS)
            renderParamValues()
            toast("翻译参数已恢复默认，下次开始翻译时生效")
        }
        btnResetSubtitle.setOnClickListener {
            SettingsStore.saveStabIdleMs(this, SettingsStore.DEFAULT_STAB_IDLE_MS)
            SettingsStore.saveStabMaxChars(this, SettingsStore.DEFAULT_STAB_MAX_CHARS)
            SettingsStore.saveStyle(
                this,
                SettingsStore.DEFAULT_FONT_SP,
                SettingsStore.DEFAULT_BG_OPACITY,
                SettingsStore.DEFAULT_OVERLAY_LINES,
            )
            slFont.value = SettingsStore.DEFAULT_FONT_SP.toFloat()
            slOpacity.value = SettingsStore.DEFAULT_BG_OPACITY.toFloat()
            slLines.value = SettingsStore.DEFAULT_OVERLAY_LINES.toFloat()
            updateStyleLabels()
            renderParamValues()
            toast("字幕设置已恢复默认")
        }
    }

    private fun renderParamValues() {
        acTargetLang.setText(SettingsStore.targetLang(this), false)
        swEchoTarget.isChecked = SettingsStore.echoTargetLanguage(this)
        slRotate.value = SettingsStore.rotateSeconds(this).toFloat()
        slIdle.value = SettingsStore.stabIdleMs(this).toFloat()
        slMaxChars.value = SettingsStore.stabMaxChars(this).toFloat()
        updateParamLabels()
    }

    private fun updateParamLabels() {
        tvRotateVal.text = "连接主动轮换 ${slRotate.value.toInt()} 秒"
        val secs = slIdle.value.toInt() / 1000.0
        val secsText = if (secs % 1.0 == 0.0) secs.toInt().toString() else secs.toString()
        tvIdleVal.text = "静默 $secsText 秒转正"
        tvMaxCharsVal.text = "当前行最长 ${slMaxChars.value.toInt()} 字"
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

    /** 同传 / 视频 共用开停入口：已在跑则停止；否则按 mode 启动。 */
    private fun onModeToggle(mode: String) {
        if (StatusBus.serviceRunning) {
            if (StatusBus.captureMode.isNotEmpty() && StatusBus.captureMode != mode) {
                val other = if (StatusBus.captureMode == StatusBus.MODE_MIC) "同传" else "视频字幕"
                toast("当前正在运行「$other」，请先停止后再切换")
                return
            }
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
            return
        }
        startCapture(mode)
    }

    private fun prepareSessionSettings(): Boolean {
        SettingsStore.saveApiKeys(this, etApiKeys.text.toString())
        SettingsStore.saveBaseUrl(
            this,
            etBaseUrl.text.toString().trim().ifEmpty { SettingsStore.DEFAULT_BASE_URL },
        )
        saveSecondAiSettings()
        SettingsStore.saveTargetLang(this, acTargetLang.text.toString())
        // 固定资料若在表单里编辑过就顺手保存，避免开播用到没保存的内容时下次丢失
        val formProfile = collectStreamerProfile()
        if (formProfile.key.isNotBlank()) {
            StreamerProfileStore.save(this, formProfile)
        }
        // 组装本场提示词 = 固定资料 + 临时上下文，写给服务用
        SettingsStore.saveComposedPrompt(this, composeSessionPrompt())

        if (SettingsStore.apiKeyList(this).isEmpty()) {
            toast("请先填 Gemini API Key")
            bottomNav.selectedItemId = R.id.nav_settings
            openSettingsSub(R.id.pageSettingsTranslate, "翻译服务")
            return false
        }
        return true
    }

    private fun startCapture(mode: String) {
        pendingStartMode = mode
        if (!prepareSessionSettings()) return

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

        if (mode == StatusBus.MODE_VIDEO) {
            if (!Settings.canDrawOverlays(this)) {
                toast("请开启悬浮窗权限，开启后回到本页再点开始")
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                return
            }
            val mpm = getSystemService(MediaProjectionManager::class.java)
            projLauncher.launch(mpm.createScreenCaptureIntent())
            return
        }

        // 麦克风同传：不强制悬浮窗；有权限就顺带挂，没有就只在 App 内看字幕
        val i = Intent(this, CaptureService::class.java)
            .setAction(CaptureService.ACTION_START)
            .putExtra(CaptureService.EXTRA_MODE, StatusBus.MODE_MIC)
        startForegroundService(i)
    }

    private fun renderStatus() {
        val s = StatusBus
        val running = s.serviceRunning
        val mode = s.captureMode
        val conn = s.connState
        val level = s.audioLevelPct.coerceIn(0, 100)
        val micActive = running && mode == StatusBus.MODE_MIC
        val videoActive = running && mode == StatusBus.MODE_VIDEO

        fun heroText(active: Boolean): String = when {
            !active -> "○ 待开始"
            conn == "ready" -> "● 翻译中"
            conn.startsWith("error") -> "● 出错"
            else -> "● 准备连接"
        }

        fun subText(active: Boolean, idle: String): String = if (!active) {
            idle
        } else {
            buildString {
                append("连接 ").append(conn)
                if (s.currentKeyLabel.isNotEmpty()) append(" · ").append(s.currentKeyLabel)
                append(" · 已发送 ").append(s.chunksSent.get()).append(" 块")
            }
        }

        // 同传页
        tvInterpStatus.text = heroText(micActive)
        tvInterpSubStatus.text = when {
            running && !micActive -> "当前正在运行视频字幕，请先停止后再开同传"
            else -> subText(micActive, "麦克风实时同传")
        }
        tvInterpAudioLevel.text = if (micActive) "$level%" else "0%"
        pbInterpAudio.progress = if (micActive) level else 0
        if (micActive) {
            tvInterpZh.text = s.zhTail.ifBlank { "等待中文字幕…" }
            tvInterpJa.text = s.jaTail.ifBlank { "等待原文输入…" }
            if (s.transcriptPath.isNotEmpty()) {
                tvInterpTranscriptPath.text = "本场记录：${s.transcriptPath}"
            }
        } else if (!running) {
            // 空闲时保留上次路径提示也可以，但字幕回到占位
            if (s.zhTail.isBlank()) tvInterpZh.text = "等待中文字幕…"
            if (s.jaTail.isBlank()) tvInterpJa.text = "等待原文输入…"
        }
        btnInterpToggle.text = when {
            micActive -> "停止同传"
            running -> "开始同传"
            else -> "开始同传"
        }
        btnInterpToggle.isEnabled = !running || micActive

        // 视频页
        tvHeroStatus.text = heroText(videoActive)
        tvHeroSubStatus.text = when {
            running && !videoActive -> "当前正在运行同传，请先停止后再开视频字幕"
            else -> subText(videoActive, "尚未开始翻译")
        }
        tvAudioLevel.text = if (videoActive) "$level%" else "0%"
        pbAudio.progress = if (videoActive) level else 0
        if (videoActive) {
            tvLiveZh.text = s.zhTail.ifBlank { "等待中文字幕…" }
            tvLiveJa.text = s.jaTail.ifBlank { "等待日文输入…" }
            if (s.transcriptPath.isNotEmpty()) {
                tvTranscriptPath.text = "本场记录：${s.transcriptPath}"
            }
        } else if (!running) {
            if (s.zhTail.isBlank()) tvLiveZh.text = "等待中文字幕…"
            if (s.jaTail.isBlank()) tvLiveJa.text = "等待日文输入…"
        }
        btnToggle.text = when {
            videoActive -> "停止视频字幕"
            else -> "开始视频字幕"
        }
        btnToggle.isEnabled = !running || videoActive

        tvStatus.text = buildString {
            append(if (running) "● 运行中" else "○ 未运行")
            if (mode.isNotEmpty()) append("  模式: ").append(if (mode == StatusBus.MODE_MIC) "同传" else "视频")
            append("  连接: ").append(conn)
            if (s.currentKeyLabel.isNotEmpty()) append("  ").append(s.currentKeyLabel)
            append("  音量: ").append(level).append("%")
            append("  已发送: ").append(s.chunksSent.get()).append(" 块\n")
            if (s.transcriptPath.isNotEmpty()) append("记录: ").append(s.transcriptPath).append("\n")
            if (s.jaTail.isNotEmpty()) append("ja: …").append(s.jaTail).append("\n")
            if (s.zhTail.isNotEmpty()) append("zh: …").append(s.zhTail)
        }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
}
