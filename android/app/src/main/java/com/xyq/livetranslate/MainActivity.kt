package com.xyq.livetranslate

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper

import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button

import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.xyq.livetranslate.ui.HistoryController
import com.xyq.livetranslate.ui.HistoryViews
import com.xyq.livetranslate.ui.SceneLibraryController
import com.xyq.livetranslate.ui.SceneLibraryViews
import com.xyq.livetranslate.ui.FriendGatewayBindingActions
import com.xyq.livetranslate.ui.SettingsController
import com.xyq.livetranslate.ui.SettingsDiagnosticsState
import com.xyq.livetranslate.ui.SettingsViews
import com.xyq.livetranslate.ui.PendingSessionSnapshot
import com.xyq.livetranslate.ui.SessionContextAccess
import com.xyq.livetranslate.ui.SessionCoordinator
import com.xyq.livetranslate.ui.SessionHost

class MainActivity : AppCompatActivity() {

    private companion object {
        const val STATE_INTERPRETATION_CONTEXT = "interpretation_context"
        const val STATE_VIDEO_CONTEXT = "video_context"
        const val STATE_VIDEO_URL = "video_url"
        const val STATE_MAIN_TAB = "main_tab"
        const val STATE_SETTINGS_SUB = "settings_sub"
        const val STATE_SETTINGS_RETURN_TAB = "settings_return_tab"
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
    private lateinit var historyController: HistoryController
    private lateinit var sceneLibraryController: SceneLibraryController
    private lateinit var settingsController: SettingsController
    private lateinit var sessionCoordinator: SessionCoordinator

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

    private var latestInterpAnalysisRequestId = ""
    private var latestVideoAnalysisRequestId = ""
    private var syncingLanguageControls = false
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
            sessionCoordinator.onAudioPermissionResult()
        }

    private val projLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            sessionCoordinator.onProjectionResult(res.resultCode, res.data)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        TranslationPlanStore.migrateLegacySavedPlans(this)
        setContentView(R.layout.activity_main)

        bindViews()
        historyController = HistoryController(
            context = this,
            views = HistoryViews.bind(rootLayout),
            openDetailPage = { returnTabId ->
                openSettingsSub(R.id.pageHistoryDetail, "历史详情", returnTabId)
            },
            toast = ::toast,
        )
        historyController.setup()
        sceneLibraryController = SceneLibraryController(
            context = this,
            views = SceneLibraryViews.bind(pageSceneLibrary),
            openPage = { returnTabId ->
                openSettingsSub(R.id.pageSceneLibrary, "场景库", returnTabId)
            },
            onSceneChanged = ::refreshSceneDependents,
            toast = ::toast,
        )
        sceneLibraryController.restoreState(savedInstanceState)
        sceneLibraryController.setup()
        friendBindingViewModel = ViewModelProvider(this)[FriendGatewayBindingViewModel::class.java]
        settingsController = SettingsController(
            context = this,
            views = SettingsViews.bind(rootLayout),
            friendActions = FriendGatewayBindingActions(
                bind = { code, version, enableOnSuccess ->
                    friendBindingViewModel.bind(code, version, enableOnSuccess)
                },
                clear = friendBindingViewModel::clearBinding,
                isBinding = friendBindingViewModel::isBinding,
            ),
            openSubPage = { pageId, title -> openSettingsSub(pageId, title) },
            openSceneLibrary = { mode -> openSceneLibrary(mode) },
            onTranslateParamsReset = ::syncLanguageControlsFromStore,
            postToUi = { action -> runOnUiThread { action() } },
            isHostActive = { !isFinishing && !isDestroyed },
            launchIntent = ::startActivity,
            toast = ::toast,
        )
        settingsController.setup()
        // LiveData 可能在 observe 时同步回放，必须晚于 controller 完整创建。
        friendBindingViewModel.state.observe(this, settingsController::renderFriendBindingState)
        applyWindowInsets()
        setupBottomNav()
        setupLanguageControls()
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

        sessionCoordinator = SessionCoordinator(
            context = this,
            persistDraftInputs = settingsController::persistDraftInputs,
            sessionContextAccess = object : SessionContextAccess {
                override fun current(mode: TranslationMode): SessionPromptContext {
                    val manual = when (mode) {
                        TranslationMode.INTERPRETATION ->
                            etInterpSessionContext.text?.toString().orEmpty()
                        TranslationMode.VIDEO ->
                            etVideoSessionContext.text?.toString().orEmpty()
                    }.trim()
                    return SessionPromptContext(manualContext = manual)
                }

                override fun clearAfterSuccessfulStart(mode: TranslationMode) {
                    when (mode) {
                        TranslationMode.INTERPRETATION -> etInterpSessionContext.setText("")
                        TranslationMode.VIDEO -> {
                            etVideoSessionContext.setText("")
                            etVideoSessionUrl.setText("")
                        }
                    }
                }
            },
            host = object : SessionHost {
                override fun checkPermission(permission: String): Boolean =
                    checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

                override fun requestPermissions(permissions: Array<String>) {
                    permLauncher.launch(permissions)
                }

                override fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(this@MainActivity)

                override fun openOverlaySettings() {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName"),
                        ),
                    )
                }

