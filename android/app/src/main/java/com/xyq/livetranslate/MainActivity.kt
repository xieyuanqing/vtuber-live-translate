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
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout

class MainActivity : AppCompatActivity() {

    private companion object {
        const val STATE_PENDING_START_MODE = "pending_start_mode"
        const val STATE_PENDING_CREDENTIAL_MODE = "pending_credential_mode"
        const val STATE_PERMISSION_REQUESTED = "permission_requested"
        const val STATE_PENDING_PROMPT = "pending_prompt"
        const val STATE_PENDING_SOURCE = "pending_source"
        const val STATE_PENDING_TARGET = "pending_target"
        const val STATE_PENDING_SCENE = "pending_scene"
        const val STATE_PENDING_SCENE_LABEL = "pending_scene_label"
        const val STATE_PENDING_TITLE = "pending_title"
        const val STATE_PENDING_CONTEXT = "pending_context"
        const val STATE_INTERPRETATION_CONTEXT = "interpretation_context"
        const val STATE_VIDEO_CONTEXT = "video_context"
        const val STATE_VIDEO_URL = "video_url"
        const val STATE_MAIN_TAB = "main_tab"
        const val STATE_SETTINGS_SUB = "settings_sub"
        const val STATE_SETTINGS_RETURN_TAB = "settings_return_tab"
        const val STATE_SCENE_LIBRARY_MODE = "scene_library_mode"
    }

    // 壳子：底部 4 Tab
    private lateinit var rootLayout: View
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var pageContainer: View
    private lateinit var pageInterp: View
    private lateinit var pageVideo: View
    private lateinit var pageHistory: View
    private lateinit var pageHistoryDetail: View
    private lateinit var pageSettings: View
    private var currentMainTabId = R.id.nav_interp

    // 设置二级页（0 = 设置首页）
    private var settingsSubId = 0
    private var settingsReturnTabId = R.id.nav_settings
    private lateinit var pageSettingsTranslate: View
    private lateinit var pageSettingsSubtitle: View
    private lateinit var pageSettingsProfileAi: View
    private lateinit var pageSettingsDiagnostics: View
    private lateinit var pageSettingsAbout: View
    private lateinit var pageSceneLibrary: View
    private lateinit var settingsSubViews: List<View>
    private lateinit var rowSetTranslate: View
    private lateinit var rowSetSubtitle: View
    private lateinit var rowSetSceneLibrary: View
    private lateinit var rowSetProfileAi: View
    private lateinit var rowSetDiagnostics: View
    private lateinit var rowSetAbout: View

    // 场景库（唯一的可复用配置库）
    private lateinit var toggleSceneLibraryMode: MaterialButtonToggleGroup
    private lateinit var btnSceneLibraryInterp: MaterialButton
    private lateinit var btnSceneLibraryVideo: MaterialButton
    private lateinit var sceneLibraryList: LinearLayout
    private lateinit var btnResetSceneLibrary: MaterialButton
    private lateinit var fabNewScene: ExtendedFloatingActionButton
    private var sceneLibraryMode: TranslationMode = TranslationMode.INTERPRETATION

    // 同传页（麦克风）
    private lateinit var interpIdleContent: View
    private lateinit var interpRunningContent: View
    private lateinit var chipGroupInterpHomeScenes: ChipGroup
    private lateinit var tvInterpStatus: TextView
    private lateinit var tvInterpSubStatus: TextView
    private lateinit var tvInterpRunningStatus: TextView
    private lateinit var viewInterpRunningStatusDot: View
    private lateinit var tvInterpElapsed: TextView
    private lateinit var tvInterpRunningMeta: TextView
    private lateinit var interpConfirmedList: LinearLayout
    private lateinit var tvInterpAudioLevel: TextView
    private lateinit var pbInterpAudio: ProgressBar
    private lateinit var btnInterpToggle: Button
    private lateinit var btnInterpStop: MaterialButton
    private lateinit var tvInterpTargetLanguageLabel: TextView
    private lateinit var tvInterpZh: TextView
    private lateinit var tvInterpJa: TextView
    private lateinit var tvInterpTranscriptPath: TextView
    private lateinit var acInterpSourceLang: MaterialAutoCompleteTextView
    private lateinit var acInterpTargetLang: MaterialAutoCompleteTextView
    private lateinit var etInterpSessionContext: com.google.android.material.textfield.TextInputEditText
    private lateinit var btnInterpAnalyzeContext: com.google.android.material.button.MaterialButton
    private lateinit var tvInterpAnalyzeStatus: TextView

    // 视频页（原实时翻译：系统内录 + 悬浮字幕）
    private lateinit var videoIdleContent: View
    private lateinit var videoRunningContent: View
    private lateinit var chipGroupVideoHomeScenes: ChipGroup
    private lateinit var tvHeroStatus: TextView
    private lateinit var viewVideoRunningStatusDot: View
    private lateinit var tvHeroSubStatus: TextView
    private lateinit var tvVideoElapsed: TextView
    private lateinit var tvVideoRunningMeta: TextView
    private lateinit var videoConfirmedList: LinearLayout
    private lateinit var tvAudioLevel: TextView
    private lateinit var pbAudio: ProgressBar
    private lateinit var btnToggle: Button
    private lateinit var btnVideoStop: MaterialButton
    private lateinit var rowOverlayPermission: View
    private lateinit var viewOverlayPermissionDot: View
    private lateinit var tvOverlayPermissionStatus: TextView
    private lateinit var btnOverlayPermissionSettings: View
    private lateinit var tvVideoTargetLanguageLabel: TextView
    private lateinit var tvLiveZh: TextView
    private lateinit var tvLiveJa: TextView
    private lateinit var tvTranscriptPath: TextView
    private lateinit var acVideoSourceLang: MaterialAutoCompleteTextView
    private lateinit var acVideoTargetLang: MaterialAutoCompleteTextView
    private lateinit var etVideoSessionUrl: com.google.android.material.textfield.TextInputEditText
    private lateinit var etVideoSessionContext: com.google.android.material.textfield.TextInputEditText
    private lateinit var btnVideoAnalyzeContext: com.google.android.material.button.MaterialButton
    private lateinit var tvVideoAnalyzeStatus: TextView

    // 历史页
    private lateinit var btnRefreshHistory: Button
    private lateinit var etHistorySearch: EditText
    private lateinit var chipHistoryAll: Chip
    private lateinit var chipHistoryInterp: Chip
    private lateinit var chipHistoryVideo: Chip
    private lateinit var tvHistoryEmpty: TextView
    private lateinit var historyList: LinearLayout
    private lateinit var cardHistoryDetail: View
    private lateinit var tvHistoryTitle: TextView
    private lateinit var btnCopyHistory: Button
    private lateinit var tvHistoryDetail: TextView
    private lateinit var tvHistoryDetailMeta: TextView
    private lateinit var tvHistoryDetailContext: TextView
    private lateinit var tvHistoryDetailEmpty: TextView
    private lateinit var historyDetailSegments: LinearLayout
    private var allHistoryItems: List<HistoryStore.HistoryItem> = emptyList()
    private var historyModeFilter: TranslationMode? = null

    // 设置页
    private lateinit var etApiKeys: EditText
    private lateinit var etBaseUrl: EditText
    private lateinit var swFriendGateway: MaterialSwitch
    private lateinit var etFriendInviteCode: EditText
    private lateinit var tvFriendGatewayStatus: TextView
    private lateinit var btnBindFriendGateway: Button
    private lateinit var btnClearFriendGateway: Button
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

