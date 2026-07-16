package com.xyq.livetranslate

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 同时维护 App 内结构化会话和公共 Markdown 导出。原文碎片先缓冲，字幕稳定器确认译文时
 * 才生成一个 [TranscriptSegment]，避免历史里全是模型流式碎片。
 */
class TranscriptLogger(
    private val context: Context,
    mode: TranslationMode = TranslationMode.VIDEO,
    plan: TranslationPlan = TranslationPlan.default(mode),
    title: String = "",
    contextSummary: String = "",
) {
    private val startedAt = System.currentTimeMillis()
    private val sessionId = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startedAt)) +
        "_" + UUID.randomUUID().toString().take(8)
    private val sourceBuffer = StringBuilder()
    private val rawTranslationBuffer = StringBuilder()
    private val segments = ArrayList<TranscriptSegment>()
    private var writer: OutputStreamWriter? = null
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
        contextSummary = contextSummary.trim().take(240),
        startedAt = startedAt,
    )

    var pathHint: String = ""
        private set

    init {
        writer = openPublicWriter()
        writeLine("# ${session.title}")
        writeLine("")
        writeLine("- 模式：${session.mode.label}")
        writeLine("- 翻译：${session.directionLabel}")
        writeLine("- 场景：${session.sceneLabel}")
        writeLine("")
        HistoryStore.save(context, session)
    }

    @Synchronized
    fun logJa(text: String) {
        if (!closed) sourceBuffer.append(text)
    }

    /** 保留流式译文用于异常停止时的最后一段，正常历史由 [commitTranslation] 写入。 */
    @Synchronized
    fun logZh(text: String) {
        if (!closed) rawTranslationBuffer.append(text)
    }

    @Synchronized
    fun commitTranslation(confirmedText: String) {
        if (closed) return
        val translated = confirmedText.trim()
        if (translated.isEmpty() || translated == lastConfirmedTranslation) return
        val source = sourceBuffer.toString().trim()
        sourceBuffer.setLength(0)
        rawTranslationBuffer.setLength(0)
        lastConfirmedTranslation = translated
        val segment = TranscriptSegment(
            elapsedMs = System.currentTimeMillis() - startedAt,
            sourceText = source,
            translatedText = translated,
        )
        segments += segment
        session = session.copy(segments = segments.toList())
        HistoryStore.save(context, session)
        writeSegment(segment)
    }

    @Synchronized
    fun close() {
        if (closed) return
        val pendingTranslation = rawTranslationBuffer.toString().trim()
        if (pendingTranslation.isNotEmpty() && pendingTranslation != lastConfirmedTranslation) {
            commitTranslation(pendingTranslation)
        }
        closed = true
        session = session.copy(
            endedAt = System.currentTimeMillis(),
            segments = segments.toList(),
        )
        HistoryStore.save(context, session)
        runCatching { writer?.close() }
        writer = null
    }

    private fun openPublicWriter(): OutputStreamWriter? {
        val displayName = "$sessionId.md"
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/markdown")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/LiveTranslate",
                )
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            val stream = uri?.let(context.contentResolver::openOutputStream)
            if (stream != null) {
                pathHint = "下载/LiveTranslate/$displayName"
                return OutputStreamWriter(stream, Charsets.UTF_8)
            }
        }.onFailure { Log.w("TranscriptLogger", "MediaStore failed: ${it.message}") }

        return runCatching {
            val dir = File(context.getExternalFilesDir(null), "transcripts").apply { mkdirs() }
            val file = File(dir, displayName)
            pathHint = file.absolutePath
            OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8)
        }.onFailure { Log.e("TranscriptLogger", "fallback writer failed", it) }.getOrNull()
    }

    private fun writeSegment(segment: TranscriptSegment) {
        writeLine("## ${HistoryStore.formatDuration(segment.elapsedMs)}")
        if (segment.sourceText.isNotBlank()) writeLine(segment.sourceText)
        writeLine("")
        writeLine("> ${segment.translatedText}")
        writeLine("")
    }

    private fun writeLine(line: String) {
        runCatching {
            writer?.apply {
                write(line + "\n")
                flush()
            }
        }
    }
}
