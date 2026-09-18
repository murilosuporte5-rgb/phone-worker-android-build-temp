package com.phoneworker.bridge

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max

object UsageStatsCollector {
    fun hasAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun androidDeviceId(context: Context): String? =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

    fun collectToday(context: Context, topN: Int = 25): JSONObject {
        if (!hasAccess(context)) {
            return JSONObject()
                .put("available", false)
                .put("reason", "usage_access_not_granted")
        }

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val startMs = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = System.currentTimeMillis()

        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = manager.queryEvents(max(0L, startMs - 86_400_000L), endMs)
        val event = UsageEvents.Event()

        data class Acc(
            var foregroundMs: Long = 0,
            var launchCount: Int = 0,
            var firstUseAt: Long? = null,
            var lastUseAt: Long? = null,
            var activeSince: Long? = null
        )

        val apps = mutableMapOf<String, Acc>()
        var screenInteractive = false
        var screenSince: Long? = null
        var screenInteractiveMs = 0L

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val t = event.timeStamp
            if (t > endMs) break

            when (event.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> {
                    screenInteractive = true
                    screenSince = max(t, startMs)
                    continue
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    if (screenInteractive && t >= startMs) {
                        val since = screenSince ?: startMs
                        screenInteractiveMs += max(0L, minOf(t, endMs) - max(since, startMs))
                    }
                    screenInteractive = false
                    screenSince = null
                    continue
                }
            }

            val pkg = event.packageName ?: continue
            val resume = event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            val pause = event.eventType == UsageEvents.Event.ACTIVITY_PAUSED
            if (!resume && !pause) continue

            val acc = apps.getOrPut(pkg) { Acc() }
            if (resume) {
                if (t >= startMs) {
                    acc.launchCount += 1
                    acc.firstUseAt = acc.firstUseAt?.let { minOf(it, t) } ?: t
                    acc.lastUseAt = acc.lastUseAt?.let { maxOf(it, t) } ?: t
                }
                acc.activeSince = max(t, startMs)
            } else {
                val since = acc.activeSince
                if (since != null && t >= startMs) {
                    acc.foregroundMs += max(0L, minOf(t, endMs) - since)
                    acc.lastUseAt = acc.lastUseAt?.let { maxOf(it, t) } ?: t
                }
                acc.activeSince = null
            }
        }

        if (screenInteractive) {
            screenInteractiveMs += max(0L, endMs - (screenSince ?: startMs))
        }

        for (acc in apps.values) {
            val since = acc.activeSince
            if (since != null) {
                acc.foregroundMs += max(0L, endMs - since)
                acc.activeSince = null
            }
        }

        val pm = context.packageManager
        val rows = apps.entries
            .filter { it.value.foregroundMs > 0L || it.value.launchCount > 0 }
            .sortedByDescending { it.value.foregroundMs }
            .take(topN.coerceIn(1, 100))

        val arr = JSONArray()
        for ((pkg, acc) in rows) {
            val label = try {
                val info = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(info).toString()
            } catch (_: Exception) {
                null
            }
            arr.put(
                JSONObject()
                    .put("package_name", pkg)
                    .put("app_label", label)
                    .put("foreground_seconds", (acc.foregroundMs / 1000L).toInt())
                    .put("launch_count", acc.launchCount)
                    .put("first_use_at", acc.firstUseAt?.let { Instant.ofEpochMilli(it).toString() })
                    .put("last_use_at", acc.lastUseAt?.let { Instant.ofEpochMilli(it).toString() })
            )
        }

        return JSONObject()
            .put("available", true)
            .put("usage_date", today.toString())
            .put("collection_start", Instant.ofEpochMilli(startMs).toString())
            .put("collection_end", Instant.ofEpochMilli(endMs).toString())
            .put("total_screen_seconds", (screenInteractiveMs / 1000L).toInt())
            .put("top_n", topN.coerceIn(1, 100))
            .put("apps", arr)
    }
}
