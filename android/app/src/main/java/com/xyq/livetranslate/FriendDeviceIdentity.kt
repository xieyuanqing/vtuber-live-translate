package com.xyq.livetranslate

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec

object FriendDeviceIdentity {
    private const val KEY_ALIAS = "livetranslate_friend_device_v1"
    private const val KEYSTORE = "AndroidKeyStore"

    data class SignedHeaders(
        val deviceId: String,
        val timestamp: String,
        val nonce: String,
        val signature: String,
    ) {
        fun asMap(): Map<String, String> = mapOf(
            "X-Device-ID" to deviceId,
            "X-Device-Time" to timestamp,
            "X-Device-Nonce" to nonce,
            "X-Device-Signature" to signature,
        )
    }

    @Synchronized
    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    @Synchronized
    private fun ensureKeyPair() {
        val store = keyStore()
        if (store.containsAlias(KEY_ALIAS)) return
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
            .build()
        generator.initialize(spec)
        generator.generateKeyPair()
    }

    fun publicKey(@Suppress("UNUSED_PARAMETER") context: Context): String {
        ensureKeyPair()
        val certificate = keyStore().getCertificate(KEY_ALIAS)
            ?: error("无法读取好友测试设备公钥")
        return base64Url(certificate.publicKey.encoded)
    }

    fun deviceId(context: Context): String {
        val publicDer = decodeBase64Url(publicKey(context))
        return base64Url(MessageDigest.getInstance("SHA-256").digest(publicDer))
    }

    fun signBinding(
        context: Context,
        challengeId: String,
        nonce: String,
        deviceId: String,
    ): String {
        val message = "LTG-BIND-V1\n$challengeId\n$nonce\n$deviceId"
        return sign(context, message.toByteArray(StandardCharsets.UTF_8))
    }

    fun signRequest(
        context: Context,
        method: String,
        path: String,
        body: ByteArray,
        token: String,
    ): SignedHeaders {
        val timestamp = (System.currentTimeMillis() / 1000L).toString()
        val random = ByteArray(18).also(SecureRandom()::nextBytes)
        val nonce = base64Url(random)
        val bodyHash = hex(MessageDigest.getInstance("SHA-256").digest(body))
        val tokenHash = hex(
            MessageDigest.getInstance("SHA-256")
                .digest(token.toByteArray(StandardCharsets.UTF_8))
        )
        val message = buildString {
            append("LTG-REQ-V1\n")
            append(timestamp).append('\n')
            append(nonce).append('\n')
            append(method.uppercase()).append('\n')
            append(path).append('\n')
            append(bodyHash).append('\n')
            append(tokenHash)
        }
        return SignedHeaders(
            deviceId = deviceId(context),
            timestamp = timestamp,
            nonce = nonce,
            signature = sign(context, message.toByteArray(StandardCharsets.UTF_8)),
        )
    }

    private fun sign(context: Context, message: ByteArray): String {
        ensureKeyPair()
        val entry = keyStore().getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            ?: error("好友测试设备私钥不可用")
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(entry.privateKey)
        signature.update(message)
        return base64Url(signature.sign())
    }

    private fun base64Url(value: ByteArray): String =
        Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun decodeBase64Url(value: String): ByteArray =
        Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun hex(value: ByteArray): String = value.joinToString("") { "%02x".format(it) }
}
