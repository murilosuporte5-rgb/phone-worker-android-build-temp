package com.phoneworker.bridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

object FocusSessionManager {
    private const val PREFS = "protocol3_focus"
    private const val HISTORY_FILE = "protocol3_focus_sessions.jsonl"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isActive(context: Context): Boolean = prefs(context).getBoolean("active", false)

    fun start(context: Context, label: String?, initialPackage: String?): JSONObject {
        if (isActive(context)) {
            return status(context).put("already_active", true)
        }

        val now = System.currentTimeMillis()
        prefs(context).edit()
            .putBoolean("active", true)
            .putString("session_id", UUID.randomUUID().toString())
            .putString("label", label?.trim()?.take(120))
            .putLong("started_at_ms", now)
            .putInt("app_switches", 0)
            .putString("current_package", initialPackage)
            .putLong("current_since_ms", now)
            .putString("durations_json", "{}")
            .putString(
                "entries_json",
                if (initialPackage.isNullOrBlank()) "{}"
                else JSONObject().put(initialPackage, 1).toString()
            )
            .apply()

        return status(context)
    }

    fun onPackageChanged(context: Context, packageName: String, atMs: Long = System.currentTimeMillis()) {
        if (!isActive(context)) return

        val p = prefs(context)
        val current = p.getString("current_package", null)
        val since = p.getLong("current_since_ms", atMs)

        if (current == packageName) return

        val durations = loadDurations(p.getString("durations_json", "{}"))
        val entries = loadDurations(p.getString("entries_json", "{}"))
        if (!current.isNullOrBlank()) {
            durations.put(current, durations.optLong(current, 0L) + (atMs - since).coerceAtLeast(0L))
        }

        entries.put(packageName, entries.optLong(packageName, 0L) + 1L)

        p.edit()
            .putString("durations_json", durations.toString())
            .putString("entries_json", entries.toString())
            .putString("current_package", packageName)
            .putLong("current_since_ms", atMs)
            .putInt("app_switches", p.getInt("app_switches", 0) + if (current.isNullOrBlank()) 0 else 1)
            .apply()
    }

    fun status(context: Context): JSONObject {
        val p = prefs(context)
        if (!p.getBoolean("active", false)) {
            return JSONObject().put("active", false)
        }

        val now = System.currentTimeMillis()
        val started = p.getLong("started_at_ms", now)
        val current = p.getString("current_package", null)
        val since = p.getLong("current_since_ms", now)
        val durations = loadDurations(p.getString("durations_json", "{}"))
        val entries = loadDurations(p.getString("entries_json", "{}"))

        if (!current.isNullOrBlank()) {
            durations.put(current, durations.optLong(current, 0L) + (now - since).coerceAtLeast(0L))
        }

        return summary(
            active = true,
            sessionId = p.getString("session_id", null),
            label = p.getString("label", null),
            startedAt = started,
            endedAt = null,
            now = now,
            appSwitches = p.getInt("app_switches", 0),
            currentPackage = current,
            durations = durations,
            entries = entries
        )
    }

    fun stop(context: Context): JSONObject {
        val p = prefs(context)
        if (!p.getBoolean("active", false)) {
            return JSONObject().put("active", false).put("stopped", false).put("reason", "no_active_session")
        }

        val now = System.currentTimeMillis()
        val started = p.getLong("started_at_ms", now)
        val current = p.getString("current_package", null)
        val since = p.getLong("current_since_ms", now)
        val durations = loadDurations(p.getString("durations_json", "{}"))
        val entries = loadDurations(p.getString("entries_json", "{}"))

        if (!current.isNullOrBlank()) {
            durations.put(current, durations.optLong(current, 0L) + (now - since).coerceAtLeast(0L))
        }

        val result = summary(
            active = false,
            sessionId = p.getString("session_id", null),
            label = p.getString("label", null),
            startedAt = started,
            endedAt = now,
            now = now,
            appSwitches = p.getInt("app_switches", 0),
            currentPackage = current,
            durations = durations,
            entries = entries
        ).put("stopped", true)

        appendHistory(context, result)
        p.edit().clear().apply()
        return result
    }

    fun history(context: Context, limit: Int = 20): JSONObject {
        val file = File(context.filesDir, HISTORY_FILE)
        if (!file.exists()) return JSONObject().put("sessions", JSONArray())

        val lines = file.readLines().filter { it.isNotBlank() }
        val arr = JSONArray()
        for (line in lines.takeLast(limit.coerceIn(1, 100))) {
            try { arr.put(JSONObject(line)) } catch (_: Exception) {}
        }
        return JSONObject().put("sessions", arr).put("count", arr.length())
    }

    private fun appendHistory(context: Context, result: JSONObject) {
        val file = File(context.filesDir, HISTORY_FILE)
        file.appendText(result.toString() + "\n")
    }

    private fun loadDurations(raw: String?): JSONObject =
        try { JSONObject(raw ?: "{}") } catch (_: Exception) { JSONObject() }

    private fun summary(
        active: Boolean,
        sessionId: String?,
        label: String?,
        startedAt: Long,
        endedAt: Long?,
        now: Long,
        appSwitches: Int,
        currentPackage: String?,
        durations: JSONObject,
        entries: JSONObject
    ): JSONObject {
        val rows = mutableListOf<Pair<String, Long>>()
        val keys = durations.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            rows += key to durations.optLong(key, 0L)
        }
        rows.sortByDescending { it.second }

        val packages = JSONArray()
        for ((pkg, ms) in rows.take(20)) {
            packages.put(
                JSONObject()
                    .put("package_name", pkg)
                    .put("seconds", (ms / 1000L).toInt())
                    .put("entries", entries.optInt(pkg, 0))
            )
        }

        return JSONObject()
            .put("active", active)
            .put("session_id", sessionId)
            .put("label", label)
            .put("started_at", Instant.ofEpochMilli(startedAt).toString())
            .put("ended_at", endedAt?.let { Instant.ofEpochMilli(it).toString() })
            .put("elapsed_seconds", ((now - startedAt).coerceAtLeast(0L) / 1000L).toInt())
            .put("app_switches", appSwitches)
            .put("current_package", currentPackage)
            .put("package_time", packages)
    }
}
