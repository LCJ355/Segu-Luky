package com.intercom.segluky

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Graba datos de sensores etiquetados por actividad (andando, moto, coche...)
 * en un CSV para calibrar la deteccion. Servicio en primer plano independiente
 * del monitor de seguridad.
 */
class SensorCalibrationService : Service(), SensorEventListener, LocationListener {
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private val handler = Handler(Looper.getMainLooper())

    private var writer: BufferedWriter? = null
    private var file: File? = null
    private var activity = CalibrationActivity.WALKING
    private var sampleCount = 0L
    private var startedAtElapsed = 0L
    private var running = false
    private var locationRegistered = false
    private var lastFlushAt = 0L

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val activity = CalibrationActivity.fromId(intent.getStringExtra(EXTRA_ACTIVITY))
                startRecording(activity)
            }
            ACTION_STOP -> stopRecording()
            else -> if (!running) stopSelf()
        }
        return if (running) START_STICKY else START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRecording(selected: CalibrationActivity) {
        if (running) return
        activity = selected
        CalibrationRepository.saveLastActivity(this, selected)
        file = CalibrationRepository.newFile(this, selected)
        writer = runCatching { BufferedWriter(FileWriter(file!!, false)) }.getOrNull()
        if (writer == null) {
            SafetyStateStore.log("Calibracion: no se pudo crear el archivo")
            stopSelf()
            return
        }
        writeHeader()
        registerSensors()
        registerLocation()
        startedAtElapsed = SystemClock.elapsedRealtime()
        lastFlushAt = startedAtElapsed
        sampleCount = 0L
        running = true
        startForegroundSafely()
        handler.post(tick)
        CalibrationStateStore.update {
            it.copy(
                recording = true,
                activity = selected,
                elapsedMs = 0L,
                samples = 0L,
                fileName = file?.name
            )
        }
        SafetyStateStore.log("Calibracion iniciada: ${selected.label}")
    }

    private fun writeHeader() {
        val w = writer ?: return
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        w.write("# Segu-Luky calibracion v1.12s\n")
        w.write("# actividad=${activity.id} (${activity.label})\n")
        w.write("# fecha=$date\n")
        w.write("# fabricante=${Build.MANUFACTURER}\n")
        w.write("# modelo=${Build.MODEL}\n")
        w.write("# dispositivo=${CalibrationRepository.deviceModel()}\n")
        w.write("# producto=${Build.PRODUCT}\n")
        w.write("# hardware=${Build.HARDWARE}\n")
        w.write("# android=${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})\n")
        w.write("# sensor_lineal=${sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.name ?: "no"}\n")
        w.write("# sensor_acelerometro=${sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.name ?: "no"}\n")
        w.write("# sensor_gravedad=${sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)?.name ?: "no"}\n")
        w.write("# sensor_giroscopio=${sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.name ?: "no"}\n")
        w.write("# columnas: tipo,t_ms,a,b,c,extra\n")
        w.write("tipo,t_ms,a,b,c,extra\n")
    }

    private fun registerSensors() {
        val linear = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gravity = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (linear != null) sensorManager.registerListener(this, linear, SensorManager.SENSOR_DELAY_GAME)
        if (accel != null) sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
        if (gravity != null) sensorManager.registerListener(this, gravity, SensorManager.SENSOR_DELAY_NORMAL)
        if (gyro != null) sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun registerLocation() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return
        locationRegistered = runCatching {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1_000L, 0f, this, Looper.getMainLooper()
            )
            true
        }.getOrDefault(false)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val w = writer ?: return
        val tMs = event.timestamp / 1_000_000L
        val kind = when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> "lin"
            Sensor.TYPE_ACCELEROMETER -> "accel"
            Sensor.TYPE_GRAVITY -> "grav"
            Sensor.TYPE_GYROSCOPE -> "gyro"
            else -> return
        }
        runCatching {
            w.write(
                "$kind,$tMs,${event.values[0]},${event.values[1]},${event.values[2]},\n"
            )
            sampleCount++
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onLocationChanged(location: Location) {
        val w = writer ?: return
        val tMs = location.elapsedRealtimeNanos / 1_000_000L
        val speed = if (location.hasSpeed()) location.speed else 0f
        val acc = if (location.hasAccuracy()) location.accuracy else 0f
        runCatching {
            w.write("gps,$tMs,${location.latitude},${location.longitude},$speed,$acc\n")
            sampleCount++
        }
    }

    override fun onProviderDisabled(provider: String) = Unit
    override fun onProviderEnabled(provider: String) = Unit

    @Deprecated("Deprecated in Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            CalibrationStateStore.update {
                it.copy(
                    recording = true,
                    activity = activity,
                    elapsedMs = SystemClock.elapsedRealtime() - startedAtElapsed,
                    samples = sampleCount,
                    fileName = file?.name
                )
            }
            // Sensors stream at 50 Hz for the whole session, so flushing on every
            // tick would rewrite the flash several times per second. Five seconds
            // only ever risks losing the last few samples of a crash.
            if (SystemClock.elapsedRealtime() - lastFlushAt >= FLUSH_INTERVAL_MS) {
                runCatching { writer?.flush() }
                lastFlushAt = SystemClock.elapsedRealtime()
            }
            handler.postDelayed(this, UI_TICK_INTERVAL_MS)
        }
    }

    private fun stopRecording() {
        running = false
        handler.removeCallbacks(tick)
        runCatching { sensorManager.unregisterListener(this) }
        if (locationRegistered) runCatching { locationManager.removeUpdates(this) }
        locationRegistered = false
        runCatching { writer?.flush() }
        runCatching { writer?.close() }
        writer = null
        val finished = file
        file = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        CalibrationStateStore.update {
            it.copy(
                recording = false,
                elapsedMs = 0L,
                samples = 0L,
                fileName = null,
                files = CalibrationRepository.listFiles(this)
            )
        }
        if (finished != null) SafetyStateStore.log("Calibracion guardada: ${finished.name}")
        stopSelf()
    }

    override fun onDestroy() {
        if (running) {
            running = false
            handler.removeCallbacks(tick)
            runCatching { sensorManager.unregisterListener(this) }
            if (locationRegistered) runCatching { locationManager.removeUpdates(this) }
            runCatching { writer?.flush() }
            runCatching { writer?.close() }
            writer = null
        }
        super.onDestroy()
    }

    private fun startForegroundSafely() {
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        }.onFailure {
            SafetyStateStore.log("Calibracion: no se pudo iniciar en primer plano")
        }
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setContentTitle("Segu-Luky: calibracion")
        .setContentText("Grabando: ${activity.label}")
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .build()

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Calibracion de sensores", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        private const val CHANNEL_ID = "interseg_calibration"
        private const val NOTIFICATION_ID = 1004
        private const val UI_TICK_INTERVAL_MS = 1_000L
        private const val FLUSH_INTERVAL_MS = 5_000L
        private const val ACTION_START = "com.intercom.segluky.CALIB_START"
        private const val ACTION_STOP = "com.intercom.segluky.CALIB_STOP"
        private const val EXTRA_ACTIVITY = "activity"

        fun start(context: Context, activity: CalibrationActivity) {
            val intent = Intent(context, SensorCalibrationService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ACTIVITY, activity.id)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, SensorCalibrationService::class.java).setAction(ACTION_STOP)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }
    }
}
