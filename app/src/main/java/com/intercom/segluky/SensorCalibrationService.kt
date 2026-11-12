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

    // Prueba de golpe: 15 s sin fichero, para validar el movil sin grabar nada.
    private var testing = false
    private var testThresholdG = 3.5f
    private var testPeakG = 0f
    private val testGravity = FloatArray(3) { 0f }
    private var testGravityReady = false

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
            ACTION_TEST -> {
                val threshold = intent.getFloatExtra(EXTRA_TEST_THRESHOLD_G, Float.NaN)
                startHitTest(threshold)
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
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "?"
        w.write("# Segu-Luky calibracion v$version\n")
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
        if (testing) {
            trackHitTestSample(event)
            return
        }
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
            if (testing) {
                if (SystemClock.elapsedRealtime() - startedAtElapsed >= TEST_DURATION_MS) {
                    finishHitTest()
                    return
                }
                handler.postDelayed(this, UI_TICK_INTERVAL_MS)
                return
            }
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

    /**
     * PRUEBA DE GOLPE: 15 s midiendo el pico real con el umbral vigente, sin fichero y
     * sin disparar nada. Sustituye a "grabar un CSV y analizarlo en el PC" para
     * comprobar que el movil se entera de un golpe.
     */
    private fun startHitTest(thresholdG: Float) {
        if (running) return
        if (SafetyStateStore.snapshot.value.phase != SafetyPhase.STOPPED) {
            SafetyStateStore.update {
                it.copy(hitTestRunning = false, hitTestVerdict = "Deten el monitor antes de probar el golpe")
            }
            SafetyStateStore.log("Prueba de golpe: el monitor esta en marcha")
            stopSelf()
            return
        }
        testThresholdG = if (thresholdG.isFinite() && thresholdG > 0f) {
            thresholdG
        } else {
            SafetyMonitorService.loadSensitivity(this)
        }
        testPeakG = 0f
        testGravityReady = false
        testing = true
        running = true
        startedAtElapsed = SystemClock.elapsedRealtime()
        registerSensors()
        startForegroundSafely()
        handler.post(tick)
        SafetyStateStore.update { it.copy(hitTestRunning = true, hitTestVerdict = null) }
        SafetyStateStore.log("Prueba de golpe: sacude el movil como pedirias ayuda")
    }

    private fun trackHitTestSample(event: SensorEvent) {
        val dynamicG = when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION ->
                magnitude(event.values[0], event.values[1], event.values[2]) /
                    AccidentDetector.STANDARD_GRAVITY
            Sensor.TYPE_ACCELEROMETER -> accelerometerDynamicG(event.values)
            else -> return
        }
        if (dynamicG.isFinite() && dynamicG > testPeakG) {
            testPeakG = dynamicG
            SafetyStateStore.update {
                it.copy(hitTestVerdict = "Sacudiendo... pico ${SensitivityAdvisor.formatG(testPeakG)} g")
            }
        }
    }

    /** Quita la gravedad con una media lenta: en 15 s basta para medir el pico. */
    private fun accelerometerDynamicG(values: FloatArray): Float {
        val x = values[0]
        val y = values[1]
        val z = values[2]
        if (!testGravityReady) {
            testGravity[0] = x
            testGravity[1] = y
            testGravity[2] = z
            testGravityReady = true
            return 0f
        }
        testGravity[0] += (x - testGravity[0]) * TEST_GRAVITY_ALPHA
        testGravity[1] += (y - testGravity[1]) * TEST_GRAVITY_ALPHA
        testGravity[2] += (z - testGravity[2]) * TEST_GRAVITY_ALPHA
        return magnitude(
            x - testGravity[0],
            y - testGravity[1],
            z - testGravity[2]
        ) / AccidentDetector.STANDARD_GRAVITY
    }

    private fun finishHitTest() {
        if (!testing) return
        testing = false
        running = false
        handler.removeCallbacks(tick)
        runCatching { sensorManager.unregisterListener(this) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        val verdict = hitTestVerdict(testPeakG, testThresholdG)
        SafetyStateStore.update { it.copy(hitTestRunning = false, hitTestVerdict = verdict) }
        SafetyStateStore.log(verdict)
        stopSelf()
    }

    private fun hitTestVerdict(peak: Float, threshold: Float): String {
        val peakText = SensitivityAdvisor.formatG(peak)
        val thresholdText = SensitivityAdvisor.formatG(threshold)
        return if (peak >= threshold) {
            "Prueba de golpe: habria disparado (pico $peakText g / umbral $thresholdText g)"
        } else {
            val suggested = SensitivityAdvisor.suggest(
                peakG = peak,
                currentG = threshold,
                rideMs = Long.MAX_VALUE
            )?.recommendedG
            val hint = if (suggested != null) {
                " - baja el ajuste a ${SensitivityAdvisor.formatG(suggested)} g"
            } else {
                ""
            }
            "Prueba de golpe: no llegaria (pico $peakText g / umbral $thresholdText g)$hint"
        }
    }

    private fun magnitude(x: Float, y: Float, z: Float): Float =
        kotlin.math.sqrt(x * x + y * y + z * z)

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
        if (testing) {
            testing = false
            SafetyStateStore.update { it.copy(hitTestRunning = false) }
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
        .setContentTitle(if (testing) "Segu-Luky: prueba de golpe" else "Segu-Luky: calibracion")
        .setContentText(
            if (testing) "Sacude el movil durante ${TEST_DURATION_MS / 1000} s" else "Grabando: ${activity.label}"
        )
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
        private const val ACTION_TEST = "com.intercom.segluky.CALIB_TEST"
        private const val EXTRA_ACTIVITY = "activity"
        private const val EXTRA_TEST_THRESHOLD_G = "test_threshold_g"
        private const val TEST_DURATION_MS = 15_000L
        private const val TEST_GRAVITY_ALPHA = 0.05f

        fun start(context: Context, activity: CalibrationActivity) {
            val intent = Intent(context, SensorCalibrationService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ACTIVITY, activity.id)
            ContextCompat.startForegroundService(context, intent)
        }

        /** PRUEBA DE GOLPE de 15 s: mide el pico y lo compara con [thresholdG]. */
        fun testHit(context: Context, thresholdG: Float) {
            val intent = Intent(context, SensorCalibrationService::class.java)
                .setAction(ACTION_TEST)
                .putExtra(EXTRA_TEST_THRESHOLD_G, thresholdG)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            val intent = Intent(context, SensorCalibrationService::class.java).setAction(ACTION_STOP)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }
    }
}
