package com.motoguard.v1

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.*
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.sqrt

class RideMonitoringService : Service(), SensorEventListener, LocationListener {

    companion object {
        const val ACTION_START = "com.motoguard.v1.START"
        const val EXTRA_PROFILE = "profile"
        private const val CHANNEL_ID = "motoguard_monitoring"
        private const val NOTIFICATION_ID = 1001
        private const val GRAVITY = 9.80665
        private const val SENSOR_PERIOD_US = 10_000 // solicitud de 100 Hz
    }

    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private lateinit var sensorThread: HandlerThread
    private lateinit var sensorHandler: Handler
    private val io = Executors.newSingleThreadExecutor()
    private var writer: BufferedWriter? = null
    private var csvFile: File? = null
    private var profileName = "pocket"
    private var lastAccelMagnitude = 0.0
    private var lastAccelTimeNs = 0L
    private var lastAlertMs = 0L
    private var registeredSensors = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            profileName = when (intent.getIntExtra(EXTRA_PROFILE, 0)) {
                1 -> "handlebar"
                2 -> "tank"
                3 -> "backpack"
                4 -> "custom"
                else -> "pocket"
            }
            startForeground(NOTIFICATION_ID, buildNotification("Monitorización activa"))
            startRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        if (::sensorThread.isInitialized) return

        val dir = File(getExternalFilesDir(null), "records").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        csvFile = File(dir, "ride_${stamp}.csv")

        io.execute {
            writer = BufferedWriter(FileWriter(csvFile!!, true))
            writer?.write(
                "elapsed_realtime_ns,wall_time_ms,type,x,y,z,magnitude,latitude,longitude,speed_mps,accuracy_m,profile,event\n"
            )
            writer?.flush()
        }

        sensorThread = HandlerThread("MotoGuardSensors").apply { start() }
        sensorHandler = Handler(sensorThread.looper)

        register(Sensor.TYPE_ACCELEROMETER)
        register(Sensor.TYPE_GYROSCOPE)
        register(Sensor.TYPE_GRAVITY)
        register(Sensor.TYPE_ROTATION_VECTOR)
        register(Sensor.TYPE_MAGNETIC_FIELD)
        requestGps()
    }

    private fun register(type: Int) {
        val sensor = sensorManager.getDefaultSensor(type) ?: return
        val ok = sensorManager.registerListener(
            this, sensor, SENSOR_PERIOD_US, sensorHandler
        )
        if (ok) registeredSensors++
    }

    override fun onSensorChanged(event: SensorEvent) {
        val values = event.values
        val x = values.getOrElse(0) { 0f }.toDouble()
        val y = values.getOrElse(1) { 0f }.toDouble()
        val z = values.getOrElse(2) { 0f }.toDouble()
        val magnitude = sqrt(x*x + y*y + z*z)

        var eventTag = ""
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val now = event.timestamp
            val dynamicG = kotlin.math.abs(magnitude - GRAVITY) / GRAVITY
            val jerk = if (lastAccelTimeNs > 0L && now > lastAccelTimeNs) {
                kotlin.math.abs(magnitude - lastAccelMagnitude) /
                    ((now - lastAccelTimeNs) / 1_000_000_000.0)
            } else 0.0

            // Umbrales exploratorios. No equivalen a una detección de accidente.
            if (dynamicG >= 2.5 || jerk >= 80.0) {
                eventTag = "ANOMALY_ACCEL"
                notifyAnomaly(dynamicG, jerk)
            }
            lastAccelMagnitude = magnitude
            lastAccelTimeNs = now
        }

        val typeName = when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> "accelerometer"
            Sensor.TYPE_GYROSCOPE -> "gyroscope"
            Sensor.TYPE_GRAVITY -> "gravity"
            Sensor.TYPE_ROTATION_VECTOR -> "rotation_vector"
            Sensor.TYPE_MAGNETIC_FIELD -> "magnetometer"
            else -> "sensor_${event.sensor.type}"
        }

        enqueueRow(
            event.timestamp, System.currentTimeMillis(), typeName,
            x, y, z, magnitude, null, null, null, null, eventTag
        )
    }

    private fun requestGps() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED) return

        try {
            val provider = when {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                    LocationManager.GPS_PROVIDER
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                    LocationManager.NETWORK_PROVIDER
                else -> return
            }
            locationManager.requestLocationUpdates(provider, 1000L, 0f, this, Looper.getMainLooper())
        } catch (_: SecurityException) {
            // Permiso retirado durante el trayecto.
        } catch (_: Exception) {
            // Proveedor no disponible.
        }
    }

    override fun onLocationChanged(location: Location) {
        enqueueRow(
            SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis(),
            "gps", 0.0, 0.0, 0.0, 0.0,
            location.latitude, location.longitude,
            if (location.hasSpeed()) location.speed.toDouble() else null,
            if (location.hasAccuracy()) location.accuracy.toDouble() else null,
            ""
        )
    }

    private fun enqueueRow(
        elapsedNs: Long, wallMs: Long, type: String,
        x: Double, y: Double, z: Double, magnitude: Double,
        lat: Double?, lon: Double?, speed: Double?, accuracy: Double?,
        event: String
    ) {
        val line = listOf(
            elapsedNs, wallMs, type, x, y, z, magnitude,
            lat ?: "", lon ?: "", speed ?: "", accuracy ?: "",
            profileName, event
        ).joinToString(",") + "\n"

        io.execute {
            try {
                if (writer == null) {
                    val file = csvFile ?: return@execute
                    writer = BufferedWriter(FileWriter(file, true))
                }
                writer?.write(line)
                writer?.flush()
            } catch (_: Exception) {
                // En producción: persistir estado de error y avisar en UI.
            }
        }
    }

    private fun notifyAnomaly(dynamicG: Double, jerk: Double) {
        val now = System.currentTimeMillis()
        if (now - lastAlertMs < 15_000L) return
        lastAlertMs = now
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(
            1002,
            buildNotification(
                "Anomalía registrada: ${"%.1f".format(dynamicG)} g; jerk ${"%.0f".format(jerk)} m/s³"
            )
        )
        enqueueRow(
            SystemClock.elapsedRealtimeNanos(), now, "event",
            dynamicG, jerk, 0.0, 0.0,
            null, null, null, null, "ANOMALY_RECORDED"
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "MotoGuard monitorización",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("MotoGuard")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("MotoGuard")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        try {
            sensorManager.unregisterListener(this)
            if (::sensorThread.isInitialized) sensorThread.quitSafely()
            locationManager.removeUpdates(this)
        } catch (_: Exception) { }

        io.execute {
            try { writer?.flush(); writer?.close() } catch (_: Exception) { }
            writer = null
        }
        io.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
