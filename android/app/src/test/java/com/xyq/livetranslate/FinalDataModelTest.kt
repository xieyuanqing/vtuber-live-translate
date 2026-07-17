package com.xyq.livetranslate

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalDataModelTest {
    @Test
    fun `glossary json normalizes and round trips`() {
        val original = GlossaryProfile(
            id = "meeting",
            name = "  项目会议  ",
            aliases = listOf(" API ", "API", ""),
            terms = listOf("SDK = SDK"),
            corrections = listOf("爱批爱→API"),
        )

        val decoded = GlossaryProfileJson.decode(GlossaryProfileJson.encode(original))

        assertEquals("项目会议", decoded.name)
        assertEquals(listOf("API"), decoded.aliases)
        assertEquals(listOf("SDK = SDK"), decoded.terms)
    }

    @Test
    fun `history json preserves mode metadata and paired segments`() {
        val session = HistorySession(
            id = "session-1",
            title = "课程同传",
            mode = TranslationMode.INTERPRETATION,
            sourceLanguageCode = "en",
            targetLanguageCode = "zh-Hans",
            scenePresetId = "lecture",
            sceneLabel = "课堂",
            contextSummary = "Android 课程",
            startedAt = 1_000L,
            endedAt = 62_000L,
            segments = listOf(
                TranscriptSegment(2_000L, "Hello", "你好"),
                TranscriptSegment(7_000L, "World", "世界"),
            ),
        )

        val decoded = HistorySessionJson.decode(HistorySessionJson.encode(session))

        assertEquals(TranslationMode.INTERPRETATION, decoded.mode)
        assertEquals("课程同传", decoded.title)
        assertEquals(61_000L, decoded.durationMs)
        assertEquals("World", decoded.segments.last().sourceText)
        assertEquals("世界", decoded.segments.last().translatedText)
    }

    @Test
    fun `content analysis parser returns generic context and glossary`() {
        val json = JSONObject()
            .put("sessionContext", "本场讨论 Android 音频捕获。")
            .put("note", "资料充分")
            .put(
                "glossary",
                JSONObject()
                    .put("name", "Android 会议")
                    .put("category", "会议")
                    .put("description", "音频捕获方案")
                    .put("aliases", JSONArray(listOf("AAudio")))
                    .put("terms", JSONArray(listOf("AudioRecord = AudioRecord")))
                    .put("corrections", JSONArray())
                    .put("style", "简洁"),
            )

        val parsed = ContentContextAnalyzer.parse(json)

        assertEquals("本场讨论 Android 音频捕获。", parsed.sessionContext)
        assertEquals("Android 会议", parsed.glossary?.name)
        assertEquals(listOf("AAudio"), parsed.glossary?.aliases)
        assertFalse(parsed.glossary?.id.isNullOrBlank())
    }

    @Test
    fun `status bus publishes immutable capped session snapshot`() {
        StatusBus.reset()
        StatusBus.startSession(123L)
        val lines = (1..30).map { "字幕 $it" }
        StatusBus.updateSessionSubtitles(lines, "当前字幕", "source")

        val snapshot = StatusBus.sessionSnapshot()
        assertTrue(snapshot.isActive)
        assertEquals(123L, snapshot.startedAtMs)
        assertEquals(20, snapshot.confirmedTranslations.size)
        assertEquals("字幕 11", snapshot.confirmedTranslations.first())
        assertEquals("字幕 30", snapshot.confirmedTranslations.last())
        assertEquals("当前字幕", snapshot.currentTranslation)
    }

    @Test
    fun `session snapshot freezes language direction and scene`() {
        val plan = TranslationPlan(
            mode = TranslationMode.VIDEO,
            sourceLanguageCode = "ja",
            targetLanguageCode = "en",
            scenePresetId = "livestream",
        )

        StatusBus.startSession(plan, 456L, sceneLabel = "直播")
        val snapshot = StatusBus.sessionSnapshot()

        assertEquals(456L, snapshot.startedAtMs)
        assertEquals("ja", snapshot.sourceLanguageCode)
        assertEquals("en", snapshot.targetLanguageCode)
        assertEquals("livestream", snapshot.scenePresetId)
        assertEquals("直播", snapshot.sceneLabel)
    }

    @Test
    fun `unfinished history uses last segment elapsed instead of wall clock`() {
        val session = HistorySession(
            id = "interrupted",
            title = "异常中断",
            mode = TranslationMode.VIDEO,
            sourceLanguageCode = "ja",
            targetLanguageCode = "zh-Hans",
            scenePresetId = "general_video",
            sceneLabel = "通用视频",
            contextSummary = "",
            startedAt = 1_000L,
            endedAt = null,
            segments = listOf(TranscriptSegment(7_000L, "source", "译文")),
        )

        assertEquals(7_000L, session.durationMs)
    }

    @Test
    fun `elapsed formatter handles minute and hour boundaries`() {
        assertEquals("00:59", formatElapsedDuration(59_000L))
        assertEquals("01:00", formatElapsedDuration(60_000L))
        assertEquals("59:59", formatElapsedDuration(3_599_000L))
        assertEquals("01:00:00", formatElapsedDuration(3_600_000L))
    }
}
