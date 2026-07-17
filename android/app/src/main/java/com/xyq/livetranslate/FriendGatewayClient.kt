package com.xyq.livetranslate

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

interface FriendGatewayIdentity {
    fun publicKey(): String
    fun deviceId(): String
    fun signBinding(challengeId: String, nonce: String, deviceId: String): String
    fun signRequest(method: String, path: String, body: ByteArray, token: String): Map<String, String>
}

private class AndroidFriendGatewayIdentity(context: Context) : FriendGatewayIdentity {
    private val appContext = context.applicationContext

    override fun publicKey(): String = FriendDeviceIdentity.publicKey(appContext)
    override fun deviceId(): String = FriendDeviceIdentity.deviceId(appContext)

    override fun signBinding(challengeId: String, nonce: String, deviceId: String): String =
        FriendDeviceIdentity.signBinding(appContext, challengeId, nonce, deviceId)

    override fun signRequest(
        method: String,
        path: String,
        body: ByteArray,
        token: String,
    ): Map<String, String> =
        FriendDeviceIdentity.signRequest(appContext, method, path, body, token).asMap()
}

data class FriendGatewayBinding(
    val accessToken: String,
    val tokenExpiresAt: Long,
    val label: String,
)

data class FriendGatewayStatus(
    val label: String,
    val tokenExpiresAt: Long,
    val textRequests: Int,
    val liveSessions: Int,
)

class FriendGatewayClient private constructor(
    private val identity: FriendGatewayIdentity,
    private val baseUrl: String,
    private val http: OkHttpClient,
    allowInsecureForTests: Boolean,
) {
    constructor(context: Context) : this(
        identity = AndroidFriendGatewayIdentity(context),
        baseUrl = FriendGatewayStore.GATEWAY_BASE_URL,
        http = defaultHttp(),
        allowInsecureForTests = false,
    )

    internal constructor(
        identity: FriendGatewayIdentity,
        baseUrl: String,
        http: OkHttpClient = defaultHttp(),
    ) : this(identity, baseUrl, http, allowInsecureForTests = true)

    init {
        val parsed = baseUrl.trimEnd('/').toHttpUrlOrNull()
            ?: throw IllegalArgumentException("好友服务器地址无效")
        if (!allowInsecureForTests && parsed.scheme != "https") {
            throw IllegalArgumentException("好友服务器必须使用 HTTPS")
        }
    }

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    fun bind(inviteCode: String, appVersion: String): FriendGatewayBinding {
        val challengeBody = JSONObject()
            .put("inviteCode", inviteCode.trim())
            .put("devicePublicKey", identity.publicKey())
            .put("appVersion", appVersion)
            .toString()
        val challenge = executeJson(
            Request.Builder()
                .url(baseUrl.trimEnd('/') + "/api/v1/bind/challenge")
                .post(challengeBody.toRequestBody(jsonMedia))
                .build()
        )
        val challengeId = challenge.optString("challengeId")
        val nonce = challenge.optString("nonce")
        val deviceId = challenge.optString("deviceId")
        if (challengeId.isBlank() || nonce.isBlank() || deviceId != identity.deviceId()) {
            throw IOException("服务器返回的设备绑定验证无效")
        }
        val signature = identity.signBinding(challengeId, nonce, deviceId)
        val completeBody = JSONObject()
            .put("challengeId", challengeId)
            .put("signature", signature)
            .put("appVersion", appVersion)
            .toString()
        val result = executeJson(
            Request.Builder()
                .url(baseUrl.trimEnd('/') + "/api/v1/bind")
                .post(completeBody.toRequestBody(jsonMedia))
                .build()
        )
        val token = result.optString("accessToken")
        val expires = result.optLong("tokenExpiresAt")
        if (token.isBlank() || expires <= 0L) throw IOException("服务器返回的绑定凭据无效")
        return FriendGatewayBinding(token, expires, result.optString("label"))
    }

    fun status(token: String): FriendGatewayStatus {
        val url = (baseUrl.trimEnd('/') + "/api/v1/status").toHttpUrlOrNull()
            ?: throw IOException("好友服务器地址无效")
        val headers = identity.signRequest("GET", url.encodedPath, byteArrayOf(), token)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .apply { headers.forEach(::header) }
            .get()
            .build()
        val result = executeJson(request)
        val usage = result.optJSONObject("usage") ?: JSONObject()
        return FriendGatewayStatus(
            label = result.optString("label"),
            tokenExpiresAt = result.optLong("tokenExpiresAt"),
            textRequests = usage.optInt("textRequests"),
            liveSessions = usage.optInt("liveSessions"),
        )
    }

    private fun executeJson(request: Request): JSONObject {
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching {
                    JSONObject(body).optJSONObject("error")?.optString("message")
                }.getOrNull().orEmpty()
                throw IOException(message.ifBlank { httpError(response.code) })
            }
            return runCatching { JSONObject(body) }
                .getOrElse { throw IOException("服务器响应格式无效") }
        }
    }

    private fun httpError(code: Int): String = when (code) {
        401, 403 -> "好友测试资格无效，请重新绑定"
        409 -> "邀请码已绑定其他设备"
        429 -> "请求过于频繁或今日额度已用完"
        in 500..599 -> "好友测试服务器暂时不可用"
        else -> "服务器请求失败（$code）"
    }

    private companion object {
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
