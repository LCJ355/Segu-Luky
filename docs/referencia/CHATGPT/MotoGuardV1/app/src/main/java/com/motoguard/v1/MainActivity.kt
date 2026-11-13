package com.motoguard.v1

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import android.graphics.Typeface

class MainActivity : Activity() {
    private val requestCode = 700
    private lateinit var status: TextView
    private lateinit var profile: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 28)
        }

        val title = TextView(this).apply {
            text = "MotoGuard V1"
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
        }
        layout.addView(title)

        val subtitle = TextView(this).apply {
            text = "Grabador de sensores y detector experimental de anomalías"
            textSize = 14f
            setPadding(0, 8, 0, 20)
        }
        layout.addView(subtitle)

        layout.addView(TextView(this).apply {
            text = "Ubicación del teléfono"
            textSize = 16f
        })

        profile = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Bolsillo de chaqueta", "Manillar", "Depósito", "Mochila", "Personalizado")
            )
        }
        layout.addView(profile)

        status = TextView(this).apply {
            text = "Estado: detenido"
            textSize = 16f
            setPadding(0, 24, 0, 24)
        }
        layout.addView(status)

        val start = Button(this).apply {
            text = "INICIAR REGISTRO"
            setOnClickListener { checkPermissionsAndStart() }
        }
        layout.addView(start)

        val stop = Button(this).apply {
            text = "DETENER REGISTRO"
            setOnClickListener {
                stopService(Intent(this@MainActivity, RideMonitoringService::class.java))
                status.text = "Estado: detenido"
            }
        }
        layout.addView(stop)

        val note = TextView(this).apply {
            text = "Mantén iniciado el servicio antes de bloquear la pantalla. " +
                "La notificación permanente indica que la monitorización está activa. " +
                "Esta versión registra datos y marca anomalías; todavía no envía avisos de emergencia."
            textSize = 13f
            setPadding(0, 24, 0, 0)
        }
        layout.addView(note)

        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun checkPermissionsAndStart() {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), requestCode)
        } else {
            startMonitoring()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == this.requestCode &&
            grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startMonitoring()
        } else if (requestCode == this.requestCode) {
            status.text = "Faltan permisos. No se ha iniciado la monitorización."
        }
    }

    private fun startMonitoring() {
        val intent = Intent(this, RideMonitoringService::class.java).apply {
            action = RideMonitoringService.ACTION_START
            putExtra(RideMonitoringService.EXTRA_PROFILE, profile.selectedItemPosition)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
            status.text = "Estado: iniciando monitorización"
        } catch (e: Exception) {
            status.text = "No se pudo iniciar: ${e.javaClass.simpleName}"
        }
    }
}
