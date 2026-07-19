package com.xyq.livetranslate

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import java.io.File
import java.security.MessageDigest

/**
 * 更新包完整性与身份校验。
 *
 * 下载源包含第三方代理镜像（ghproxy 等），镜像内容不可信：
 * 1. 清单提供 sha256 时，下载完成后先校验摘要（UpdateDownloader 调用）；
 * 2. 安装前无论有无摘要，都校验 APK 的包名与签名证书和当前安装一致，
 *    防止镜像用另一个包名的恶意 APK 冒充更新装成「新应用」。
 */
object UpdateIntegrity {

    /** 流式计算文件 SHA-256，返回小写十六进制。 */
    fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().toHexString()
    }

    /**
     * 校验 APK 能否作为当前应用的更新安装。
     * 返回 null 表示通过；否则返回给用户看的失败原因。
     */
    fun apkInstallProblem(context: Context, apk: File): String? {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val archive = pm.getPackageArchiveInfo(
            apk.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES,
        ) ?: return "安装包无法解析"
        if (archive.packageName != context.packageName) {
            return "安装包包名不符：${archive.packageName}"
        }
        val archiveCerts = signerDigests(archive.signingInfo)
            ?: return "安装包缺少签名信息"
        val installedCerts = signerDigests(
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo,
        ) ?: return "无法读取当前应用签名"
        if (archiveCerts != installedCerts) {
            return "安装包签名与当前应用不一致"
        }
        return null
    }

    private fun signerDigests(info: SigningInfo?): Set<String>? {
        info ?: return null
        val signatures = if (info.hasMultipleSigners()) {
            info.apkContentsSigners
        } else {
            info.signingCertificateHistory
        }
        if (signatures.isNullOrEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256")
        return signatures.map { digest.digest(it.toByteArray()).toHexString() }.toSet()
    }

    private fun ByteArray.toHexString(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte) }
}