                override fun launchProjection(intent: Intent) {
                    projLauncher.launch(intent)
                }

                override fun startForegroundService(intent: Intent) {
                    this@MainActivity.startForegroundService(intent)
                }

                override fun startService(intent: Intent) {
                    this@MainActivity.startService(intent)
                }

                override fun openTranslationSettings() {
                    bottomNav.selectedItemId = R.id.nav_settings
                    openSettingsSub(R.id.pageSettingsTranslate, "翻译服务")
                }

                override fun toast(message: String) {
                    this@MainActivity.toast(message)
                }
            },
        ).also { it.restoreState(savedInstanceState) }

        btnToggle.setOnClickListener { sessionCoordinator.onModeToggle(StatusBus.MODE_VIDEO) }
        btnVideoStop.setOnClickListener { sessionCoordinator.onModeToggle(StatusBus.MODE_VIDEO) }
        btnInterpToggle.setOnClickListener { sessionCoordinator.onModeToggle(StatusBus.MODE_MIC) }
        btnInterpStop.setOnClickListener { sessionCoordinator.onModeToggle(StatusBus.MODE_MIC) }
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
        sessionCoordinator.saveState(outState)
        outState.putString(STATE_INTERPRETATION_CONTEXT, etInterpSessionContext.text?.toString().orEmpty())
        outState.putString(STATE_VIDEO_CONTEXT, etVideoSessionContext.text?.toString().orEmpty())
        outState.putString(STATE_VIDEO_URL, etVideoSessionUrl.text?.toString().orEmpty())
        outState.putInt(STATE_MAIN_TAB, currentMainTabId)
        outState.putInt(STATE_SETTINGS_SUB, settingsSubId)
        outState.putInt(STATE_SETTINGS_RETURN_TAB, settingsReturnTabId)
        sceneLibraryController.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        syncLanguageControlsFromStore()
        ui.removeCallbacks(refresh)
        ui.post(refresh)
    }

    override fun onPause() {
        if (::settingsController.isInitialized) settingsController.persistSecondAiInputs()
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
        if (itemId == R.id.nav_history) historyController.reload()
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
            sceneLibraryController.reload()
        }
    }

    private fun closeSettingsSub() {
        if (settingsSubId == R.id.pageSettingsProfileAi) settingsController.persistSecondAiInputs()
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
        sceneLibraryController.open(mode, returnTabId)
    }

    internal fun renderFriendGatewayBindingForTest(bindingInProgress: Boolean) {
        settingsController.renderFriendGatewayUiForTest(bindingInProgress)
    }

    internal fun installPendingSessionForTest(snapshot: PendingSessionSnapshot) {
        sessionCoordinator.installPendingSnapshotForTest(snapshot)
    }

    internal fun captureStartIntentForTest(): Intent =
        sessionCoordinator.captureStartIntentForTest()

    internal fun prepareSessionSettingsForTest(captureMode: String): Boolean =
        sessionCoordinator.prepareSessionSettingsForTest(captureMode)

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
        // 语言胶囊用 endIconMode=none（规避旧的 ExposedDropdownMenu 启动闪退），
        // 因此没有下拉委托来响应点击；这里手动接上「点击即弹出选项」，否则不可编辑
        // 的下拉框点了没反应、语言无法切换。
        listOf(controls.sourceView, controls.targetView).forEach { dropdown ->
            dropdown.setOnClickListener { dropdown.showDropDown() }
        }
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

    private fun setupLanguageControls() {
        bindModeLanguageControls(
            TranslationMode.INTERPRETATION,
            LanguageControls(acInterpSourceLang, acInterpTargetLang),
        )
        bindModeLanguageControls(
            TranslationMode.VIDEO,
            LanguageControls(acVideoSourceLang, acVideoTargetLang),
        )
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

        settingsController.renderDiagnostics(
            SettingsDiagnosticsState(
                serviceRunning = running,
                captureMode = mode,
                connState = conn,
                currentKeyLabel = s.currentKeyLabel,
                audioLevelPct = level,
                chunksSent = s.chunksSent.get(),
                transcriptPath = s.transcriptPath,
                jaTail = s.jaTail,
                zhTail = s.zhTail,
            ),
        )
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
}
