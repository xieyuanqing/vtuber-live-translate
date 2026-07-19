package com.xyq.livetranslate

/**
 * 远端更新清单。versionCode 是唯一比较依据。
 * downloadUrls 按优先级排列：GitHub 官方 → 国内镜像。
 * sha256 为 APK 的十六进制摘要（可空）；下载源里有第三方代理镜像，
 * 提供摘要时必须校验通过才允许安装。
 */
data class AppUpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val title: String,
    val notes: String,
    val apkName: String,
    val downloadUrls: List<String>,
    val sourceLabel: String = "",
    val sha256: String = "",
)

sealed class UpdateCheckResult {
    data class UpToDate(val currentCode: Long, val latestCode: Long) : UpdateCheckResult()
    data class Available(val info: AppUpdateInfo) : UpdateCheckResult()
    data class Failed(val message: String) : UpdateCheckResult()
    data object Ignored : UpdateCheckResult()
}

data class UpdateDownloadProgress(
    val sourceIndex: Int,
    val sourceTotal: Int,
    val sourceLabel: String,
    val bytesRead: Long,
    val contentLength: Long,
) {
    val percent: Int
        get() = if (contentLength > 0L) {
            ((bytesRead * 100L) / contentLength).toInt().coerceIn(0, 100)
        } else {
            -1
        }
}
