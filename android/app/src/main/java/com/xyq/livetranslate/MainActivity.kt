package com.xyq.livetranslate

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.xyq.livetranslate.ui.FriendGatewayBindingActions
import com.xyq.livetranslate.ui.HistoryController
import com.xyq.livetranslate.ui.HistoryViews
import com.xyq.livetranslate.ui.ModeHomeController
import com.xyq.livetranslate.ui.ModeHomeViews
import com.xyq.livetranslate.ui.PendingSessionSnapshot
import com.xyq.livetranslate.ui.SceneLibraryController
import com.xyq.livetranslate.ui.SceneLibraryViews
import com.xyq.livetranslate.ui.SessionContextController
import com.xyq.livetranslate.ui.SessionCoordinator
import com.xyq.livetranslate.ui.SessionHost
import com.xyq.livetranslate.ui.SettingsController
import com.xyq.livetranslate.ui.SettingsViews
import com.xyq.livetranslate.ui.UiRuntimeStatus

class MainActivity : AppCompatActivity() {
    private companion object {
        const val STATE_MAIN_TAB = "main_tab"
        const val STATE_SETTINGS_SUB = "settings_sub"
        const val STATE_SETTINGS_RETURN_TAB = "settings_return_tab"
    }

    // 壳导航（步骤 6 再整体抽出 MainNavigator）。
    private lateinit var rootLayout: View
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var pageInterp: View
    private lateinit var pageVideo: View
    private lateinit var pageHistory: View
    private lateinit var pageHistoryDetail: View
    private lateinit var pageSettings: View
    private lateinit var pageSettingsTranslate: View
    private lateinit var pageSettingsSubtitle: View
    private lateinit var pageSettingsProfileAi: View
    private lateinit var pageSettingsDiagnostics: View
    private lateinit var pageSettingsAbout: View
    private lateinit var pageSceneLibrary: View
    private lateinit var settingsSubViews: List<View>
    private var currentMainTabId = R.id.nav_interp
    private var settingsSubId = 0
    private var settingsReturnTabId = R.id.nav_settings

    private lateinit var historyController: HistoryController
    private lateinit var sceneLibraryController: SceneLibraryController
    private lateinit var settingsController: SettingsController
    private lateinit var sessionContextController: SessionContextController
    private lateinit var sessionCoordinator: SessionCoordinator
    private lateinit var modeHomeControllers: Map<TranslationMode, ModeHomeController>
    private lateinit var friendBindingViewModel: FriendGatewayBindingViewModel

