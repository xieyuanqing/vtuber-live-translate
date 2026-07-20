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
    fun rejectsUnknownDestinationsAndSwitchesBetweenValidPages() =
        withNavigatorFixture { fixture ->
            val navigator = MainNavigator(views = fixture.views)
            navigator.setup(null)
            assertEquals(View.GONE, fixture.views.toolbar.visibility)
            assertEquals(View.VISIBLE, fixture.views.bottomNav.visibility)
            assertEquals(View.VISIBLE, fixture.views.pageInterp.visibility)

            // 主页 id 集合外的目的地被拒，不改变当前页。
            assertFalse(navigator.showMain(R.id.pageSettingsAbout))
            assertEquals(View.VISIBLE, fixture.views.pageInterp.visibility)

            // 合法主页切换成功。
            assertTrue(navigator.showMain(R.id.nav_history))
            assertEquals(View.VISIBLE, fixture.views.pageHistory.visibility)

            // 非子页 id、或返回 tab 不是主页 id 的 openSub 都被拒。
            assertFalse(navigator.openSub(R.id.nav_interp, R.id.nav_settings))
            assertFalse(navigator.openSub(R.id.pageSettingsAbout, R.id.pageSettingsAbout))
            assertEquals(View.VISIBLE, fixture.views.pageHistory.visibility)
        }

    @Test
    fun restoresSubPageReturnTabAndBackClosesItBeforeFallingThrough() =
        withNavigatorFixture { fixture ->
            val mainHooks = mutableListOf<Int>()
            val subHooks = mutableListOf<Int>()
            val closedHooks = mutableListOf<Int>()
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
            )

            navigator.setup(restoredState)

            assertEquals(View.VISIBLE, fixture.views.pageSceneLibrary.visibility)
            assertEquals(View.GONE, fixture.views.bottomNav.visibility)
            assertEquals(View.VISIBLE, fixture.views.toolbar.visibility)
            assertEquals("场景库", fixture.views.toolbar.title.toString())
            assertEquals(listOf(R.id.nav_video), mainHooks)
            assertEquals(listOf(R.id.pageSceneLibrary), subHooks)

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
            assertEquals(View.GONE, fixture.views.toolbar.visibility)
            assertEquals(R.id.nav_video, fixture.views.bottomNav.selectedItemId)
            assertEquals(R.id.nav_video, mainHooks.last())
            assertFalse(navigator.handleBack())
        }


    @Test
    fun mainTabsHideToolbarWhileSubPagesShowIt() = withNavigatorFixture { fixture ->
        val navigator = MainNavigator(views = fixture.views)
        navigator.setup(null)
        assertEquals(View.GONE, fixture.views.toolbar.visibility)
        assertEquals(View.VISIBLE, fixture.views.pageInterp.visibility)

        assertTrue(navigator.openSub(R.id.pageSettingsAbout, R.id.nav_settings))
        assertEquals(View.VISIBLE, fixture.views.toolbar.visibility)
        assertEquals("关于", fixture.views.toolbar.title.toString())
        assertEquals(View.GONE, fixture.views.bottomNav.visibility)

        assertTrue(navigator.handleBack())
        assertEquals(View.GONE, fixture.views.toolbar.visibility)
        assertEquals(View.VISIBLE, fixture.views.pageSettings.visibility)
        assertEquals(View.VISIBLE, fixture.views.bottomNav.visibility)
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
