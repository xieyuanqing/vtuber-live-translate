package com.xyq.livetranslate

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** App 内部 transcript 历史。公共 Download 负责给用户找文件，这里负责 App 内稳定读取。 */
object HistoryStore {
    data class HistoryItem(
        val fileName: String,
        val title: String,
        val updatedAt: Long,
        val sizeBytes: Long,
    )

    fun createHistoryFile(context: Context, name: String): File {
        val dir = historyDir(context).apply { mkdirs() }
        return File(dir, name)
    }

    fun list(context: Context): List<HistoryItem> = historyDir(context)
        .listFiles { f -> f.isFile && f.extension.equals("md", ignoreCase = true) }
        .orEmpty()
        .sortedByDescending { it.lastModified() }
        .map { f ->
            HistoryItem(
                fileName = f.name,
                title = f.name.removeSuffix(".md"),
                updatedAt = f.lastModified(),
                sizeBytes = f.length(),
            )
        }

    fun read(context: Context, fileName: String): String {
        val safeName = File(fileName).name
        val f = File(historyDir(context), safeName)
        return if (f.isFile) f.readText(Charsets.UTF_8) else ""
    }

    fun formatTime(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))

    private fun historyDir(context: Context): File = File(context.filesDir, "history")
}