    private var pendingSessionPrompt = ""
    private var pendingSessionSource = ""
    private var pendingSessionTarget = ""
    private var pendingSessionScene = ""
    private var pendingSessionSceneLabel = ""
    private var pendingSessionTitle = ""
    private var pendingSessionContext = ""
    private var permRequested = false
    private var latestInterpAnalysisRequestId = ""
    private var latestVideoAnalysisRequestId = ""
    /** 权限回调后要启动的模式：video / mic */
    private var pendingStartMode: String = StatusBus.MODE_VIDEO
    /** 权限回调期间冻结的凭据模式：只传模式，不传 Token。 */
    private var pendingCredentialMode: String = FriendGatewayStore.MODE_PERSONAL
    private var syncingLanguageControls = false
    private var syncingFriendGatewayUi = false
    private lateinit var friendBindingViewModel: FriendGatewayBindingViewModel

    private data class LanguageControls(
        val sourceView: MaterialAutoCompleteTextView,
        val targetView: MaterialAutoCompleteTextView,
    )

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
        pendingCredentialMode = savedInstanceState
            ?.getString(STATE_PENDING_CREDENTIAL_MODE)
            ?.takeIf {
                it == FriendGatewayStore.MODE_PERSONAL || it == FriendGatewayStore.MODE_FRIEND
            }
            ?: FriendGatewayStore.MODE_PERSONAL
        permRequested = savedInstanceState?.getBoolean(STATE_PERMISSION_REQUESTED) ?: false
        pendingSessionPrompt = savedInstanceState?.getString(STATE_PENDING_PROMPT).orEmpty()
        pendingSessionSource = savedInstanceState?.getString(STATE_PENDING_SOURCE).orEmpty()
        pendingSessionTarget = savedInstanceState?.getString(STATE_PENDING_TARGET).orEmpty()
        pendingSessionScene = savedInstanceState?.getString(STATE_PENDING_SCENE).orEmpty()
        pendingSessionSceneLabel = savedInstanceState?.getString(STATE_PENDING_SCENE_LABEL).orEmpty()
        pendingSessionTitle = savedInstanceState?.getString(STATE_PENDING_TITLE).orEmpty()
        pendingSessionContext = savedInstanceState?.getString(STATE_PENDING_CONTEXT).orEmpty()
        val mainTabs = setOf(R.id.nav_interp, R.id.nav_video, R.id.nav_history, R.id.nav_settings)
        val restorableSubPages = setOf(
            R.id.pageSettingsTranslate,
            R.id.pageSettingsSubtitle,
            R.id.pageSettingsProfileAi,
            R.id.pageSettingsDiagnostics,
            R.id.pageSettingsAbout,
            R.id.pageSceneLibrary,
        )
        val restoredMainTabId = savedInstanceState?.getInt(STATE_MAIN_TAB)
            ?.takeIf { it in mainTabs }
            ?: R.id.nav_interp
        val restoredSettingsSubId = savedInstanceState?.getInt(STATE_SETTINGS_SUB)
            ?.takeIf { it in restorableSubPages }
            ?: 0
        settingsReturnTabId = savedInstanceState?.getInt(STATE_SETTINGS_RETURN_TAB)
            ?.takeIf { it in mainTabs }
            ?: R.id.nav_settings
        sceneLibraryMode = savedInstanceState?.getString(STATE_SCENE_LIBRARY_MODE)
            ?.let { key -> TranslationMode.entries.firstOrNull { it.storageKey == key } }
            ?: TranslationMode.INTERPRETATION
        TranslationPlanStore.migrateLegacySavedPlans(this)
        setContentView(R.layout.activity_main)

        bindViews()
        friendBindingViewModel = ViewModelProvider(this)[FriendGatewayBindingViewModel::class.java]
        friendBindingViewModel.state.observe(this, ::renderFriendBindingState)
        applyWindowInsets()
        setupBottomNav()
        setupHistoryPage()
        setupSettings()
        setupSceneLibraryPage()
        setupStyleSliders()
        setupParamControls()
        setupFinalPlanUi()
        setupSessionContextUi()

        // 恢复本场临时上下文到主页输入框（不进方案库）
        etInterpSessionContext.setText(
            savedInstanceState?.getString(STATE_INTERPRETATION_CONTEXT).orEmpty(),
        )
        etVideoSessionContext.setText(
            savedInstanceState?.getString(STATE_VIDEO_CONTEXT).orEmpty(),
        )
        etVideoSessionUrl.setText(
            savedInstanceState?.getString(STATE_VIDEO_URL).orEmpty(),
        )

        btnBattery.setOnClickListener { requestBatteryWhitelist() }
        btnToggle.setOnClickListener { onModeToggle(StatusBus.MODE_VIDEO) }
        btnVideoStop.setOnClickListener { onModeToggle(StatusBus.MODE_VIDEO) }
        btnInterpToggle.setOnClickListener { onModeToggle(StatusBus.MODE_MIC) }
        btnInterpStop.setOnClickListener { onModeToggle(StatusBus.MODE_MIC) }
        val openOverlaySettings = View.OnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        rowOverlayPermission.setOnClickListener(openOverlaySettings)
        btnOverlayPermissionSettings.setOnClickListener(openOverlaySettings)

