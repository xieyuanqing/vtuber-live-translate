package com.xyq.livetranslate.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.xyq.livetranslate.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class MainNavigatorTest {
    @Test
    fun accessGateRejectsWithoutChangingPageAndObserverOnlySeesSuccessfulNavigation() =
        withNavigatorFixture { fixture ->
            val denied = mutableSetOf<NavigationDestination>()
            val observed = mutableListOf<NavigationDestination>()
            val navigator = MainNavigator(
                views = fixture.views,
                accessGate = { destination -> destination !in denied },
                onDestinationShown = observed::add,
            )
            navigator.setup(null)
            observed.clear()

            val deniedSub = NavigationDestination.SubPage(R.id.pageSettingsProfileAi)
            denied += deniedSub
            assertFalse(navigator.openSub(R.id.pageSettingsProfileAi, R.id.nav_interp))
            assertEquals(View.VISIBLE, fixture.views.pageInterp.visibility)
            assertEquals(View.GONE, fixture.views.pageSettingsProfileAi.visibility)
            assertTrue(observed.isEmpty())

            assertTrue(navigator.showMain(R.id.nav_history))
            assertEquals(View.VISIBLE, fixture.views.pageHistory.visibility)
            assertEquals(
                listOf(NavigationDestination.MainPage(R.id.nav_history)),
                observed,
            )

            denied += NavigationDestination.MainPage(R.id.nav_settings)
            assertFalse(navigator.showMain(R.id.nav_settings))
            assertEquals(View.VISIBLE, fixture.views.pageHistory.visibility)
            assertEquals(
                listOf(NavigationDestination.MainPage(R.id.nav_history)),
                observed,
            )
        }

    @Test
    fun restoresSubPageReturnTabAndBackClosesItBeforeFallingThrough() =
        withNavigatorFixture { fixture ->
            val mainHooks = mutableListOf<Int>()
            val subHooks = mutableListOf<Int>()
            val closedHooks = mutableListOf<Int>()
            val observed = mutableListOf<NavigationDestination>()
            val restoredState = Bundle().apply {
                putInt(MainNavigator.STATE_MAIN_TAB, R.id.nav_video)
                putInt(MainNavigator.STATE_SETTINGS_SUB, R.id.pageSceneLibrary)
                putInt(MainNavigator.STATE_SETTINGS_RETURN_TAB, R.id.nav_video)
            }
            val navigator = MainNavigator(
                views = fixture.views,
                onMainPageShown = mainHooks::add,
                onSubPageShown = subHooks::add,
                beforeSubPageClosed = closedHooks::add,
                onDestinationShown = observed::add,
            )

            navigator.setup(restoredState)

            assertEquals(View.VISIBLE, fixture.views.pageSceneLibrary.visibility)
            assertEquals(View.GONE, fixture.views.bottomNav.visibility)
            assertEquals("场景库", fixture.views.toolbar.title.toString())
            assertEquals(listOf(R.id.nav_video), mainHooks)
            assertEquals(listOf(R.id.pageSceneLibrary), subHooks)
            assertEquals(
                listOf(
                    NavigationDestination.MainPage(R.id.nav_video),
                    NavigationDestination.SubPage(R.id.pageSceneLibrary),
                ),
                observed,
            )

            val savedState = Bundle()
            navigator.saveState(savedState)
            assertEquals(R.id.nav_video, savedState.getInt(MainNavigator.STATE_MAIN_TAB))
            assertEquals(R.id.pageSceneLibrary, savedState.getInt(MainNavigator.STATE_SETTINGS_SUB))
            assertEquals(R.id.nav_video, savedState.getInt(MainNavigator.STATE_SETTINGS_RETURN_TAB))

            assertTrue(navigator.handleBack())
            assertEquals(listOf(R.id.pageSceneLibrary), closedHooks)
            assertEquals(View.GONE, fixture.views.pageSceneLibrary.visibility)
            assertEquals(View.VISIBLE, fixture.views.pageVideo.visibility)
            assertEquals(View.VISIBLE, fixture.views.bottomNav.visibility)
            assertEquals(R.id.nav_video, fixture.views.bottomNav.selectedItemId)
            assertEquals(R.id.nav_video, mainHooks.last())
            assertEquals(NavigationDestination.MainPage(R.id.nav_video), observed.last())
            assertFalse(navigator.handleBack())
        }

    private fun withNavigatorFixture(block: (NavigatorFixture) -> Unit) {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java).setup()
        try {
            val activity = controller.get()
            val content = LayoutInflater.from(activity).inflate(R.layout.activity_main, null, false)
            activity.setContentView(content)
            val root = content.findViewById<View>(R.id.rootLayout)
            block(NavigatorFixture(MainNavigatorViews.bind(root)))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private data class NavigatorFixture(val views: MainNavigatorViews)
}
