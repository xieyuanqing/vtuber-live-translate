package com.xyq.livetranslate

import java.io.File
import java.security.MessageDigest

/**
 * 安装前核对 APK 的 SHA-256。
 *
 * 没有摘要就拒绝，而不是放行旧清单。Debug 证书和口令都在仓库里，
 * 攻击者可以签一个证书相同的包；缺 sha256 的清单（或被删掉该字段的清单）不能再当更新源。
 */
object UpdateVerifier {
    private val HEX_64 = Regex("^[0-9a-f]{64}$")
    private val IN_TEXT = Regex("""(?i)sha256\s*[:=]\s*([0-9a-f]{64})""")

    sealed interface Result {
        data object Trusted : Result
        data object Missing : Result
        data class Mismatch(val actual: String) : Result
        data object Unreadable : Result
    }

    fun normalize(raw: String?): String {
        val compact = raw?.trim()?.lowercase()?.replace("\\s".toRegex(), "").orEmpty()
            .removePrefix("sha256:")
        return compact.takeIf { HEX_64.matches(it) }.orEmpty()
    }

    fun sha256InText(text: String): String =
        IN_TEXT.find(text)?.groupValues?.getOrNull(1)?.let(::normalize).orEmpty()

    fun digest(file: File): String {
        val messageDigest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                messageDigest.update(buffer, 0, n)
            }
        }
        return messageDigest.digest().joinToString("") { "%02x".format(it) }
    }

    fun verify(file: File, expectedRaw: String): Result {
        val expected = normalize(expectedRaw)
        if (expected.isEmpty()) return Result.Missing
        val actual = runCatching { digest(file) }.getOrNull() ?: return Result.Unreadable
        return if (actual == expected) Result.Trusted else Result.Mismatch(actual)
    }
}
