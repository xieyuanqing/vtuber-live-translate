package com.xyq.livetranslate

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 新版结构化历史。只读取 history_v2 下的 JSON 会话。 */
object HistoryStore {
    data class HistoryItem(
        val fileName: String,
        val title: String,
        val updatedAt: Long,
        val sizeBytes: Long,
        val mode: TranslationMode,
        val sourceLanguageCode: String,
        val targetLanguageCode: String,
        val scenePresetId: String,
        val sceneLabel: String,
        val durationMs: Long,
        val summary: String,
    )

    @Synchronized
    fun save(context: Context, session: HistorySession) {
        val dir = historyDir(context).apply { mkdirs() }
        val target = File(dir, safeFileName(session.id))
        val temp = File(dir, ".${target.name}.tmp")
        temp.writeText(HistorySessionJson.encode(session).toString(), Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    fun list(context: Context): List<HistoryItem> = historyDir(context)
        .listFiles { file -> file.isFile && file.extension.equals("json", ignoreCase = true) }
        .orEmpty()
        .mapNotNull { file ->
            runCatching { HistorySessionJson.decode(org.json.JSONObject(file.readText(Charsets.UTF_8))) }
                .getOrNull()
                ?.takeIf { it.id.isNotBlank() }
                ?.let { session ->
                    HistoryItem(
                        fileName = file.name,
                        title = session.title,
                        updatedAt = session.endedAt ?: session.startedAt,
                        sizeBytes = file.length(),
                        mode = session.mode,
                        sourceLanguageCode = session.sourceLanguageCode,
                        targetLanguageCode = session.targetLanguageCode,
                        scenePresetId = session.scenePresetId,
                        sceneLabel = session.sceneLabel,
                        durationMs = session.durationMs,
                        summary = session.segments.lastOrNull()?.translatedText
                            ?: session.contextSummary,
                    )
                }
        }
        .sortedByDescending { it.updatedAt }

    fun load(context: Context, fileNameOrId: String): HistorySession? {
        val file = File(historyDir(context), safeFileName(fileNameOrId.removeSuffix(".json")))
        if (!file.isFile) return null
        return runCatching {
            HistorySessionJson.decode(org.json.JSONObject(file.readText(Charsets.UTF_8)))
        }.getOrNull()
    }

    fun delete(context: Context, fileNameOrId: String): Boolean =
        File(historyDir(context), safeFileName(fileNameOrId.removeSuffix(".json"))).delete()

    fun toMarkdown(session: HistorySession): String = buildString {
        appendLine("# ${session.title}")
        appendLine()
        appendLine("- 模式：${session.mode.label}")
        appendLine("- 翻译：${session.directionLabel}")
        appendLine("- 场景：${session.sceneLabel}")
        appendLine("- 开始：${formatTime(session.startedAt)}")
        appendLine("- 时长：${formatDuration(session.durationMs)}")
        if (session.contextSummary.isNotBlank()) appendLine("- 本场背景：${session.contextSummary}")
        appendLine()
        session.segments.forEach { segment ->
            appendLine("## ${formatElapsed(segment.elapsedMs)}")
            if (segment.sourceText.isNotBlank()) appendLine(segment.sourceText)
            appendLine()
            appendLine("> ${segment.translatedText}")
            appendLine()
        }
    }.trimEnd()

    fun formatTime(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

    fun formatDuration(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = totalSeconds % 3600L / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%02d:%02d".format(minutes, seconds)
    }

    private fun formatElapsed(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
        return "%02d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
    }

    private fun safeFileName(id: String): String =
        File(id).name.removeSuffix(".json").ifBlank { "invalid" } + ".json"

    private fun historyDir(context: Context): File = File(context.filesDir, "history_v2")
}