        if (bottomNav.selectedItemId != restoredMainTabId) {
            bottomNav.selectedItemId = restoredMainTabId
        } else {
            showPage(restoredMainTabId)
        }
        if (restoredSettingsSubId != 0) {
            val title = when (restoredSettingsSubId) {
                R.id.pageSettingsTranslate -> "翻译服务"
                R.id.pageSettingsSubtitle -> "字幕与悬浮窗"
                R.id.pageSettingsProfileAi -> "内容分析 AI"
                R.id.pageSettingsDiagnostics -> "诊断"
                R.id.pageSettingsAbout -> "关于"
                R.id.pageSceneLibrary -> "场景库"
                else -> "流译"
            }
            openSettingsSub(restoredSettingsSubId, title, settingsReturnTabId)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_PENDING_START_MODE, pendingStartMode)
        outState.putString(STATE_PENDING_CREDENTIAL_MODE, pendingCredentialMode)
        outState.putBoolean(STATE_PERMISSION_REQUESTED, permRequested)
        outState.putString(STATE_PENDING_PROMPT, pendingSessionPrompt)
        outState.putString(STATE_PENDING_SOURCE, pendingSessionSource)
        outState.putString(STATE_PENDING_TARGET, pendingSessionTarget)
        outState.putString(STATE_PENDING_SCENE, pendingSessionScene)
        outState.putString(STATE_PENDING_SCENE_LABEL, pendingSessionSceneLabel)
        outState.putString(STATE_PENDING_TITLE, pendingSessionTitle)
        outState.putString(STATE_PENDING_CONTEXT, pendingSessionContext)
        outState.putString(STATE_INTERPRETATION_CONTEXT, etInterpSessionContext.text?.toString().orEmpty())
        outState.putString(STATE_VIDEO_CONTEXT, etVideoSessionContext.text?.toString().orEmpty())
        outState.putString(STATE_VIDEO_URL, etVideoSessionUrl.text?.toString().orEmpty())
        outState.putInt(STATE_MAIN_TAB, currentMainTabId)
        outState.putInt(STATE_SETTINGS_SUB, settingsSubId)
        outState.putInt(STATE_SETTINGS_RETURN_TAB, settingsReturnTabId)
        outState.putString(STATE_SCENE_LIBRARY_MODE, sceneLibraryMode.storageKey)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        syncLanguageControlsFromStore()
        ui.removeCallbacks(refresh)
        ui.post(refresh)
    }

    override fun onPause() {
        if (::etSecondAiKey.isInitialized) saveSecondAiSettings()
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
        pageHistory = findViewById(R.id.pageHistory)
        pageHistoryDetail = findViewById(R.id.pageHistoryDetail)
        pageSettings = findViewById(R.id.pageSettings)
        pageSettingsTranslate = findViewById(R.id.pageSettingsTranslate)
        pageSettingsSubtitle = findViewById(R.id.pageSettingsSubtitle)
        pageSettingsProfileAi = findViewById(R.id.pageSettingsProfileAi)
        pageSettingsDiagnostics = findViewById(R.id.pageSettingsDiagnostics)
        pageSettingsAbout = findViewById(R.id.pageSettingsAbout)
        pageSceneLibrary = findViewById(R.id.pageSceneLibrary)
        settingsSubViews = listOf(
            pageHistoryDetail, pageSceneLibrary,
            pageSettingsTranslate, pageSettingsSubtitle, pageSettingsProfileAi,
            pageSettingsDiagnostics, pageSettingsAbout,
        )
        rowSetTranslate = findViewById(R.id.rowSetTranslate)
        rowSetSubtitle = findViewById(R.id.rowSetSubtitle)
        rowSetSceneLibrary = findViewById(R.id.rowSetSceneLibrary)
        rowSetProfileAi = findViewById(R.id.rowSetProfileAi)
        rowSetDiagnostics = findViewById(R.id.rowSetDiagnostics)
        rowSetAbout = findViewById(R.id.rowSetAbout)

        toggleSceneLibraryMode = findViewById(R.id.toggleSceneLibraryMode)
        btnSceneLibraryInterp = findViewById(R.id.btnSceneLibraryInterp)
        btnSceneLibraryVideo = findViewById(R.id.btnSceneLibraryVideo)
        sceneLibraryList = findViewById(R.id.sceneLibraryList)
        btnResetSceneLibrary = findViewById(R.id.btnResetSceneLibrary)
        fabNewScene = findViewById(R.id.fabNewScene)

        interpIdleContent = findViewById(R.id.interpIdleContent)
        interpRunningContent = findViewById(R.id.interpRunningContent)
        chipGroupInterpHomeScenes = findViewById(R.id.chipGroupInterpHomeScenes)
        tvInterpStatus = findViewById(R.id.tvInterpStatus)
        tvInterpSubStatus = findViewById(R.id.tvInterpSubStatus)
        tvInterpRunningStatus = findViewById(R.id.tvInterpRunningStatus)
        viewInterpRunningStatusDot = findViewById(R.id.viewInterpRunningStatusDot)
        tvInterpElapsed = findViewById(R.id.tvInterpElapsed)
        tvInterpRunningMeta = findViewById(R.id.tvInterpRunningMeta)
        interpConfirmedList = findViewById(R.id.interpConfirmedList)
        tvInterpAudioLevel = findViewById(R.id.tvInterpAudioLevel)
        pbInterpAudio = findViewById(R.id.pbInterpAudio)
        btnInterpToggle = findViewById(R.id.btnInterpToggle)
        btnInterpStop = findViewById(R.id.btnInterpStop)
        tvInterpTargetLanguageLabel = findViewById(R.id.tvInterpTargetLanguageLabel)
        tvInterpZh = findViewById(R.id.tvInterpZh)
        tvInterpJa = findViewById(R.id.tvInterpJa)
        tvInterpTranscriptPath = findViewById(R.id.tvInterpTranscriptPath)
        acInterpSourceLang = findViewById(R.id.acInterpSourceLang)
        acInterpTargetLang = findViewById(R.id.acInterpTargetLang)
        etInterpSessionContext = findViewById(R.id.etInterpSessionContext)
        btnInterpAnalyzeContext = findViewById(R.id.btnInterpAnalyzeContext)
        tvInterpAnalyzeStatus = findViewById(R.id.tvInterpAnalyzeStatus)

        videoIdleContent = findViewById(R.id.videoIdleContent)
        videoRunningContent = findViewById(R.id.videoRunningContent)
        chipGroupVideoHomeScenes = findViewById(R.id.chipGroupVideoHomeScenes)
        tvHeroStatus = findViewById(R.id.tvHeroStatus)
        viewVideoRunningStatusDot = findViewById(R.id.viewVideoRunningStatusDot)
        tvHeroSubStatus = findViewById(R.id.tvHeroSubStatus)
        tvVideoElapsed = findViewById(R.id.tvVideoElapsed)
        tvVideoRunningMeta = findViewById(R.id.tvVideoRunningMeta)
        videoConfirmedList = findViewById(R.id.videoConfirmedList)
        tvAudioLevel = findViewById(R.id.tvAudioLevel)
        pbAudio = findViewById(R.id.pbAudio)
        btnToggle = findViewById(R.id.btnToggle)
        btnVideoStop = findViewById(R.id.btnVideoStop)
        rowOverlayPermission = findViewById(R.id.rowOverlayPermission)
        viewOverlayPermissionDot = findViewById(R.id.viewOverlayPermissionDot)
        tvOverlayPermissionStatus = findViewById(R.id.tvOverlayPermissionStatus)
        btnOverlayPermissionSettings = findViewById(R.id.btnOverlayPermissionSettings)
        tvVideoTargetLanguageLabel = findViewById(R.id.tvVideoTargetLanguageLabel)
        tvLiveZh = findViewById(R.id.tvLiveZh)
        tvLiveJa = findViewById(R.id.tvLiveJa)
        tvTranscriptPath = findViewById(R.id.tvTranscriptPath)
        acVideoSourceLang = findViewById(R.id.acVideoSourceLang)
        acVideoTargetLang = findViewById(R.id.acVideoTargetLang)
        etVideoSessionUrl = findViewById(R.id.etVideoSessionUrl)
        etVideoSessionContext = findViewById(R.id.etVideoSessionContext)
        btnVideoAnalyzeContext = findViewById(R.id.btnVideoAnalyzeContext)
        tvVideoAnalyzeStatus = findViewById(R.id.tvVideoAnalyzeStatus)

        btnRefreshHistory = findViewById(R.id.btnRefreshHistory)
        etHistorySearch = findViewById(R.id.etHistorySearch)
        chipHistoryAll = findViewById(R.id.chipHistoryAll)
        chipHistoryInterp = findViewById(R.id.chipHistoryInterp)
        chipHistoryVideo = findViewById(R.id.chipHistoryVideo)
        tvHistoryEmpty = findViewById(R.id.tvHistoryEmpty)
        historyList = findViewById(R.id.historyList)
        cardHistoryDetail = findViewById(R.id.cardHistoryDetail)
        tvHistoryTitle = findViewById(R.id.tvHistoryTitle)
        btnCopyHistory = findViewById(R.id.btnCopyHistory)
        tvHistoryDetail = findViewById(R.id.tvHistoryDetail)
        tvHistoryDetailMeta = findViewById(R.id.tvHistoryDetailMeta)
        tvHistoryDetailContext = findViewById(R.id.tvHistoryDetailContext)
        tvHistoryDetailEmpty = findViewById(R.id.tvHistoryDetailEmpty)
        historyDetailSegments = findViewById(R.id.historyDetailSegments)

        etApiKeys = findViewById(R.id.etApiKeys)
        etBaseUrl = findViewById(R.id.etBaseUrl)
        swFriendGateway = findViewById(R.id.swFriendGateway)
        etFriendInviteCode = findViewById(R.id.etFriendInviteCode)
        tvFriendGatewayStatus = findViewById(R.id.tvFriendGatewayStatus)
        btnBindFriendGateway = findViewById(R.id.btnBindFriendGateway)
        btnClearFriendGateway = findViewById(R.id.btnClearFriendGateway)
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
        pageInterp.visibility = if (itemId == R.id.nav_interp) View.VISIBLE else View.GONE
        pageVideo.visibility = if (itemId == R.id.nav_video) View.VISIBLE else View.GONE
        pageHistory.visibility = if (itemId == R.id.nav_history) View.VISIBLE else View.GONE
        pageSettings.visibility = if (itemId == R.id.nav_settings) View.VISIBLE else View.GONE

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
    private fun openSettingsSub(
        pageId: Int,
        title: String,
        returnTabId: Int = R.id.nav_settings,
    ) {
        settingsSubId = pageId
        settingsReturnTabId = returnTabId
        pageInterp.visibility = View.GONE
        pageVideo.visibility = View.GONE
        pageHistory.visibility = View.GONE
        pageSettings.visibility = View.GONE
        settingsSubViews.forEach { it.visibility = if (it.id == pageId) View.VISIBLE else View.GONE }
        toolbar.title = title
        toolbar.logo = null
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back_24)
        bottomNav.visibility = View.GONE
        if (pageId == R.id.pageSceneLibrary) {
            reloadSceneLibrary()
        }
    }

    private fun closeSettingsSub() {
        if (settingsSubId == R.id.pageSettingsProfileAi) saveSecondAiSettings()
        settingsSubId = 0
        settingsSubViews.forEach { it.visibility = View.GONE }
        val returnTab = settingsReturnTabId
        settingsReturnTabId = R.id.nav_settings
        toolbar.title = "流译"
        toolbar.setLogo(R.drawable.ic_brand_translate_24)
        toolbar.navigationIcon = null
        bottomNav.visibility = View.VISIBLE
        if (bottomNav.selectedItemId != returnTab) {
            bottomNav.selectedItemId = returnTab
        } else {
            showPage(returnTab)
        }
        currentMainTabId = returnTab
    }

    private fun consumeStartedSession(captureMode: String) {
        when (promptMode(captureMode)) {
            TranslationMode.INTERPRETATION -> etInterpSessionContext.setText("")
            TranslationMode.VIDEO -> {
                etVideoSessionContext.setText("")
                etVideoSessionUrl.setText("")
            }
        }
        pendingSessionPrompt = ""
        pendingSessionSource = ""
        pendingSessionTarget = ""
        pendingSessionScene = ""
        pendingSessionSceneLabel = ""
        pendingSessionTitle = ""
        pendingSessionContext = ""
    }

    private fun currentSessionContext(mode: TranslationMode): SessionPromptContext {
        val manual = when (mode) {
            TranslationMode.INTERPRETATION -> etInterpSessionContext.text?.toString().orEmpty()
            TranslationMode.VIDEO -> etVideoSessionContext.text?.toString().orEmpty()
        }.trim()
        // 视频元数据在 AI 解析时写入 manual 文本；启动时只传 manual 即可。
        return SessionPromptContext(manualContext = manual)
    }

    private fun composeSessionPrompt(mode: TranslationMode): String {
        val plan = TranslationPlanStore.loadDraft(this, mode)
        val scene = SceneLibraryStore.resolve(this, mode, plan.scenePresetId)
        return PromptBuilder.build(
            scene = scene,
            context = currentSessionContext(mode),
            plan = plan,
        )
    }

    private fun setupSessionContextUi() {
        btnInterpAnalyzeContext.setOnClickListener {
            analyzeSessionContext(TranslationMode.INTERPRETATION)
        }
        btnVideoAnalyzeContext.setOnClickListener {
            analyzeSessionContext(TranslationMode.VIDEO)
        }
    }

    private fun analyzeSessionContext(mode: TranslationMode) {
        val friendAccess = FriendGatewayStore.isActive(this)
        val apiKey = if (friendAccess) {
            FriendGatewayStore.token(this)
        } else {
            SettingsStore.secondAiApiKey(this)
        }
        val statusView = if (mode == TranslationMode.INTERPRETATION) {
            tvInterpAnalyzeStatus
        } else {
            tvVideoAnalyzeStatus
        }
        val button = if (mode == TranslationMode.INTERPRETATION) {
            btnInterpAnalyzeContext
        } else {
            btnVideoAnalyzeContext
        }
        if (
            FriendGatewayStore.mode(this) == FriendGatewayStore.MODE_FRIEND &&
            !friendAccess
        ) {
            statusView.text = "好友测试凭据已失效，请回到设置重新绑定"
            return
        }
        if (apiKey.isBlank()) {
            statusView.text = if (friendAccess) {
                "好友测试凭据已失效，请回到设置重新绑定"
            } else {
                "请先在设置 → 内容分析 AI 中填写 API Key"
            }
            return
        }
        val material = when (mode) {
            TranslationMode.INTERPRETATION -> etInterpSessionContext.text?.toString().orEmpty().trim()
            TranslationMode.VIDEO -> etVideoSessionContext.text?.toString().orEmpty().trim()
        }
        val url = etVideoSessionUrl.text?.toString().orEmpty().trim()
        if (mode == TranslationMode.INTERPRETATION && material.isBlank()) {
            statusView.text = "请先填写本场背景或资料"
            return
        }
        if (mode == TranslationMode.VIDEO && url.isBlank() && material.isBlank()) {
            statusView.text = "请先填写视频链接或本场资料"
            return
        }
        if (mode == TranslationMode.VIDEO && url.isBlank()) {
            statusView.text = "解析视频需要先填写 YouTube 链接"
            return
        }

        val requestId = java.util.UUID.randomUUID().toString()
        if (mode == TranslationMode.INTERPRETATION) {
            latestInterpAnalysisRequestId = requestId
        } else {
            latestVideoAnalysisRequestId = requestId
        }
        val plan = TranslationPlanStore.loadDraft(this, mode).normalized()
        val baseUrl = if (friendAccess) {
            FriendGatewayStore.GATEWAY_BASE_URL + "/gateway"
        } else {
            SettingsStore.secondAiBaseUrl(this)
        }
        val model = if (friendAccess) "gemini-3.5-flash" else SettingsStore.secondAiModel(this)
        val format = if (friendAccess) {
            AiTextClient.Format.GEMINI
        } else {
            AiTextClient.Format.fromKey(SettingsStore.secondAiFormat(this))
        }
        val credentialMode = if (friendAccess) {
            ApiCredentialMode.BEARER_TOKEN
        } else {
            ApiCredentialMode.QUERY_API_KEY
        }
        val deviceId = if (friendAccess) FriendGatewayStore.deviceId(this) else ""
        val requestSignatureProvider:
            ((String, String, ByteArray, String) -> Map<String, String>)? = if (friendAccess) {
                { method, path, body, token ->
                    FriendDeviceIdentity.signRequest(this, method, path, body, token).asMap()
                }
            } else {
                null
            }
        button.isEnabled = false
        statusView.text = "正在整理，请稍候…"
        Thread({
            runCatching {
                val videoInfo = if (mode == TranslationMode.VIDEO) {
                    YouTubeOEmbedClient.fetch(url)
                } else {
                    null
                }
                val source = TranslationLanguageCatalog.source(plan.sourceLanguageCode)
                val target = TranslationLanguageCatalog.target(plan.targetLanguageCode)
                ContentContextAnalyzer.analyze(
                    request = ContentAnalysisRequest(
                        mode = mode,
                        sourceLanguageLabel = source.label,
                        targetLanguageLabel = target.label,
                        material = material,
                        video = videoInfo,
                    ),
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    model = model,
                    format = format,
                    credentialMode = credentialMode,
                    deviceId = deviceId,
                    requestSignatureProvider = requestSignatureProvider,
                ) to videoInfo
            }.onSuccess { (result, videoInfo) ->
                runOnUiThread {
                    val latest = if (mode == TranslationMode.INTERPRETATION) {
                        latestInterpAnalysisRequestId
                    } else {
                        latestVideoAnalysisRequestId
                    }
                    if (requestId != latest) return@runOnUiThread
                    val inputChanged = when (mode) {
                        TranslationMode.INTERPRETATION ->
                            etInterpSessionContext.text?.toString().orEmpty().trim() != material
                        TranslationMode.VIDEO ->
                            etVideoSessionContext.text?.toString().orEmpty().trim() != material ||
                                etVideoSessionUrl.text?.toString().orEmpty().trim() != url
                    }
                    if (inputChanged) {
                        if (mode == TranslationMode.INTERPRETATION) latestInterpAnalysisRequestId = ""
                        else latestVideoAnalysisRequestId = ""
                        statusView.text = "输入已修改，之前的分析结果已忽略"
                        button.isEnabled = true
                        return@runOnUiThread
                    }
                    if (result.sessionContext.isBlank()) {
                        statusView.text = "AI 没有返回可用背景，请补充资料后重试"
                    } else {
                        // 把标题/频道一并写入本场文本，方便用户看见并编辑
                        val composed = buildString {
                            if (videoInfo != null) {
                                if (videoInfo.title.isNotBlank()) appendLine("视频标题：${videoInfo.title}")
                                if (videoInfo.authorName.isNotBlank()) appendLine("频道/作者：${videoInfo.authorName}")
                                if (isNotEmpty()) appendLine()
                            }
                            append(result.sessionContext)
                        }.trim()
                        if (mode == TranslationMode.INTERPRETATION) {
                            etInterpSessionContext.setText(composed)
                        } else {
                            etVideoSessionContext.setText(composed)
                        }
                        statusView.text = result.note.ifBlank { "本场资料已整理" }
                    }
                    button.isEnabled = true
                }
            }.onFailure { error ->
                runOnUiThread {
                    val latest = if (mode == TranslationMode.INTERPRETATION) {
                        latestInterpAnalysisRequestId
                    } else {
                        latestVideoAnalysisRequestId
                    }
                    if (requestId != latest) return@runOnUiThread
                    statusView.text = "整理失败：${error.message ?: "未知错误"}"
                    button.isEnabled = true
                }
            }
        }, "session-context-${mode.storageKey}").start()
    }

    // ---------- 历史记录 ----------

    private fun setupHistoryPage() {
        btnRefreshHistory.setOnClickListener { reloadHistory() }
        etHistorySearch.doAfterTextChanged { renderHistoryList() }
        chipHistoryAll.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                historyModeFilter = null
                renderHistoryList()
            }
        }
        chipHistoryInterp.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                historyModeFilter = TranslationMode.INTERPRETATION
                renderHistoryList()
            }
        }
        chipHistoryVideo.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                historyModeFilter = TranslationMode.VIDEO
                renderHistoryList()
            }
        }
        btnCopyHistory.setOnClickListener {
            val text = tvHistoryDetail.text.toString()
            if (text.isBlank()) return@setOnClickListener
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("transcript", text))
            toast("已复制全文")
        }
    }

    private fun reloadHistory() {
        allHistoryItems = HistoryStore.list(this)
        renderHistoryList()
    }

    private fun renderHistoryList() {
        val query = etHistorySearch.text?.toString().orEmpty().trim().lowercase()
        val items = allHistoryItems.filter { item ->
            val direction = "${TranslationLanguageCatalog.source(item.sourceLanguageCode).label} → " +
                TranslationLanguageCatalog.target(item.targetLanguageCode).label
            val scene = item.sceneLabel
            val matchesMode = historyModeFilter == null || item.mode == historyModeFilter
            val haystack = listOf(item.title, item.summary, direction, scene).joinToString(" ").lowercase()
            matchesMode && (query.isEmpty() || query in haystack)
        }
        historyList.removeAllViews()
        tvHistoryEmpty.text = if (allHistoryItems.isEmpty()) "暂无历史记录" else "没有匹配的记录"
        tvHistoryEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        items.forEach { item ->
            val card = layoutInflater.inflate(R.layout.item_history_session, historyList, false)
            val icon = card.findViewById<TextView>(R.id.tvHistoryItemIcon)
            val mode = card.findViewById<TextView>(R.id.tvHistoryItemMode)
            val title = card.findViewById<TextView>(R.id.tvHistoryItemTitle)
            val time = card.findViewById<TextView>(R.id.tvHistoryItemTime)
            val meta = card.findViewById<TextView>(R.id.tvHistoryItemMeta)
            val summary = card.findViewById<TextView>(R.id.tvHistoryItemSummary)
            val isInterpretation = item.mode == TranslationMode.INTERPRETATION
            icon.text = if (isInterpretation) "麦" else "播"
            icon.setTextColor(getColor(if (isInterpretation) R.color.brand else R.color.warning))
            ViewCompat.setBackgroundTintList(
                icon,
                getColorStateList(if (isInterpretation) R.color.primary_fixed else R.color.warning_container),
            )
            mode.text = item.mode.label
            mode.setTextColor(getColor(if (isInterpretation) R.color.brand else R.color.warning))
            title.text = item.title
            time.text = HistoryStore.formatTime(item.updatedAt)
            val direction = "${TranslationLanguageCatalog.source(item.sourceLanguageCode).label} → " +
                TranslationLanguageCatalog.target(item.targetLanguageCode).label
            val scene = item.sceneLabel
            meta.text = "$direction · $scene · ${HistoryStore.formatDuration(item.durationMs)}"
            summary.text = item.summary.trim().replace('\n', ' ').ifBlank { "暂无字幕摘要" }
            card.setOnClickListener { showHistoryDetail(item) }
            card.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = resources.getDimensionPixelSize(R.dimen.space_12) }
            historyList.addView(card)
        }
    }

    private fun showHistoryDetail(item: HistoryStore.HistoryItem) {
        val session = HistoryStore.load(this, item.fileName)
        if (session == null) {
            toast("记录不存在或已损坏")
            reloadHistory()
            return
        }
        tvHistoryTitle.text = session.title
        tvHistoryDetail.text = HistoryStore.toMarkdown(session)
        tvHistoryDetailMeta.text = buildString {
            append(session.mode.label).append(" · ").append(session.directionLabel)
            append("\n").append(session.sceneLabel)
            append(" · ").append(HistoryStore.formatTime(session.startedAt))
            append(" · ").append(HistoryStore.formatDuration(session.durationMs))
        }
        tvHistoryDetailContext.text = "本场背景\n${session.contextSummary}"
        tvHistoryDetailContext.visibility = if (session.contextSummary.isBlank()) View.GONE else View.VISIBLE
        historyDetailSegments.removeAllViews()
        tvHistoryDetailEmpty.visibility = if (session.segments.isEmpty()) View.VISIBLE else View.GONE
        session.segments.forEach { segment ->
            val row = layoutInflater.inflate(R.layout.item_history_segment, historyDetailSegments, false)
            val elapsed = row.findViewById<TextView>(R.id.tvHistorySegmentTime)
            val source = row.findViewById<TextView>(R.id.tvHistorySegmentSource)
            val translation = row.findViewById<TextView>(R.id.tvHistorySegmentTranslation)
            elapsed.text = HistoryStore.formatDuration(segment.elapsedMs)
            source.text = segment.sourceText
            source.visibility = if (segment.sourceText.isBlank()) View.GONE else View.VISIBLE
            translation.text = segment.translatedText
            row.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = resources.getDimensionPixelSize(R.dimen.space_12) }
            historyDetailSegments.addView(row)
        }
        openSettingsSub(R.id.pageHistoryDetail, "历史详情", returnTabId = R.id.nav_history)
    }

    // ---------- 设置 ----------

    private fun setupSettings() {
        rowSetTranslate.setOnClickListener { openSettingsSub(R.id.pageSettingsTranslate, "翻译服务") }
        rowSetSubtitle.setOnClickListener { openSettingsSub(R.id.pageSettingsSubtitle, "字幕与悬浮窗") }
        rowSetSceneLibrary.setOnClickListener {
            sceneLibraryMode = TranslationMode.INTERPRETATION
            openSettingsSub(R.id.pageSceneLibrary, "场景库")
        }
        rowSetProfileAi.setOnClickListener { openSettingsSub(R.id.pageSettingsProfileAi, "内容分析 AI") }
        rowSetDiagnostics.setOnClickListener { openSettingsSub(R.id.pageSettingsDiagnostics, "诊断") }
        rowSetAbout.setOnClickListener { openSettingsSub(R.id.pageSettingsAbout, "关于") }

        etApiKeys.setText(SettingsStore.apiKeysRaw(this))
        etBaseUrl.setText(SettingsStore.baseUrl(this))
        setupFriendGatewayUi()
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

    private fun setupFriendGatewayUi() {
        swFriendGateway.setOnCheckedChangeListener { _, checked ->
            if (syncingFriendGatewayUi) return@setOnCheckedChangeListener
            if (checked) {
                if (!FriendGatewayStore.useFriend(this)) {
                    toast("请先输入邀请码并完成绑定")
                }
            } else {
                FriendGatewayStore.usePersonal(this)
            }
            renderFriendGatewayUi()
        }
        btnBindFriendGateway.setOnClickListener { bindFriendGateway() }
        btnClearFriendGateway.setOnClickListener {
            friendBindingViewModel.clearBinding()
            etFriendInviteCode.setText("")
            toast("已清除本机好友凭据")
        }
        renderFriendGatewayUi()
        if (FriendGatewayStore.isBound(this)) refreshFriendGatewayStatus()
    }

    private fun renderFriendGatewayUi(
        remote: FriendGatewayStatus? = null,
        bindingInProgress: Boolean = friendBindingViewModel.isBinding(),
    ) {
        val bound = FriendGatewayStore.isBound(this)
        val active = FriendGatewayStore.isActive(this)
        syncingFriendGatewayUi = true
        swFriendGateway.isEnabled = bound && !bindingInProgress
        swFriendGateway.isChecked = active
        syncingFriendGatewayUi = false
        etFriendInviteCode.isEnabled = !bindingInProgress
        btnBindFriendGateway.isEnabled = !bindingInProgress
        btnClearFriendGateway.visibility = if (bound) View.VISIBLE else View.GONE
        btnClearFriendGateway.isEnabled = bound && !bindingInProgress
        btnBindFriendGateway.text = if (bound) "重新绑定" else "绑定并启用"
        tvFriendGatewayStatus.text = when {
            remote != null && active -> {
                val name = remote.label.ifBlank { "好友测试" }
                "$name 已启用 · 今日实时 ${remote.liveSessions} 次 · 内容分析 ${remote.textRequests} 次"
            }
            active -> "好友测试通道已启用，翻译和内容分析均由服务器提供"
            bound -> "邀请码已绑定，当前仍使用你自己的 API Key"
            else -> "当前使用你自己的 API Key"
        }
    }

    private fun renderFriendBindingState(state: FriendGatewayBindingState) {
        val binding = state.phase == FriendGatewayBindingPhase.BINDING
        renderFriendGatewayUi(bindingInProgress = binding)
        when (state.phase) {
            FriendGatewayBindingPhase.BINDING -> {
                tvFriendGatewayStatus.text = "正在验证邀请码并绑定当前设备…"
            }
            FriendGatewayBindingPhase.SUCCESS -> {
                etFriendInviteCode.setText("")
                if (FriendGatewayStore.isBound(this)) refreshFriendGatewayStatus()
            }
            FriendGatewayBindingPhase.FAILURE -> {
                tvFriendGatewayStatus.text = state.message
            }
            FriendGatewayBindingPhase.IDLE -> Unit
        }
    }

    private fun bindFriendGateway() {
        val code = etFriendInviteCode.text?.toString().orEmpty().trim()
        if (code.isBlank()) {
            toast("请输入好友邀请码")
            return
        }
        val version = runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            "${info.versionName}(${info.longVersionCode})"
        }.getOrDefault("unknown")
        val enableFriendOnSuccess =
            !FriendGatewayStore.isBound(this) || FriendGatewayStore.isActive(this)
        friendBindingViewModel.bind(code, version, enableFriendOnSuccess)
    }

    private fun refreshFriendGatewayStatus() {
        val token = FriendGatewayStore.token(this)
        if (token.isBlank()) return
        Thread({
            runCatching { FriendGatewayClient(this).status(token) }
                .onSuccess { status ->
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed && !friendBindingViewModel.isBinding()) {
                            renderFriendGatewayUi(status)
                        }
                    }
                }
                .onFailure { error ->
                    runOnUiThread {
                        if (
                            !isFinishing && !isDestroyed && !friendBindingViewModel.isBinding() &&
                            FriendGatewayStore.isActive(this)
                        ) {
                            tvFriendGatewayStatus.text =
                                "好友通道验证失败：${error.message ?: "未知错误"}"
                        }
                    }
                }
        }, "friend-gateway-status").start()
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
            openSceneLibrary(TranslationMode.INTERPRETATION, returnTabId = R.id.nav_interp)
        }
        findViewById<View>(R.id.cardVideoPlan).setOnClickListener {
            openSceneLibrary(TranslationMode.VIDEO, returnTabId = R.id.nav_video)
        }
        findViewById<View>(R.id.btnInterpOpenPlanLibrary).setOnClickListener {
            openSceneLibrary(TranslationMode.INTERPRETATION, returnTabId = R.id.nav_interp)
        }
        findViewById<View>(R.id.btnVideoOpenPlanLibrary).setOnClickListener {
            openSceneLibrary(TranslationMode.VIDEO, returnTabId = R.id.nav_video)
        }
        setupHomeSceneChips(TranslationMode.INTERPRETATION)
        setupHomeSceneChips(TranslationMode.VIDEO)
        updatePlanSummary(TranslationMode.INTERPRETATION)
        updatePlanSummary(TranslationMode.VIDEO)
    }

    internal fun openSceneLibrary(mode: TranslationMode, returnTabId: Int = R.id.nav_settings) {
        sceneLibraryMode = mode
        openSettingsSub(R.id.pageSceneLibrary, "场景库", returnTabId)
    }

    private fun setupHomeSceneChips(mode: TranslationMode) {
        val group = if (mode == TranslationMode.INTERPRETATION) {
            chipGroupInterpHomeScenes
        } else {
            chipGroupVideoHomeScenes
        }
        val current = TranslationPlanStore.loadDraft(this, mode)
        val selectedSceneId = SceneLibraryStore.resolve(this, mode, current.scenePresetId).id
        group.removeAllViews()
        SceneLibraryStore.list(this, mode).forEach { scene ->
            group.addView(Chip(this).apply {
                text = scene.label
                isCheckable = true
                isCheckedIconVisible = false
                isChecked = scene.id == selectedSceneId
                minHeight = resources.getDimensionPixelSize(R.dimen.touch_target)
                chipBackgroundColor = getColorStateList(R.color.selector_scene_chip_bg)
                setTextColor(getColorStateList(R.color.selector_scene_chip_text))
                chipStrokeWidth = 0f
                setOnClickListener {
                    val latest = TranslationPlanStore.loadDraft(this@MainActivity, mode)
                    TranslationPlanStore.saveDraft(
                        this@MainActivity,
                        latest.copy(scenePresetId = scene.id),
                    )
                    updatePlanSummary(mode)
                }
            })
        }
    }

    private fun setupSceneLibraryPage() {
        toggleSceneLibraryMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            sceneLibraryMode = when (checkedId) {
                R.id.btnSceneLibraryVideo -> TranslationMode.VIDEO
                else -> TranslationMode.INTERPRETATION
            }
            reloadSceneLibrary()
        }
        fabNewScene.setOnClickListener { showSceneEditor() }
        btnResetSceneLibrary.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("恢复默认场景？")
                .setMessage("会替换当前模式的全部场景和默认选择；正在使用已删除场景的模式会回退到默认场景。")
                .setNegativeButton("取消", null)
                .setPositiveButton("恢复") { _, _ ->
                    SceneLibraryStore.reset(this, sceneLibraryMode)
                    refreshSceneDependents(sceneLibraryMode)
                    reloadSceneLibrary()
                    toast("已恢复${sceneLibraryMode.label}默认场景")
                }
                .show()
        }
    }

    private fun reloadSceneLibrary() {
        val checkedId = if (sceneLibraryMode == TranslationMode.VIDEO) {
            R.id.btnSceneLibraryVideo
        } else {
            R.id.btnSceneLibraryInterp
        }
        if (toggleSceneLibraryMode.checkedButtonId != checkedId) {
            toggleSceneLibraryMode.check(checkedId)
        }
        val items = SceneLibraryStore.list(this, sceneLibraryMode)
        val defaultId = SceneLibraryStore.default(this, sceneLibraryMode).id
        val inUseId = SceneLibraryStore.resolve(
            this,
            sceneLibraryMode,
            TranslationPlanStore.loadDraft(this, sceneLibraryMode).scenePresetId,
        ).id
        sceneLibraryList.removeAllViews()
        items.forEach { scene ->
            sceneLibraryList.addView(
                buildSceneCard(scene, scene.id == defaultId, scene.id == inUseId),
            )
        }
    }

    private fun buildSceneCard(
        scene: ScenePromptPreset,
        isDefault: Boolean,
        isInUse: Boolean,
    ): View {
        val card = layoutInflater.inflate(R.layout.item_scene_preset, sceneLibraryList, false)
        card.findViewById<TextView>(R.id.tvSceneIcon).text =
            scene.label.firstOrNull()?.toString() ?: "场"
        card.findViewById<TextView>(R.id.tvSceneName).text = scene.label
        card.findViewById<TextView>(R.id.tvSceneInstruction).text = scene.instruction
        card.findViewById<Chip>(R.id.chipSceneDefault).visibility =
            if (isDefault) View.VISIBLE else View.GONE
        card.findViewById<Chip>(R.id.chipSceneInUse).visibility =
            if (isInUse) View.VISIBLE else View.GONE
        card.findViewById<MaterialButton>(R.id.btnUseScene).apply {
            visibility = if (isInUse) View.GONE else View.VISIBLE
            setOnClickListener {
                val draft = TranslationPlanStore.loadDraft(this@MainActivity, sceneLibraryMode)
                TranslationPlanStore.saveDraft(
                    this@MainActivity,
                    draft.copy(scenePresetId = scene.id),
                )
                refreshSceneDependents(sceneLibraryMode)
                reloadSceneLibrary()
                toast("已使用：${scene.label}")
            }
        }
        card.findViewById<MaterialButton>(R.id.btnSetDefaultScene).apply {
            visibility = if (isDefault) View.GONE else View.VISIBLE
            setOnClickListener {
                if (SceneLibraryStore.setDefault(this@MainActivity, sceneLibraryMode, scene.id)) {
                    val draft = TranslationPlanStore.loadDraft(this@MainActivity, sceneLibraryMode)
                    TranslationPlanStore.saveDraft(
                        this@MainActivity,
                        draft.copy(scenePresetId = scene.id),
                    )
                    refreshSceneDependents(sceneLibraryMode)
                    reloadSceneLibrary()
                    toast("已设为${sceneLibraryMode.label}默认场景")
                } else {
                    toast("场景库数据异常，请先恢复模板")
                }
            }
        }
        card.findViewById<MaterialButton>(R.id.btnEditScene).setOnClickListener {
            showSceneEditor(scene)
        }
        card.findViewById<MaterialButton>(R.id.btnDeleteScene).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("删除“${scene.label}”？")
                .setMessage("正在使用该场景的模式下次启动会回退到当前默认场景。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除") { _, _ ->
                    if (SceneLibraryStore.delete(this, sceneLibraryMode, scene.id)) {
                        refreshSceneDependents(sceneLibraryMode)
                        reloadSceneLibrary()
                        toast("已删除：${scene.label}")
                    } else {
                        val message = if (SceneLibraryStore.list(this, sceneLibraryMode).size <= 1) {
                            "每种模式至少保留一个场景"
                        } else {
                            "场景库数据异常，请先恢复模板"
                        }
                        toast(message)
                    }
                }
                .show()
        }
        return card
    }

    private fun showSceneEditor(existing: ScenePromptPreset? = null) {
        val content = layoutInflater.inflate(R.layout.dialog_scene_editor, null, false)
        val nameLayout = content.findViewById<TextInputLayout>(R.id.tilSceneName)
        val promptLayout = content.findViewById<TextInputLayout>(R.id.tilSceneInstruction)
        val name = content.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSceneName)
        val prompt = content.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSceneInstruction)
        name.setText(existing?.label.orEmpty())
        prompt.setText(existing?.instruction.orEmpty())

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "新建${sceneLibraryMode.label}场景" else "编辑场景")
            .setView(content)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val label = name.text?.toString().orEmpty().trim()
                val instruction = prompt.text?.toString().orEmpty().trim()
                nameLayout.error = if (label.isEmpty()) "请填写场景名称" else null
                promptLayout.error = if (instruction.isEmpty()) "请填写场景提示词" else null
                if (label.isEmpty() || instruction.isEmpty()) return@setOnClickListener

                val saved = if (existing == null) {
                    SceneLibraryStore.create(this, sceneLibraryMode, label, instruction)
                } else {
                    if (SceneLibraryStore.update(
                        this,
                        sceneLibraryMode,
                        existing.copy(label = label, instruction = instruction),
                    )) existing else null
                }
                if (saved == null) {
                    promptLayout.error = "场景库数据异常，请先恢复模板"
                    return@setOnClickListener
                }
                refreshSceneDependents(sceneLibraryMode)
                reloadSceneLibrary()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun refreshSceneDependents(mode: TranslationMode) {
        setupHomeSceneChips(mode)
        updatePlanSummary(mode)
    }

    private fun updatePlanSummary(mode: TranslationMode) {
        val plan = TranslationPlanStore.loadDraft(this, mode)
        val scene = SceneLibraryStore.resolve(this, mode, plan.scenePresetId)
        val summary = "${scene.label} · ${plan.directionLabel}"
        val detail = scene.instruction.replace('\n', ' ').take(48)
            .ifEmpty { "点击管理场景库；本场上下文在主页填写" }
        if (mode == TranslationMode.INTERPRETATION) {
            findViewById<TextView>(R.id.tvInterpPlanSummary)?.text = summary
            findViewById<TextView>(R.id.tvInterpProfile)?.text = detail
        } else {
            findViewById<TextView>(R.id.tvVideoPlanSummary)?.text = summary
            findViewById<TextView>(R.id.tvCurrentProfile)?.text = detail
        }
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
        val friendSelected = FriendGatewayStore.mode(this) == FriendGatewayStore.MODE_FRIEND
        val friendAccess = FriendGatewayStore.isActive(this)
        pendingCredentialMode = if (friendSelected) {
            FriendGatewayStore.MODE_FRIEND
        } else {
            FriendGatewayStore.MODE_PERSONAL
        }
        if (friendSelected && !friendAccess) {
            toast("好友测试凭据已失效，请重新绑定邀请码")
            bottomNav.selectedItemId = R.id.nav_settings
            openSettingsSub(R.id.pageSettingsTranslate, "翻译服务")
            return false
        }
        if (!friendAccess && SettingsStore.apiKeyList(this).isEmpty()) {
            toast("请先填 Gemini API Key")
            bottomNav.selectedItemId = R.id.nav_settings
            openSettingsSub(R.id.pageSettingsTranslate, "翻译服务")
            return false
        }
        val scene = SceneLibraryStore.resolve(this, mode, plan.scenePresetId)
        pendingSessionPrompt = PromptBuilder.build(
            scene = scene,
            context = currentSessionContext(mode),
            plan = plan,
        )
        pendingSessionSource = plan.sourceLanguageCode
        pendingSessionTarget = plan.targetLanguageCode
        pendingSessionScene = scene.id
        pendingSessionSceneLabel = scene.label
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
            .putExtra(CaptureService.EXTRA_CREDENTIAL_MODE, pendingCredentialMode)
            .putExtra(CaptureService.EXTRA_SESSION_PROMPT, pendingSessionPrompt)
            .putExtra(CaptureService.EXTRA_SOURCE_LANGUAGE, pendingSessionSource)
            .putExtra(CaptureService.EXTRA_TARGET_LANGUAGE, pendingSessionTarget)
            .putExtra(CaptureService.EXTRA_SCENE_PRESET, pendingSessionScene)
            .putExtra(CaptureService.EXTRA_SCENE_LABEL, pendingSessionSceneLabel)
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

    private fun renderConfirmedTranslations(container: LinearLayout, translations: List<String>) {
        val visible = translations.map(String::trim).filter(String::isNotEmpty).takeLast(6)
        val renderKey = visible.joinToString("\u0000")
        if (container.tag == renderKey) return
        container.tag = renderKey
        container.removeAllViews()
        if (visible.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "等待语音…"
                textSize = 14f
                setTextColor(getColor(R.color.text_muted))
            })
            return
        }
        visible.forEachIndexed { index, line ->
            container.addView(TextView(this).apply {
                text = line
                textSize = 16f
                alpha = 0.48f + (index + 1).toFloat() / visible.size * 0.34f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, resources.getDimensionPixelSize(R.dimen.grid_4), 0,
                    resources.getDimensionPixelSize(R.dimen.grid_4))
            })
        }
    }

    private fun formatRunningElapsed(startedAtMs: Long): String {
        if (startedAtMs <= 0L) return "00:00"
        return formatElapsedDuration(System.currentTimeMillis() - startedAtMs)
    }

    private fun renderStatus() {
        val s = StatusBus
        val running = s.serviceRunning
        val mode = s.captureMode
        val conn = s.connState
        val level = s.audioLevelPct.coerceIn(0, 100)
        val micActive = running && mode == StatusBus.MODE_MIC
        val videoActive = running && mode == StatusBus.MODE_VIDEO
        val snapshot = s.sessionSnapshot()

        listOf(
            LanguageControls(acInterpSourceLang, acInterpTargetLang),
            LanguageControls(acVideoSourceLang, acVideoTargetLang),
        ).forEach { controls ->
            controls.sourceView.isEnabled = !running
            controls.targetView.isEnabled = !running
        }

        interpIdleContent.visibility = if (micActive) View.GONE else View.VISIBLE
        interpRunningContent.visibility = if (micActive) View.VISIBLE else View.GONE
        videoIdleContent.visibility = if (videoActive) View.GONE else View.VISIBLE
        videoRunningContent.visibility = if (videoActive) View.VISIBLE else View.GONE

        val activeStatusText = when {
            s.paused -> "已暂停"
            conn == "ready" -> "翻译中"
            conn.startsWith("error") -> "连接出错"
            conn == "rotating" -> "正在切换连接"
            else -> "准备连接"
        }
        val activeStatusColor = when {
            s.paused -> R.color.warning
            conn == "ready" -> R.color.success
            conn.startsWith("error") -> R.color.error
            else -> R.color.brand
        }

        val overlayAllowed = Settings.canDrawOverlays(this)
        tvOverlayPermissionStatus.text = if (overlayAllowed) "已授权，可在其他应用上显示字幕" else "未授权，开始视频字幕前需要开启"
        ViewCompat.setBackgroundTintList(
            viewOverlayPermissionDot,
            getColorStateList(if (overlayAllowed) R.color.success else R.color.error),
        )
        btnOverlayPermissionSettings.visibility = if (overlayAllowed) View.GONE else View.VISIBLE

        // 同传空闲态与运行态
        tvInterpStatus.text = if (running && !micActive) "视频运行中" else "待开始"
        tvInterpSubStatus.text = if (running && !micActive) {
            "当前正在运行视频字幕，请先停止后再开同传"
        } else {
            "麦克风实时同传"
        }
        btnInterpToggle.isEnabled = !running
        if (micActive) {
            val plan = TranslationPlanStore.loadDraft(this, TranslationMode.INTERPRETATION)
            val sourceCode = snapshot.sourceLanguageCode.ifBlank { plan.sourceLanguageCode }
            val targetCode = snapshot.targetLanguageCode.ifBlank { plan.targetLanguageCode }
            val sceneId = snapshot.scenePresetId.ifBlank { plan.scenePresetId }
            val direction = "${TranslationLanguageCatalog.source(sourceCode).label} → " +
                TranslationLanguageCatalog.target(targetCode).label
            val scene = snapshot.sceneLabel.ifBlank {
                SceneLibraryStore.resolve(this, TranslationMode.INTERPRETATION, sceneId).label
            }
            tvInterpRunningStatus.text = activeStatusText
            ViewCompat.setBackgroundTintList(viewInterpRunningStatusDot, getColorStateList(activeStatusColor))
            tvInterpElapsed.text = formatRunningElapsed(snapshot.startedAtMs)
            tvInterpRunningMeta.text = "$direction · $scene"
            tvInterpAudioLevel.text = "$level%"
            pbInterpAudio.progress = level
            renderConfirmedTranslations(interpConfirmedList, snapshot.confirmedTranslations)
            tvInterpZh.text = snapshot.currentTranslation.trim()
                .ifBlank { snapshot.confirmedTranslations.lastOrNull().orEmpty() }
                .ifBlank { "等待译文…" }
            tvInterpJa.text = snapshot.sourceTail.trim().ifBlank { "等待原文输入…" }
            tvInterpTranscriptPath.text = if (s.transcriptPath.isNotEmpty()) {
                "本场记录：${s.transcriptPath}"
            } else {
                "自动保存到应用内历史"
            }
        }

        // 视频空闲态与运行态
        btnToggle.isEnabled = !running
        if (videoActive) {
            val plan = TranslationPlanStore.loadDraft(this, TranslationMode.VIDEO)
            val sourceCode = snapshot.sourceLanguageCode.ifBlank { plan.sourceLanguageCode }
            val targetCode = snapshot.targetLanguageCode.ifBlank { plan.targetLanguageCode }
            val sceneId = snapshot.scenePresetId.ifBlank { plan.scenePresetId }
            val direction = "${TranslationLanguageCatalog.source(sourceCode).label} → " +
                TranslationLanguageCatalog.target(targetCode).label
            val scene = snapshot.sceneLabel.ifBlank {
                SceneLibraryStore.resolve(this, TranslationMode.VIDEO, sceneId).label
            }
            tvHeroStatus.text = activeStatusText
            ViewCompat.setBackgroundTintList(viewVideoRunningStatusDot, getColorStateList(activeStatusColor))
            tvHeroSubStatus.text = buildString {
                append("其他应用音频 · ")
                append(if (overlayAllowed) "悬浮字幕已开启" else "悬浮字幕未授权")
                if (s.currentKeyLabel.isNotEmpty()) append(" · ").append(s.currentKeyLabel)
            }
            tvVideoElapsed.text = formatRunningElapsed(snapshot.startedAtMs)
            tvVideoRunningMeta.text = "$direction · $scene"
            tvAudioLevel.text = "$level%"
            pbAudio.progress = level
            renderConfirmedTranslations(videoConfirmedList, snapshot.confirmedTranslations)
            tvLiveZh.text = snapshot.currentTranslation.trim()
                .ifBlank { snapshot.confirmedTranslations.lastOrNull().orEmpty() }
                .ifBlank { "等待译文…" }
            tvLiveJa.text = snapshot.sourceTail.trim().ifBlank { "等待原文输入…" }
            tvTranscriptPath.text = if (s.transcriptPath.isNotEmpty()) {
                "本场记录：${s.transcriptPath}"
            } else {
                "自动保存到应用内历史"
            }
        }

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
