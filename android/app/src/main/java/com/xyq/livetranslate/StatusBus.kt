package com.xyq.livetranslate

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class TranslationSessionSnapshot(
    val startedAtMs: Long = 0L,
    val sourceLanguageCode: String = "",
    val targetLanguageCode: String = "",
    val scenePresetId: String = "",
    val sceneLabel: String = "",
    val confirmedTranslations: List<String> = emptyList(),
    val currentTranslation: String = "",
    val sourceTail: String = "",
    val lastSubtitleAtMs: Long = 0L,
) {
    val isActive: Boolean get() = startedAtMs > 0L
}

internal fun formatElapsedDuration(elapsedMs: Long): String {
    val totalSeconds = elapsedMs.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = totalSeconds % 3600L / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) "%02d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}

/** 服务 → 界面 的进程内状态。复杂字幕使用不可变快照，标量保留给现有 UI。 */
object StatusBus {
    const val MODE_VIDEO = "video"
    const val MODE_MIC = "mic"

    @Volatile var serviceRunning = false
    @Volatile var paused = false
    @Volatile var captureMode = ""
    @Volatile var connState = "idle"
    @Volatile var transcriptPath = ""
    @Volatile var jaTail = ""
    @Volatile var zhTail = ""
    @Volatile var currentKeyLabel = ""
    @Volatile var audioLevelPct = 0
    val chunksSent = AtomicLong(0)

    private val sessionRef = AtomicReference(TranslationSessionSnapshot())

    /** 悬浮窗样式版本号：设置保存时 +1，悬浮窗发现变化就重新读取应用。 */
    val styleVersion = AtomicInteger(0)

    fun sessionSnapshot(): TranslationSessionSnapshot = sessionRef.get()

    fun startSession(nowMs: Long = System.currentTimeMillis()) {
        sessionRef.set(TranslationSessionSnapshot(startedAtMs = nowMs))
    }

    fun startSession(
        plan: TranslationPlan,
        nowMs: Long = System.currentTimeMillis(),
        sceneLabel: String = "",
    ) {
        val normalized = plan.normalized()
        sessionRef.set(
            TranslationSessionSnapshot(
                startedAtMs = nowMs,
                sourceLanguageCode = normalized.sourceLanguageCode,
                targetLanguageCode = normalized.targetLanguageCode,
                scenePresetId = normalized.scenePresetId,
                sceneLabel = sceneLabel.trim(),
            ),
        )
    }

    fun updateSessionSubtitles(
        confirmed: List<String>,
        current: String,
        source: String = jaTail,
    ) {
        sessionRef.updateAndGet { old ->
            old.copy(
                startedAtMs = old.startedAtMs.takeIf { it > 0L } ?: System.currentTimeMillis(),
                confirmedTranslations = confirmed.takeLast(80).toList(),
                currentTranslation = current.trim(),
                sourceTail = source.trim(),
                lastSubtitleAtMs = System.currentTimeMillis(),
            )
        }
    }

    fun updateSessionSource(source: String) {
        sessionRef.updateAndGet { old ->
            old.copy(
                startedAtMs = old.startedAtMs.takeIf { it > 0L } ?: System.currentTimeMillis(),
                sourceTail = source.trim(),
            )
        }
    }

    fun reset() {
        connState = "idle"
        paused = false
        transcriptPath = ""
        jaTail = ""
        zhTail = ""
        currentKeyLabel = ""
        audioLevelPct = 0
        chunksSent.set(0)
        sessionRef.set(TranslationSessionSnapshot())
        // captureMode 在 start 时写入，stop 时清空，不在这里抹掉「即将启动」的意图
    }
}
