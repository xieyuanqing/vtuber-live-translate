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
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout

class MainActivity : AppCompatActivity(), TranslationPlanBottomSheet.Listener {

    private companion object {
        const val STATE_PENDING_START_MODE = "pending_start_mode"
        const val STATE_PERMISSION_REQUESTED = "permission_requested"
        const val STATE_PENDING_PROMPT = "pending_prompt"
        const val STATE_PENDING_SOURCE = "pending_source"
        const val STATE_PENDING_TARGET = "pending_target"
        const val STATE_PENDING_SCENE = "pending_scene"
        const val STATE_PENDING_GLOSSARY = "pending_glossary"
        const val STATE_PENDING_TITLE = "pending_title"
        const val STATE_PENDING_CONTEXT = "pending_context"
        const val STATE_INTERPRETATION_CONTEXT = "interpretation_context"
        const val STATE_VIDEO_CONTEXT = "video_context"
        const val STATE_VIDEO_URL = "video_url"
    }

    // 壳子：底部 4 Tab
    private lateinit var rootLayout: View
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var pageContainer: View
    private lateinit var pageInterp: View
    private lateinit var pageVideo: View
    private lateinit var pageGlossary: View
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
    private lateinit var tvInterpTargetLanguageLabel: TextView
    private lateinit var tvInterpZh: TextView
    private lateinit var tvInterpJa: TextView
    private lateinit var tvInterpTranscriptPath: TextView
    private lateinit var acInterpSourceLang: MaterialAutoCompleteTextView
    private lateinit var acInterpTargetLang: MaterialAutoCompleteTextView

    // 视频页（原实时翻译：系统内录 + 悬浮字幕）
    private lateinit var tvHeroStatus: TextView
    private lateinit var tvHeroSubStatus: TextView
    private lateinit var tvAudioLevel: TextView
    private lateinit var pbAudio: ProgressBar
    private lateinit var btnToggle: Button
    private lateinit var tvVideoTargetLanguageLabel: TextView
    private lateinit var tvLiveZh: TextView
    private lateinit var tvLiveJa: TextView
    private lateinit var tvTranscriptPath: TextView
    private lateinit var acVideoSourceLang: MaterialAutoCompleteTextView
    private lateinit var acVideoTargetLang: MaterialAutoCompleteTextView

    // 通用术语库
    private lateinit var acGlossaryProfile: MaterialAutoCompleteTextView
    private lateinit var btnNewGlossary: Button
    private lateinit var btnDeleteGlossary: Button
    private lateinit var btnSaveGlossary: Button
    private lateinit var etGlossaryName: EditText
    private lateinit var acGlossaryCategory: MaterialAutoCompleteTextView
    private lateinit var etGlossaryDescription: EditText
    private lateinit var etGlossaryAliases: EditText
    private lateinit var etGlossaryTerms: EditText
    private lateinit var etGlossaryCorrections: EditText
    private lateinit var etGlossaryStyle: EditText

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

    private var glossaryProfiles: List<GlossaryProfile> = emptyList()
    private var selectedGlossaryId: String = ""
    private var interpretationSessionContext = ""
    private var videoSessionContext = ""
    private var videoSessionUrl = ""
    private var pendingSessionPrompt = ""
    private var pendingSessionSource = ""
    private var pendingSessionTarget = ""
    private var pendingSessionScene = ""
    private var pendingSessionGlossaryKey = ""
    private var pendingSessionTitle = ""
    private var pendingSessionContext = ""
    private var permRequested = false
    /** 权限回调后要启动的模式：video / mic */
    private var pendingStartMode: String = StatusBus.MODE_VIDEO
    private var syncingLanguageControls = false

    private data class LanguageControls(
        val sourceView: MaterialAutoCompleteTextView,
        val targetView: MaterialAutoCompleteTextView,
    )

