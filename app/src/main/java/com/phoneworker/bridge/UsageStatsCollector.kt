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

    fun collectHistory(context: Context, days: Int = 90, topN: Int = 30): JSONObject {
        if (!hasAccess(context)) {
            return JSONObject()
                .put("available", false)
                .put("reason", "usage_access_not_granted")
        }

        val safeDays = days.coerceIn(1, 365)
        val safeTopN = topN.coerceIn(1, 100)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val startDate = today.minusDays((safeDays - 1).toLong())
        val startMs = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = System.currentTimeMillis()
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        val daily = collectIntervalHistory(
            context = context,
            manager = manager,
            intervalType = UsageStatsManager.INTERVAL_DAILY,
            intervalName = "daily",
            startMs = startMs,
            endMs = endMs,
            topN = safeTopN
        )

        val weekly = collectIntervalHistory(
            context = context,
            manager = manager,
            intervalType = UsageStatsManager.INTERVAL_WEEKLY,
            intervalName = "weekly",
            startMs = startMs,
            endMs = endMs,
            topN = safeTopN
        )

        val monthly = collectIntervalHistory(
            context = context,
            manager = manager,
            intervalType = UsageStatsManager.INTERVAL_MONTHLY,
            intervalName = "monthly",
            startMs = startMs,
            endMs = endMs,
            topN = safeTopN
        )

        return JSONObject()
            .put("available", true)
            .put("source", "android_usage_stats_aggregated")
            .put("requested_days", safeDays)
            .put("top_n_per_period", safeTopN)
            .put("requested_start_date", startDate.toString())
            .put("requested_end_date", today.toString())
            .put("requested_start", Instant.ofEpochMilli(startMs).toString())
            .put("requested_end", Instant.ofEpochMilli(endMs).toString())
            .put(
                "resolutions",
                JSONObject()
                    .put("daily", daily)
                    .put("weekly", weekly)
                    .put("monthly", monthly)
            )
            .put("days", daily.optJSONArray("periods") ?: JSONArray())
            .put("days_with_any_data", daily.optInt("periods_with_any_data", 0))
            .put("earliest_available_date", daily.optString("earliest_period_date", null))
            .put("latest_available_date", daily.optString("latest_period_date", null))
            .put("exact_launch_counts_available", false)
            .put("exact_event_timeline_available", false)
            .put(
                "note",
                "Android keeps different retention windows for daily, weekly and monthly UsageStats. Use daily for recent detail, weekly for medium-term history and monthly for older trend recovery. Do not add totals across resolutions because the same usage is represented more than once."
            )
    }

    private fun collectIntervalHistory(
        context: Context,
        manager: UsageStatsManager,
        intervalType: Int,
        intervalName: String,
        startMs: Long,
        endMs: Long,
        topN: Int
    ): JSONObject {
        val zone = ZoneId.systemDefault()

        data class AppAcc(
            var foregroundMs: Long = 0L,
            var lastTimeUsed: Long = 0L
        )

        data class PeriodAcc(
            var startMs: Long,
            var endMs: Long,
            var screenInteractiveMs: Long? = null,
            val apps: MutableMap<String, AppAcc> = mutableMapOf()
        )

        val periods = linkedMapOf<Long, PeriodAcc>()

        val usageRows = manager.queryUsageStats(
            intervalType,
            startMs,
            endMs
        ) ?: emptyList()

        for (row in usageRows) {
            val periodStart = row.firstTimeStamp
            if (periodStart <= 0L) continue

            val periodEnd = maxOf(row.lastTimeStamp, periodStart)
            val period = periods.getOrPut(periodStart) {
                PeriodAcc(
                    startMs = periodStart,
                    endMs = periodEnd
                )
            }
            period.endMs = maxOf(period.endMs, periodEnd)

            if (row.totalTimeInForeground <= 0L && row.lastTimeUsed <= 0L) continue

            val pkg = row.packageName ?: continue
            val app = period.apps.getOrPut(pkg) { AppAcc() }
            app.foregroundMs += row.totalTimeInForeground.coerceAtLeast(0L)
            app.lastTimeUsed = maxOf(app.lastTimeUsed, row.lastTimeUsed)
        }

        if (android.os.Build.VERSION.SDK_INT >= 28) {
            val eventStats = manager.queryEventStats(
                intervalType,
                startMs,
                endMs
            ) ?: emptyList()

            for (row in eventStats) {
                if (row.eventType != UsageEvents.Event.SCREEN_INTERACTIVE) continue

                val periodStart = row.firstTimeStamp
                if (periodStart <= 0L) continue

                val periodEnd = maxOf(row.lastTimeStamp, periodStart)
                val period = periods.getOrPut(periodStart) {
                    PeriodAcc(
                        startMs = periodStart,
                        endMs = periodEnd
                    )
                }
                period.endMs = maxOf(period.endMs, periodEnd)
                period.screenInteractiveMs =
                    (period.screenInteractiveMs ?: 0L) + row.totalTime.coerceAtLeast(0L)
            }
        }

        val pm = context.packageManager
        val arr = JSONArray()
        var periodsWithAnyData = 0
        var earliestPeriodDate: String? = null
        var latestPeriodDate: String? = null

        val sortedPeriods = periods.values.sortedBy { it.startMs }

        for (period in sortedPeriods) {
            val sortedApps = period.apps.entries
                .filter { it.value.foregroundMs > 0L }
                .sortedByDescending { it.value.foregroundMs }

            val hasData = sortedApps.isNotEmpty() || period.screenInteractiveMs != null
            if (!hasData) continue

            periodsWithAnyData += 1

            val startDate = Instant.ofEpochMilli(period.startMs)
                .atZone(zone)
                .toLocalDate()
                .toString()

            val endDate = Instant.ofEpochMilli(period.endMs)
                .atZone(zone)
                .toLocalDate()
                .toString()

            if (earliestPeriodDate == null) earliestPeriodDate = startDate
            latestPeriodDate = endDate

            val appsArray = JSONArray()
            for ((pkg, app) in sortedApps.take(topN)) {
                val label = try {
                    val info = pm.getApplicationInfo(pkg, 0)
                    pm.getApplicationLabel(info).toString()
                } catch (_: Exception) {
                    null
                }

                appsArray.put(
                    JSONObject()
                        .put("package_name", pkg)
                        .put("app_label", label)
                        .put("foreground_seconds", (app.foregroundMs / 1000L).toInt())
                        .put(
                            "last_time_used",
                            if (app.lastTimeUsed > 0L) {
                                Instant.ofEpochMilli(app.lastTimeUsed).toString()
                            } else {
                                JSONObject.NULL
                            }
                        )
                )
            }

            arr.put(
                JSONObject()
                    .put("period_start", Instant.ofEpochMilli(period.startMs).toString())
                    .put("period_end", Instant.ofEpochMilli(period.endMs).toString())
                    .put("start_date", startDate)
                    .put("end_date", endDate)
                    .put("has_data", true)
                    .put("starts_before_requested_window", period.startMs < startMs)
                    .put("ends_after_requested_window", period.endMs > endMs)
                    .put(
                        "screen_total_seconds",
                        period.screenInteractiveMs?.let { (it / 1000L).toInt() }
                            ?: JSONObject.NULL
                    )
                    .put("apps", appsArray)
            )
        }

        return JSONObject()
            .put("interval", intervalName)
            .put("periods_with_any_data", periodsWithAnyData)
            .put("earliest_period_date", earliestPeriodDate)
            .put("latest_period_date", latestPeriodDate)
            .put("periods", arr)
    }

}
