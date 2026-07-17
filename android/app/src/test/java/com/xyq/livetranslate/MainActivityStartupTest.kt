package com.xyq.livetranslate

import android.content.Context
import android.view.View
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
        listOf("scene_library_v1", "translation_plans_v3").forEach { name ->
            androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
                .getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
    }

    @Test
    fun launchesWithoutCrashingAndBindsFinalUi() = withActivity { activity ->
        listOf(
            R.id.pagePlanLibrary,
            R.id.fabNewPlan,
            R.id.rowSetSceneLibrary,
            R.id.pageSceneLibrary,
            R.id.sceneLibraryList,
            R.id.fabNewScene,
            R.id.btnInterpOpenPlanLibrary,
            R.id.interpIdleContent,
            R.id.interpRunningContent,
            R.id.videoIdleContent,
            R.id.videoRunningContent,
            R.id.etHistorySearch,
            R.id.pageHistoryDetail,
            R.id.viewInterpRunningStatusDot,
            R.id.viewVideoRunningStatusDot,
        ).forEach { id ->
            require(activity.findViewById<View>(id) != null) { "缺少视图 id=$id" }
        }
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
    fun manageScenesConfirmsBeforeDiscardingEditedPlan() = withActivity { activity ->
        activity.findViewById<View>(R.id.cardInterpPlan).performClick()
        activity.supportFragmentManager.executePendingTransactions()
        val sheet = activity.supportFragmentManager.findFragmentByTag("plan_interpretation")
            as TranslationPlanBottomSheet
        sheet.requireView().findViewById<android.widget.EditText>(R.id.etPlanName).setText("未保存方案")

        sheet.requireView().findViewById<View>(R.id.btnManageScenes).performClick()

        assertTrue(org.robolectric.shadows.ShadowDialog.getLatestDialog().isShowing)
        assertTrue(sheet.isAdded)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.pageSceneLibrary).visibility)
    }

    @Test
    fun sceneLibraryOpenedFromPlanLibraryReturnsToPlanLibrary() = withActivity { activity ->
        activity.findViewById<View>(R.id.rowSetPlanLibrary).performClick()
        activity.onSceneLibraryRequested(TranslationMode.INTERPRETATION)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pageSceneLibrary).visibility)

        activity.onBackPressed()

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pagePlanLibrary).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.pageSceneLibrary).visibility)
    }

    @Test
    fun videoSceneLibrarySurvivesRecreateAndReturnsToVideoHome() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            controller.get().findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(
                R.id.bottomNav,
            ).selectedItemId = R.id.nav_video
            controller.get().onSceneLibraryRequested(TranslationMode.VIDEO)

            controller.recreate()
            val recreated = controller.get()
            assertEquals(View.VISIBLE, recreated.findViewById<View>(R.id.pageSceneLibrary).visibility)
            assertEquals(
                R.id.btnSceneLibraryVideo,
                recreated.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(
                    R.id.toggleSceneLibraryMode,
                ).checkedButtonId,
            )

            recreated.onBackPressed()
            assertEquals(View.VISIBLE, recreated.findViewById<View>(R.id.pageVideo).visibility)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun sceneLibraryParentPlanSurvivesRecreate() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            controller.get().findViewById<View>(R.id.rowSetPlanLibrary).performClick()
            controller.get().findViewById<View>(R.id.btnPlanLibraryVideo).performClick()
            controller.get().onSceneLibraryRequested(TranslationMode.VIDEO)

            controller.recreate()
            val recreated = controller.get()
            recreated.onBackPressed()

            assertEquals(View.VISIBLE, recreated.findViewById<View>(R.id.pagePlanLibrary).visibility)
            assertEquals(View.GONE, recreated.findViewById<View>(R.id.pageSceneLibrary).visibility)
            assertEquals(
                R.id.btnPlanLibraryVideo,
                recreated.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(
                    R.id.togglePlanLibraryMode,
                ).checkedButtonId,
            )
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
        MainActivity::class.java.getDeclaredMethod("renderStatus").apply {
            isAccessible = true
            invoke(activity)
        }
    }

    private fun refreshHomeScenes(
        activity: MainActivity,
        mode: TranslationMode,
        group: com.google.android.material.chip.ChipGroup,
    ) {
        MainActivity::class.java.getDeclaredMethod(
            "setupHomeSceneChips",
            TranslationMode::class.java,
        ).apply {
            isAccessible = true
            invoke(activity, mode)
        }
        check(group.childCount > 0)
    }
}
