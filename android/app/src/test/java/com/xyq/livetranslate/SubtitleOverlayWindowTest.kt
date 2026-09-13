package com.xyq.livetranslate

import android.app.Application
import android.content.Context
import android.graphics.drawable.LayerDrawable
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

            // 收起后窗口按触摸宽度算：看得见的蓝条只有 10dp，其余是便于点击的半透明晕
            assertEquals((28 * density).roundToInt(), params.width)
            assertEquals((48 * density).roundToInt(), params.height)
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
    fun headerControlsToggleWhenTappingBlankArea() {
        val overlay = SubtitleOverlay(appContext(), StatusBus.MODE_MIC)
        try {
            assertTrue(overlay.show())
            val header = overlay.field<View>("headerRow")
            val root = overlay.field<ViewGroup>("root")
            assertEquals(View.VISIBLE, header.visibility)

            // 点面板空白处收起控制条，字幕独占面板
            root.tap()
            assertEquals(View.GONE, header.visibility)

            // 再点一下复原
            root.tap()
            assertEquals(View.VISIBLE, header.visibility)
        } finally {
            overlay.hide()
        }
    }

    /**
     * 控制条不能再被任何计时器自动收起：`maybeReapplyStyle` 每条字幕都会调用一次，
     * 旧实现在这里重排自动隐藏，导致译文连续输出时控制条永远不收。
     */
    @Test
    fun headerControlsStayPutWhileSubtitlesKeepArriving() {
        val overlay = SubtitleOverlay(appContext(), StatusBus.MODE_MIC)
        try {
            assertTrue(overlay.show())
            val header = overlay.field<View>("headerRow")
            overlay.field<ViewGroup>("root").tap()
            assertEquals(View.GONE, header.visibility)

            repeat(5) {
                overlay.maybeReapplyStyle()
                overlay.setLines("已确认", "当前行")
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))
            }

            // 用户收起了就该一直收着，字幕刷新不得把它顶回来
            assertEquals(View.GONE, header.visibility)

            StatusBus.paused = true
            overlay.maybeReapplyStyle()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5000))
            assertEquals(View.GONE, header.visibility)
        } finally {
            overlay.hide()
            StatusBus.paused = false
        }
    }

    /**
     * 看得见的蓝条必须压在贴屏幕边的那一侧，多出来的触摸宽度全部让给朝屏幕内侧的半透明晕。
     * 贴左边时晕在右，贴右边时晕在左——插反了就会变成蓝条悬空、晕贴边。
     */
    @Test
    fun collapsedBarHugsTheDockedEdgeAndFadesInward() {
        assertCollapsedBarInsets(dockRight = false)
        assertCollapsedBarInsets(dockRight = true)
    }

    private fun assertCollapsedBarInsets(dockRight: Boolean) {
        val context = appContext()
        val overlay = SubtitleOverlay(context, StatusBus.MODE_MIC)
        try {
            assertTrue(overlay.show())
            val params = overlay.field<WindowManager.LayoutParams>("lp")
            // 收起方向取自收起前面板中心落在哪半屏
            if (dockRight) params.x = 10_000
            assertTrue(overlay.field<ImageView>("collapseButton").performClick())

            val inward = ((28 - 10) * context.resources.displayMetrics.density).roundToInt()
            val background = overlay.field<ViewGroup>("root").background as LayerDrawable
            assertEquals(if (dockRight) inward else 0, background.getLayerInsetLeft(BAR_LAYER))
            assertEquals(if (dockRight) 0 else inward, background.getLayerInsetRight(BAR_LAYER))
        } finally {
            overlay.hide()
        }
    }

    /** 收起态的小蓝条要能纵向拖动：触摸不能被胶囊自己吃掉。 */
    @Test
    fun collapsedBarCanStillBeDraggedVertically() {
        val overlay = SubtitleOverlay(appContext(), StatusBus.MODE_MIC)
        try {
            assertTrue(overlay.show())
            assertTrue(overlay.field<ImageView>("collapseButton").performClick())
            val handle = overlay.field<TextView>("collapsedHandle")
            val params = overlay.field<WindowManager.LayoutParams>("lp")
            val startY = params.y

            handle.dispatch(MotionEvent.ACTION_DOWN, 0f, 0f)
            handle.dispatch(MotionEvent.ACTION_MOVE, 0f, 200f)
            handle.dispatch(MotionEvent.ACTION_UP, 0f, 200f)

            assertNotEquals(startY, params.y)
        } finally {
            overlay.hide()
        }
    }

    private fun View.tap() {
        dispatch(MotionEvent.ACTION_DOWN, 10f, 10f)
        dispatch(MotionEvent.ACTION_UP, 10f, 10f)
    }

    private fun View.dispatch(action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0L, 0L, action, x, y, 0)
        dispatchTouchEvent(event)
        event.recycle()
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

    private companion object {
        /** LayerDrawable 里蓝条那一层的下标（0 是半透明晕）。 */
        const val BAR_LAYER = 1
    }

    private fun appContext(): Context = ApplicationProvider.getApplicationContext()

    private inline fun <reified T> SubtitleOverlay.field(name: String): T {
        val field = SubtitleOverlay::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }
        return field.get(this) as T
    }
}
