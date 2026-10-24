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

    /**
     * 只有本进程里还活着的 [TranscriptLogger] 才算「正在进行」。
     * `endedAt == null` 也会出现在被系统杀掉的旧会话上，那种文件必须能删。
     */
    fun isLiveSession(fileNameOrId: String, activeSessionId: String? = StatusBus.activeHistoryId): Boolean {
        val active = activeSessionId?.takeIf { it.isNotBlank() } ?: return false
        return fileNameOrId.removeSuffix(".json") == active
    }

    /**
     * 给没有结束时间、且不是当前正在写的会话补上 `endedAt`。
     * 优先用文件最后修改时间（每次确认字幕都会重写文件），否则用最后一段的相对时间。
     * @return 补写的条数
     */
    fun repairOrphans(
        context: Context,
        activeSessionId: String? = StatusBus.activeHistoryId,
        nowMs: Long = System.currentTimeMillis(),
    ): Int {
        val dir = historyDir(context)
        if (!dir.isDirectory) return 0
        var repaired = 0
        dir.listFiles { file -> file.isFile && file.extension.equals("json", ignoreCase = true) }
            .orEmpty()
            .forEach { file ->
                val session = runCatching {
                    HistorySessionJson.decode(org.json.JSONObject(file.readText(Charsets.UTF_8)))
                }.getOrNull() ?: return@forEach
                if (session.id.isBlank() || session.endedAt != null) return@forEach
                if (isLiveSession(session.id, activeSessionId)) return@forEach
                val lastModified = file.lastModified()
                val endedAt = when {
                    lastModified >= session.startedAt && lastModified > 0L -> lastModified
                    session.segments.isNotEmpty() ->
                        session.startedAt + session.segments.last().elapsedMs
                    else -> nowMs
                }
                save(context, session.copy(endedAt = endedAt.coerceAtLeast(session.startedAt)))
                repaired++
            }
        return repaired
    }

    fun load(context: Context, fileNameOrId: String): HistorySession? {
        val file = File(historyDir(context), safeFileName(fileNameOrId.removeSuffix(".json")))
        if (!file.isFile) return null
        return runCatching {
            HistorySessionJson.decode(org.json.JSONObject(file.readText(Charsets.UTF_8)))
        }.getOrNull()
    }

    fun delete(context: Context, fileNameOrId: String): Boolean =
        File(historyDir(context), safeFileName(fileNameOrId.removeSuffix(".json"))).delete()

    fun toMarkdown(session: HistorySession, context: Context? = null): String = buildString {
        appendLine("# ${session.title}")
        appendLine()
        val modeLabel = if (context != null) {
            if (session.mode == TranslationMode.INTERPRETATION) {
                context.getString(R.string.rt_mode_interpretation)
            } else {
                context.getString(R.string.rt_mode_video)
            }
        } else {
            session.mode.label
        }
        val lblMode = context?.getString(R.string.rt_history_md_mode) ?: "模式"
        val lblTranslation = context?.getString(R.string.rt_history_md_translation) ?: "翻译"
        val lblScene = context?.getString(R.string.rt_history_md_scene) ?: "场景"
        val lblStart = context?.getString(R.string.rt_history_md_start) ?: "开始"
        val lblDuration = context?.getString(R.string.rt_history_md_duration) ?: "时长"
        val lblContext = context?.getString(R.string.rt_history_md_context) ?: "本场背景"

        appendLine("- $lblMode：$modeLabel")
        appendLine("- $lblTranslation：${session.directionLabel}")
        appendLine("- $lblScene：${session.sceneLabel}")
        appendLine("- $lblStart：${formatTime(session.startedAt)}")
        appendLine("- $lblDuration：${formatDuration(session.durationMs)}")
        if (session.contextSummary.isNotBlank()) appendLine("- $lblContext：${session.contextSummary}")
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