    private val ui = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            renderStatus()
            ui.postDelayed(this, 300)
        }
    }

    // launcher 必须作为 Activity 字段无条件、固定顺序注册。
    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            sessionCoordinator.onAudioPermissionResult()
        }
    private val projLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            sessionCoordinator.onProjectionResult(result.resultCode, result.data)
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
        bindShellViews()
        friendBindingViewModel = ViewModelProvider(this)[FriendGatewayBindingViewModel::class.java]

        // 两个模式各 bind 一次；主页与本场上下文 controller 共享这两个对象。
        val interpViews = ModeHomeViews.bind(rootLayout, TranslationMode.INTERPRETATION)
        val videoViews = ModeHomeViews.bind(rootLayout, TranslationMode.VIDEO)
        val homeControllers = mutableMapOf<TranslationMode, ModeHomeController>()

        historyController = HistoryController(
            context = this,
            views = HistoryViews.bind(rootLayout),
            openDetailPage = { returnTabId ->
                openSettingsSub(R.id.pageHistoryDetail, "历史详情", returnTabId)
            },
            toast = ::toast,
        )
        sceneLibraryController = SceneLibraryController(
            context = this,
            views = SceneLibraryViews.bind(pageSceneLibrary),
            openPage = { returnTabId ->
                openSettingsSub(R.id.pageSceneLibrary, "场景库", returnTabId)
            },
            onSceneChanged = { mode -> homeControllers[mode]?.refreshConfiguration() },
            toast = ::toast,
        )
        sceneLibraryController.restoreState(savedInstanceState)

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
            onTranslateParamsReset = {
                homeControllers.values.forEach(ModeHomeController::refreshConfiguration)
            },
            postToUi = { action -> runOnUiThread { action() } },
            isHostActive = { !isFinishing && !isDestroyed },
            launchIntent = ::startActivity,
            toast = ::toast,
        )
        sessionContextController = SessionContextController(
            context = this,
            interpretationViews = interpViews,
            videoViews = videoViews,
            persistSecondAiInputs = settingsController::persistSecondAiInputs,
            postToUi = { action -> runOnUiThread { action() } },
            isHostActive = { !isFinishing && !isDestroyed },
            toast = ::toast,
        )
        sessionContextController.restoreState(savedInstanceState)

        sessionCoordinator = SessionCoordinator(
            context = this,
            persistDraftInputs = settingsController::persistDraftInputs,
            host = createSessionHost(),
        ).also { it.restoreState(savedInstanceState) }

        homeControllers[TranslationMode.INTERPRETATION] = ModeHomeController(
            context = this,
            mode = TranslationMode.INTERPRETATION,
            views = interpViews,
            toggleSession = sessionCoordinator::onModeToggle,
            openSceneLibrary = ::openSceneLibrary,
            openOverlaySettings = ::openOverlaySettings,
        )
        homeControllers[TranslationMode.VIDEO] = ModeHomeController(
            context = this,
            mode = TranslationMode.VIDEO,
            views = videoViews,
            toggleSession = sessionCoordinator::onModeToggle,
            openSceneLibrary = ::openSceneLibrary,
            openOverlaySettings = ::openOverlaySettings,
        )
        modeHomeControllers = homeControllers.toMap()
        sessionCoordinator.bindSessionContextAccess(sessionContextController)

        // 所有 callback 目标构造完成后才安装 listener / observer。
        historyController.setup()
        sceneLibraryController.setup()
        settingsController.setup()
        sessionContextController.setup()
        modeHomeControllers.values.forEach(ModeHomeController::setup)
        friendBindingViewModel.state.observe(this, settingsController::renderFriendBindingState)

        applyWindowInsets()
        setupBottomNav()
        renderStatus()

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
        sessionContextController.saveState(outState)
        outState.putInt(STATE_MAIN_TAB, currentMainTabId)
        outState.putInt(STATE_SETTINGS_SUB, settingsSubId)
        outState.putInt(STATE_SETTINGS_RETURN_TAB, settingsReturnTabId)
        sceneLibraryController.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (::modeHomeControllers.isInitialized) {
            modeHomeControllers.values.forEach(ModeHomeController::refreshConfiguration)
        }
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
        if (settingsSubId != 0) {
            closeSettingsSub()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun bindShellViews() {
        rootLayout = findViewById(R.id.rootLayout)
        bottomNav = findViewById(R.id.bottomNav)
        toolbar = findViewById(R.id.toolbar)
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
            pageHistoryDetail,
            pageSceneLibrary,
            pageSettingsTranslate,
            pageSettingsSubtitle,
            pageSettingsProfileAi,
            pageSettingsDiagnostics,
            pageSettingsAbout,
        )
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(bottomNav) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = bars.bottom)
            insets
        }
    }

    private fun setupBottomNav() {
        toolbar.setNavigationOnClickListener {
            if (settingsSubId != 0) closeSettingsSub()
        }
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
        toolbar.title = "流译"
        toolbar.setLogo(R.drawable.ic_brand_translate_24)
        toolbar.navigationIcon = null
        bottomNav.visibility = View.VISIBLE
        // 不得在选中回调内再次写 selectedItemId，Material 会无限重入。
        currentMainTabId = itemId
        if (itemId == R.id.nav_history) historyController.reload()
    }

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
        if (pageId == R.id.pageSceneLibrary) sceneLibraryController.reload()
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

    private fun createSessionHost(): SessionHost = object : SessionHost {
        override fun checkPermission(permission: String): Boolean =
            checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

        override fun requestPermissions(permissions: Array<String>) = permLauncher.launch(permissions)
        override fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(this@MainActivity)
        override fun openOverlaySettings() = this@MainActivity.openOverlaySettings()
        override fun launchProjection(intent: Intent) = projLauncher.launch(intent)
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
        override fun toast(message: String) = this@MainActivity.toast(message)
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    /** 每 tick 只在这里采样一次，再把同一不可变对象分发给三个页面。 */
    private fun renderStatus() {
        val status = UiRuntimeStatus.capture(
            overlayAllowed = Settings.canDrawOverlays(this),
        )
        modeHomeControllers.getValue(TranslationMode.INTERPRETATION).render(status)
        modeHomeControllers.getValue(TranslationMode.VIDEO).render(status)
        settingsController.renderDiagnostics(status.toDiagnostics())
    }

    internal fun openSceneLibrary(mode: TranslationMode, returnTabId: Int = R.id.nav_settings) {
        sceneLibraryController.open(mode, returnTabId)
    }

    internal fun renderStatusForTest() = renderStatus()

    internal fun refreshHomeScenesForTest(mode: TranslationMode) {
        modeHomeControllers.getValue(mode).refreshHomeScenesForTest()
    }

    internal fun renderFriendGatewayBindingForTest(bindingInProgress: Boolean) {
        settingsController.renderFriendGatewayUiForTest(bindingInProgress)
    }

    internal fun installPendingSessionForTest(snapshot: PendingSessionSnapshot) {
        sessionCoordinator.installPendingSnapshotForTest(snapshot)
    }

    internal fun captureStartIntentForTest(): Intent = sessionCoordinator.captureStartIntentForTest()

    internal fun prepareSessionSettingsForTest(captureMode: String): Boolean =
        sessionCoordinator.prepareSessionSettingsForTest(captureMode)

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
