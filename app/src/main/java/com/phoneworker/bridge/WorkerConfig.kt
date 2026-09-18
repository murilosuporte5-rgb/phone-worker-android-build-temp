package com.phoneworker.bridge

import android.content.Context

object WorkerConfig {
    private const val PREFS = "phone_worker_config"
    private const val KEY_RELAY = "relay_url"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_TOKEN = "device_token"

    private const val DEFAULT_RELAY = "ws://127.0.0.1:8787/ws/device"
    private const val DEFAULT_DEVICE_ID = "phone-01"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun relayWsUrl(context: Context): String =
        prefs(context).getString(KEY_RELAY, DEFAULT_RELAY)?.trim().orEmpty().ifBlank { DEFAULT_RELAY }

    fun deviceId(context: Context): String =
        prefs(context).getString(KEY_DEVICE_ID, DEFAULT_DEVICE_ID)?.trim().orEmpty().ifBlank { DEFAULT_DEVICE_ID }

    fun deviceToken(context: Context): String =
        prefs(context).getString(KEY_DEVICE_TOKEN, "")?.trim().orEmpty()

    fun save(context: Context, relayUrl: String, deviceId: String, deviceToken: String) {
        prefs(context).edit()
            .putString(KEY_RELAY, relayUrl.trim())
            .putString(KEY_DEVICE_ID, deviceId.trim())
            .putString(KEY_DEVICE_TOKEN, deviceToken.trim())
            .apply()
    }
}
