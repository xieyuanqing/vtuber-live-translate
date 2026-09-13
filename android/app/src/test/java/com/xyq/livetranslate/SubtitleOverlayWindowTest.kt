package com.xyq.livetranslate

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SubtitleOverlayWindowTest {
    @Test
    fun expandedWindowWrapsContentWithoutFullHeightChildren() {
        val overlay = SubtitleOverlay(appContext(), StatusBus.MODE_MIC)
        try {
            assertTrue(overlay.show())
            val params = overlay.field<WindowManager.LayoutParams>("lp")
            val root = overlay.field<ViewGroup>("root")

            assertEquals(WindowManager.LayoutParams.WRAP_CONTENT, params.height)
            repeat(root.childCount) { index ->
                assertNotEquals(
                    "A MATCH_PARENT-height child can expand a WRAP_CONTENT overlay to the full screen",
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    root.getChildAt(index).layoutParams.height,
                )
            }
        } finally {
            overlay.hide()
        }
    }

    @Test
    fun collapseCreatesASideHandleWithoutPausingTranslation() {
        val context = appContext()
        val overlay = SubtitleOverlay(context, StatusBus.MODE_MIC)
        StatusBus.paused = false
        try {
            assertTrue(overlay.show())
            val collapseButton = overlay.field<ImageView>("collapseButton")
            val panel = overlay.field<View>("panel")
            val handle = overlay.field<TextView>("collapsedHandle")
            val params = overlay.field<WindowManager.LayoutParams>("lp")
            val density = context.resources.displayMetrics.density
            val expandedWidth = params.width
            val expandedX = params.x
            val expandedY = params.y

            assertTrue(collapseButton.performClick())

            assertEquals((44 * density).roundToInt(), params.width)
            assertEquals((60 * density).roundToInt(), params.height)
            assertEquals(View.GONE, panel.visibility)
            assertEquals(View.VISIBLE, handle.visibility)
            assertFalse(StatusBus.paused)
            assertNull(shadowOf(context as Application).nextStartedService)

            assertTrue(handle.performClick())

            assertEquals(expandedWidth, params.width)
            assertEquals(WindowManager.LayoutParams.WRAP_CONTENT, params.height)
            assertEquals(expandedX, params.x)
            assertEquals(expandedY, params.y)
            assertEquals(View.VISIBLE, panel.visibility)
            assertEquals(View.GONE, handle.visibility)
            assertFalse(StatusBus.paused)
            assertNull(shadowOf(context).nextStartedService)
        } finally {
            overlay.hide()
            StatusBus.paused = false
        }
    }

    @Test
    fun headerControlsAutoHideAndRevealOnTouch() {
        val overlay = SubtitleOverlay(appContext(), StatusBus.MODE_MIC)
        try {
            assertTrue(overlay.show())
            val header = overlay.field<View>("headerRow")
            assertEquals(View.VISIBLE, header.visibility)

            // 无操作约 3.5 秒后控制条自动隐藏，字幕独占面板
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4000))
            assertEquals(View.GONE, header.visibility)

            // 触摸悬浮窗重新唤出控制条
            val root = overlay.field<ViewGroup>("root")
            val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
            root.dispatchTouchEvent(down)
            down.recycle()
            assertEquals(View.VISIBLE, header.visibility)

            // 暂停态控制条常驻，不自动隐藏（真实链路：状态变化经 maybeReapplyStyle 进入悬浮窗）
            StatusBus.paused = true
            overlay.maybeReapplyStyle()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5000))
            assertEquals(View.VISIBLE, header.visibility)
        } finally {
            overlay.hide()
            StatusBus.paused = false
        }
    }

    @Test
    fun openMainAppCarriesSessionModeForLandingPage() {
        val context = appContext()
        val overlay = SubtitleOverlay(context, StatusBus.MODE_VIDEO)
        try {
            assertTrue(overlay.show())
            overlay.field<ImageView>("openButton").performClick()
            val intent = shadowOf(context as Application).nextStartedActivity
            assertEquals(MainActivity::class.java.name, intent.component?.className)
            // 主应用按会话模式落到对应主页：视频会话必须落视频页，而不是默认同传页。
            assertEquals(StatusBus.MODE_VIDEO, intent.getStringExtra(MainActivity.EXTRA_OPEN_SESSION_TAB))
        } finally {
            overlay.hide()
        }
    }

    private fun appContext(): Context = ApplicationProvider.getApplicationContext()

    private inline fun <reified T> SubtitleOverlay.field(name: String): T {
        val field = SubtitleOverlay::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }
        return field.get(this) as T
    }
}
