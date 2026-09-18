package com.phoneworker.bridge

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WorkerService : Service() {
    private val channelId = "phone_worker"
    private var socket: WebSocket? = null
    private var stopped = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()

    private val heartbeat = object : Runnable {
        override fun run() {
            if (!stopped) {
                sendHeartbeat()
                mainHandler.postDelayed(this, 20_000)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Phone Worker ativo")
            .setContentText("Sessão remota ativa. Use o kill switch no app para encerrar.")
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(7, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(7, notification)
        }

        connect()
        mainHandler.post(heartbeat)
    }

    private fun connect() {
        if (stopped) return

        val token = WorkerConfig.deviceToken(this)
        if (token.isBlank()) {
            stopSelf()
            return
        }

        val rawRelay = WorkerConfig.relayWsUrl(this)
        val httpCompatible = when {
            rawRelay.startsWith("ws://", ignoreCase = true) -> "http://" + rawRelay.substring(5)
            rawRelay.startsWith("wss://", ignoreCase = true) -> "https://" + rawRelay.substring(6)
            else -> rawRelay
        }

        val url = try {
            httpCompatible.toHttpUrl().newBuilder()
                .addQueryParameter("device_id", WorkerConfig.deviceId(this))
                .build()
        } catch (_: Exception) {
            mainHandler.postDelayed({ connect() }, 5000)
            return
        }

        val request = Request.Builder()
            .url(url)
            .header("X-Device-Token", token)
            .build()

        val newSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!stopped) sendHeartbeat()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!stopped) handle(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!stopped) mainHandler.postDelayed({ connect() }, 3000)
            }
        })
        socket = newSocket
    }

    private fun handle(text: String) {
        val msg = try { JSONObject(text) } catch (_: Exception) { return }
        if (msg.optString("type") != "command") return
        val commandId = msg.optString("command_id")
        val action = msg.optString("action")
        val args = msg.optJSONObject("args") ?: JSONObject()
        val service = BridgeAccessibilityService.instance

        try {
            val data: Any? = when (action) {
                "state" -> state()
                "ui_tree" -> service?.uiTree(args.optInt("max_nodes", 300))
                    ?: throw IllegalStateException("accessibility_service_off")
                "tap" -> JSONObject().put("accepted", service?.tap(
                    args.getDouble("x").toFloat(), args.getDouble("y").toFloat()) ?: false)
                "swipe" -> JSONObject().put("accepted", service?.swipe(
                    args.getDouble("x1").toFloat(), args.getDouble("y1").toFloat(),
                    args.getDouble("x2").toFloat(), args.getDouble("y2").toFloat(),
                    args.optLong("duration_ms", 400)) ?: false)
                "type_text" -> JSONObject().put("accepted", service?.typeText(args.getString("text")) ?: false)
                "back" -> JSONObject().put("accepted", service?.back() ?: false)
                "home" -> JSONObject().put("accepted", service?.home() ?: false)
                "open_app" -> openApp(args.getString("package"))

                "usage_today" -> UsageStatsCollector.collectToday(
                    this,
                    args.optInt("top_n", 25)
                )

                "focus_start" -> FocusSessionManager.start(
                    this,
                    args.optString("label").takeIf { it.isNotBlank() },
                    service?.currentPackage()
                )

                "focus_status" -> FocusSessionManager.status(this)

                "focus_stop" -> FocusSessionManager.stop(this)

                "focus_history" -> FocusSessionManager.history(
                    this,
                    args.optInt("limit", 20)
                )

                "protocol3_snapshot" -> protocol3Snapshot(args)

                else -> throw IllegalArgumentException("unsupported_action:$action")
            }
            result(commandId, true, data, null)
        } catch (e: Exception) {
            result(commandId, false, null, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun protocol3Snapshot(args: JSONObject): JSONObject {
        return JSONObject()
            .put("captured_at_ms", System.currentTimeMillis())
            .put("state", state())
            .put("focus", FocusSessionManager.status(this))
            .put("usage_today", UsageStatsCollector.collectToday(
                this,
                args.optInt("top_n", 20)
            ))
    }

    private fun openApp(packageName: String): JSONObject {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: return JSONObject().put("launched", false).put("reason", "package_not_launchable_or_not_visible")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        return JSONObject().put("launched", true).put("package", packageName)
    }

    private fun state(): JSONObject {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return JSONObject()
            .put("device_id", WorkerConfig.deviceId(this))
            .put("android_device_id", UsageStatsCollector.androidDeviceId(this))
            .put("battery_pct", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .put("foreground_package", BridgeAccessibilityService.instance?.currentPackage())
            .put("accessibility_active", BridgeAccessibilityService.instance != null)
            .put("usage_access", UsageStatsCollector.hasAccess(this))
            .put("focus_active", FocusSessionManager.isActive(this))
    }

    private fun result(commandId: String, ok: Boolean, data: Any?, error: String?) {
        val obj = JSONObject()
            .put("type", "result")
            .put("command_id", commandId)
            .put("ok", ok)
            .put("evidence", state())
        if (data != null) obj.put("data", data)
        if (error != null) obj.put("error", error)
        socket?.send(obj.toString())
    }

    private fun sendHeartbeat() {
        socket?.send(JSONObject().put("type", "heartbeat").put("state", state()).toString())
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(channelId, "Phone Worker", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onDestroy() {
        stopped = true
        mainHandler.removeCallbacksAndMessages(null)
        socket?.close(1000, "local_kill_switch")
        socket = null
        client.dispatcher.executorService.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
