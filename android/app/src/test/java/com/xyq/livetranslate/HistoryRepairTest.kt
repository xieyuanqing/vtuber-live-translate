package com.xyq.livetranslate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HistoryRepairTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun cleanup() {
        HistoryStore.list(context).forEach { HistoryStore.delete(context, it.fileName) }
        StatusBus.activeHistoryId = null
    }

    @Test
    fun repairStampsOrphansAndLeavesTheLiveSessionOpen() {
        save("orphan", endedAt = null, elapsedMs = 4_000L)
        save("live", endedAt = null, elapsedMs = 2_000L)
        save("done", endedAt = 8_000L, elapsedMs = 3_000L)

        val repaired = HistoryStore.repairOrphans(
            context,
            activeSessionId = "live",
            nowMs = 20_000L,
        )

        assertEquals(1, repaired)
        val orphan = HistoryStore.load(context, "orphan")
        assertNotNull(orphan?.endedAt)
        assertTrue(orphan!!.endedAt!! >= orphan.startedAt)
        assertNull(HistoryStore.load(context, "live")?.endedAt)
        assertEquals(8_000L, HistoryStore.load(context, "done")?.endedAt)
    }

    @Test
    fun liveSessionIdMatchesFileNameWithOrWithoutJsonSuffix() {
        assertTrue(HistoryStore.isLiveSession("live-id.json", "live-id"))
        assertTrue(HistoryStore.isLiveSession("live-id", "live-id"))
        assertTrue(!HistoryStore.isLiveSession("other.json", "live-id"))
        assertTrue(!HistoryStore.isLiveSession("live-id.json", null))
    }

    @Test
    fun closeWritesEndedAtBeforeItReturns() {
        val logger = TranscriptLogger(
            context,
            TranslationMode.INTERPRETATION,
            TranslationPlan.default(TranslationMode.INTERPRETATION),
            sceneLabel = "通用",
            title = "正常停止",
        )
        logger.commitTranslation("你好")
        logger.close()

        val loaded = HistoryStore.load(context, logger.sessionId)
        assertNotNull(loaded)
        assertNotNull(loaded!!.endedAt)
        assertEquals(listOf("你好"), loaded.segments.map { it.translatedText })
    }

    @Test
    fun closeDropsEmptySessionShorterThanTenSeconds() {
        val logger = TranscriptLogger(
            context,
            TranslationMode.VIDEO,
            TranslationPlan.default(TranslationMode.VIDEO),
            sceneLabel = "通用视频",
        )
        logger.close()
        assertNull(HistoryStore.load(context, logger.sessionId))
    }

    private fun save(id: String, endedAt: Long?, elapsedMs: Long) {
        HistoryStore.save(
            context,
            HistorySession(
                id = id,
                title = id,
                mode = TranslationMode.INTERPRETATION,
                sourceLanguageCode = "ja",
                targetLanguageCode = "zh",
                scenePresetId = "general",
                sceneLabel = "通用",
                contextSummary = "",
                startedAt = 1_000L,
                endedAt = endedAt,
                segments = listOf(TranscriptSegment(elapsedMs, "原文", "译文")),
            ),
        )
    }
}
