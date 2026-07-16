package com.xyq.livetranslate

import org.json.JSONArray
import org.json.JSONObject

data class TranscriptSegment(
    val elapsedMs: Long,
    val sourceText: String,
    val translatedText: String,
)

data class HistorySession(
    val id: String,
    val title: String,
    val mode: TranslationMode,
    val sourceLanguageCode: String,
    val targetLanguageCode: String,
    val scenePresetId: String,
    val contextSummary: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val segments: List<TranscriptSegment> = emptyList(),
) {
    val durationMs: Long
        get() = ((endedAt ?: System.currentTimeMillis()) - startedAt).coerceAtLeast(0L)

    val sceneLabel: String
        get() = ScenePromptCatalog.resolve(mode, scenePresetId).label

    val directionLabel: String
        get() = "${TranslationLanguageCatalog.source(sourceLanguageCode).label} → " +
            TranslationLanguageCatalog.target(targetLanguageCode).label
}

object HistorySessionJson {
    fun encode(session: HistorySession): JSONObject = JSONObject().apply {
        put("id", session.id)
        put("title", session.title)
        put("mode", session.mode.storageKey)
        put("sourceLanguageCode", session.sourceLanguageCode)
        put("targetLanguageCode", session.targetLanguageCode)
        put("scenePresetId", session.scenePresetId)
        put("contextSummary", session.contextSummary)
        put("startedAt", session.startedAt)
        session.endedAt?.let { put("endedAt", it) }
        put("segments", JSONArray().apply {
            session.segments.forEach { segment ->
                put(JSONObject().apply {
                    put("elapsedMs", segment.elapsedMs)
                    put("sourceText", segment.sourceText)
                    put("translatedText", segment.translatedText)
                })
            }
        })
    }

    fun decode(json: JSONObject): HistorySession {
        val modeKey = json.optString("mode")
        val mode = TranslationMode.entries.firstOrNull { it.storageKey == modeKey }
            ?: TranslationMode.INTERPRETATION
        val segmentsJson = json.optJSONArray("segments")
        val segments = buildList {
            if (segmentsJson != null) {
                for (index in 0 until segmentsJson.length()) {
                    val item = segmentsJson.optJSONObject(index) ?: continue
                    val translated = item.optString("translatedText").trim()
                    if (translated.isNotEmpty()) {
                        add(
                            TranscriptSegment(
                                elapsedMs = item.optLong("elapsedMs").coerceAtLeast(0L),
                                sourceText = item.optString("sourceText").trim(),
                                translatedText = translated,
                            ),
                        )
                    }
                }
            }
        }
        return HistorySession(
            id = json.optString("id"),
            title = json.optString("title"),
            mode = mode,
            sourceLanguageCode = json.optString("sourceLanguageCode", "auto"),
            targetLanguageCode = json.optString("targetLanguageCode", "zh"),
            scenePresetId = json.optString(
                "scenePresetId",
                TranslationPlan.defaultSceneId(mode),
            ),
            contextSummary = json.optString("contextSummary"),
            startedAt = json.optLong("startedAt"),
            endedAt = if (json.has("endedAt")) json.optLong("endedAt") else null,
            segments = segments,
        )
    }
}
