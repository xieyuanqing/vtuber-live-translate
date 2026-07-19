package com.xyq.livetranslate

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 维护 App 内结构化会话。原文碎片先缓冲，字幕稳定器确认译文时才生成一个
 * [TranscriptSegment]，避免历史里全是模型流式碎片。
 *
 * 不自动写入公共 Downloads：历史删除后不应留下包含会议或同传内容的公共副本。
 */
class TranscriptLogger(
    context: Context,
    mode: TranslationMode,
    plan: TranslationPlan,
    sceneLabel: String,
    title: String = "",
    contextSummary: String = "",
) {
    companion object {
        private const val TAG = "TranscriptLogger"
    }

    private val appContext = context.applicationContext
    private val historyWriter: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "history-writer")
    }
    private val startedAt = System.currentTimeMillis()
    private val sessionId = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startedAt)) +
        "_" + UUID.randomUUID().toString().take(8)
    private val sourceBuffer = StringBuilder()
    private val rawTranslationBuffer = StringBuilder()
    private val segments = ArrayList<TranscriptSegment>()
    private var closed = false
    private var lastConfirmedTranslation = ""

    private var session = HistorySession(
        id = sessionId,
        title = title.trim().ifEmpty {
            "${mode.label} · ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(startedAt))}"
        },
        mode = mode,
        sourceLanguageCode = plan.sourceLanguageCode,
        targetLanguageCode = plan.targetLanguageCode,
        scenePresetId = plan.scenePresetId,
        sceneLabel = sceneLabel.trim().ifEmpty { plan.scenePresetId },
        contextSummary = contextSummary.trim().take(240),
        startedAt = startedAt,
    )

    val pathHint: String = "应用内历史"

    init {
        saveHistory(session)
    }

    private fun enqueueHistoryWrite(action: () -> Unit) {
        runCatching {
            historyWriter.execute {
                runCatching(action).onFailure { error ->
                    Log.w(TAG, "history write failed: ${error.message}", error)
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "history writer unavailable: ${error.message}", error)
        }
    }

    private fun saveHistory(snapshot: HistorySession) {
        enqueueHistoryWrite { HistoryStore.save(appContext, snapshot) }
    }

    private fun deleteHistory(id: String) {
        enqueueHistoryWrite { HistoryStore.delete(appContext, id) }
    }

    @Synchronized
    fun logJa(text: String) {
        if (!closed) sourceBuffer.append(text)
    }

    /** 保留流式译文，用于异常停止时补齐最后一段。 */
    @Synchronized
    fun logZh(text: String) {
        if (!closed) rawTranslationBuffer.append(text)
    }

    @Synchronized
    fun commitTranslation(confirmedText: String) {
        if (closed) return
        val translated = confirmedText.trim()
        if (translated.isEmpty()) return

        val source = sourceBuffer.toString().trim()
        sourceBuffer.setLength(0)
        rawTranslationBuffer.setLength(0)

        // 稳定器可能重复回调同一确认结果；只有没有新原文时才视为重复。
        if (translated == lastConfirmedTranslation && source.isEmpty()) return

        lastConfirmedTranslation = translated
        val segment = TranscriptSegment(
            elapsedMs = System.currentTimeMillis() - startedAt,
            sourceText = source,
            translatedText = translated,
        )
        segments += segment
        session = session.copy(segments = segments.toList())
        saveHistory(session)
    }

    @Synchronized
    fun close() {
        if (closed) return
        val pendingTranslation = rawTranslationBuffer.toString().trim()
        val pendingSource = sourceBuffer.toString().trim()
        if (pendingTranslation.isNotEmpty() &&
            (pendingTranslation != lastConfirmedTranslation || pendingSource.isNotEmpty())
        ) {
            commitTranslation(pendingTranslation)
        }
        closed = true
        val endedAt = System.currentTimeMillis()
        session = session.copy(
            endedAt = endedAt,
            segments = segments.toList(),
        )
        // C9：无确认字幕且时长 < 10s 的空会话不落盘。删除与保存共用单线程，顺序可靠。
        val durationMs = (endedAt - startedAt).coerceAtLeast(0L)
        if (segments.isEmpty() && durationMs < 10_000L) {
            deleteHistory(session.id)
        } else {
            saveHistory(session)
        }
        historyWriter.shutdown()
    }
}
