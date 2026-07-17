package com.xyq.livetranslate

import android.app.Application
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
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
        val overlay = SubtitleOverlay(appContext())
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
        val overlay = SubtitleOverlay(context)
        StatusBus.paused = false
        try {
            assertTrue(overlay.show())
            val collapseButton = overlay.field<TextView>("collapseButton")
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

    private fun appContext(): Context = ApplicationProvider.getApplicationContext()

    private inline fun <reified T> SubtitleOverlay.field(name: String): T {
        val field = SubtitleOverlay::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }
        return field.get(this) as T
    }
}