    private val categorySuggestions = arrayOf("通用", "人物 / 专名", "游戏", "动漫", "技术", "其他")

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
                val i = captureStartIntent(StatusBus.MODE_VIDEO)
                    .putExtra(CaptureService.EXTRA_RESULT_CODE, res.resultCode)
                    .putExtra(CaptureService.EXTRA_RESULT_DATA, res.data)
                startForegroundService(i)
                consumeStartedSession(StatusBus.MODE_VIDEO)
            } else {
                toast("未获得屏幕捕获授权，无法内录")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingStartMode = savedInstanceState
            ?.getString(STATE_PENDING_START_MODE)
            ?.takeIf { it == StatusBus.MODE_MIC || it == StatusBus.MODE_VIDEO }
            ?: StatusBus.MODE_VIDEO
        permRequested = savedInstanceState?.getBoolean(STATE_PERMISSION_REQUESTED) ?: false
        pendingSessionPrompt = savedInstanceState?.getString(STATE_PENDING_PROMPT).orEmpty()
        pendingSessionSource = savedInstanceState?.getString(STATE_PENDING_SOURCE).orEmpty()
        pendingSessionTarget = savedInstanceState?.getString(STATE_PENDING_TARGET).orEmpty()
        pendingSessionScene = savedInstanceState?.getString(STATE_PENDING_SCENE).orEmpty()
        pendingSessionGlossaryKey = savedInstanceState?.getString(STATE_PENDING_GLOSSARY).orEmpty()
        pendingSessionTitle = savedInstanceState?.getString(STATE_PENDING_TITLE).orEmpty()
        pendingSessionContext = savedInstanceState?.getString(STATE_PENDING_CONTEXT).orEmpty()
        interpretationSessionContext = savedInstanceState?.getString(STATE_INTERPRETATION_CONTEXT).orEmpty()
        videoSessionContext = savedInstanceState?.getString(STATE_VIDEO_CONTEXT).orEmpty()
        videoSessionUrl = savedInstanceState?.getString(STATE_VIDEO_URL).orEmpty()
        setContentView(R.layout.activity_main)

        bindViews()
        applyWindowInsets()
        setupBottomNav()
        setupGlossaryPage()
        setupHistoryPage()
        setupSettings()
        setupStyleSliders()
        setupParamControls()
        setupFinalPlanUi()

        btnBattery.setOnClickListener { requestBatteryWhitelist() }
        btnToggle.setOnClickListener { onModeToggle(StatusBus.MODE_VIDEO) }
        btnInterpToggle.setOnClickListener { onModeToggle(StatusBus.MODE_MIC) }

        showPage(R.id.nav_interp)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_PENDING_START_MODE, pendingStartMode)
        outState.putBoolean(STATE_PERMISSION_REQUESTED, permRequested)
        outState.putString(STATE_PENDING_PROMPT, pendingSessionPrompt)
        outState.putString(STATE_PENDING_SOURCE, pendingSessionSource)
        outState.putString(STATE_PENDING_TARGET, pendingSessionTarget)
        outState.putString(STATE_PENDING_SCENE, pendingSessionScene)
        outState.putString(STATE_PENDING_GLOSSARY, pendingSessionGlossaryKey)
        outState.putString(STATE_PENDING_TITLE, pendingSessionTitle)
        outState.putString(STATE_PENDING_CONTEXT, pendingSessionContext)
        outState.putString(STATE_INTERPRETATION_CONTEXT, interpretationSessionContext)
        outState.putString(STATE_VIDEO_CONTEXT, videoSessionContext)
        outState.putString(STATE_VIDEO_URL, videoSessionUrl)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        syncLanguageControlsFromStore()
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
        pageGlossary = findViewById(R.id.pageGlossary)
        pageHistory = findViewById(R.id.pageHistory)
        pageSettings = findViewById(R.id.pageSettings)
        pageSettingsTranslate = findViewById(R.id.pageSettingsTranslate)
        pageSettingsSubtitle = findViewById(R.id.pageSettingsSubtitle)
        pageSettingsProfileAi = findViewById(R.id.pageSettingsProfileAi)
        pageSettingsDiagnostics = findViewById(R.id.pageSettingsDiagnostics)
        pageSettingsAbout = findViewById(R.id.pageSettingsAbout)
        settingsSubViews = listOf(
            pageGlossary,
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
        tvInterpTargetLanguageLabel = findViewById(R.id.tvInterpTargetLanguageLabel)
        tvInterpZh = findViewById(R.id.tvInterpZh)
        tvInterpJa = findViewById(R.id.tvInterpJa)
        tvInterpTranscriptPath = findViewById(R.id.tvInterpTranscriptPath)
        acInterpSourceLang = findViewById(R.id.acInterpSourceLang)
        acInterpTargetLang = findViewById(R.id.acInterpTargetLang)

        tvHeroStatus = findViewById(R.id.tvHeroStatus)
        tvHeroSubStatus = findViewById(R.id.tvHeroSubStatus)
        tvAudioLevel = findViewById(R.id.tvAudioLevel)
        pbAudio = findViewById(R.id.pbAudio)
        btnToggle = findViewById(R.id.btnToggle)
        tvVideoTargetLanguageLabel = findViewById(R.id.tvVideoTargetLanguageLabel)
        tvLiveZh = findViewById(R.id.tvLiveZh)
        tvLiveJa = findViewById(R.id.tvLiveJa)
        tvTranscriptPath = findViewById(R.id.tvTranscriptPath)
        acVideoSourceLang = findViewById(R.id.acVideoSourceLang)
        acVideoTargetLang = findViewById(R.id.acVideoTargetLang)

        acGlossaryProfile = findViewById(R.id.acGlossaryProfile)
        btnNewGlossary = findViewById(R.id.btnNewGlossary)
        btnDeleteGlossary = findViewById(R.id.btnDeleteGlossary)
        btnSaveGlossary = findViewById(R.id.btnSaveGlossary)
        etGlossaryName = findViewById(R.id.etGlossaryName)
        acGlossaryCategory = findViewById(R.id.acGlossaryCategory)
        etGlossaryDescription = findViewById(R.id.etGlossaryDescription)
        etGlossaryAliases = findViewById(R.id.etGlossaryAliases)
        etGlossaryTerms = findViewById(R.id.etGlossaryTerms)
        etGlossaryCorrections = findViewById(R.id.etGlossaryCorrections)
        etGlossaryStyle = findViewById(R.id.etGlossaryStyle)

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
        // 术语库作为设置二级页隐藏
        pageInterp.visibility = if (itemId == R.id.nav_interp) View.VISIBLE else View.GONE
        pageVideo.visibility = if (itemId == R.id.nav_video) View.VISIBLE else View.GONE
        pageHistory.visibility = if (itemId == R.id.nav_history) View.VISIBLE else View.GONE
        pageSettings.visibility = if (itemId == R.id.nav_settings) View.VISIBLE else View.GONE
        pageGlossary.visibility = View.GONE

        // 主页面标题由内容区承担，顶栏固定显示品牌，避免重复的「同传 / 同传」。
        toolbar.title = "流译"
        toolbar.setLogo(R.drawable.ic_brand_translate_24)
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
        toolbar.logo = null
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
        pageGlossary.visibility = View.GONE
        toolbar.title = "流译"
        toolbar.setLogo(R.drawable.ic_brand_translate_24)
        toolbar.navigationIcon = null
        bottomNav.visibility = View.VISIBLE
        if (bottomNav.selectedItemId != R.id.nav_settings) {
            bottomNav.selectedItemId = R.id.nav_settings
        }
        currentMainTabId = R.id.nav_settings
    }

    // ---------- 通用术语库 ----------

    private fun setupGlossaryPage() {
        acGlossaryCategory.setSimpleItems(categorySuggestions)
        reloadGlossaryProfiles()

        acGlossaryProfile.setOnItemClickListener { _, _, position, _ ->
            glossaryProfiles.getOrNull(position)?.let(::renderGlossaryProfile)
        }
        btnNewGlossary.setOnClickListener {
            selectedGlossaryId = ""
            renderGlossaryProfile(
                GlossaryProfile(id = "", name = "", category = "通用"),
            )
            acGlossaryProfile.setText("", false)
            etGlossaryName.requestFocus()
        }
        btnSaveGlossary.setOnClickListener {
            val name = etGlossaryName.text.toString().trim()
            if (name.isEmpty()) {
                toast("术语库名称不能为空")
                return@setOnClickListener
            }
            val saved = GlossaryStore.upsert(this, collectGlossaryProfile())
            selectedGlossaryId = saved.id
            reloadGlossaryProfiles(saved.id)
            toast("已保存术语库：${saved.name}")
        }
        btnDeleteGlossary.setOnClickListener {
            val id = selectedGlossaryId
            if (id.isEmpty()) {
                toast("当前是未保存的新术语库")
                return@setOnClickListener
            }
            GlossaryStore.delete(this, id)
            TranslationMode.entries.forEach { mode ->
                val plan = TranslationPlanStore.loadDraft(this, mode)
                if (plan.glossaryKey == id) {
                    TranslationPlanStore.saveDraft(this, plan.copy(glossaryKey = ""))
                    updatePlanSummary(mode)
                }
            }
            selectedGlossaryId = ""
            reloadGlossaryProfiles()
            toast("术语库已删除")
        }
    }

    private fun reloadGlossaryProfiles(selectId: String = selectedGlossaryId) {
        glossaryProfiles = GlossaryStore.list(this)
        acGlossaryProfile.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_list_item_1,
                glossaryProfiles.map { it.name },
            ),
        )
        val selected = glossaryProfiles.firstOrNull { it.id == selectId }
            ?: glossaryProfiles.firstOrNull()
        if (selected == null) {
            selectedGlossaryId = ""
            acGlossaryProfile.setText("", false)
            renderGlossaryProfile(GlossaryProfile(id = "", name = "", category = "通用"))
        } else {
            acGlossaryProfile.setText(selected.name, false)
            renderGlossaryProfile(selected)
        }
    }

    private fun renderGlossaryProfile(profile: GlossaryProfile) {
        selectedGlossaryId = profile.id
        etGlossaryName.setText(profile.name)
        acGlossaryCategory.setText(profile.category, false)
        etGlossaryDescription.setText(profile.description)
        etGlossaryAliases.setText(profile.aliases.joinToString("\n"))
        etGlossaryTerms.setText(profile.terms.joinToString("\n"))
        etGlossaryCorrections.setText(profile.corrections.joinToString("\n"))
        etGlossaryStyle.setText(profile.style)
        btnDeleteGlossary.isEnabled = profile.id.isNotBlank()
    }

    private fun collectGlossaryProfile(): GlossaryProfile = GlossaryProfile(
        id = selectedGlossaryId,
        name = etGlossaryName.text.toString(),
        category = acGlossaryCategory.text.toString(),
        description = etGlossaryDescription.text.toString(),
        aliases = parseLines(etGlossaryAliases.text.toString()),
        terms = parseLines(etGlossaryTerms.text.toString()),
        corrections = parseLines(etGlossaryCorrections.text.toString()),
        style = etGlossaryStyle.text.toString(),
    )

    private fun parseLines(raw: String): List<String> = raw
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .toList()

    private fun consumeStartedSession(captureMode: String) {
        when (promptMode(captureMode)) {
            TranslationMode.INTERPRETATION -> interpretationSessionContext = ""
            TranslationMode.VIDEO -> {
                videoSessionContext = ""
                videoSessionUrl = ""
            }
        }
        pendingSessionPrompt = ""
        pendingSessionSource = ""
        pendingSessionTarget = ""
        pendingSessionScene = ""
        pendingSessionGlossaryKey = ""
        pendingSessionTitle = ""
        pendingSessionContext = ""
    }

    private fun currentSessionContext(mode: TranslationMode): SessionPromptContext = SessionPromptContext(
        manualContext = when (mode) {
            TranslationMode.INTERPRETATION -> interpretationSessionContext
            TranslationMode.VIDEO -> videoSessionContext
        },
    )

    private fun composeSessionPrompt(mode: TranslationMode): String {
        val plan = TranslationPlanStore.loadDraft(this, mode)
        return PromptBuilder.build(
            glossary = GlossaryStore.find(this, plan.glossaryKey),
            context = currentSessionContext(mode),
            plan = plan,
        )
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
                ).apply { bottomMargin = resources.getDimensionPixelSize(R.dimen.space_8) }
                isAllCaps = false
                minHeight = resources.getDimensionPixelSize(R.dimen.touch_target)
                cornerRadius = resources.getDimensionPixelSize(R.dimen.field_radius)
                insetTop = 0
                insetBottom = 0
                backgroundTintList = getColorStateList(R.color.surface_white)
                setTextColor(getColor(R.color.text_primary))
                val modeLabel = item.mode.label
                val direction = "${TranslationLanguageCatalog.source(item.sourceLanguageCode).label} → " +
                    TranslationLanguageCatalog.target(item.targetLanguageCode).label
                val summary = item.summary.trim().replace('\n', ' ').take(72)
                text = buildString {
                    append(item.title)
                    append("\n").append(modeLabel).append(" · ").append(direction)
                    append(" · ").append(HistoryStore.formatDuration(item.durationMs))
                    append("\n").append(HistoryStore.formatTime(item.updatedAt))
                    if (summary.isNotEmpty()) append("\n").append(summary)
                }
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
        rowSetScenes.setOnClickListener { openSettingsSub(R.id.pageGlossary, "术语库") }
        rowSetProfileAi.setOnClickListener { openSettingsSub(R.id.pageSettingsProfileAi, "内容分析 AI") }
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

    private fun setupFinalPlanUi() {
        findViewById<View>(R.id.cardInterpPlan).setOnClickListener {
            TranslationPlanBottomSheet.newInstance(
                TranslationMode.INTERPRETATION,
                interpretationSessionContext,
            ).also { sheet ->
                sheet.listener = this
                sheet.show(supportFragmentManager, "plan_interpretation")
            }
        }
        findViewById<View>(R.id.cardVideoPlan).setOnClickListener {
            TranslationPlanBottomSheet.newInstance(
                TranslationMode.VIDEO,
                videoSessionContext,
                videoSessionUrl,
            ).also { sheet ->
                sheet.listener = this
                sheet.show(supportFragmentManager, "plan_video")
            }
        }
        updatePlanSummary(TranslationMode.INTERPRETATION)
        updatePlanSummary(TranslationMode.VIDEO)
    }

    override fun onTranslationPlanApplied(
        mode: TranslationMode,
        plan: TranslationPlan,
        sessionContext: String,
        videoUrl: String,
    ) {
        when (mode) {
            TranslationMode.INTERPRETATION -> {
                interpretationSessionContext = sessionContext
                renderModeLanguageControls(
                    mode,
                    LanguageControls(acInterpSourceLang, acInterpTargetLang),
                )
            }
            TranslationMode.VIDEO -> {
                videoSessionContext = sessionContext
                videoSessionUrl = videoUrl
                renderModeLanguageControls(
                    mode,
                    LanguageControls(acVideoSourceLang, acVideoTargetLang),
                )
            }
        }
        updatePlanSummary(mode)
    }

    private fun updatePlanSummary(mode: TranslationMode) {
        val plan = TranslationPlanStore.loadDraft(this, mode)
        val summary = "${plan.scene.label} · ${plan.directionLabel}"
        val viewId = if (mode == TranslationMode.INTERPRETATION) {
            R.id.tvInterpPlanSummary
        } else {
            R.id.tvVideoPlanSummary
        }
        findViewById<TextView>(viewId)?.text = summary
    }

    // ---------- 翻译参数 / 断句参数 ----------

    private fun bindModeLanguageControls(
        mode: TranslationMode,
        controls: LanguageControls,
    ) {
        val sourceLabels = TranslationLanguageCatalog.sources.map { it.label }.toTypedArray()
        val targetLabels = TranslationLanguageCatalog.targets.map { it.label }.toTypedArray()
        controls.sourceView.setSimpleItems(sourceLabels)
        controls.targetView.setSimpleItems(targetLabels)
        controls.sourceView.setOnItemClickListener { _, _, position, _ ->
            val code = TranslationLanguageCatalog.sources.getOrNull(position)?.code
                ?: return@setOnItemClickListener
            val current = TranslationPlanStore.loadDraft(this, mode)
            TranslationPlanStore.saveDraft(this, current.copy(sourceLanguageCode = code))
            renderModeLanguageControls(mode, controls)
        }
        controls.targetView.setOnItemClickListener { _, _, position, _ ->
            val code = TranslationLanguageCatalog.targets.getOrNull(position)?.code
                ?: return@setOnItemClickListener
            val current = TranslationPlanStore.loadDraft(this, mode)
            TranslationPlanStore.saveDraft(this, current.copy(targetLanguageCode = code))
            renderModeLanguageControls(mode, controls)
        }
        renderModeLanguageControls(mode, controls)
    }

    private fun renderModeLanguageControls(
        mode: TranslationMode,
        controls: LanguageControls,
    ) {
        val plan = TranslationPlanStore.loadDraft(this, mode)
        controls.sourceView.setText(
            TranslationLanguageCatalog.source(plan.sourceLanguageCode).label,
            false,
        )
        controls.targetView.setText(
            TranslationLanguageCatalog.target(plan.targetLanguageCode).label,
            false,
        )
        val targetLabel = TranslationLanguageCatalog.target(plan.targetLanguageCode).label
        if (mode == TranslationMode.INTERPRETATION) {
            tvInterpTargetLanguageLabel.text = "目标译文 · $targetLabel"
        } else {
            tvVideoTargetLanguageLabel.text = "目标译文 · $targetLabel"
        }
        updatePlanSummary(mode)
    }

    private fun setupParamControls() {
        bindModeLanguageControls(
            TranslationMode.INTERPRETATION,
            LanguageControls(acInterpSourceLang, acInterpTargetLang),
        )
        bindModeLanguageControls(
            TranslationMode.VIDEO,
            LanguageControls(acVideoSourceLang, acVideoTargetLang),
        )

        renderParamValues()

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
            TranslationPlanStore.resetDraft(this, TranslationMode.INTERPRETATION)
            TranslationPlanStore.resetDraft(this, TranslationMode.VIDEO)
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

    private fun syncLanguageControlsFromStore() {
        renderModeLanguageControls(
            TranslationMode.INTERPRETATION,
            LanguageControls(acInterpSourceLang, acInterpTargetLang),
        )
        renderModeLanguageControls(
            TranslationMode.VIDEO,
            LanguageControls(acVideoSourceLang, acVideoTargetLang),
        )
    }

    private fun renderParamValues() {
        syncLanguageControlsFromStore()
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

    private fun promptMode(captureMode: String): TranslationMode =
        if (captureMode == StatusBus.MODE_MIC) TranslationMode.INTERPRETATION else TranslationMode.VIDEO

    private fun prepareSessionSettings(captureMode: String): Boolean {
        val mode = promptMode(captureMode)
        SettingsStore.saveApiKeys(this, etApiKeys.text.toString())
        SettingsStore.saveBaseUrl(
            this,
            etBaseUrl.text.toString().trim().ifEmpty { SettingsStore.DEFAULT_BASE_URL },
        )
        saveSecondAiSettings()
        val plan = TranslationPlanStore.loadDraft(this, mode)
        if (plan.scenePresetId == "custom" && plan.customSceneInstruction.isBlank()) {
            toast("请在翻译方案中填写自定义场景要求")
            return false
        }
        if (SettingsStore.apiKeyList(this).isEmpty()) {
            toast("请先填 Gemini API Key")
            bottomNav.selectedItemId = R.id.nav_settings
            openSettingsSub(R.id.pageSettingsTranslate, "翻译服务")
            return false
        }

        pendingSessionPrompt = composeSessionPrompt(mode)
        pendingSessionSource = plan.sourceLanguageCode
        pendingSessionTarget = plan.targetLanguageCode
        pendingSessionScene = plan.scenePresetId
        pendingSessionGlossaryKey = plan.glossaryKey
        pendingSessionContext = currentSessionContext(mode).manualContext.trim()
        pendingSessionTitle = when (mode) {
            TranslationMode.INTERPRETATION -> "同传记录"
            TranslationMode.VIDEO -> "视频翻译"
        }
        return true
    }

    private fun captureStartIntent(captureMode: String): Intent =
        Intent(this, CaptureService::class.java)
            .setAction(CaptureService.ACTION_START)
            .putExtra(CaptureService.EXTRA_MODE, captureMode)
            .putExtra(CaptureService.EXTRA_SESSION_PROMPT, pendingSessionPrompt)
            .putExtra(CaptureService.EXTRA_SOURCE_LANGUAGE, pendingSessionSource)
            .putExtra(CaptureService.EXTRA_TARGET_LANGUAGE, pendingSessionTarget)
            .putExtra(CaptureService.EXTRA_SCENE_PRESET, pendingSessionScene)
            .putExtra(CaptureService.EXTRA_GLOSSARY_KEY, pendingSessionGlossaryKey)
            .putExtra(CaptureService.EXTRA_SESSION_TITLE, pendingSessionTitle)
            .putExtra(CaptureService.EXTRA_SESSION_CONTEXT, pendingSessionContext)

    private fun startCapture(mode: String) {
        val reusePendingSnapshot = permRequested &&
            pendingStartMode == mode &&
            pendingSessionPrompt.isNotBlank()
        pendingStartMode = mode
        if (!reusePendingSnapshot && !prepareSessionSettings(mode)) return

        val missingAudioPermission =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        if (missingAudioPermission) {
            if (permRequested) {
                permRequested = false
                toast("缺少录音权限，请在系统设置中授予")
                return
            }
            val request = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (
                Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                request += Manifest.permission.POST_NOTIFICATIONS
            }
            permRequested = true
            permLauncher.launch(request.toTypedArray())
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

        // 麦克风同传：不强制悬浮窗；有权限就顺带挂，没有就只在 App 内看字幕。
        startForegroundService(captureStartIntent(StatusBus.MODE_MIC))
        consumeStartedSession(StatusBus.MODE_MIC)
    }

    private fun renderStatus() {
        val s = StatusBus
        val running = s.serviceRunning
        val mode = s.captureMode
        val conn = s.connState
        val level = s.audioLevelPct.coerceIn(0, 100)

        listOf(
            LanguageControls(acInterpSourceLang, acInterpTargetLang),
            LanguageControls(acVideoSourceLang, acVideoTargetLang),
        ).forEach { controls ->
            controls.sourceView.isEnabled = !running
            controls.targetView.isEnabled = !running
        }


        val micActive = running && mode == StatusBus.MODE_MIC
        val videoActive = running && mode == StatusBus.MODE_VIDEO
        val sessionSnapshot = s.sessionSnapshot()
        val translatedSnapshot = buildList {
            addAll(sessionSnapshot.confirmedTranslations.takeLast(6))
            sessionSnapshot.currentTranslation.takeIf { it.isNotBlank() }?.let(::add)
        }.joinToString("\n")
        val sourceSnapshot = sessionSnapshot.sourceTail

        fun heroText(active: Boolean): String = when {
            !active -> "○ 待开始"
            s.paused -> "Ⅱ 已暂停"
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
            tvInterpZh.text = translatedSnapshot.ifBlank { "等待译文…" }
            tvInterpJa.text = sourceSnapshot.ifBlank { "等待原文输入…" }
            if (s.transcriptPath.isNotEmpty()) {
                tvInterpTranscriptPath.text = "本场记录：${s.transcriptPath}"
            }
        } else if (!running) {
            // 空闲时保留上次路径提示也可以，但字幕回到占位
            if (s.zhTail.isBlank()) tvInterpZh.text = "等待译文…"
            if (s.jaTail.isBlank()) tvInterpJa.text = "等待原文输入…"
        }
        btnInterpToggle.text = when {
            micActive -> "停止同传"
            running -> "开始同传"
            else -> "开始同传"
        }
        btnInterpToggle.backgroundTintList = getColorStateList(if (micActive) R.color.error else R.color.brand)
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
            tvLiveZh.text = translatedSnapshot.ifBlank { "等待译文…" }
            tvLiveJa.text = sourceSnapshot.ifBlank { "等待原文输入…" }
            if (s.transcriptPath.isNotEmpty()) {
                tvTranscriptPath.text = "本场记录：${s.transcriptPath}"
            }
        } else if (!running) {
            if (s.zhTail.isBlank()) tvLiveZh.text = "等待译文…"
            if (s.jaTail.isBlank()) tvLiveJa.text = "等待原文输入…"
        }
        btnToggle.text = when {
            videoActive -> "停止视频字幕"
            else -> "开始视频字幕"
        }
        btnToggle.backgroundTintList = getColorStateList(if (videoActive) R.color.error else R.color.brand)
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
