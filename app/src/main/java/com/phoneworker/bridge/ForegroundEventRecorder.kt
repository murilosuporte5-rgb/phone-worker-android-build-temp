package com.phoneworker.bridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object ForegroundEventRecorder {
    private const val PREFS = "protocol3_foreground_events"
    private const val RETENTION_DAYS = 45

    @Synchronized
    fun recordTransition(context: Context, packageName: String, atMs: Long = System.currentTimeMillis()) {
        val pkg = packageName.trim()
        if (pkg.isBlank()) return

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getString("last_package", null)
        if (previous == pkg) return

        val zone = ZoneId.systemDefault()
        val at = Instant.ofEpochMilli(atMs)
        val localDate = at.atZone(zone).toLocalDate().toString()

        val event = JSONObject()
            .put("schema_version", 1)
            .put("event_type", "foreground_transition")
            .put("at", at.toString())
            .put("at_ms", atMs)
            .put("local_date", localDate)
            .put("timezone", zone.id)
            .put("from_package", previous)
            .put("to_package", pkg)
            .put("source", "accessibility_package_transition")

        eventFile(context, localDate).appendText(event.toString() + "\n")

        prefs.edit()
            .putString("last_package", pkg)
            .putLong("last_at_ms", atMs)
            .apply()

        if (prefs.getString("last_pruned_date", null) != localDate) {
            prune(context, LocalDate.parse(localDate), RETENTION_DAYS)
            prefs.edit().putString("last_pruned_date", localDate).apply()
        }
    }

    fun history(context: Context, dateKey: String?, limit: Int = 10000): JSONObject {
        val zone = ZoneId.systemDefault()
        val date = try {
            if (dateKey.isNullOrBlank()) LocalDate.now(zone) else LocalDate.parse(dateKey)
        } catch (_: Exception) {
            return JSONObject()
                .put("available", false)
                .put("reason", "invalid_local_date")
        }

        val file = eventFile(context, date.toString())
        val rows = if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()
        val safeLimit = limit.coerceIn(1, 20000)
        val selected = if (rows.size > safeLimit) rows.takeLast(safeLimit) else rows

        val arr = JSONArray()
        for (line in selected) {
            try { arr.put(JSONObject(line)) } catch (_: Exception) {}
        }

        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()

        return JSONObject()
            .put("available", true)
            .put("local_date", date.toString())
            .put("timezone", zone.id)
            .put("period_start", start.toString())
            .put("period_end", end.toString())
            .put("events", arr)
            .put("event_count", arr.length())
            .put("truncated", rows.size > safeLimit)
            .put("semantics", "package_transitions_only_no_screen_text_no_content_capture")
    }

    private fun eventFile(context: Context, dateKey: String): File =
        File(context.filesDir, "protocol3_foreground_$dateKey.jsonl")

    private fun prune(context: Context, today: LocalDate, retentionDays: Int) {
        val cutoff = today.minusDays(retentionDays.toLong())
        context.filesDir.listFiles()?.forEach { file ->
            val name = file.name
            if (!name.startsWith("protocol3_foreground_") || !name.endsWith(".jsonl")) return@forEach
            val key = name.removePrefix("protocol3_foreground_").removeSuffix(".jsonl")
            val date = try { LocalDate.parse(key) } catch (_: Exception) { return@forEach }
            if (date.isBefore(cutoff)) {
                try { file.delete() } catch (_: Exception) {}
            }
        }
    }
}
