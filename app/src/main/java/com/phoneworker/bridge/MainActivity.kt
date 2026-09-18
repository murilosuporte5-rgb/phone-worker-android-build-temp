package com.phoneworker.bridge

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 50, 40, 40)
        }

        root.addView(TextView(this).apply {
            text = "Phone Worker V1.5\nProjeto independente do JARVIS.\nModo PROTOCOLO 3% disponível."
            textSize = 20f
        })

        val relayInput = EditText(this).apply {
            hint = "Relay WebSocket URL"
            setText(WorkerConfig.relayWsUrl(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val deviceIdInput = EditText(this).apply {
            hint = "Device ID"
            setText(WorkerConfig.deviceId(this@MainActivity))
        }
        val tokenInput = EditText(this).apply {
            hint = "Device token"
            setText(WorkerConfig.deviceToken(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        root.addView(relayInput)
        root.addView(deviceIdInput)
        root.addView(tokenInput)

        fun saveConfig(): Boolean {
            val relay = relayInput.text.toString().trim()
            val deviceId = deviceIdInput.text.toString().trim()
            val token = tokenInput.text.toString().trim()
            if (relay.isBlank() || deviceId.isBlank() || token.isBlank()) {
                Toast.makeText(this, "Preencha relay, device ID e token.", Toast.LENGTH_LONG).show()
                return false
            }
            WorkerConfig.save(this, relay, deviceId, token)
            Toast.makeText(this, "Configuração salva.", Toast.LENGTH_SHORT).show()
            return true
        }

        root.addView(Button(this).apply {
            text = "Salvar configuração"
            setOnClickListener { saveConfig() }
        })

        root.addView(Button(this).apply {
            text = "Abrir Acessibilidade"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        root.addView(Button(this).apply {
            text = "Abrir acesso de uso (PROTOCOLO 3%)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        })

        root.addView(Button(this).apply {
            text = "Iniciar Worker"
            setOnClickListener {
                if (!saveConfig()) return@setOnClickListener
                WorkerConfig.setAutoStart(this@MainActivity, true)
                ContextCompat.startForegroundService(
                    this@MainActivity,
                    Intent(this@MainActivity, WorkerService::class.java)
                )
            }
        })

        root.addView(Button(this).apply {
            text = "PARAR / KILL SWITCH"
            setOnClickListener {
                WorkerConfig.setAutoStart(this@MainActivity, false)
                stopService(Intent(this@MainActivity, WorkerService::class.java))
                Toast.makeText(this@MainActivity, "Worker parado e auto-start desativado.", Toast.LENGTH_SHORT).show()
            }
        })

        setContentView(root)
    }
}
