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

/**
 * 转写落盘。优先写到公共“下载/LiveTranslate/”目录（文件管理器直接可见），
 * 失败时退回 App 私有目录。碎片攒到一定长度写一行，避免文件全是单词碎片。
 */
class TranscriptLogger(context: Context) {

    private var writer: OutputStreamWriter? = null
    private var historyWriter: OutputStreamWriter? = null
    var pathHint: String = ""
        private set

    private val ts = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val jaBuf = StringBuilder()
    private val zhBuf = StringBuilder()

    init {
        val name = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date()) + ".md"
        var w: OutputStreamWriter? = null
        try {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/markdown")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/LiveTranslate"
                )
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            if (uri != null) {
                val os = context.contentResolver.openOutputStream(uri)
                if (os != null) {
                    w = OutputStreamWriter(os, Charsets.UTF_8)
                    pathHint = "下载/LiveTranslate/$name"
                }
            }
        } catch (e: Exception) {
            Log.w("TranscriptLogger", "MediaStore failed: ${e.message}")
        }
        if (w == null) {
            val dir = File(context.getExternalFilesDir(null), "transcripts").apply { mkdirs() }
            val f = File(dir, name)
            w = OutputStreamWriter(FileOutputStream(f, true), Charsets.UTF_8)
            pathHint = f.absolutePath
        }
        writer = w
        historyWriter = OutputStreamWriter(
            FileOutputStream(HistoryStore.createHistoryFile(context, name), true),
            Charsets.UTF_8,
        )
        writeLine(
            "# 直播翻译 transcript · " +
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        )
        writeLine("")
    }

    @Synchronized
    fun logJa(t: String) {
        jaBuf.append(t)
        if (jaBuf.length > 60) {
            writeLine("- ${ts.format(Date())} ja: $jaBuf")
            jaBuf.setLength(0)
        }
    }

    @Synchronized
    fun logZh(t: String) {
        zhBuf.append(t)
        if (zhBuf.length > 40) {
            writeLine("- ${ts.format(Date())} zh: $zhBuf")
            zhBuf.setLength(0)
        }
    }

    @Synchronized
    fun close() {
        if (jaBuf.isNotEmpty()) writeLine("- ${ts.format(Date())} ja: $jaBuf")
        if (zhBuf.isNotEmpty()) writeLine("- ${ts.format(Date())} zh: $zhBuf")
        runCatching { writer?.close() }
        runCatching { historyWriter?.close() }
        writer = null
        historyWriter = null
    }

    @Synchronized
    private fun writeLine(l: String) {
        runCatching {
            writer?.apply {
                write(l + "\n")
                flush()
            }
        }
        runCatching {
            historyWriter?.apply {
                write(l + "\n")
                flush()
            }
        }
    }
}
