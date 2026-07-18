package com.xyq.livetranslate.ui

import com.xyq.livetranslate.R
import com.xyq.livetranslate.StatusBus
import com.xyq.livetranslate.TranslationMode
import com.xyq.livetranslate.TranslationSessionSnapshot

/** 每个 UI tick 唯一采集、随后不可变分发的完整运行态。 */
internal data class UiRuntimeStatus(
    val serviceRunning: Boolean,
    val captureMode: String,
    val connState: String,
    val paused: Boolean,
    val audioLevelPct: Int,
    val overlayAllowed: Boolean,
    val currentKeyLabel: String,
    val transcriptPath: String,
    val chunksSent: Long,
    val jaTail: String,
    val zhTail: String,
    val startedAtMs: Long,
    val sourceLanguageCode: String,
    val targetLanguageCode: String,
    val scenePresetId: String,
    val sceneLabel: String,
    val confirmedTranslations: List<String>,
    val currentTranslation: String,
    val sourceTail: String,
    val activeStatusText: String,
    val activeStatusColorRes: Int,
    val sampledAtMs: Long,
) {
    fun toDiagnostics(): SettingsDiagnosticsState = SettingsDiagnosticsState(
        serviceRunning = serviceRunning,
        captureMode = captureMode,
        connState = connState,
        currentKeyLabel = currentKeyLabel,
        audioLevelPct = audioLevelPct,
        chunksSent = chunksSent,
        transcriptPath = transcriptPath,
        jaTail = jaTail,
        zhTail = zhTail,
    )

    companion object {
        fun captureMode(mode: TranslationMode): String = when (mode) {
            TranslationMode.INTERPRETATION -> StatusBus.MODE_MIC
            TranslationMode.VIDEO -> StatusBus.MODE_VIDEO
        }

        /** StatusBus 只能从 Activity 的单次采样入口经这里读取。 */
        fun capture(
            overlayAllowed: Boolean,
            sampledAtMs: Long = System.currentTimeMillis(),
        ): UiRuntimeStatus {
            val session: TranslationSessionSnapshot = StatusBus.sessionSnapshot()
            val running = StatusBus.serviceRunning
            val mode = StatusBus.captureMode
            val conn = StatusBus.connState
            val paused = StatusBus.paused
            val level = StatusBus.audioLevelPct.coerceIn(0, 100)
            val activeStatusText = when {
                paused -> "已暂停"
                conn == "ready" -> "翻译中"
                conn.startsWith("error") -> "连接出错"
                conn == "rotating" -> "正在切换连接"
                else -> "准备连接"
            }
            val activeStatusColorRes = when {
                paused -> R.color.warning
                conn == "ready" -> R.color.success
                conn.startsWith("error") -> R.color.error
                else -> R.color.brand
            }
            return UiRuntimeStatus(
                serviceRunning = running,
                captureMode = mode,
                connState = conn,
                paused = paused,
                audioLevelPct = level,
                overlayAllowed = overlayAllowed,
                currentKeyLabel = StatusBus.currentKeyLabel,
                transcriptPath = StatusBus.transcriptPath,
                chunksSent = StatusBus.chunksSent.get(),
                jaTail = StatusBus.jaTail,
                zhTail = StatusBus.zhTail,
                startedAtMs = session.startedAtMs,
                sourceLanguageCode = session.sourceLanguageCode,
                targetLanguageCode = session.targetLanguageCode,
                scenePresetId = session.scenePresetId,
                sceneLabel = session.sceneLabel,
                confirmedTranslations = session.confirmedTranslations.toList(),
                currentTranslation = session.currentTranslation,
                sourceTail = session.sourceTail,
                activeStatusText = activeStatusText,
                activeStatusColorRes = activeStatusColorRes,
                sampledAtMs = sampledAtMs,
            )
        }
    }
}
