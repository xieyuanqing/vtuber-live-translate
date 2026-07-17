package com.xyq.livetranslate

import android.content.Intent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class CaptureServiceSnapshotTest {

    @After
    fun resetStatus() {
        StatusBus.serviceRunning = false
        StatusBus.captureMode = ""
        StatusBus.reset()
    }

    @Test
    fun rejectsStartWhenAnyRequiredSnapshotFieldIsMissing() {
        val requiredExtras = listOf(
            CaptureService.EXTRA_MODE,
            CaptureService.EXTRA_SESSION_PROMPT,
            CaptureService.EXTRA_SOURCE_LANGUAGE,
            CaptureService.EXTRA_TARGET_LANGUAGE,
            CaptureService.EXTRA_SCENE_PRESET,
            CaptureService.EXTRA_SCENE_LABEL,
        )

        requiredExtras.forEach { missingExtra ->
            assertRejected(missingExtra) { removeExtra(missingExtra) }
        }
    }

    @Test
    fun rejectsUnsupportedLanguageCodes() {
        assertRejected("invalid source") {
            putExtra(CaptureService.EXTRA_SOURCE_LANGUAGE, "not-supported")
        }
        assertRejected("invalid target") {
            putExtra(CaptureService.EXTRA_TARGET_LANGUAGE, "not-supported")
        }
        assertRejected("auto target") {
            putExtra(CaptureService.EXTRA_TARGET_LANGUAGE, "auto")
        }
    }

    private fun assertRejected(label: String, mutate: Intent.() -> Unit) {
        resetStatus()
        val controller = Robolectric.buildService(CaptureService::class.java).create()
        try {
            val service = controller.get()
            val intent = Intent(service, CaptureService::class.java)
                .setAction(CaptureService.ACTION_START)
                .putExtra(CaptureService.EXTRA_MODE, StatusBus.MODE_MIC)
                .putExtra(CaptureService.EXTRA_SESSION_PROMPT, "完整 Prompt")
                .putExtra(CaptureService.EXTRA_SOURCE_LANGUAGE, "ja")
                .putExtra(CaptureService.EXTRA_TARGET_LANGUAGE, "zh")
                .putExtra(CaptureService.EXTRA_SCENE_PRESET, "general")
                .putExtra(CaptureService.EXTRA_SCENE_LABEL, "通用")
            intent.mutate()

            service.onStartCommand(intent, 0, 1)

            assertEquals(label, "error:会话快照无效", StatusBus.connState)
            assertFalse(label, StatusBus.serviceRunning)
        } finally {
            controller.destroy()
        }
    }
}
