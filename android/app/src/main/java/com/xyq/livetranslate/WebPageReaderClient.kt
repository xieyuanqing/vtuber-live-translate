package com.xyq.livetranslate

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * 通过 Jina Reader 把未知公网页面转换为受限纯文本。App 不直接访问目标站点；
 * 目标 URL 仍需先拒绝本地、私网和带凭据地址，避免把敏感地址交给第三方抓取。
 */
object WebPageReaderClient {
    private const val USER_AGENT = "LiveTranslate/2.4.1 (Android)"
    private const val MAX_CONTENT_CHARS = 12_000
    private val DEFAULT_ENDPOINT = "https://r.jina.ai/".toHttpUrl()
    private val URL_PATTERN = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private val CONTROL_CHARS = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")
    private val IPV4_LITERAL = Regex("^[0-9.]+$")

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun fetch(inputUrl: String): VideoMetadata = fetch(inputUrl, http, DEFAULT_ENDPOINT)

    internal fun fetch(
        inputUrl: String,
        client: OkHttpClient,
        endpoint: HttpUrl,
    ): VideoMetadata {
        val target = validateTargetUrl(inputUrl, client.dns)
        val encodedTarget = target.toString().replace("#", "%23")
        val requestUrl = (endpoint.toString().trimEnd('/') + "/" + encodedTarget).toHttpUrl()
        val request = Request.Builder()
            .url(requestUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("X-Respond-With", "text")
            .header("X-Max-Tokens", "3000")
            .header("X-Retain-Links", "text")
            .header("X-Retain-Images", "alt")
            .header("X-Retain-Media", "text")
            .header("X-Timeout", "15")
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val reason = when (response.code) {
                    401, 403 -> "服务拒绝了匿名抓取请求"
                    429 -> "请求过于频繁，请稍后重试"
                    else -> "HTTP ${response.code}"
                }
                error("通用网页抓取失败：$reason")
            }
            val raw = response.body?.string().orEmpty()
            if (raw.isBlank()) error("通用网页抓取失败：服务返回为空")
            val root = runCatching { JSONObject(raw) }
                .getOrElse { error("通用网页抓取失败：返回格式无效") }
            val code = root.optInt("code", 200)
            if (code !in 200..299) error("通用网页抓取失败：服务错误 $code")
            val data = root.optJSONObject("data")
                ?: error("通用网页抓取失败：没有返回页面内容")
            val targetStatus = data.optInt("httpStatus", 200)
            if (targetStatus !in 200..399) {
                error("通用网页抓取失败：目标页面 HTTP $targetStatus")
            }
            val finalUrl = data.optString("url").trim()
            if (finalUrl.isNotEmpty()) {
                runCatching { validateTargetUrl(finalUrl, client.dns) }
                    .getOrElse { error("通用网页抓取失败：目标页面发生不安全重定向") }
            }
            val content = cleanContent(data.optString("text"))
            if (content.isBlank()) error("通用网页抓取失败：没有提取到有效内容")
            VideoMetadata(
                platform = VideoPlatform.WEB,
                url = target.toString(),
                title = cleanLine(data.optString("title")).take(200),
                authorName = "",
                description = cleanLine(data.optString("description")).take(500),
                content = content,
            )
        }
    }

    internal fun validateTargetUrl(input: String, dns: Dns? = null): HttpUrl {
        val raw = input.trim()
        require(raw.length in 1..2048) { "网页链接为空或过长" }
        val withScheme = if (raw.contains("://")) raw else "https://$raw"
        val url = withScheme.toHttpUrlOrNull() ?: throw IllegalArgumentException("网页链接格式无效")
        require(url.scheme == "http" || url.scheme == "https") { "网页链接只允许 HTTP(S)" }
        require(url.encodedUsername.isEmpty() && url.encodedPassword.isEmpty()) {
            "网页链接不能包含账号或密码"
        }
        require(url.port == 80 || url.port == 443) { "网页链接不允许使用非标准端口" }
        require(!isPrivateHost(url.host, dns)) { "网页链接不能指向本地、私网或保留地址" }
        return url
    }

    private fun isPrivateHost(host: String, dns: Dns?): Boolean {
        val normalized = host.lowercase().trimEnd('.')
        if (
            normalized == "localhost" ||
            normalized.endsWith(".localhost") ||
            normalized.endsWith(".local") ||
            normalized.endsWith(".internal") ||
            normalized.endsWith(".home") ||
            normalized.endsWith(".home.arpa") ||
            normalized.endsWith(".lan") ||
            normalized.endsWith(".corp") ||
            normalized.endsWith(".onion")
        ) {
            return true
        }
        val looksLikeLiteral = ':' in normalized || IPV4_LITERAL.matches(normalized)
        if (looksLikeLiteral) {
            val address = runCatching { InetAddress.getByName(normalized) }.getOrNull() ?: return true
            return isPrivateAddress(address)
        }
        if ('.' !in normalized) return true
        if (dns == null) return false
        val addresses = runCatching { dns.lookup(normalized) }
            .getOrElse { throw IllegalArgumentException("网页域名无法解析") }
        return addresses.isEmpty() || addresses.any(::isPrivateAddress)
    }

    private fun isPrivateAddress(address: InetAddress): Boolean {
        if (
            address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }
        return when (address) {
            is Inet4Address -> isReservedIpv4(address.address)
            is Inet6Address -> {
                val bytes = address.address
                val first = bytes[0].toInt() and 0xff
                val isUniqueLocal = first and 0xfe == 0xfc
                val isDocumentation =
                    first == 0x20 &&
                        (bytes[1].toInt() and 0xff) == 0x01 &&
                        (bytes[2].toInt() and 0xff) == 0x0d &&
                        (bytes[3].toInt() and 0xff) == 0xb8
                isUniqueLocal || isDocumentation
            }
            else -> true
        }
    }

    private fun isReservedIpv4(bytes: ByteArray): Boolean {
        val a = bytes[0].toInt() and 0xff
        val b = bytes[1].toInt() and 0xff
        val c = bytes[2].toInt() and 0xff
        return a == 0 ||
            a == 10 ||
            a == 127 ||
            a >= 224 ||
            (a == 100 && b in 64..127) ||
            (a == 169 && b == 254) ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 192 && b == 0 && (c == 0 || c == 2)) ||
            (a == 198 && b in 18..19) ||
            (a == 198 && b == 51 && c == 100) ||
            (a == 203 && b == 0 && c == 113)
    }

    private fun cleanLine(value: String): String = value
        .replace(CONTROL_CHARS, " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun cleanContent(value: String): String = value
        .replace(URL_PATTERN, " ")
        .replace(CONTROL_CHARS, " ")
        .trim()
        .take(MAX_CONTENT_CHARS)
}
