package com.xyq.livetranslate

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import com.xyq.livetranslate.ui.ModeHomeController
import com.xyq.livetranslate.ui.ModeHomeViews
import com.xyq.livetranslate.ui.PendingSessionSnapshot
import com.xyq.livetranslate.ui.UiRuntimeStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class MainActivityStartupTest {
    @After
    fun resetStatusBus() {
        StatusBus.serviceRunning = false
        StatusBus.captureMode = ""
        StatusBus.reset()
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        listOf(
            "scene_library_v1",
            "translation_plans_v3",
            "settings",
            "friend_gateway_v1",
        ).forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
        HistoryStore.list(context).forEach { HistoryStore.delete(context, it.fileName) }
    }

    @Test
    fun launchesWithoutCrashingAndBindsFinalUi() = withActivity { activity ->
        listOf(
            // MainNavigatorViews.bind(root) 必须覆盖完整 shell，且每个 page root 只有这一处绑定真源。
            R.id.toolbar,
            R.id.bottomNav,
            R.id.pageContainer,
            R.id.pageInterp,
            R.id.pageVideo,
            R.id.pageHistory,
            R.id.pageSettings,
            R.id.pageHistoryDetail,
            R.id.pageSettingsTranslate,
            R.id.pageSettingsSubtitle,
            R.id.pageSettingsProfileAi,
            R.id.pageSettingsDiagnostics,
            R.id.pageSettingsAbout,
            R.id.pageSceneLibrary,
            // 各 controller 的关键控件仍必须可绑定。
            R.id.rowSetSceneLibrary,
            R.id.sceneLibraryList,
            R.id.fabNewScene,
            R.id.btnInterpOpenPlanLibrary,
            R.id.interpIdleContent,
            R.id.interpRunningContent,
            R.id.videoIdleContent,
            R.id.videoRunningContent,
            R.id.etHistorySearch,
            R.id.viewInterpRunningStatusDot,
            R.id.viewVideoRunningStatusDot,
        ).forEach { id ->
            require(activity.findViewById<View>(id) != null) { "缺少视图 id=$id" }
        }
    }

    @Test
    fun historyTabReloadsSessionsCreatedAfterStartup() = withActivity { activity ->
        val historyList = activity.findViewById<android.widget.LinearLayout>(R.id.historyList)
        assertEquals(0, historyList.childCount)

        HistoryStore.save(
            activity,
            HistorySession(
                id = "reload-after-startup",
                title = "切入历史后可见",
                mode = TranslationMode.INTERPRETATION,
                sourceLanguageCode = "ja",
                targetLanguageCode = "zh",
                scenePresetId = "scene-history-test",
                sceneLabel = "历史测试场景",
                contextSummary = "",
                startedAt = 1_000L,
                endedAt = 2_000L,
                segments = listOf(TranscriptSegment(500L, "こんにちは", "你好")),
            ),
        )

        activity.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(
            R.id.bottomNav,
        ).selectedItemId = R.id.nav_history

        // 日期分组头 + 会话卡
        assertEquals(2, historyList.childCount)
        val sessionCard = (0 until historyList.childCount)
            .map(historyList::getChildAt)
            .first { it.findViewById<android.widget.TextView>(R.id.tvHistoryItemTitle) != null }
        assertEquals(
            "历史测试场景",
            sessionCard.findViewById<android.widget.TextView>(R.id.tvHistoryItemTitle).text,
        )

        sessionCard.performClick()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pageHistoryDetail).visibility)
        activity.onBackPressed()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pageHistory).visibility)
    }

    @Test
    fun languageDropdownsOpenOnTap() = withActivity { activity ->
        // endIconMode=none 的下拉没有下拉委托，必须自己接 onClick → showDropDown，
        // 否则语言胶囊点了没反应、根本切换不了语言。
        listOf(
            R.id.acInterpSourceLang,
            R.id.acInterpTargetLang,
            R.id.acVideoSourceLang,
            R.id.acVideoTargetLang,
        ).forEach { id ->
            assertTrue(
                "语言下拉 id=$id 缺少点击监听，点击无法弹出选项",
                activity.findViewById<View>(id).hasOnClickListeners(),
            )
        }
    }

    @Test
    fun fourSessionButtonsAndModeSpecificEntriesDelegateCorrectly() = withActivity { activity ->
        val inflated = LayoutInflater.from(activity).inflate(R.layout.activity_main, null)
        val root = inflated.findViewById<View>(R.id.rootLayout)
        val toggles = mutableListOf<String>()
        val sceneEntries = mutableListOf<Pair<TranslationMode, Int>>()
        var overlayEntries = 0
        val interp = ModeHomeController(
            context = activity,
            mode = TranslationMode.INTERPRETATION,
            views = ModeHomeViews.bind(root, TranslationMode.INTERPRETATION),
            toggleSession = toggles::add,
            openSceneLibrary = { mode, tab -> sceneEntries += mode to tab },
            openOverlaySettings = { overlayEntries++ },
        )
        val video = ModeHomeController(
            context = activity,
            mode = TranslationMode.VIDEO,
            views = ModeHomeViews.bind(root, TranslationMode.VIDEO),
            toggleSession = toggles::add,
            openSceneLibrary = { mode, tab -> sceneEntries += mode to tab },
            openOverlaySettings = { overlayEntries++ },
        )
        interp.setup()
        video.setup()

        listOf(R.id.btnInterpToggle, R.id.btnInterpStop, R.id.btnToggle, R.id.btnVideoStop)
            .forEach { root.findViewById<View>(it).performClick() }
        listOf(
            R.id.cardInterpPlan,
            R.id.btnInterpOpenPlanLibrary,
            R.id.cardVideoPlan,
            R.id.btnVideoOpenPlanLibrary,
        ).forEach { root.findViewById<View>(it).performClick() }
        root.findViewById<View>(R.id.rowOverlayPermission).performClick()
        root.findViewById<View>(R.id.btnOverlayPermissionSettings).performClick()

        assertEquals(
            listOf(StatusBus.MODE_MIC, StatusBus.MODE_MIC, StatusBus.MODE_VIDEO, StatusBus.MODE_VIDEO),
            toggles,
        )
        assertEquals(
            listOf(
                TranslationMode.INTERPRETATION to R.id.nav_interp,
                TranslationMode.INTERPRETATION to R.id.nav_interp,
                TranslationMode.VIDEO to R.id.nav_video,
                TranslationMode.VIDEO to R.id.nav_video,
            ),
            sceneEntries,
        )
        assertEquals(2, overlayEntries)
    }

    @Test
    fun homeSceneGroupsAlwaysKeepOneSelection() = withActivity { activity ->
        listOf(R.id.chipGroupInterpHomeScenes, R.id.chipGroupVideoHomeScenes).forEach { id ->
            val group = activity.findViewById<com.google.android.material.chip.ChipGroup>(id)
            assertTrue(group.isSelectionRequired)
            assertTrue(group.checkedChipId != View.NO_ID)
        }
    }

    @Test
    fun homeSceneSelectionFallsBackAfterDeleteAndReturnsAfterReset() = withActivity { activity ->
        val mode = TranslationMode.INTERPRETATION
        val group = activity.findViewById<com.google.android.material.chip.ChipGroup>(
            R.id.chipGroupInterpHomeScenes,
        )
        val originalId = TranslationPlanStore.loadDraft(activity, mode).scenePresetId

        assertTrue(SceneLibraryStore.delete(activity, mode, originalId))
        refreshHomeScenes(activity, mode, group)
        assertTrue(group.checkedChipId != View.NO_ID)
        assertEquals(
            SceneLibraryStore.default(activity, mode).label,
            group.findViewById<com.google.android.material.chip.Chip>(group.checkedChipId).text.toString(),
        )

        SceneLibraryStore.reset(activity, mode)
        refreshHomeScenes(activity, mode, group)
        assertEquals(
            SceneLibraryStore.resolve(activity, mode, originalId).label,
            group.findViewById<com.google.android.material.chip.Chip>(group.checkedChipId).text.toString(),
        )
    }

    @Test
    fun sceneLibrarySettingsEntryOpensSeededLibrary() = withActivity { activity ->
        activity.findViewById<View>(R.id.rowSetSceneLibrary).performClick()

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pageSceneLibrary).visibility)
        val list = activity.findViewById<android.widget.LinearLayout>(R.id.sceneLibraryList)
        assertEquals(DefaultSceneCatalog.defaults(TranslationMode.INTERPRETATION).size, list.childCount)
    }

    @Test
    fun homeCardOpensUnifiedSceneLibrary() = withActivity { activity ->
        activity.findViewById<View>(R.id.cardInterpPlan).performClick()

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pageSceneLibrary).visibility)
        assertEquals(
            R.id.btnSceneLibraryInterp,
            activity.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(
                R.id.toggleSceneLibraryMode,
            ).checkedButtonId,
        )

        activity.onBackPressed()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pageInterp).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.pageSceneLibrary).visibility)
    }

    @Test
    fun sceneUseRefreshesHomeDependentsThroughControllerCallback() = withActivity { activity ->
        val mode = TranslationMode.INTERPRETATION
        val created = requireNotNull(
            SceneLibraryStore.create(activity, mode, "回调接线场景", "验证场景变更会刷新主页"),
        )
        activity.openSceneLibrary(mode, R.id.nav_interp)

        val list = activity.findViewById<android.widget.LinearLayout>(R.id.sceneLibraryList)
        val card = (0 until list.childCount)
            .map(list::getChildAt)
            .first {
                it.findViewById<android.widget.TextView>(R.id.tvSceneName).text.toString() == created.label
            }
        card.performClick()

        assertEquals(created.id, TranslationPlanStore.loadDraft(activity, mode).scenePresetId)
        val group = activity.findViewById<com.google.android.material.chip.ChipGroup>(
            R.id.chipGroupInterpHomeScenes,
        )
        assertEquals(
            created.label,
            group.findViewById<com.google.android.material.chip.Chip>(group.checkedChipId).text.toString(),
        )
        assertTrue(
            activity.findViewById<android.widget.TextView>(R.id.tvInterpPlanSummary)
                .text
                .startsWith(created.label),
        )
    }

    @Test
    fun videoSceneLibrarySurvivesRecreateAndReturnsToVideoHome() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val interpOnly = requireNotNull(
                SceneLibraryStore.create(
                    activity,
                    TranslationMode.INTERPRETATION,
                    "仅同传 Bundle 场景",
                    "不应在恢复后的视频列表出现",
                ),
            )
            val videoOnly = requireNotNull(
                SceneLibraryStore.create(
                    activity,
                    TranslationMode.VIDEO,
                    "仅视频 Bundle 场景",
                    "应由 controller Bundle 模式恢复",
                ),
            )
            activity.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(
                R.id.bottomNav,
            ).selectedItemId = R.id.nav_video
            activity.openSceneLibrary(TranslationMode.VIDEO, R.id.nav_video)

            // 禁用 Toggle 及子按钮的 View 状态保存，确保恢复来源只能是 controller Bundle。
            activity.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(
                R.id.toggleSceneLibraryMode,
            ).apply {
                isSaveEnabled = false
                (0 until childCount).forEach { index -> getChildAt(index).isSaveEnabled = false }
            }

            controller.recreate()
            val recreated = controller.get()
            assertEquals(View.VISIBLE, recreated.findViewById<View>(R.id.pageSceneLibrary).visibility)
            assertEquals(
                R.id.btnSceneLibraryVideo,
                recreated.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(
                    R.id.toggleSceneLibraryMode,
                ).checkedButtonId,
            )
            val list = recreated.findViewById<android.widget.LinearLayout>(R.id.sceneLibraryList)
            val labels = (0 until list.childCount).map { index ->
                list.getChildAt(index)
                    .findViewById<android.widget.TextView>(R.id.tvSceneName)
                    .text
                    .toString()
            }
            assertTrue(videoOnly.label in labels)
            assertFalse(interpOnly.label in labels)

            recreated.onBackPressed()
            assertEquals(View.VISIBLE, recreated.findViewById<View>(R.id.pageVideo).visibility)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun idleStateShowsConfigurationAndHidesRunningPanels() = withActivity { activity ->
        renderStatus(activity)

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.interpIdleContent).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.interpRunningContent).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.videoIdleContent).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.videoRunningContent).visibility)
    }

    @Test
    fun microphoneModeOnlyShowsInterpretationRunningPanel() {
        StatusBus.serviceRunning = true
        StatusBus.captureMode = StatusBus.MODE_MIC
        StatusBus.startSession(TranslationPlan.default(TranslationMode.INTERPRETATION), 1_000L)
        withActivity { activity ->
            renderStatus(activity)

            assertEquals(View.GONE, activity.findViewById<View>(R.id.interpIdleContent).visibility)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.interpRunningContent).visibility)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.videoIdleContent).visibility)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.videoRunningContent).visibility)
            assertFalse(activity.findViewById<View>(R.id.btnToggle).isEnabled)
        }
    }

    @Test
    fun videoModeOnlyShowsVideoRunningPanel() {
        StatusBus.serviceRunning = true
        StatusBus.captureMode = StatusBus.MODE_VIDEO
        StatusBus.startSession(TranslationPlan.default(TranslationMode.VIDEO), 1_000L)
        withActivity { activity ->
            renderStatus(activity)

            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.interpIdleContent).visibility)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.interpRunningContent).visibility)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.videoIdleContent).visibility)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.videoRunningContent).visibility)
            assertFalse(activity.findViewById<View>(R.id.btnInterpToggle).isEnabled)
        }
    }

    @Test
    fun runtimeStatusCopiesAllDiagnosticsAndMutableSessionData() {
        StatusBus.serviceRunning = true
        StatusBus.captureMode = StatusBus.MODE_VIDEO
        StatusBus.connState = "ready"
        StatusBus.currentKeyLabel = "Key 2/3"
        StatusBus.transcriptPath = "/private/session.json"
        StatusBus.audioLevelPct = 73
        StatusBus.chunksSent.set(44)
        StatusBus.jaTail = "source-tail"
        StatusBus.zhTail = "target-tail"
        StatusBus.startSession(TranslationPlan.default(TranslationMode.VIDEO), 1_000L, "冻结场景")
        StatusBus.updateSessionSubtitles(listOf("第一行", "第二行"), "当前行", "原文尾巴")

        val status = UiRuntimeStatus.capture(overlayAllowed = true, sampledAtMs = 5_000L)
        val diagnostics = status.toDiagnostics()
        StatusBus.updateSessionSubtitles(listOf("后来行"), "后来当前行", "后来原文")

        assertEquals(listOf("第一行", "第二行"), status.confirmedTranslations)
        assertEquals("当前行", status.currentTranslation)
        assertEquals("原文尾巴", status.sourceTail)
        assertEquals("Key 2/3", diagnostics.currentKeyLabel)
        assertEquals("/private/session.json", diagnostics.transcriptPath)
        assertEquals(44L, diagnostics.chunksSent)
        assertEquals("source-tail", diagnostics.jaTail)
        assertEquals("target-tail", diagnostics.zhTail)
    }

    @Test
    fun sessionContextAndVideoUrlRestoreFromControllerBundle() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            controller.get().findViewById<android.widget.EditText>(R.id.etInterpSessionContext).apply {
                isSaveEnabled = false
                setText("同传 Bundle 上下文")
            }
            controller.get().findViewById<android.widget.EditText>(R.id.etVideoSessionContext).apply {
                isSaveEnabled = false
                setText("视频 Bundle 上下文")
            }
            controller.get().findViewById<android.widget.EditText>(R.id.etVideoSessionUrl).apply {
                isSaveEnabled = false
                setText("https://youtu.be/bundle")
            }

            controller.recreate()

            assertEquals(
                "同传 Bundle 上下文",
                controller.get().findViewById<android.widget.EditText>(R.id.etInterpSessionContext).text.toString(),
            )
            assertEquals(
                "视频 Bundle 上下文",
                controller.get().findViewById<android.widget.EditText>(R.id.etVideoSessionContext).text.toString(),
            )
            assertEquals(
                "https://youtu.be/bundle",
                controller.get().findViewById<android.widget.EditText>(R.id.etVideoSessionUrl).text.toString(),
            )
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun captureIntentCarriesCompleteFrozenSessionSnapshotWithoutPlaintextCredentials() =
        withActivity { activity ->
            val snapshot = distinctPendingSnapshot()
            setPendingSnapshot(activity, snapshot)

            assertCaptureIntentMatches(snapshot, captureStartIntentForPendingMode(activity))
        }

    @Test
    fun pendingSessionSnapshotSurvivesActivityRecreation() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val snapshot = distinctPendingSnapshot()
            setPendingSnapshot(controller.get(), snapshot)
            assertCaptureIntentMatches(snapshot, captureStartIntentForPendingMode(controller.get()))

            controller.recreate()

            assertCaptureIntentMatches(snapshot, captureStartIntentForPendingMode(controller.get()))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun friendBindingDisablesAllMutableControls() = withActivity { activity ->
        activity.renderFriendGatewayBindingForTest(bindingInProgress = true)

        assertFalse(activity.findViewById<View>(R.id.swFriendGateway).isEnabled)
        assertFalse(activity.findViewById<View>(R.id.etFriendInviteCode).isEnabled)
        assertFalse(activity.findViewById<View>(R.id.btnBindFriendGateway).isEnabled)
        assertFalse(activity.findViewById<View>(R.id.btnClearFriendGateway).isEnabled)
    }

    @Test
    fun prepareSessionSettingsPersistsControllerDraftInputs() = withActivity { activity ->
        FriendGatewayStore.usePersonal(activity)
        val settingsPrefs = activity.getSharedPreferences("settings", Context.MODE_PRIVATE)
        settingsPrefs.edit().remove("apiKeysEnc").commit()
        activity.findViewById<android.widget.EditText>(R.id.etApiKeys).setText("[REDACTED]")
        activity.findViewById<android.widget.EditText>(R.id.etBaseUrl)
            .setText("https://draft.example.test")

        activity.prepareSessionSettingsForTest(StatusBus.MODE_MIC)

        // Robolectric 没有 AndroidKeyStore；这里只验证保存入口写入了加密偏好项。
        assertTrue(settingsPrefs.contains("apiKeysEnc"))
        assertEquals("https://draft.example.test", SettingsStore.baseUrl(activity))
    }

    @Test
    fun friendBindingStateSynchronousReplaySurvivesRecreate() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val viewModel = MainActivity::class.java.getDeclaredField("friendBindingViewModel").run {
                isAccessible = true
                get(controller.get()) as FriendGatewayBindingViewModel
            }
            viewModel.clearBinding()

            controller.recreate()

            assertEquals(
                "当前使用你自己的 API Key",
                controller.get().findViewById<android.widget.TextView>(R.id.tvFriendGatewayStatus).text,
            )
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun withActivity(block: (MainActivity) -> Unit) {
        val controller: ActivityController<MainActivity> =
            Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            block(controller.get())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun renderStatus(activity: MainActivity) {
        activity.renderStatusForTest()
    }

    private fun distinctPendingSnapshot(): PendingSessionSnapshot = PendingSessionSnapshot(
        captureMode = StatusBus.MODE_MIC,
        credentialMode = FriendGatewayStore.MODE_FRIEND,
        prompt = "frozen-prompt",
        sourceLanguageCode = "ja",
        targetLanguageCode = "zh",
        scenePresetId = "frozen-scene-id",
        sceneLabel = "frozen-scene-label",
        sessionTitle = "frozen-session-title",
        sessionContext = "frozen-session-context",
    ).also { snapshot ->
        val values = listOf(
            snapshot.captureMode,
            snapshot.credentialMode,
            snapshot.prompt,
            snapshot.sourceLanguageCode,
            snapshot.targetLanguageCode,
            snapshot.scenePresetId,
            snapshot.sceneLabel,
            snapshot.sessionTitle,
            snapshot.sessionContext,
        )
        check(values.distinct().size == values.size)
    }

    private fun setPendingSnapshot(activity: MainActivity, snapshot: PendingSessionSnapshot) {
        activity.installPendingSessionForTest(snapshot)
    }

    private fun captureStartIntentForPendingMode(activity: MainActivity): Intent =
        activity.captureStartIntentForTest()

    private fun assertCaptureIntentMatches(snapshot: PendingSessionSnapshot, intent: Intent) {
        assertEquals(CaptureService.ACTION_START, intent.action)
        assertEquals(snapshot.captureMode, intent.getStringExtra(CaptureService.EXTRA_MODE))
        assertEquals(
            snapshot.credentialMode,
            intent.getStringExtra(CaptureService.EXTRA_CREDENTIAL_MODE),
        )
        assertEquals(snapshot.prompt, intent.getStringExtra(CaptureService.EXTRA_SESSION_PROMPT))
        assertEquals(snapshot.sourceLanguageCode, intent.getStringExtra(CaptureService.EXTRA_SOURCE_LANGUAGE))
        assertEquals(snapshot.targetLanguageCode, intent.getStringExtra(CaptureService.EXTRA_TARGET_LANGUAGE))
        assertEquals(snapshot.scenePresetId, intent.getStringExtra(CaptureService.EXTRA_SCENE_PRESET))
        assertEquals(snapshot.sceneLabel, intent.getStringExtra(CaptureService.EXTRA_SCENE_LABEL))
        assertEquals(snapshot.sessionTitle, intent.getStringExtra(CaptureService.EXTRA_SESSION_TITLE))
        assertEquals(snapshot.sessionContext, intent.getStringExtra(CaptureService.EXTRA_SESSION_CONTEXT))

        val extraKeys = requireNotNull(intent.extras).keySet()
        assertEquals(
            setOf(
                CaptureService.EXTRA_MODE,
                CaptureService.EXTRA_CREDENTIAL_MODE,
                CaptureService.EXTRA_SESSION_PROMPT,
                CaptureService.EXTRA_SOURCE_LANGUAGE,
                CaptureService.EXTRA_TARGET_LANGUAGE,
                CaptureService.EXTRA_SCENE_PRESET,
                CaptureService.EXTRA_SCENE_LABEL,
                CaptureService.EXTRA_SESSION_TITLE,
                CaptureService.EXTRA_SESSION_CONTEXT,
            ),
            extraKeys,
        )
        assertFalse(
            "启动 Intent 不应携带明文 key/token extra",
            extraKeys.any { key ->
                key.contains("key", ignoreCase = true) || key.contains("token", ignoreCase = true)
            },
        )
    }

    private fun refreshHomeScenes(
        activity: MainActivity,
        mode: TranslationMode,
        group: com.google.android.material.chip.ChipGroup,
    ) {
        activity.refreshHomeScenesForTest(mode)
        check(group.childCount > 0)
    }
}
