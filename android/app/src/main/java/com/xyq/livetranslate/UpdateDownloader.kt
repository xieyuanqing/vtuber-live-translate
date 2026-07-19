package com.xyq.livetranslate

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object UpdateDownloader {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun download(
        context: Context,
        info: AppUpdateInfo,
        onProgress: (UpdateDownloadProgress) -> Unit = {},
    ): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, sanitizeFileName(info.apkName))
        if (target.exists()) target.delete()
        val partial = File(dir, target.name + ".part")
        if (partial.exists()) partial.delete()

        val errors = mutableListOf<String>()
        val urls = info.downloadUrls
        urls.forEachIndexed { index, url ->
            val label = sourceLabel(url, index)
            try {
                downloadOne(url, partial, index, urls.size, label, onProgress)
                if (!partial.exists() || partial.length() < 1024L) {
                    throw IOException("文件过小")
                }
                // 下载源含第三方镜像；清单给出摘要时不匹配就换下一个源。
                if (info.sha256.isNotEmpty()) {
                    val actual = UpdateIntegrity.sha256Of(partial)
                    if (actual != info.sha256) {
                        throw IOException("SHA-256 校验失败")
                    }
                }
                if (target.exists()) target.delete()
                if (!partial.renameTo(target)) {
                    partial.copyTo(target, overwrite = true)
                    partial.delete()
                }
                return target
            } catch (e: Exception) {
                partial.delete()
                errors += "$label: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        throw IOException("全部下载源失败：${errors.take(3).joinToString("；")}")
    }

    private fun downloadOne(
        url: String,
        partial: File,
        index: Int,
        total: Int,
        label: String,
        onProgress: (UpdateDownloadProgress) -> Unit,
    ) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "LiveTranslate-UpdateDownloader")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("空响应")
            val length = body.contentLength()
            body.byteStream().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var readTotal = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        readTotal += n
                        onProgress(
                            UpdateDownloadProgress(
                                sourceIndex = index + 1,
                                sourceTotal = total,
                                sourceLabel = label,
                                bytesRead = readTotal,
                                contentLength = length,
                            ),
                        )
                    }
                    output.flush()
                }
            }
        }
    }

    private fun sourceLabel(url: String, index: Int): String = when {
        url.contains("ghproxy.net") -> "镜像 ${index + 1}·ghproxy"
        url.contains("mirror.ghproxy.com") -> "镜像 ${index + 1}·mirror"
        url.contains("github.com") -> "源 ${index + 1}·GitHub"
        else -> "源 ${index + 1}"
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("""[^\w.\-()+\u4e00-\u9fff]"""), "_")
        return if (cleaned.endsWith(".apk", ignoreCase = true)) cleaned else "$cleaned.apk"
    }
}
