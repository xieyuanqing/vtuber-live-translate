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
import androidx.lifecycle.ViewModelProvider
import com.xyq.livetranslate.ui.FriendGatewayBindingActions
import com.xyq.livetranslate.ui.HistoryController
import com.xyq.livetranslate.ui.HistoryViews
import com.xyq.livetranslate.ui.MainNavigator
import com.xyq.livetranslate.ui.MainNavigatorViews
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
    private lateinit var navigator: MainNavigator
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
        TranslationPlanStore.migrateLegacySavedPlans(this)
        setContentView(R.layout.activity_main)
        friendBindingViewModel = ViewModelProvider(this)[FriendGatewayBindingViewModel::class.java]
        val root = findViewById<View>(R.id.rootLayout)
        val navigatorViews = MainNavigatorViews.bind(root)

        // Navigator 可先构造，但 setup 必须最后执行，避免恢复动作早于 controller 初始化。
        navigator = MainNavigator(
            views = navigatorViews,
            onMainPageShown = { pageId ->
                if (pageId == R.id.nav_history) historyController.reload()
            },
            onSubPageShown = { pageId ->
                if (pageId == R.id.pageSceneLibrary) sceneLibraryController.reload()
            },
            beforeSubPageClosed = { pageId ->
                if (pageId == R.id.pageSettingsProfileAi) settingsController.persistSecondAiInputs()
            },
        )

        // 两个模式各 bind 一次；主页与本场上下文 controller 共享这两个对象。
        val interpViews = ModeHomeViews.bind(root, TranslationMode.INTERPRETATION)
        val videoViews = ModeHomeViews.bind(root, TranslationMode.VIDEO)
        val homeControllers = mutableMapOf<TranslationMode, ModeHomeController>()

        historyController = HistoryController(
            context = this,
            views = HistoryViews.bind(root),
            openDetailPage = { returnTabId ->
                navigator.openSub(R.id.pageHistoryDetail, returnTabId)
            },
            toast = ::toast,
        )
        sceneLibraryController = SceneLibraryController(
            context = this,
            views = SceneLibraryViews.bind(navigatorViews.pageSceneLibrary),
            openPage = { returnTabId ->
                navigator.openSub(R.id.pageSceneLibrary, returnTabId)
            },
            onSceneChanged = { mode -> homeControllers[mode]?.refreshConfiguration() },
            toast = ::toast,
        )
        sceneLibraryController.restoreState(savedInstanceState)

        settingsController = SettingsController(
            context = this,
            views = SettingsViews.bind(root),
            friendActions = FriendGatewayBindingActions(
                bind = { code, version, enableOnSuccess ->
                    friendBindingViewModel.bind(code, version, enableOnSuccess)
                },
                clear = friendBindingViewModel::clearBinding,
                isBinding = friendBindingViewModel::isBinding,
            ),
            openSubPage = { pageId -> navigator.openSub(pageId) },
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

        renderStatus()
        navigator.setup(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        sessionCoordinator.saveState(outState)
        sessionContextController.saveState(outState)
        navigator.saveState(outState)
        sceneLibraryController.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (::sessionCoordinator.isInitialized) {
            sessionCoordinator.onHostResume()
        }
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
        if (!navigator.handleBack()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
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
            if (navigator.showMain(R.id.nav_settings)) {
                navigator.openSub(R.id.pageSettingsTranslate)
            }
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
