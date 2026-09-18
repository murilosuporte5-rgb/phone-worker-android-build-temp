package com.phoneworker.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class WorkerBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (
            action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        if (!WorkerConfig.autoStart(context)) return
        if (WorkerConfig.deviceToken(context).isBlank()) return

        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, WorkerService::class.java)
            )
        } catch (_: Exception) {
            // Android may restrict foreground-service starts during boot.
            // The local Termux supervisor can start it after unlock if needed.
        }
    }
}
