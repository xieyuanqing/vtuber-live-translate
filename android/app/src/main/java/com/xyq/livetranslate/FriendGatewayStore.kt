package com.xyq.livetranslate

import android.content.Context

object FriendGatewayStore {
    const val GATEWAY_BASE_URL = "https://translate-test.994431.xyz"
    const val GATEWAY_WS_BASE_URL = "wss://translate-test.994431.xyz/gateway"
    const val MODE_PERSONAL = "personal"
    const val MODE_FRIEND = "friend"

    private const val PREFS = "friend_gateway_v1"
    private const val KEY_MODE = "mode"
    private const val KEY_TOKEN_ENC = "token_enc"
    private const val KEY_LABEL = "label"
    private const val KEY_EXPIRES_AT = "expires_at"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun deviceId(context: Context): String = FriendDeviceIdentity.deviceId(context)

    fun mode(context: Context): String =
        prefs(context).getString(KEY_MODE, MODE_PERSONAL) ?: MODE_PERSONAL

    fun token(context: Context): String =
        KeystoreCrypto.decrypt(prefs(context).getString(KEY_TOKEN_ENC, "").orEmpty())

    fun label(context: Context): String = prefs(context).getString(KEY_LABEL, "").orEmpty()

    fun expiresAt(context: Context): Long = prefs(context).getLong(KEY_EXPIRES_AT, 0L)

    fun isBound(context: Context): Boolean =
        token(context).isNotBlank() && expiresAt(context) > System.currentTimeMillis() / 1000L

    fun isActive(context: Context): Boolean = mode(context) == MODE_FRIEND && isBound(context)

    fun saveBinding(
        context: Context,
        token: String,
        label: String,
        expiresAt: Long,
    ): Boolean {
        val encrypted = KeystoreCrypto.encrypt(token.trim())
        if (encrypted.isBlank()) return false
        prefs(context).edit()
            .putString(KEY_TOKEN_ENC, encrypted)
            .putString(KEY_LABEL, label.trim())
            .putLong(KEY_EXPIRES_AT, expiresAt)
            .apply()
        return true
    }

    fun usePersonal(context: Context) {
        prefs(context).edit().putString(KEY_MODE, MODE_PERSONAL).apply()
    }

    fun useFriend(context: Context): Boolean {
        if (!isBound(context)) return false
        prefs(context).edit().putString(KEY_MODE, MODE_FRIEND).apply()
        return true
    }

    fun clearBinding(context: Context) {
        prefs(context).edit()
            .remove(KEY_TOKEN_ENC)
            .remove(KEY_LABEL)
            .remove(KEY_EXPIRES_AT)
            .putString(KEY_MODE, MODE_PERSONAL)
            .apply()
    }
}
