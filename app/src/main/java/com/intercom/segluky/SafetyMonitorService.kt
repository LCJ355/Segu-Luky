package com.intercom.segluky

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.Notification
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
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

class SafetyMonitorService : Service(), SensorEventListener, LocationListener {
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private lateinit var wakeScreenController: WakeScreenController
    private lateinit var runtimeRepository: SafetyRuntimeRepository
    private lateinit var countdownWakeLock: PowerManager.WakeLock
    private lateinit var alarmManager: AlarmManager
    private lateinit var detector: AccidentDetector

    private var toneGenerator: ToneGenerator? = null
    private var primaryMotionSensor: Sensor? = null
    private var gyroscopeSensor: Sensor? = null
    private var gyroscopeRegistered = false
    private var gravitySensor: Sensor? = null
    private var sensorsRegistered = false
    private var locationUpdatesRegistered = false
    private var monitoringEnabled = false
    private var wasMonitoringBeforeEmergency = false
    private var runtimeLoaded = false
    private var foregroundMode = ForegroundMode.NONE
    private var currentLocationIntervalMs = LOCATION_INTERVAL_INITIAL_MS
    private var currentLocationMinDistanceM = 0f

    private var lastSpeed: Float? = null
    private var lastSpeedAt: Long? = null
    private var lastAccuracy: Float? = null
    private var lastQualifiedLocation: Location? = null
    private var lastQualifiedLocationAt: Long? = null
    private var lastQualifiedLocationWallTime: Long? = null
    private var lastSensorEventAt: Long? = null
    private var sensorRegisteredAt: Long? = null
    private var sensorAccuracyReliable = true
    private var sensorDescription = "Sensor pendiente"
    private var orientationAccuracyReliable = true
    private var orientationConfigured = false
    private var orientationGateActive = false
    private var orientationDescription = "Orientacion desactivada"
    private var orientationCalibrated = false
    private var orientationTiltDegrees: Float? = null
    private var startingOrientationFall = false
    private val fallOrientationDetector = FallOrientationDetector(
        tiltThresholdDegrees = AccidentDetector.FALL_TILT_THRESHOLD_DEGREES,
        sustainedDurationMs = FALL_SUSTAINED_TILT_MS,
        calibrationDurationMs = FALL_CALIBRATION_MS
    )
    private var gravityAttitudeSensor: Sensor? = null
    private var uprightAbortCounter = 0
    private var voiceMonitor: VoiceKeywordMonitor? = null
    private var escalationActive = false
    private var telephonyRegistered = false
    private var currentCallOffhookAt: Long? = null
    private var pendingCallAdvance = false
    private var dispatchInProgress = false
    private var dispatchWatchdogStartedAt: Long? = null
    private var exactAlarmFallbackLogged = false

    private val callStateListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            if (!escalationActive) return
            when (state) {
                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    if (currentCallOffhookAt == null) {
                        currentCallOffhookAt = SystemClock.elapsedRealtime()
                    }
                }
                TelephonyManager.CALL_STATE_IDLE -> onCallEnded()
                TelephonyManager.CALL_STATE_RINGING -> Unit
            }
        }
    }
    private var countdownEndsAt: Long? = null
    private var countdownEndsAtWallTime: Long? = null
    private var countdownReason = ""
    private var activeEventId: String? = null
    private var nextBeepAt: Long? = null
    private var lastSnapshotUpdateAt = 0L
    private var lastNotificationUpdateAt = 0L
    private var lastNotificationText: String? = null
    private var lastMovementUpdateAt = 0L
    private var lastPublishedMovementLevel = Int.MIN_VALUE
    private var lastPublishedMovementG = Int.MIN_VALUE
    private var lastRuntimeSyncAt = 0L
    private var lastSyncedValidationStart: Long? = null
    private var lastSyncedImmobileSince: Long? = null
    private var lastTelemetryUpdateAt = 0L
    private var movementLevel = 0f
    private var tickerRunning = false
    private var lastPersistedRuntime: PersistedSafetyRuntime? = null
    private var backgroundStartRequestCode = 0

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        wakeScreenController = WakeScreenController(this)
        runtimeRepository = SafetyRuntimeRepository(this)
        countdownWakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Segu-Luky:SosCountdown")
            .apply { setReferenceCounted(false) }
        toneGenerator = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 100) }.getOrNull()

        detector = AccidentDetector(
            listener = object : AccidentDetector.Listener {
                override fun onValidationStarted(startedAt: Long) {
                    if (activeEventId == null) activeEventId = UUID.randomUUID().toString()
                    SafetyStateStore.update {
                        it.copy(
                            phase = SafetyPhase.VALIDATING,
                            detail = if (startingOrientationFall) {
                                "Validando golpe y caida confirmada"
                            } else {
                                "Validando posible impacto"
                            },
                            validationStartedAt = startedAt,
                            immobileSince = detector.runtimeState().immobileSince,
                            countdownEndsAt = null
                        )
                    }
                    SafetyStateStore.log(
                        if (startingOrientationFall) "Caida confirmada; validacion iniciada" else "Validacion iniciada"
                    )
                    syncGyroscopeListener()
                    persistRuntime()
                }

                override fun onValidationExpired() {
                    activeEventId = null
                    countdownReason = ""
                    SafetyStateStore.update {
                        it.copy(
                            phase = SafetyPhase.MONITORING,
                            detail = "Validacion descartada por tiempo maximo",
                            validationStartedAt = null,
                            immobileSince = null,
                            countdownEndsAt = null
                        )
                    }
                    SafetyStateStore.log("Validacion descartada; no se detecto inmovilidad continua")
                    syncGyroscopeListener()
                    persistRuntime()
                }

                override fun onAutomaticEmergency() {
                    beginCountdown("impacto e inmovilidad durante 5 min")
                }

                override fun onManualEmergency(reason: String) {
                    beginCountdown(reason)
                }

                override fun onVibrationDetected() {
                    // A screen that lights up on every strong bump is the single
                    // most expensive reaction available. It is only justified when
                    // the hit is being treated as a possible crash, so the rider
                    // can see and cancel the SOS.
                    val phase = detector.runtimeState().phase
                    if (phase != SafetyPhase.FALL_CANDIDATE && phase != SafetyPhase.COUNTDOWN) return
                    wakeScreenController.wakeFor(CANDIDATE_SCREEN_WAKE_MS)
                    SafetyStateStore.log("Movimiento fuerte detectado; pantalla activada")
                }

                override fun onMotionDetected() {
                    SafetyStateStore.update {
                        it.copy(detail = "Movimiento detectado; validacion reiniciada")
                    }
                }

                override fun onFallCandidateArmed() {
                    SafetyStateStore.update {
                        it.copy(
                            phase = SafetyPhase.FALL_CANDIDATE,
                            detail = "POSIBLE CAIDA: golpe detectado, verificando inclinacion..."
                        )
                    }
                    SafetyStateStore.log("Golpe detectado; esperando caida confirmada (inclinacion 55 grados / 4 s)")
                    // The candidate is the only phase where the rider can still
                    // cancel before the countdown, so make it visible immediately.
                    wakeScreenController.wakeFor(CANDIDATE_SCREEN_WAKE_MS)
                    // Speed and tilt confirmation need fresh fixes during the
                    // 15 s candidate window.
                    setLocationUpdateInterval(LOCATION_INTERVAL_VALIDATION_MS, 0f)
                    // The monitor heartbeat is slow, so surface the candidate now.
                    syncGyroscopeListener()
                    updateNotification(force = true)
                }

                override fun onFallCandidateExpired() {
                    SafetyStateStore.update {
                        it.copy(
                            phase = SafetyPhase.MONITORING,
                            detail = "Golpe sin caida; monitor rearmado",
                            validationStartedAt = null,
                            immobileSince = null,
                            countdownEndsAt = null
                        )
                    }
                    SafetyStateStore.log("Golpe sin caida confirmada; monitor rearmado")
                    syncGyroscopeListener()
                    setLocationUpdateInterval(LOCATION_INTERVAL_ROUTINE_MS, LOCATION_MIN_DISTANCE_ROUTINE_M)
                    persistRuntime()
                    updateNotification(force = true)
                }

                override fun onFallCandidateAborted() {
                    SafetyStateStore.update {
                        it.copy(
                            phase = SafetyPhase.MONITORING,
                            detail = "Caida descartada: el movil volvio a vertical",
                            validationStartedAt = null,
                            immobileSince = null,
                            countdownEndsAt = null
                        )
                    }
                    SafetyStateStore.log("Caida descartada; monitor rearmado")
                    syncGyroscopeListener()
                    setLocationUpdateInterval(LOCATION_INTERVAL_ROUTINE_MS, LOCATION_MIN_DISTANCE_ROUTINE_M)
                    persistRuntime()
                    updateNotification(force = true)
                }
            },
            now = { SystemClock.elapsedRealtime() }
        )
        detector.setGolpeSensitivity(loadSensitivity(this))
        orientationConfigured = loadFallOrientationGate(this)
        detector.setFallOrientationGateEnabled(orientationConfigured)
        voiceMonitor = VoiceKeywordMonitor(
            onKeyword = ::runOnVoiceKeyword,
            onState = { levelDb, recognizing, detail ->
                SafetyStateStore.update {
                    it.copy(
                        voiceLevelDb = levelDb,
                        voiceRecognizing = recognizing,
                        voiceDetail = detail
                    )
                }
            }
        )
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        loadRuntimeOnce()

        when (intent?.action) {
            ACTION_START -> {
                if (!resumePendingDispatchIfAny()) resumePersistedStateOrStartMonitoring()
            }
            ACTION_CONFIRM -> {
                if (ensureEmergencyForeground() && !detector.confirm("confirmacion manual")) {
                    SafetyStateStore.log("Confirmacion ignorada: la cuenta atras ya esta activa")
                }
            }
            ACTION_CANCEL -> {
                if (ensureForegroundForCurrentState()) cancelActiveEvent()
            }
            ACTION_STOP -> stopMonitoringAndService()
            ACTION_ALARM_COMPLETE -> {
                val restoredEnd = restoredCountdownEnd()
                if (restoredEnd == null) {
                    if (!resumePendingDispatchIfAny()) {
                        if (monitoringEnabled) {
                            // A duplicated backup alarm must never tear down a
                            // healthy monitor or an in-flight delivery.
                            if (!sensorsRegistered) activateMonitoring(preserveDetectorState = true)
                            SafetyStateStore.log("Alarma SOS duplicada ignorada")
                        } else {
                            runtimeRepository.clearActiveState()
                            stopSelfResult(startId)
                        }
                    }
                } else if (startEmergencyForeground("Completando protocolo SOS")) {
                    countdownEndsAt = restoredEnd
                    if (monitoringEnabled && !sensorsRegistered) {
                        activateMonitoring(preserveDetectorState = true)
                    }
                    if (SystemClock.elapsedRealtime() >= restoredEnd) completeProtocol() else startTicker()
                }
            }
            ACTION_WHATSAPP_ADVANCE -> continueEmergencyDispatch()
            ACTION_DISPATCH_WATCHDOG -> handleDispatchWatchdog()
            ACTION_ESCALATE -> handleEscalationAlarm()
            ACTION_SET_SENSITIVITY -> {
                val g = intent.getFloatExtra(EXTRA_SENSITIVITY_G, Float.NaN)
                if (!monitoringEnabled && g.isFinite()) detector.setGolpeSensitivity(g)
            }
            ACTION_RELOAD_CONFIG -> {
                val wasOrientationConfigured = orientationConfigured
                orientationConfigured = loadFallOrientationGate(this)
                val sensitivity = loadSensitivity(this)
                detector.setFallOrientationGateEnabled(orientationConfigured)
                detector.setGolpeSensitivity(sensitivity)
                if (monitoringEnabled && foregroundMode != ForegroundMode.NONE) {
                    val registered = primaryMotionSensor
                    val needsRebind = registered == null ||
                        registered.maximumRange < sensitivity * AccidentDetector.STANDARD_GRAVITY
                    if (needsRebind) {
                        unregisterSensors()
                        registerSensors()
                    } else {
                        // Rebinding would discard the learned riding attitude and leave
                        // the fall gate uncalibrated for a few seconds mid-journey, so a
                        // settings change only refreshes the gate settings.
                        bindAttitudeGate(keepCalibration = wasOrientationConfigured == orientationConfigured)
                    }
                    restartVoiceMonitor()
                } else if (countdownEndsAt == null) {
                    // Satisfy the startForegroundService contract and stop right away.
                    if (startSpecialForeground("Configuracion aplicada")) {
                        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    }
                    stopSelfResult(startId)
                    return START_NOT_STICKY
                }
                SafetyStateStore.update {
                    it.copy(orientationDetail = orientationDescription)
                }
            }
            null -> {
                if (!resumePendingDispatchIfAny()) resumePersistedStateOrStop()
            }
        }

        return if (monitoringEnabled || countdownEndsAt != null || dispatchInProgress) {
            START_STICKY
        } else {
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        stopTicker()
        window.removeCallbacksAndMessages(null)
        unregisterSensors()
        removeLocationUpdates()
        releaseCountdownWakeLock()
        stopVoiceMonitor()
        escalationActive = false
        cancelEscalationAlarm()
        cancelDispatchWatchdog()
        clearCallTracking()
        unregisterTelephonyListener()
        toneGenerator?.release()
        toneGenerator = null
        wakeScreenController.release()
        // The foreground notification goes away with the service, but the sticky
        // emergency alert (id 1002) would survive as an undismissable leftover.
        getSystemService(NotificationManager::class.java).cancel(EMERGENCY_ALERT_NOTIFICATION_ID)
        SafetyStateStore.reset()
        super.onDestroy()
    }

    private fun runOnVoiceKeyword(word: String) {
        if (!monitoringEnabled) return
        if (countdownEndsAt != null || EmergencyDispatchRepository(this).load() != null) {
            SafetyStateStore.log("Voz ignorada: SOS o envio en curso")
            return
        }
        if (ensureEmergencyForeground() && !detector.confirm("voz: \"$word\"")) {
            SafetyStateStore.log("Voz ignorada: la cuenta atras ya esta activa")
        }
    }

    private fun restartVoiceMonitor() {
        val monitor = voiceMonitor ?: return
        val enabled = monitoringEnabled && loadVoiceKeywordsGate(this)
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        monitor.start(this, enabled, hasMic)
    }

    private fun stopVoiceMonitor() {
        voiceMonitor?.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSensorChanged(event: SensorEvent) {
        val eventTime = event.timestamp / 1_000_000L
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                detector.onGravitySensor(event.values[0], event.values[1], event.values[2], eventTime)
                if (orientationConfigured) {
                    onGravityAttitude(
                        event.values[0],
                        event.values[1],
                        event.values[2],
                        eventTime
                    )
                }
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                lastSensorEventAt = eventTime
                val dynamicG = detector.onLinearAcceleration(
                    event.values[0],
                    event.values[1],
                    event.values[2],
                    eventTime
                )
                updateMovementLevel(
                    level = (dynamicG / AccidentDetector.MAX_SENSITIVITY_G).coerceIn(0f, 1f),
                    dynamicG = dynamicG
                )
                syncDetectorRuntime()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                lastSensorEventAt = eventTime
                detector.onAccelerometer(
                    event.values[0],
                    event.values[1],
                    event.values[2],
                    eventTime
                )?.let { dynamicG ->
                    updateMovementLevel(
                        level = (dynamicG / AccidentDetector.MAX_SENSITIVITY_G).coerceIn(0f, 1f),
                        dynamicG = dynamicG
                    )
                }
                syncDetectorRuntime()
            }
            Sensor.TYPE_GYROSCOPE -> {
                if (!gyroscopeRegistered) return
                detector.onGyroscope(event.values[0], event.values[1], event.values[2])
                syncDetectorRuntime()
            }
        }
    }

    private fun onGravityAttitude(x: Float, y: Float, z: Float, timestamp: Long) {
        val reading = fallOrientationDetector.onGravity(x, y, z, timestamp)
        orientationCalibrated = reading.calibrated
        orientationTiltDegrees = reading.tiltDegrees
        if (reading.sustainedFall) {
            detector.onFallTilt(true, timestamp)
            return
        }
        if (detector.runtimeState().phase != SafetyPhase.FALL_CANDIDATE) {
            uprightAbortCounter = 0
            return
        }
        val tilt = reading.tiltDegrees
        if (reading.calibrated && tilt != null && tilt < AccidentDetector.FALL_UPRIGHT_ABORT_DEGREES) {
            uprightAbortCounter++
            if (uprightAbortCounter >= UPRIGHT_ABORT_SAMPLES) {
                uprightAbortCounter = 0
                detector.abortFallCandidate()
            }
        } else {
            uprightAbortCounter = 0
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        val reliable = accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE
        if (sensor == gravitySensor) {
            // The attitude gate must fail closed: an unreliable gravity reading can
            // no longer confirm a fall, so the detector falls back to the plain
            // strong-hit rule instead of trusting a bad tilt.
            orientationAccuracyReliable = reliable
            setOrientationGateActive(
                orientationConfigured && orientationAccuracyReliable && gravityAttitudeSensor != null
            )
        }
        if (sensor != primaryMotionSensor) return
        sensorAccuracyReliable = reliable
        if (!sensorAccuracyReliable) {
            SafetyStateStore.update {
                it.copy(sensorAvailable = false, sensorDetail = "$sensorDescription: precision no fiable")
            }
        }
    }

    override fun onLocationChanged(location: Location) {
        val eventTime = location.elapsedRealtimeNanos / 1_000_000L
        val accuracy = location.accuracy.takeIf { location.hasAccuracy() && it.isFinite() }
        val qualifiedFix = accuracy != null && accuracy <= MAX_QUALIFIED_GPS_ACCURACY_METERS
        lastAccuracy = accuracy

        val speed = if (location.hasSpeed() && location.speed.isFinite() && location.speed >= 0f) {
            location.speed * 3.6f
        } else {
            null
        }
        val speedQualified = speed != null &&
            (!location.hasSpeedAccuracy() || location.speedAccuracyMetersPerSecond <= MAX_SPEED_ACCURACY_MS)

        if (qualifiedFix) {
            lastQualifiedLocation = Location(location)
            lastQualifiedLocationAt = eventTime
            lastQualifiedLocationWallTime = System.currentTimeMillis()
            if (speedQualified) {
                lastSpeed = speed
                lastSpeedAt = eventTime
            }
        }
        detector.onLocation(
            speedKmh = speed.takeIf { speedQualified },
            timestamp = eventTime,
            qualifiedFix = qualifiedFix
        )
        if (qualifiedFix && countdownEndsAt == null &&
            (currentLocationIntervalMs != LOCATION_INTERVAL_ROUTINE_MS ||
                currentLocationMinDistanceM != LOCATION_MIN_DISTANCE_ROUTINE_M)
        ) {
            setLocationUpdateInterval(LOCATION_INTERVAL_ROUTINE_MS, LOCATION_MIN_DISTANCE_ROUTINE_M)
        }
        // The location is only persisted when the change is meaningful, so this
        // stays cheap even though GPS callbacks are frequent.
        persistRuntime()
        publishTelemetry(eventTime, force = true)
    }

    override fun onProviderDisabled(provider: String) {
        if (provider != LocationManager.GPS_PROVIDER) return
        lastQualifiedLocation = null
        lastQualifiedLocationAt = null
        lastQualifiedLocationWallTime = null
        lastSpeedAt = null
        lastSpeed = null
        detector.clearLocation()
        removeLocationUpdates()
        if (monitoringEnabled) startSpecialForeground("Monitor activo; GPS desactivado")
        SafetyStateStore.update {
            it.copy(
                gpsAvailable = false,
                gpsFixAgeSeconds = null,
                speedKmh = null,
                detail = "GPS desactivado"
            )
        }
    }

    override fun onProviderEnabled(provider: String) {
        if (provider != LocationManager.GPS_PROVIDER || !monitoringEnabled) return
        if (hasPreciseLocationPermission(this)) {
            startLocationForeground("Monitor de seguridad activo")
            requestLocationUpdates()
        }
    }

    @Deprecated("Deprecated in Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit

    private fun loadRuntimeOnce() {
        if (runtimeLoaded) return
        val runtime = runtimeRepository.load()
        monitoringEnabled = runtime.monitoringEnabled
        countdownEndsAt = runtime.countdownEndsAt
        countdownEndsAtWallTime = runtime.countdownEndsAtWallTime
        countdownReason = runtime.countdownReason
        activeEventId = runtime.eventId
        if (runtime.latitude != null && runtime.longitude != null) {
            lastQualifiedLocation = Location(LocationManager.GPS_PROVIDER).apply {
                latitude = runtime.latitude
                longitude = runtime.longitude
                runtime.locationAccuracyMeters?.let {
                    accuracy = it
                }
            }
            lastQualifiedLocationWallTime = runtime.locationWallTime
            val wallAge = runtime.locationWallTime?.let { System.currentTimeMillis() - it }
            if (wallAge != null && wallAge in 0..MAP_LINK_LOCATION_WINDOW_MS) {
                lastQualifiedLocationAt = SystemClock.elapsedRealtime() - wallAge
            }
        }
        detector.restoreRuntime(
            AccidentDetector.RuntimeState(
                phase = runtime.phase,
                validationStartedAt = runtime.validationStartedAt,
                immobileSince = runtime.immobileSince
            )
        )
        lastPersistedRuntime = runtime
        runtimeLoaded = true
    }

    /**
     * A delivery pipeline can outlive the process. When the service starts again
     * (user, START_STICKY, alarm or accessibility advance) the pending dispatch
     * must take priority over a plain monitor resume.
     */
    private fun resumePendingDispatchIfAny(): Boolean {
        val dispatch = EmergencyDispatchRepository(this).load() ?: return false
        if (runtimeRepository.wasEventCompleted(dispatch.eventId)) {
            EmergencyDispatchRepository(this).clear()
            return false
        }
        if (monitoringEnabled && !sensorsRegistered) {
            activateMonitoring(preserveDetectorState = true)
        }
        if (!startEmergencyForeground("Reanudando avisos SOS")) return false
        dispatchInProgress = true
        SafetyStateStore.update {
            it.copy(dispatchInProgress = true, detail = "Reanudando avisos SOS pendientes")
        }
        scheduleDispatchWatchdog(DISPATCH_WATCHDOG_INITIAL_MS)
        continueEmergencyDispatch()
        return true
    }

    private fun resumePersistedStateOrStartMonitoring() {
        if (hasPersistedActiveState()) {
            resumePersistedStateOrStop()
        } else {
            activateMonitoring(preserveDetectorState = false)
        }
    }

    private fun resumePersistedStateOrStop() {
        val end = restoredCountdownEnd()
        countdownEndsAt = end
        if (end != null) {
            if (!startEmergencyForeground("Cuenta atras SOS recuperada")) {
                failAndStop("No se pudo recuperar la cuenta atras SOS")
                return
            }
            if (monitoringEnabled) activateMonitoring(preserveDetectorState = true)
            startTicker()
            publishRestoredCountdown(end)
            if (SystemClock.elapsedRealtime() >= end) completeProtocol()
            return
        }

        if (monitoringEnabled) {
            if (!activateMonitoring(preserveDetectorState = true)) {
                runtimeRepository.clearActiveState()
            }
        } else {
            stopSelf()
        }
    }

    private fun hasPersistedActiveState(): Boolean =
        countdownEndsAt != null ||
            (monitoringEnabled && detector.runtimeState().phase == SafetyPhase.VALIDATING)

    private fun ensureMonitoringForeground(): Boolean {
        if (monitoringEnabled && foregroundMode != ForegroundMode.NONE) return true
        return activateMonitoring(preserveDetectorState = detector.runtimeState().phase == SafetyPhase.VALIDATING)
    }

    private fun canUsePreciseLocation(): Boolean =
        hasPreciseLocationPermission(this) && isGpsProviderEnabled(this)

    private fun ensureEmergencyForeground(): Boolean {
        if (foregroundMode != ForegroundMode.NONE) return true
        wasMonitoringBeforeEmergency = monitoringEnabled
        if (!startEmergencyForeground("Preparando cuenta atras SOS")) {
            failAndStop("No se pudo iniciar la cuenta atras SOS")
            return false
        }
        startTicker()
        return true
    }

    private fun ensureForegroundForCurrentState(): Boolean {
        if (foregroundMode != ForegroundMode.NONE) return true
        return if (countdownEndsAt != null) {
            startEmergencyForeground("Cuenta atras SOS activa").also { if (it) startTicker() }
        } else if (monitoringEnabled) {
            activateMonitoring(preserveDetectorState = true)
        } else {
            false
        }
    }

    private fun activateMonitoring(preserveDetectorState: Boolean): Boolean {
        val started = if (canUsePreciseLocation()) {
            // Starting a location foreground service from the background is
            // restricted on modern Android. A special-use service still keeps
            // the motion monitor alive, just without live GPS.
            startLocationForeground("Monitor de seguridad activo") ||
                startSpecialForeground("Monitor activo; GPS no disponible")
        } else {
            startSpecialForeground("Monitor activo; GPS no disponible")
        }
        if (!started) {
            failAndStop("Android no permitio iniciar el monitor")
            return false
        }

        monitoringEnabled = true
        runtimeRepository.setMonitoringRequested(true)
        val dispatchPending = EmergencyDispatchRepository(this).load() != null
        if (!preserveDetectorState ||
            (detector.runtimeState().phase == SafetyPhase.MONITORING && !dispatchPending)
        ) {
            detector.resumeMonitoring()
            activeEventId = null
            countdownReason = ""
        }
        registerSensors()
        if (!sensorsRegistered) {
            failAndStop("No hay un sensor de movimiento utilizable")
            return false
        }
        if (canUsePreciseLocation()) requestLocationUpdates()
        startTicker()
        restartVoiceMonitor()
        publishDetectorState(if (preserveDetectorState) "Estado de seguridad recuperado" else "")
        persistRuntime()
        SafetyStateStore.log("Servicio iniciado")
        return true
    }

    private fun registerSensors() {
        if (sensorsRegistered) return

        val linearWakeUp = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION, true)
        val accelerometerWakeUp = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
        val linear = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val requiredRange = loadSensitivity(this) * AccidentDetector.STANDARD_GRAVITY
        val candidates = listOfNotNull(linearWakeUp, accelerometerWakeUp, linear, accelerometer)
            .distinctBy { it.id }
        val ordered = candidates.filter { it.maximumRange >= requiredRange } +
            candidates.filter { it.maximumRange < requiredRange }
        val selected = ordered.firstOrNull { sensor ->
            val latencyUs = if (sensor.fifoMaxEventCount > 0) SENSOR_BATCH_LATENCY_US else 0
            sensorManager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_GAME,
                latencyUs
            )
        }
        primaryMotionSensor = selected

        if (selected == null) {
            sensorDescription = "Sensor de movimiento no disponible"
            SafetyStateStore.update { it.copy(sensorAvailable = false, sensorDetail = sensorDescription) }
            SafetyStateStore.log(sensorDescription)
            return
        }

        sensorsRegistered = true
        sensorRegisteredAt = SystemClock.elapsedRealtime()
        sensorAccuracyReliable = true
        val kind = if (selected.type == Sensor.TYPE_LINEAR_ACCELERATION) "lineal" else "acelerometro con filtro"
        val wake = if (selected.isWakeUpSensor) "wake-up" else "no wake-up"
        val maxG = selected.maximumRange / AccidentDetector.STANDARD_GRAVITY
        sensorDescription = "$kind, $wake, max ${"%.1f".format(maxG)} g"
        SafetyStateStore.update {
            it.copy(sensorAvailable = sensorsRegistered, sensorDetail = sensorDescription)
        }
        SafetyStateStore.log(if (sensorsRegistered) "Sensor activo: $sensorDescription" else "Fallo al registrar $sensorDescription")
        if (maxG < loadSensitivity(this)) {
            SafetyStateStore.log("ADVERTENCIA: el sensor no alcanza la sensibilidad configurada")
        }

        bindAttitudeGate(keepCalibration = false)
    }

    /**
     * Wires the gravity sensor that feeds the fall gate. [keepCalibration] keeps the
     * learned riding attitude: changing a setting mid-journey must not blind the
     * fall confirmation for the length of a fresh calibration window.
     */
    private fun bindAttitudeGate(keepCalibration: Boolean) {
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY, true)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        if (gravitySensor != null) {
            sensorManager.registerListener(this, gravitySensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        val keepAttitude = keepCalibration && orientationConfigured
        if (orientationConfigured) {
            gravityAttitudeSensor = gravitySensor
            if (gravitySensor == null) {
                orientationDescription = "Sin sensor de gravedad: la confirmacion de caida no esta disponible"
                if (!keepCalibration) SafetyStateStore.log(orientationDescription)
            } else {
                orientationDescription =
                    "Gravedad: confirma caida con inclinacion >= ${AccidentDetector.FALL_TILT_THRESHOLD_DEGREES.toInt()} grados durante 4 s"
            }
            if (!keepAttitude) {
                fallOrientationDetector.reset()
                uprightAbortCounter = 0
                orientationCalibrated = false
                orientationTiltDegrees = null
                orientationAccuracyReliable = true
            }
            // The candidate phase must stay armed from the first sample. Waiting for
            // the accuracy callback would leave the configured fall gate silently
            // disabled on devices that never report a status change.
            setOrientationGateActive(gravitySensor != null)
        } else {
            gravityAttitudeSensor = null
            orientationDescription = "Desactivada en configuracion"
            setOrientationGateActive(false)
            if (!keepAttitude) {
                fallOrientationDetector.reset()
                uprightAbortCounter = 0
                orientationCalibrated = false
                orientationTiltDegrees = null
            }
        }
        SafetyStateStore.update {
            it.copy(
                orientationAvailable = orientationConfigured && gravityAttitudeSensor != null,
                orientationTiltDegrees = if (keepAttitude) it.orientationTiltDegrees else null,
                orientationDetail = orientationDescription
            )
        }
    }

    private fun setOrientationGateActive(active: Boolean) {
        if (orientationGateActive == active) return
        orientationGateActive = active
        detector.setFallOrientationGateEnabled(active)
    }

    /**
     * The gyroscope only ever feeds the validation window, so it is registered on
     * demand instead of streaming at the sensor rate for the whole ride.
     */
    private fun syncGyroscopeListener() {
        val phase = detector.runtimeState().phase
        val wanted = phase == SafetyPhase.VALIDATING || phase == SafetyPhase.FALL_CANDIDATE
        if (wanted == gyroscopeRegistered) return
        if (!wanted) {
            unregisterGyroscope()
            return
        }
        val sensor = gyroscopeSensor
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE).also { gyroscopeSensor = it }
            ?: return
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        gyroscopeRegistered = true
    }

    private fun unregisterGyroscope() {
        if (!gyroscopeRegistered) return
        gyroscopeSensor?.let { sensorManager.unregisterListener(this, it) }
        gyroscopeRegistered = false
    }

    private fun unregisterSensors() {
        if (::sensorManager.isInitialized) sensorManager.unregisterListener(this)
        sensorsRegistered = false
        sensorRegisteredAt = null
        sensorAccuracyReliable = true
        primaryMotionSensor = null
        gyroscopeSensor = null
        gyroscopeRegistered = false
        gravitySensor = null
        orientationCalibrated = false
        orientationTiltDegrees = null
        fallOrientationDetector.reset()
        setOrientationGateActive(false)
    }

    private fun requestLocationUpdates() {
        if (locationUpdatesRegistered || !hasPreciseLocationPermission(this)) return
        val detectorPhase = detector.runtimeState().phase
        currentLocationIntervalMs = when {
            detectorPhase == SafetyPhase.COUNTDOWN ||
                detectorPhase == SafetyPhase.VALIDATING ||
                detectorPhase == SafetyPhase.FALL_CANDIDATE -> LOCATION_INTERVAL_VALIDATION_MS
            lastQualifiedLocationAt != null -> LOCATION_INTERVAL_ROUTINE_MS
            else -> LOCATION_INTERVAL_INITIAL_MS
        }
        currentLocationMinDistanceM = when {
            detectorPhase == SafetyPhase.COUNTDOWN ||
                detectorPhase == SafetyPhase.VALIDATING ||
                detectorPhase == SafetyPhase.FALL_CANDIDATE -> 0f
            lastQualifiedLocationAt != null -> LOCATION_MIN_DISTANCE_ROUTINE_M
            else -> 0f
        }
        seedFromLastKnownLocation()
        var registeredProviders = 0
        for (provider in availableLocationProviders()) {
            try {
                locationManager.requestLocationUpdates(
                    provider,
                    currentLocationIntervalMs,
                    currentLocationMinDistanceM,
                    this,
                    Looper.getMainLooper()
                )
                registeredProviders++
            } catch (_: SecurityException) {
                SafetyStateStore.log("Permiso de ubicacion revocado")
                break
            } catch (_: IllegalArgumentException) {
                SafetyStateStore.log("Proveedor de ubicacion no disponible")
            }
        }
        locationUpdatesRegistered = registeredProviders > 0
    }

    private fun availableLocationProviders(): List<String> {
        val providers = mutableListOf<String>()
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
            val enabled = runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
            if (enabled) providers += provider
        }
        return providers
    }

    // Bajo techo el GPS puede tardar minutos o no fijar nunca; la ultima posicion
    // conocida mantiene informado al usuario y el mapa solo la usa si es reciente.
    @SuppressLint("MissingPermission") // Se comprueba el permiso y ademas se captura SecurityException.
    private fun seedFromLastKnownLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val best = availableLocationProviders()
            .mapNotNull { provider ->
                runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
            }
            .filter { fix ->
                fix.elapsedRealtimeNanos > 0L &&
                    fix.hasAccuracy() &&
                    fix.accuracy.isFinite() &&
                    fix.accuracy <= MAX_QUALIFIED_GPS_ACCURACY_METERS
            }
            .maxByOrNull { it.elapsedRealtimeNanos }
            ?: return
        val seededAt = best.elapsedRealtimeNanos / 1_000_000L
        lastQualifiedLocationAt?.let { existing -> if (seededAt <= existing) return }
        lastQualifiedLocation = Location(best)
        lastQualifiedLocationAt = seededAt
        lastQualifiedLocationWallTime = best.time.takeIf { it > 0L } ?: System.currentTimeMillis()
        lastAccuracy = best.accuracy
        val speedValid = best.hasSpeed() && best.speed.isFinite() && best.speed >= 0f &&
            (!best.hasSpeedAccuracy() || best.speedAccuracyMetersPerSecond <= MAX_SPEED_ACCURACY_MS)
        if (speedValid) {
            lastSpeed = best.speed * 3.6f
            lastSpeedAt = seededAt
        }
    }

    private fun setLocationUpdateInterval(intervalMs: Long, minDistanceM: Float = 0f) {
        if (!locationUpdatesRegistered) return
        if (currentLocationIntervalMs == intervalMs && currentLocationMinDistanceM == minDistanceM) return
        removeLocationUpdates()
        currentLocationIntervalMs = intervalMs
        currentLocationMinDistanceM = minDistanceM
        requestLocationUpdates()
    }

    private fun removeLocationUpdates() {
        if (!::locationManager.isInitialized || !locationUpdatesRegistered) return
        try {
            locationManager.removeUpdates(this)
        } catch (_: SecurityException) {
            // Permission may have been revoked while the service was running.
        }
        locationUpdatesRegistered = false
    }

    private fun startTicker() {
        if (tickerRunning) return
        tickerRunning = true
        window.post(tickRunnable)
    }

    private fun stopTicker() {
        tickerRunning = false
        window.removeCallbacks(tickRunnable)
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!tickerRunning) return
            val now = SystemClock.elapsedRealtime()
            detector.tick(now)
            syncDetectorRuntime()

            val end = countdownEndsAt
            if (end == null) {
                // Monitoring only needs a slow heartbeat: the detector advances on
                // sensor and GPS callbacks, so nothing is decided here.
                publishTelemetry(now)
                window.postDelayed(this, TICK_MONITOR_INTERVAL_MS)
                return
            }

            publishTelemetry(now, force = true)
            playCountdownBeepIfDue(now, end)
            if (now >= end) {
                completeProtocol()
                return
            }
            if (now - lastSnapshotUpdateAt >= SNAPSHOT_INTERVAL_MS) {
                val seconds = remainingSeconds(end, now)
                SafetyStateStore.update {
                    it.copy(detail = "Cuenta atras activa: ${seconds}s", countdownEndsAt = end)
                }
                lastSnapshotUpdateAt = now
            }
            if (now - lastNotificationUpdateAt >= NOTIFICATION_INTERVAL_MS) {
                updateNotification()
                lastNotificationUpdateAt = now
            }
            window.postDelayed(this, TICK_COUNTDOWN_INTERVAL_MS)
        }
    }

    private fun beginCountdown(reason: String) {
        if (countdownEndsAt != null) return
        val now = SystemClock.elapsedRealtime()
        countdownEndsAt = now + COUNTDOWN_MS
        countdownEndsAtWallTime = System.currentTimeMillis() + COUNTDOWN_MS
        countdownReason = reason
        if (activeEventId == null) activeEventId = UUID.randomUUID().toString()
        nextBeepAt = countdownEndsAt!! - FIRST_BEEP_REMAINING_MS
        lastSnapshotUpdateAt = 0L
        SafetyStateStore.update {
            it.copy(
                phase = SafetyPhase.COUNTDOWN,
                detail = "Cuenta atras: $reason",
                validationStartedAt = null,
                immobileSince = null,
                countdownEndsAt = countdownEndsAt
            )
        }
        SafetyStateStore.log("Cuenta atras iniciada: $reason")
        syncGyroscopeListener()
        acquireCountdownWakeLock(COUNTDOWN_MS + WAKE_LOCK_GRACE_MS)
        wakeScreenController.wakeFor(COUNTDOWN_MS + WAKE_LOCK_GRACE_MS)
        if (locationUpdatesRegistered) setLocationUpdateInterval(LOCATION_INTERVAL_VALIDATION_MS, 0f)
        persistRuntime()
        scheduleSosAlarm()
        getSystemService(NotificationManager::class.java).notify(
            EMERGENCY_ALERT_NOTIFICATION_ID,
            notification("SOS en 30s; pulsa CANCELAR si estas bien", onlyAlertOnce = false)
        )
        updateNotification(force = true)
        startTicker()
    }

    private fun publishRestoredCountdown(end: Long) {
        val now = SystemClock.elapsedRealtime()
        // A restored countdown must own an event id, otherwise completeProtocol()
        // cannot identify and report the emergency.
        if (activeEventId == null) activeEventId = UUID.randomUUID().toString()
        detector.restoreRuntime(AccidentDetector.RuntimeState(SafetyPhase.COUNTDOWN, null, null))
        nextBeepAt = maxOf(now, end - FIRST_BEEP_REMAINING_MS)
        SafetyStateStore.update {
            it.copy(
                phase = SafetyPhase.COUNTDOWN,
                detail = "Cuenta atras recuperada: ${remainingSeconds(end, now)}s",
                validationStartedAt = null,
                immobileSince = null,
                countdownEndsAt = end
            )
        }
        acquireCountdownWakeLock((end - now).coerceAtLeast(0L) + WAKE_LOCK_GRACE_MS)
        scheduleSosAlarm()
        persistRuntime()
        updateNotification(force = true)
    }

    private fun completeProtocol() {
        // The ticker and the AlarmManager backup can land on the same moment. The
        // guard must run before the state is torn down and before a fresh event id
        // is minted, otherwise a second pass looks like a brand new emergency and
        // repeats the SMS/WhatsApp burst.
        val completedEventId = activeEventId ?: run {
            // Without an event id nothing can be dispatched, but the countdown must
            // still be torn down or the UI stays on a dead countdown forever.
            SafetyStateStore.log("Protocolo sin evento activo: cuenta atras descartada")
            countdownEndsAt = null
            countdownEndsAtWallTime = null
            countdownReason = ""
            nextBeepAt = null
            releaseCountdownWakeLock()
            wakeScreenController.release()
            cancelSosAlarm()
            getSystemService(NotificationManager::class.java).cancel(EMERGENCY_ALERT_NOTIFICATION_ID)
            detector.resumeMonitoring()
            finishProtocolState()
            return
        }
        if (runtimeRepository.wasEventCompleted(completedEventId)) {
            countdownEndsAt = null
            countdownEndsAtWallTime = null
            countdownReason = ""
            nextBeepAt = null
            releaseCountdownWakeLock()
            wakeScreenController.release()
            cancelSosAlarm()
            getSystemService(NotificationManager::class.java).cancel(EMERGENCY_ALERT_NOTIFICATION_ID)
            detector.resumeMonitoring()
            finishProtocolState()
            return
        }

        countdownEndsAt = null
        countdownEndsAtWallTime = null
        countdownReason = ""
        nextBeepAt = null
        releaseCountdownWakeLock()
        wakeScreenController.release()
        cancelSosAlarm()
        getSystemService(NotificationManager::class.java).cancel(EMERGENCY_ALERT_NOTIFICATION_ID)
        detector.resumeMonitoring()

        val contactsRepository = ContactsRepository(this)
        if (!contactsRepository.areSosActionsEnabled()) {
            SafetyStateStore.log("AVISOS SOS DESACTIVADOS: no se llamara ni enviara WhatsApp")
            runtimeRepository.markEventCompleted(completedEventId)
            finishProtocolState()
            return
        }

        val contacts = contactsRepository.load()
        val mapLink = if (hasFreshQualifiedLocation()) {
            lastQualifiedLocation?.let { googleMapsLink(it.latitude, it.longitude) }
        } else {
            null
        }
        val callPhones = contacts.filter { it.callEnabled }.map { it.phone }
        val whatsappContacts = contacts.filter { it.whatsappEnabled }
        val whatsappPackage = whatsappPackageName()
        val autoSendRequested = WhatsAppAutoSend.isEnabled(this)
        val accessibilityReady = AutomationPermissions.isAccessibilityEnabled(this)
        val autoSend = autoSendRequested && whatsappPackage != null && accessibilityReady
        if (autoSendRequested && !accessibilityReady) {
            SafetyStateStore.log("ENVIO AUTO activo pero Accesibilidad desactivada: se ofrecera WhatsApp manual")
        }

        // Telegram: bot si hay token configurado (no necesita pantalla) y enlace
        // t.me por accesibilidad como respaldo para quien solo tenga usuario.
        val telegramContacts = contacts.filter { it.telegramEnabled }
        val telegramBotToken = contactsRepository.telegramBotToken()
            .takeIf { contactsRepository.isTelegramBotConfigured() }
        val telegramBotContacts = if (telegramBotToken == null) {
            emptyList()
        } else {
            telegramContacts.filter { it.telegramReadyForBot }
        }
        val telegramLinkContacts = telegramContacts.filter {
            !it.telegramReadyForBot && it.telegramUsername.isNotEmpty()
        }
        val telegramLinkAuto = autoSendRequested &&
            accessibilityReady &&
            telegramLinkContacts.isNotEmpty() &&
            TelegramSos.isInstalled(this)
        if (telegramBotToken == null && telegramContacts.any { it.telegramChatId.isNotEmpty() }) {
            SafetyStateStore.log("TELEGRAM: falta el token del bot; se usara el enlace t.me")
        }

        // SMS automatico: funciona con pantalla apagada/bloqueada, sin accesibilidad.
        if (loadSmsAuto(this)) {
            sendSosSms(contacts, mapLink)
        }

        if (telegramBotContacts.isNotEmpty() && telegramBotToken != null) {
            dispatchTelegramBot(telegramBotToken, telegramBotContacts, mapLink)
        }

        if ((autoSend && whatsappPackage != null && whatsappContacts.isNotEmpty()) || telegramLinkAuto) {
            WhatsAppAutoSend.disarmAll(this)
            var queued = 0
            if (autoSend && whatsappPackage != null) {
                queued += whatsappContacts.count { contact ->
                    WhatsAppAutoSend.enqueue(
                        context = this,
                        eventId = completedEventId,
                        recipient = contact.phone,
                        expectedPackage = whatsappPackage,
                        message = contact.whatsappMessageFor(mapLink),
                        ttlMillis = WhatsAppAutoSend.MAX_REQUEST_TTL_MILLIS
                    ).queued
                }
            }
            if (telegramLinkAuto) {
                queued += telegramLinkContacts.count { contact ->
                    WhatsAppAutoSend.enqueue(
                        context = this,
                        eventId = completedEventId,
                        recipient = contact.telegramUsername,
                        expectedPackage = TelegramSos.PACKAGE,
                        message = contact.sosMessageFor(mapLink),
                        ttlMillis = WhatsAppAutoSend.MAX_REQUEST_TTL_MILLIS
                    ).queued
                }
            }
            if (queued > 0 && EmergencyDispatchRepository(this).save(
                    PendingEmergencyDispatch(
                        eventId = completedEventId,
                        callPhones = callPhones,
                        callIndex = 0,
                        answered = false,
                        step = DispatchStep.WHATSAPP
                    )
                )
            ) {
                activeEventId = completedEventId
                dispatchInProgress = true
                SafetyStateStore.update {
                    it.copy(dispatchInProgress = true, detail = "Enviando avisos SOS...")
                }
                persistRuntime()
                startEmergencyForeground("Enviando avisos SOS")
                stopTicker()
                scheduleDispatchWatchdog(DISPATCH_WATCHDOG_INITIAL_MS)
                launchQueuedWhatsAppHead()
                SafetyStateStore.log("Avisos automaticos en cola: $queued; llamadas en orden al terminar los envios")
                return
            }
            WhatsAppAutoSend.disarmAll(this)
        }

        // Sin autoenvio: aviso manual para cada contacto con WhatsApp.
        // Reaching this point means no automatic send was launched (disabled, not
        // ready, enqueue failure or a failed dispatch persistence), so every
        // contact with WhatsApp must still get its manual action.
        postManualWhatsAppActions(whatsappContacts, mapLink)
        postManualTelegramActions(telegramLinkContacts, mapLink)

        if (callPhones.isNotEmpty()) {
            if (EmergencyDispatchRepository(this).save(
                    PendingEmergencyDispatch(
                        eventId = completedEventId,
                        callPhones = callPhones,
                        callIndex = 0,
                        answered = false,
                        step = DispatchStep.CALLS
                    )
                )
            ) {
                activeEventId = completedEventId
                dispatchInProgress = true
                SafetyStateStore.update {
                    it.copy(dispatchInProgress = true, detail = "Llamando a los contactos SOS en orden")
                }
                persistRuntime()
                startEmergencyForeground("Llamando a los contactos SOS en orden")
                scheduleDispatchWatchdog(DISPATCH_WATCHDOG_INITIAL_MS)
                startCallEscalation()
                return
            }
        }

        SafetyStateStore.log(
            if (whatsappContacts.isEmpty()) "SOS sin contactos configurados" else "WhatsApp manual disponible en notificacion"
        )
        runtimeRepository.markEventCompleted(completedEventId)
        dispatchInProgress = false
        finishProtocolState()
    }

    private fun continueEmergencyDispatch() {
        val repository = EmergencyDispatchRepository(this)
        val dispatch = repository.load()
        if (dispatch == null) {
            dispatchInProgress = false
            cancelDispatchWatchdog()
            SafetyStateStore.update { it.copy(dispatchInProgress = false) }
            if (monitoringEnabled || countdownEndsAt != null) {
                SafetyStateStore.log("Avisos SOS ya finalizados")
                if (monitoringEnabled && !sensorsRegistered) {
                    activateMonitoring(preserveDetectorState = true)
                }
                return
            }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (dispatch.step == DispatchStep.DONE) {
            finishEscalation(dispatch, answered = dispatch.answered, detail = "protocolo completado")
            return
        }
        if (monitoringEnabled && !sensorsRegistered) {
            // The process may have been recreated by an alarm or by the
            // accessibility advance; restore the monitor before finishing.
            activateMonitoring(preserveDetectorState = true)
        }
        if (!startEmergencyForeground("Continuando avisos SOS")) return

        // A dispatch written by an older build has no step. If its WhatsApp
        // queue is still pending, finish that phase before calling anyone.
        val pendingQueue = WhatsAppAutoSend.inspectQueue(this)
        val effectiveStep = if (dispatch.step == DispatchStep.CALLS &&
            pendingQueue.any { it.eventId == dispatch.eventId && !it.status.isTerminal }
        ) {
            DispatchStep.WHATSAPP
        } else {
            dispatch.step
        }

        when (effectiveStep) {
            DispatchStep.WHATSAPP -> {
                val head = WhatsAppAutoSend.inspectQueue(this).firstOrNull()
                if (head != null && !head.status.isTerminal) {
                    // launchQueuedWhatsAppHead() may fail while WhatsApp is still
                    // starting; the watchdog retries instead of dropping the queue.
                    launchQueuedWhatsAppHead()
                    scheduleDispatchWatchdog(DISPATCH_WATCHDOG_RETRY_MS)
                    return
                }
                if (head != null) {
                    WhatsAppAutoSend.advance(this, head.requestId)
                    scheduleDispatchWatchdog(0L)
                    return
                }
                val calls = dispatch.copy(step = DispatchStep.CALLS, callIndex = 0, answered = false)
                if (!repository.save(calls)) {
                    SafetyStateStore.log("No se pudo guardar el avance de avisos SOS")
                    scheduleDispatchWatchdog(DISPATCH_WATCHDOG_RETRY_MS)
                    return
                }
                SafetyStateStore.log("WhatsApp enviado a todos los contactos; comenzando llamadas en orden")
                startCallEscalation()
            }
            DispatchStep.CALLS -> startCallEscalation()
            DispatchStep.DONE -> finishEscalation(dispatch, dispatch.answered, "protocolo completado")
        }
    }

    private fun startCallEscalation() {
        val dispatch = EmergencyDispatchRepository(this).load() ?: run {
            dispatchInProgress = false
            finishProtocolState()
            return
        }
        if (dispatch.callPhones.isEmpty()) {
            finishEscalation(dispatch, answered = false, detail = "sin contactos de llamada")
            return
        }
        if (dispatch.answered) {
            finishEscalation(dispatch, answered = true, detail = "un contacto respondio la llamada")
            return
        }
        registerTelephonyListener()
        if (escalationActive) return
        escalateCurrent(dispatch)
    }

    private fun escalateCurrent(dispatch: PendingEmergencyDispatch) {
        escalationActive = true
        val index = dispatch.callIndex
        if (index >= dispatch.callPhones.size) {
            finishEscalation(dispatch, answered = false, detail = "sin respuesta de todos los contactos")
            return
        }
        currentCallOffhookAt = null
        pendingCallAdvance = false
        val phone = dispatch.callPhones[index]
        val placed = placeEmergencyCall(phone)
        SafetyStateStore.log(
            "Llamada ${index + 1}/${dispatch.callPhones.size} a $phone ${if (placed) "iniciada" else "no disponible"}"
        )
        if (placed) {
            scheduleEscalationAlarm(dispatch)
        } else {
            clearCallTracking()
            advanceEscalation(dispatch)
        }
    }

    /** Called when a call has been ringing or active for ~1 minute. */
    private fun onEscalationTimeout(dispatch: PendingEmergencyDispatch) {
        if (!escalationActive) return
        val now = SystemClock.elapsedRealtime()
        if (pendingCallAdvance) {
            if (currentCallOffhookAt == null) {
                clearCallTracking()
                advanceEscalation(dispatch)
            } else if (!endCurrentCall()) {
                clearCallTracking()
                advanceEscalation(dispatch)
            } else {
                scheduleEscalationAlarm(dispatch, ESCALATION_END_RETRY_MS)
            }
            return
        }
        val offhookAt = currentCallOffhookAt
        if (offhookAt != null) {
            if (EscalationPolicy.shouldWaitForCallEnd(offhookAt, now)) {
                SafetyStateStore.log("Llamada ${dispatch.callIndex + 1} en curso; se espera a que termine")
                scheduleEscalationAlarm(dispatch)
                return
            }
            SafetyStateStore.log("Llamada ${dispatch.callIndex + 1} demasiado larga; se corta para continuar")
            if (!endCurrentCall()) {
                SafetyStateStore.log("No se pudo cortar la llamada; se pasa al siguiente contacto")
                clearCallTracking()
                advanceEscalation(dispatch)
                return
            }
            pendingCallAdvance = true
            scheduleEscalationAlarm(dispatch, ESCALATION_END_RETRY_MS)
            return
        }
        SafetyStateStore.log("Llamada ${dispatch.callIndex + 1} sin respuesta en 1 minuto")
        advanceEscalation(dispatch)
    }

    private fun onCallEnded() {
        if (!escalationActive) return
        val dispatch = EmergencyDispatchRepository(this).load() ?: return
        val offhookAt = currentCallOffhookAt
        val wasPendingAdvance = pendingCallAdvance
        val now = SystemClock.elapsedRealtime()
        clearCallTracking()
        if (wasPendingAdvance) {
            cancelEscalationAlarm()
            advanceEscalation(dispatch)
            return
        }
        if (EscalationPolicy.isAnswered(offhookAt, now)) {
            SafetyStateStore.log("Llamada ${dispatch.callIndex + 1} conectada y finalizada")
            finishEscalation(dispatch, answered = true, detail = "un contacto respondio la llamada")
            return
        }
        if (offhookAt != null) {
            cancelEscalationAlarm()
            advanceEscalation(dispatch)
        }
    }

    private fun advanceEscalation(dispatch: PendingEmergencyDispatch) {
        clearCallTracking()
        val next = dispatch.callIndex + 1
        if (next >= dispatch.callPhones.size) {
            EmergencyDispatchRepository(this).save(dispatch.copy(callIndex = next))
            finishEscalation(dispatch, answered = false, detail = "sin respuesta de todos los contactos")
            return
        }
        val updated = dispatch.copy(callIndex = next)
        if (!EmergencyDispatchRepository(this).save(updated)) {
            SafetyStateStore.log("No se pudo guardar el avance de llamada")
        }
        escalateCurrent(updated)
    }

    private fun finishEscalation(dispatch: PendingEmergencyDispatch, answered: Boolean, detail: String) {
        escalationActive = false
        clearCallTracking()
        unregisterTelephonyListener()
        cancelEscalationAlarm()
        cancelDispatchWatchdog()
        // Never leave a late WhatsApp authorization alive after the dispatch
        // has been closed by a timeout or an answered call.
        WhatsAppAutoSend.disarmAll(this)
        dispatchInProgress = false
        SafetyStateStore.update { it.copy(dispatchInProgress = false) }
        SafetyStateStore.log("SOS: ${if (answered) "contacto atendio la llamada" else "llamadas sin respuesta: $detail"}")
        if (!runtimeRepository.markEventCompleted(dispatch.eventId)) {
            SafetyStateStore.log("No se pudo marcar el evento SOS como completado")
        }
        EmergencyDispatchRepository(this).clear()
        finishProtocolState()
    }

    private fun clearCallTracking() {
        currentCallOffhookAt = null
        pendingCallAdvance = false
    }

    private fun endCurrentCall(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return runCatching {
            getSystemService(TelecomManager::class.java).endCall()
            true
        }.getOrDefault(false)
    }

    private fun registerTelephonyListener() {
        if (telephonyRegistered) return
        val telephony = getSystemService(TelephonyManager::class.java)
        val hasStatePermission = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasStatePermission) {
            SafetyStateStore.log("Sin permiso READ_PHONE_STATE: no se detectara el fin de llamada; se avanza cada minuto")
            return
        }
        val ok = runCatching {
            telephony.listen(callStateListener, PhoneStateListener.LISTEN_CALL_STATE)
            true
        }.getOrDefault(false)
        telephonyRegistered = ok
        if (!ok) SafetyStateStore.log("No se pudo escuchar el estado de la llamada")
    }

    private fun unregisterTelephonyListener() {
        if (!telephonyRegistered) return
        runCatching {
            getSystemService(TelephonyManager::class.java)
                .listen(callStateListener, PhoneStateListener.LISTEN_NONE)
        }
        telephonyRegistered = false
    }

    private fun scheduleEscalationAlarm(
        dispatch: PendingEmergencyDispatch,
        delayMs: Long = CALL_ESCALATION_TIMEOUT_MS
    ) {
        val triggerAt = System.currentTimeMillis() + delayMs
        val pending = escalationAlarmIntent()
        val scheduled = scheduleExactOrInexact(triggerAt, pending, "avance de llamada")
        if (!scheduled) {
            SafetyStateStore.log("No se pudo programar el avance de llamada")
            advanceEscalation(dispatch)
        }
    }

    private fun cancelEscalationAlarm() {
        if (!::alarmManager.isInitialized) return
        runCatching { alarmManager.cancel(escalationAlarmIntent()) }
    }

    private fun scheduleDispatchWatchdog(delayMs: Long) {
        if (delayMs <= 0L) {
            window.post { handleDispatchWatchdog() }
            return
        }
        if (dispatchWatchdogStartedAt == null) {
            dispatchWatchdogStartedAt = SystemClock.elapsedRealtime()
        }
        val triggerAt = System.currentTimeMillis() + delayMs
        val scheduled = scheduleExactOrInexact(triggerAt, dispatchWatchdogIntent(), "supervision de avisos SOS")
        if (!scheduled) {
            // Best effort while the process is alive; a dead process simply
            // loses this retry, but the next service start resumes the dispatch.
            window.postDelayed({ handleDispatchWatchdog() }, delayMs.coerceAtMost(30_000L))
        }
    }

    private fun cancelDispatchWatchdog() {
        dispatchWatchdogStartedAt = null
        if (!::alarmManager.isInitialized) return
        runCatching { alarmManager.cancel(dispatchWatchdogIntent()) }
    }

    private fun dispatchWatchdogIntent(): PendingIntent = PendingIntent.getBroadcast(
        this,
        DISPATCH_WATCHDOG_REQUEST_CODE,
        Intent(this, SosAlarmReceiver::class.java).setAction(SosAlarmReceiver.ACTION_DISPATCH_WATCHDOG),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun handleDispatchWatchdog() {
        val dispatch = EmergencyDispatchRepository(this).load()
        if (dispatch == null) {
            dispatchInProgress = false
            cancelDispatchWatchdog()
            SafetyStateStore.update { it.copy(dispatchInProgress = false) }
            if (monitoringEnabled) {
                if (!sensorsRegistered) activateMonitoring(preserveDetectorState = true)
            } else if (countdownEndsAt == null) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return
        }
        val startedAt = dispatchWatchdogStartedAt ?: SystemClock.elapsedRealtime().also {
            dispatchWatchdogStartedAt = it
        }
        if (SystemClock.elapsedRealtime() - startedAt > DISPATCH_WATCHDOG_MAX_AGE_MS) {
            SafetyStateStore.log("Avisos SOS agotaron el tiempo maximo; se cierra el protocolo")
            WhatsAppAutoSend.disarmAll(this)
            finishEscalation(dispatch, answered = false, detail = "tiempo maximo de avisos")
            return
        }
        if (escalationActive) {
            scheduleDispatchWatchdog(DISPATCH_WATCHDOG_RETRY_MS)
            return
        }
        if (!startEmergencyForeground("Supervisando avisos SOS")) {
            scheduleDispatchWatchdog(DISPATCH_WATCHDOG_RETRY_MS)
            return
        }
        dispatchInProgress = true
        continueEmergencyDispatch()
    }

    private fun scheduleExactOrInexact(triggerAt: Long, pending: PendingIntent, label: String): Boolean =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else {
                if (!exactAlarmFallbackLogged) {
                    exactAlarmFallbackLogged = true
                    SafetyStateStore.log("Alarmas exactas no autorizadas: $label puede retrasarse")
                }
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
            true
        }.getOrDefault(false)

    private fun escalationAlarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        this,
        ESCALATION_ALARM_REQUEST_CODE,
        Intent(this, SosAlarmReceiver::class.java).setAction(SosAlarmReceiver.ACTION_ESCALATE_CALL),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun handleEscalationAlarm() {
        val dispatch = EmergencyDispatchRepository(this).load()
        if (dispatch == null) {
            if (monitoringEnabled || countdownEndsAt != null) {
                if (monitoringEnabled && !sensorsRegistered) {
                    activateMonitoring(preserveDetectorState = true)
                }
                SafetyStateStore.log("Aviso de llamada sin despacho activo; ignorado")
                return
            }
            stopSelf()
            return
        }
        if (!startEmergencyForeground("Continuando llamadas SOS")) return
        escalationActive = true
        if (dispatch.answered) {
            finishEscalation(dispatch, answered = true, detail = "un contacto respondio la llamada")
            return
        }
        onEscalationTimeout(dispatch)
    }

    private fun finishProtocolState() {
        activeEventId = null
        dispatchInProgress = false
        cancelDispatchWatchdog()
        val restoreMonitoring = monitoringEnabled || wasMonitoringBeforeEmergency
        wasMonitoringBeforeEmergency = false
        if (restoreMonitoring) {
            SafetyStateStore.update {
                it.copy(
                    phase = SafetyPhase.MONITORING,
                    detail = "Protocolo completado; monitor rearmado",
                    validationStartedAt = null,
                    immobileSince = null,
                    countdownEndsAt = null,
                    dispatchInProgress = false
                )
            }
            if (foregroundMode == ForegroundMode.NONE && canUsePreciseLocation()) {
                startLocationForeground("Monitor de seguridad activo") ||
                    startSpecialForeground("Monitor de seguridad activo")
            }
            startTicker()
            if (locationUpdatesRegistered) {
                setLocationUpdateInterval(LOCATION_INTERVAL_ROUTINE_MS, LOCATION_MIN_DISTANCE_ROUTINE_M)
            }
            persistRuntime()
            updateNotification(force = true)
        } else {
            runtimeRepository.clearActiveState()
            SafetyStateStore.update {
                it.copy(
                    phase = SafetyPhase.STOPPED,
                    detail = "Protocolo completado; monitor detenido",
                    validationStartedAt = null,
                    immobileSince = null,
                    countdownEndsAt = null,
                    dispatchInProgress = false
                )
            }
            stopTicker()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun hasFreshQualifiedLocation(): Boolean {
        val timestamp = lastQualifiedLocationAt ?: return false
        val age = SystemClock.elapsedRealtime() - timestamp
        return age in 0..MAP_LINK_LOCATION_WINDOW_MS
    }

    private fun placeEmergencyCall(phone: String): Boolean {
        val number = phone.trim()
        if (number.isEmpty()) return false
        val callPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        if (!callPermission) {
            SafetyStateStore.log("LLAMADA no iniciada: falta permiso CALL_PHONE")
            return false
        }
        val uri = Uri.parse("tel:$number")
        return runCatching {
            getSystemService(TelecomManager::class.java).placeCall(uri, Bundle())
            true
        }.onFailure {
            SafetyStateStore.log("LLAMADA no iniciada: ${it.message.orEmpty()}")
        }.getOrDefault(false)
    }

    private fun launchQueuedWhatsAppHead(): Boolean {
        val request = WhatsAppAutoSend.peek(this) ?: return false
        if (request.status != WhatsAppRequestStatus.PENDING) return false
        val target = if (request.expectedPackage == TelegramSos.PACKAGE) {
            buildTelegramIntent(request.recipient, request.normalizedMessage)
        } else {
            buildWhatsAppIntent(
                request.expectedPackage,
                request.recipient.filter(Char::isDigit),
                request.normalizedMessage
            )
        } ?: run {
            SafetyStateStore.log("Aviso SOS: ninguna actividad puede abrir el chat")
            return false
        }
        // The accessibility click only lands while the screen is on: keep it awake
        // for the whole retry window of the send attempt.
        wakeScreenController.wakeFor(WHATSAPP_SCREEN_WAKE_MS)
        return startActivityFromBackground(withKeyguardRelay(target))
    }

    /**
     * Prefers the native whatsapp:// scheme, which opens the chat directly instead
     * of going through the api.whatsapp.com redirect (that one may end in a
     * browser). Falls back to the https link when the package ignores the scheme.
     */
    private fun buildWhatsAppIntent(pkg: String, number: String, message: String): Intent? {
        if (number.isEmpty()) return null
        val encoded = Uri.encode(message)
        val candidates = listOf(
            "whatsapp://send?phone=$number&text=$encoded",
            "https://api.whatsapp.com/send/?phone=$number&text=$encoded"
        )
        for (url in candidates) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg)
            val resolvable = runCatching {
                packageManager.queryIntentActivities(intent, 0).isNotEmpty()
            }.getOrDefault(false)
            if (resolvable) return intent
        }
        return null
    }

    /**
     * Para Telegram se prefiere el esquema `tg://`, que solo resuelve en Telegram;
     * el enlace https tambien lo captura el navegador del sistema.
     */
    private fun buildTelegramIntent(username: String, message: String): Intent? {
        val user = TelegramSos.normalizeUsername(username) ?: return null
        val encoded = Uri.encode(message)
        val candidates = listOf(
            "tg://resolve?domain=$user&text=$encoded",
            "https://t.me/$user?text=$encoded"
        )
        for (url in candidates) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(TelegramSos.PACKAGE)
            val resolvable = runCatching {
                packageManager.queryIntentActivities(intent, 0).isNotEmpty()
            }.getOrDefault(false)
            if (resolvable) return intent
        }
        return null
    }

    /**
     * A locked screen makes the accessibility tap impossible, so the launch goes
     * through a transparent activity that turns the screen on and asks the system
     * to dismiss the keyguard (only automatic when there is no PIN/pattern).
     */
    private fun withKeyguardRelay(target: Intent): Intent {
        val keyguard = getSystemService(KeyguardManager::class.java)
        val screenOff = !getSystemService(PowerManager::class.java).isInteractive
        if (keyguard?.isKeyguardLocked != true && !screenOff) return target
        return Intent(this, WhatsAppUnlockActivity::class.java)
            .putExtra(WhatsAppUnlockActivity.EXTRA_TARGET, target)
    }

    private fun postManualWhatsAppActions(contacts: List<EmergencyContact>, mapLink: String?) {
        contacts.take(MAX_MANUAL_WHATSAPP_NOTIFICATIONS).forEachIndexed { index, contact ->
            postManualWhatsAppAction(contact, contact.whatsappMessageFor(mapLink), index)
        }
    }

    private fun postManualWhatsAppAction(
        contact: EmergencyContact,
        message: String,
        index: Int
    ) {
        val pkg = whatsappPackageName() ?: return
        val number = contact.phone.filter(Char::isDigit)
        if (number.isEmpty()) return
        val intent = buildWhatsAppIntent(pkg, number, message) ?: return
        val pending = PendingIntent.getActivity(
            this,
            MANUAL_WHATSAPP_REQUEST_CODE + index,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, EMERGENCY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Segu-Luky: WhatsApp SOS para ${contact.name}")
            .setContentText("Pulsa para abrir y enviar el mensaje de emergencia")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pending)
            .addAction(android.R.drawable.ic_menu_send, "ABRIR WHATSAPP", pending)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(MANUAL_WHATSAPP_NOTIFICATION_ID + index, notification)
    }

    /**
     * Via principal de Telegram: Bot API. No abre ninguna app ni necesita la
     * pantalla desbloqueada, pero requiere token del bot y chat id del contacto.
     * Corre en segundo plano para no retrasar llamadas ni WhatsApp.
     */
    private fun dispatchTelegramBot(
        token: String,
        contacts: List<EmergencyContact>,
        mapLink: String?
    ) {
        if (contacts.isEmpty()) return
        val targets = contacts.map { it.telegramChatId to it.sosMessageFor(mapLink) }
        val label = contacts.size
        Thread {
            val powerManager = getSystemService(PowerManager::class.java)
            val wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Segu-Luky:telegram-bot"
            )?.apply { runCatching { acquire(TELEGRAM_BOT_WAKE_LOCK_MS) } }
            try {
                var sent = 0
                val problems = mutableListOf<String>()
                targets.forEach { (chatId, message) ->
                    val result = TelegramSos.sendViaBot(token, chatId, message)
                    if (result.ok) {
                        sent += 1
                    } else {
                        problems += "$chatId (${result.detail})"
                    }
                }
                val detail = if (problems.isEmpty()) "" else ": ${problems.joinToString("; ")}"
                SafetyStateStore.log("TELEGRAM bot: $sent/$label avisos enviados$detail")
            } finally {
                runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun postManualTelegramActions(contacts: List<EmergencyContact>, mapLink: String?) {
        contacts.take(MAX_MANUAL_WHATSAPP_NOTIFICATIONS).forEachIndexed { index, contact ->
            val intent = buildTelegramIntent(contact.telegramUsername, contact.sosMessageFor(mapLink))
                ?: return@forEachIndexed
            val pending = PendingIntent.getActivity(
                this,
                MANUAL_TELEGRAM_REQUEST_CODE + index,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(this, EMERGENCY_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Segu-Luky: Telegram SOS para ${contact.name}")
                .setContentText("Pulsa para abrir el chat y enviar el mensaje de emergencia")
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pending)
                .addAction(android.R.drawable.ic_menu_send, "ABRIR TELEGRAM", pending)
                .build()
            getSystemService(NotificationManager::class.java)
                .notify(MANUAL_TELEGRAM_NOTIFICATION_ID + index, notification)
        }
    }

    private fun sendSosSms(contacts: List<EmergencyContact>, mapLink: String?) {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            SafetyStateStore.log("SMS SOS activado pero falta el permiso de SMS")
            return
        }
        if (contacts.isEmpty()) return
        val manager = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        } ?: return
        var sent = 0
        contacts.forEach { contact ->
            val number = contact.phone.filter { it.isDigit() || it == '+' }
            if (number.isEmpty()) return@forEach
            val text = contact.whatsappMessageFor(mapLink)
            val parts = runCatching { manager.divideMessage(text) }.getOrNull() ?: return@forEach
            val ok = runCatching {
                manager.sendMultipartTextMessage(number, null, parts, null, null)
                true
            }.getOrDefault(false)
            if (ok) sent++
        }
        SafetyStateStore.log(
            "SMS SOS enviados: $sent/${contacts.size}" +
                if (mapLink != null) " (con enlace de mapa)" else " (sin GPS reciente)"
        )
    }

    private fun whatsappPackageName(): String? {
        val candidates = listOf("com.whatsapp", "com.whatsapp.w4b")
        return candidates.firstOrNull { pkg ->
            runCatching { packageManager.getPackageInfo(pkg, 0) }.isSuccess
        }
    }

    private fun startActivityFromBackground(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            val requestCode = backgroundStartRequestCode and 0x3FFF
            backgroundStartRequestCode += 1
            val pending = PendingIntent.getActivity(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            if (Build.VERSION.SDK_INT >= 34) {
                val options = ActivityOptions.makeBasic().apply {
                    setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                }
                pending.send(options.toBundle())
            } else {
                pending.send()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun cancelActiveEvent() {
        val active = detector.runtimeState().phase == SafetyPhase.VALIDATING || countdownEndsAt != null
        if (!active) return

        countdownEndsAt = null
        countdownEndsAtWallTime = null
        countdownReason = ""
        activeEventId = null
        nextBeepAt = null
        releaseCountdownWakeLock()
        wakeScreenController.release()
        cancelSosAlarm()
        getSystemService(NotificationManager::class.java).cancel(EMERGENCY_ALERT_NOTIFICATION_ID)
        detector.cancel()
        SafetyStateStore.log("Evento cancelado por el usuario")

        // Restore the monitoring state from before the SOS was triggered. A
        // process death loses wasMonitoringBeforeEmergency, but monitoringEnabled
        // is persisted, so it is the durable signal.
        if (wasMonitoringBeforeEmergency || monitoringEnabled) {
            monitoringEnabled = true
            wasMonitoringBeforeEmergency = false
            if (!sensorsRegistered) {
                activateMonitoring(preserveDetectorState = true)
            }
            SafetyStateStore.update {
                it.copy(
                    phase = SafetyPhase.MONITORING,
                    detail = "Evento cancelado; monitor rearmado",
                    validationStartedAt = null,
                    immobileSince = null,
                    countdownEndsAt = null
                )
            }
            persistRuntime()
            updateNotification(force = true)
        } else {
            runtimeRepository.clearActiveState()
            stopTicker()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopMonitoringAndService() {
        monitoringEnabled = false
        runtimeRepository.setMonitoringRequested(false)
        wasMonitoringBeforeEmergency = false
        countdownEndsAt = null
        countdownEndsAtWallTime = null
        countdownReason = ""
        activeEventId = null
        detector.resumeMonitoring()
        runtimeRepository.clearActiveState()
        releaseCountdownWakeLock()
        wakeScreenController.release()
        cancelSosAlarm()
        getSystemService(NotificationManager::class.java).cancel(EMERGENCY_ALERT_NOTIFICATION_ID)
        stopTicker()
        unregisterSensors()
        removeLocationUpdates()
        stopVoiceMonitor()
        escalationActive = false
        clearCallTracking()
        dispatchInProgress = false
        cancelEscalationAlarm()
        cancelDispatchWatchdog()
        WhatsAppAutoSend.disarmAll(this)
        EmergencyDispatchRepository(this).clear()
        unregisterTelephonyListener()
        SafetyStateStore.reset()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun syncDetectorRuntime() {
        // Runs on every motion sensor callback (~50 Hz). Checking the phase is
        // allocation free, so the snapshot is only rebuilt when a validation is
        // actually running, at most RUNTIME_SYNC_INTERVAL_MS apart, and only when
        // the mirrored values really changed. Publishing identical snapshots used
        // to emit a StateFlow update 50 times per second during the whole
        // five minute validation window.
        if (detector.currentPhase != SafetyPhase.VALIDATING || countdownEndsAt != null) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRuntimeSyncAt < RUNTIME_SYNC_INTERVAL_MS) return
        lastRuntimeSyncAt = now
        val state = detector.runtimeState()
        if (state.validationStartedAt == lastSyncedValidationStart &&
            state.immobileSince == lastSyncedImmobileSince
        ) {
            return
        }
        lastSyncedValidationStart = state.validationStartedAt
        lastSyncedImmobileSince = state.immobileSince
        SafetyStateStore.update {
            it.copy(
                validationStartedAt = state.validationStartedAt,
                immobileSince = state.immobileSince,
                countdownEndsAt = null
            )
        }
    }

    private fun persistRuntime() {
        val detectorState = detector.runtimeState()
        val previous = lastPersistedRuntime
        val candidateWallTime = lastQualifiedLocationWallTime
        // A fresher fix updates the wall clock so a restored location stays
        // useful, but rewriting it on every fix would defeat the write cache.
        // A 15 s bucket still leaves the fix well inside the 5 minute map window.
        val wallTime = if (previous?.locationWallTime != null &&
            candidateWallTime != null &&
            abs(candidateWallTime - previous.locationWallTime) < LOCATION_WALL_TIME_PERSIST_INTERVAL_MS
        ) {
            previous.locationWallTime
        } else {
            candidateWallTime
        }
        val runtime = PersistedSafetyRuntime(
            monitoringEnabled = monitoringEnabled,
            phase = if (countdownEndsAt != null) SafetyPhase.COUNTDOWN else detectorState.phase,
            validationStartedAt = detectorState.validationStartedAt,
            immobileSince = detectorState.immobileSince,
            countdownEndsAt = countdownEndsAt,
            countdownEndsAtWallTime = countdownEndsAtWallTime,
            countdownReason = countdownReason,
            eventId = activeEventId,
            latitude = lastQualifiedLocation?.latitude,
            longitude = lastQualifiedLocation?.longitude,
            locationAccuracyMeters = lastAccuracy,
            locationWallTime = wallTime
        )
        if (lastPersistedRuntime.matchesMeaningfully(runtime)) return
        if (runtimeRepository.save(runtime)) {
            lastPersistedRuntime = runtime
        } else {
            SafetyStateStore.log("No se pudo guardar el estado de seguridad")
        }
    }

    private fun publishDetectorState(detail: String) {
        val detectorState = detector.runtimeState()
        SafetyStateStore.update {
            it.copy(
                phase = detectorState.phase,
                detail = detail,
                validationStartedAt = detectorState.validationStartedAt,
                immobileSince = detectorState.immobileSince,
                countdownEndsAt = countdownEndsAt
            )
        }
    }

    private fun publishTelemetry(now: Long, force: Boolean = false) {
        if (!force && now - lastTelemetryUpdateAt < SNAPSHOT_INTERVAL_MS) return
        lastTelemetryUpdateAt = now
        val locationAge = lastQualifiedLocationAt?.let { timestamp ->
            if (now >= timestamp) now - timestamp else Long.MAX_VALUE
        }
        val speedAge = lastSpeedAt?.let { timestamp ->
            if (now >= timestamp) now - timestamp else Long.MAX_VALUE
        }
        val sensorAge = (lastSensorEventAt ?: sensorRegisteredAt)?.let { timestamp ->
            if (now >= timestamp) now - timestamp else Long.MAX_VALUE
        }
        val gpsFresh = locationAge != null &&
            locationAge <= AccidentDetector.RECENT_LOCATION_WINDOW_MS &&
            isGpsProviderEnabled(this)
        val speedFresh = speedAge != null && speedAge <= AccidentDetector.RECENT_LOCATION_WINDOW_MS
        val sensorFresh = sensorsRegistered && sensorAccuracyReliable &&
            sensorAge != null && sensorAge <= SENSOR_STALE_MS
        val orientationAvailable = orientationConfigured && gravityAttitudeSensor != null
        val orientationDetail = when {
            !orientationConfigured -> "Desactivada en configuracion"
            gravityAttitudeSensor == null -> "Sin sensor de gravedad: confirmacion de caida no disponible"
            !orientationCalibrated -> "Gravedad: calibrando actitud de conduccion..."
            else -> orientationTiltDegrees?.let {
                "Inclinacion ${it.toInt()} grados; caida si se mantiene >= ${AccidentDetector.FALL_TILT_THRESHOLD_DEGREES.toInt()} grados 4 s"
            } ?: orientationDescription
        }

        SafetyStateStore.update {
            it.copy(
                gpsAvailable = gpsFresh,
                gpsAccuracyMeters = lastAccuracy,
                gpsFixAgeSeconds = locationAge?.takeIf { age -> age != Long.MAX_VALUE }?.div(1_000L),
                speedKmh = lastSpeed.takeIf { speedFresh },
                sensorAvailable = sensorFresh,
                sensorDetail = if (sensorFresh) sensorDescription else "$sensorDescription: sin datos recientes",
                orientationAvailable = orientationAvailable,
                orientationTiltDegrees = orientationTiltDegrees,
                orientationDetail = orientationDetail
            )
        }
    }

    private fun updateMovementLevel(level: Float, dynamicG: Float) {
        val now = SystemClock.elapsedRealtime()
        movementLevel = maxOf(level, movementLevel * MOVEMENT_DECAY)
        if (now - lastMovementUpdateAt < MOVEMENT_UPDATE_INTERVAL_MS) return
        // Standing still keeps producing the same rounded values; publishing them
        // would wake the UI five times per second for no visible change.
        val roundedLevel = (movementLevel * 100f).toInt()
        val roundedG = (dynamicG * 10f).toInt()
        if (roundedLevel == lastPublishedMovementLevel && roundedG == lastPublishedMovementG) return
        lastMovementUpdateAt = now
        lastPublishedMovementLevel = roundedLevel
        lastPublishedMovementG = roundedG
        SafetyStateStore.update {
            it.copy(movementLevel = movementLevel, movementG = dynamicG)
        }
    }

    private fun playCountdownBeepIfDue(now: Long, end: Long?) {
        if (end == null || now >= end) return
        val remaining = end - now
        val interval = countdownBeepIntervalMs(remaining)
        if (interval == null) {
            if (remaining > FIRST_BEEP_REMAINING_MS) nextBeepAt = end - FIRST_BEEP_REMAINING_MS
            return
        }
        val scheduled = nextBeepAt ?: now
        if (now >= scheduled) {
            val played = runCatching {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_DURATION_MS)
            }.isSuccess
            if (!played) SafetyStateStore.log("No se pudo reproducir el pitido")
            nextBeepAt = now + interval
        }
    }

    private fun acquireCountdownWakeLock(durationMs: Long) {
        val boundedDuration = durationMs.coerceIn(WAKE_LOCK_GRACE_MS, COUNTDOWN_MS + WAKE_LOCK_GRACE_MS)
        if (countdownWakeLock.isHeld) countdownWakeLock.release()
        countdownWakeLock.acquire(boundedDuration)
    }

    private fun releaseCountdownWakeLock() {
        if (::countdownWakeLock.isInitialized && countdownWakeLock.isHeld) countdownWakeLock.release()
    }

    private fun startLocationForeground(text: String): Boolean = startForegroundSafely(
        mode = ForegroundMode.LOCATION,
        text = text,
        type = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
    )

    private fun startSpecialForeground(text: String): Boolean = startForegroundSafely(
        mode = ForegroundMode.SPECIAL,
        text = text,
        type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
    )

    private fun startEmergencyForeground(text: String): Boolean =
        if (monitoringEnabled && canUsePreciseLocation()) {
            // Fall back to specialUse when the OS rejects a background
            // location foreground service; the emergency protocol must not
            // die because live GPS is unavailable.
            startLocationForeground(text) || startSpecialForeground(text)
        } else {
            startSpecialForeground(text)
        }

    private fun restoredCountdownEnd(): Long? {
        val elapsedEnd = countdownEndsAt
        val wallEnd = countdownEndsAtWallTime ?: return elapsedEnd?.takeIf {
            it - SystemClock.elapsedRealtime() <= MAX_RESTORED_COUNTDOWN_FUTURE_MS
        }
        val remaining = wallEnd - System.currentTimeMillis()
        if (remaining < -MAX_RESTORED_COUNTDOWN_LATENESS_MS) return null
        if (remaining > MAX_RESTORED_COUNTDOWN_FUTURE_MS) return null
        return SystemClock.elapsedRealtime() + remaining.coerceAtLeast(0L)
    }

    private fun sosAlarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        this,
        SOS_ALARM_REQUEST_CODE,
        Intent(this, SosAlarmReceiver::class.java).setAction(SosAlarmReceiver.ACTION_COMPLETE_SOS),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun scheduleSosAlarm() {
        val triggerAt = countdownEndsAtWallTime ?: return
        if (!scheduleExactOrInexact(triggerAt, sosAlarmIntent(), "respaldo de cuenta atras")) {
            SafetyStateStore.log("No se pudo programar respaldo de cuenta atras")
        }
    }

    private fun cancelSosAlarm() {
        if (!::alarmManager.isInitialized) return
        runCatching { alarmManager.cancel(sosAlarmIntent()) }
    }

    private fun startForegroundSafely(
        mode: ForegroundMode,
        text: String,
        type: Int
    ): Boolean {
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(text), type)
            foregroundMode = mode
            true
        } catch (error: SecurityException) {
            SafetyStateStore.log("Inicio de servicio rechazado: ${error.message.orEmpty()}")
            false
        } catch (error: IllegalArgumentException) {
            SafetyStateStore.log("Tipo de servicio no valido: ${error.message.orEmpty()}")
            false
        } catch (error: RuntimeException) {
            SafetyStateStore.log("Android rechazo el servicio: ${error.message.orEmpty()}")
            false
        }
    }

    private fun updateNotification(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotificationUpdateAt < NOTIFICATION_INTERVAL_MS) return
        val snapshot = SafetyStateStore.snapshot.value
        val text = when (snapshot.phase) {
            SafetyPhase.COUNTDOWN -> {
                val seconds = countdownEndsAt?.let { remainingSeconds(it, now) } ?: 0L
                "SOS en ${seconds}s; pulsa CANCELAR si estas bien"
            }
            SafetyPhase.VALIDATING -> "Validando posible impacto"
            SafetyPhase.FALL_CANDIDATE -> "POSIBLE CAIDA: verificando inclinacion"
            else -> "Monitor de seguridad activo"
        }
        if (!force && text == lastNotificationText) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
        lastNotificationText = text
        lastNotificationUpdateAt = now
    }

    private fun notification(text: String, onlyAlertOnce: Boolean = true): Notification {
        val emergency = countdownEndsAt != null
        val channelId = if (emergency) EMERGENCY_CHANNEL_ID else MONITOR_CHANNEL_ID
        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(if (emergency) "Segu-Luky: SOS ACTIVO" else "Segu-Luky")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setOngoing(true)
            .setOnlyAlertOnce(onlyAlertOnce)
            .setCategory(if (emergency) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_SERVICE)
            .setPriority(if (emergency) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )

        if (emergency) {
            val cancelIntent = Intent(this, SafetyMonitorService::class.java).setAction(ACTION_CANCEL)
            val cancelPendingIntent = PendingIntent.getForegroundService(
                this,
                1,
                cancelIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "CANCELAR", cancelPendingIntent)
        }
        return builder.build()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                MONITOR_CHANNEL_ID,
                "Monitor de seguridad",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                EMERGENCY_CHANNEL_ID,
                "Alertas SOS",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Cuenta atras y acciones de emergencia"
                enableVibration(true)
            }
        )
    }

    private fun failAndStop(message: String) {
        // Don't erase a persisted countdown - the SOS is still active
        if (countdownEndsAt != null) {
            SafetyStateStore.log("Error durante cuenta atras: $message")
            SafetyStateStore.update { it.copy(detail = message) }
            return
        }
        monitoringEnabled = false
        wasMonitoringBeforeEmergency = false
        runtimeRepository.clearActiveState()
        SafetyStateStore.update {
            it.copy(
                phase = SafetyPhase.STOPPED,
                detail = message,
                validationStartedAt = null,
                immobileSince = null,
                countdownEndsAt = null
            )
        }
        SafetyStateStore.log(message)
        if (foregroundMode != ForegroundMode.NONE) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    private val window by lazy { android.os.Handler(Looper.getMainLooper()) }

    private enum class ForegroundMode { NONE, LOCATION, SPECIAL }

    companion object {
        private const val MONITOR_CHANNEL_ID = "interseg_monitor"
        private const val EMERGENCY_CHANNEL_ID = "interseg_emergency_v2"
        private const val NOTIFICATION_ID = 1001
        private const val EMERGENCY_ALERT_NOTIFICATION_ID = 1002
        private const val COUNTDOWN_MS = 30_000L
        private const val FIRST_BEEP_REMAINING_MS = 20_000L
        private const val WAKE_LOCK_GRACE_MS = 5_000L
        private const val TICK_COUNTDOWN_INTERVAL_MS = 100L
        private const val TICK_MONITOR_INTERVAL_MS = 5_000L
        private const val SNAPSHOT_INTERVAL_MS = 1_000L
        private const val NOTIFICATION_INTERVAL_MS = 1_000L
        private const val MOVEMENT_UPDATE_INTERVAL_MS = 200L
    private const val RUNTIME_SYNC_INTERVAL_MS = 250L
        private const val MOVEMENT_DECAY = 0.78f
        private const val BEEP_DURATION_MS = 120
        private const val LOCATION_INTERVAL_INITIAL_MS = 2_000L
        private const val LOCATION_INTERVAL_ROUTINE_MS = 20_000L
        private const val LOCATION_INTERVAL_VALIDATION_MS = 2_000L
        private const val LOCATION_MIN_DISTANCE_ROUTINE_M = 25f
        /** Keeps a stationary last fix usable for the map link after routine GPS slows down. */
        private const val MAP_LINK_LOCATION_WINDOW_MS = 5 * 60_000L
        private const val LOCATION_WALL_TIME_PERSIST_INTERVAL_MS = 15_000L
        private const val SENSOR_BATCH_LATENCY_US = 250_000
        private const val FALL_SUSTAINED_TILT_MS = 4_000L
        private const val FALL_CALIBRATION_MS = 2_000L
        /** Keeps the screen awake while a fall candidate may still be confirmed. */
        private const val CANDIDATE_SCREEN_WAKE_MS = 20_000L
        private const val WHATSAPP_SCREEN_WAKE_MS = 40_000L
        private const val TELEGRAM_BOT_WAKE_LOCK_MS = 120_000L
        private const val UPRIGHT_ABORT_SAMPLES = 12
        private const val SENSOR_STALE_MS = 5_000L
        private const val MAX_RESTORED_COUNTDOWN_LATENESS_MS = 2 * 60_000L
        private const val MAX_RESTORED_COUNTDOWN_FUTURE_MS = 60_000L
        private const val SOS_ALARM_REQUEST_CODE = 2001
        private const val MANUAL_WHATSAPP_REQUEST_CODE = 2002
        private const val MANUAL_WHATSAPP_NOTIFICATION_ID = 1003
        private const val MANUAL_TELEGRAM_REQUEST_CODE = 2012
        private const val MANUAL_TELEGRAM_NOTIFICATION_ID = 1013
        private const val MAX_MANUAL_WHATSAPP_NOTIFICATIONS = 5
        private const val ESCALATION_ALARM_REQUEST_CODE = 2003
        private const val CALL_ESCALATION_TIMEOUT_MS = 60_000L
        private const val ESCALATION_END_RETRY_MS = 10_000L
        private const val DISPATCH_WATCHDOG_REQUEST_CODE = 2004
        private const val DISPATCH_WATCHDOG_INITIAL_MS = 10_000L
        private const val DISPATCH_WATCHDOG_RETRY_MS = 10_000L
        private const val DISPATCH_WATCHDOG_MAX_AGE_MS = 10 * 60_000L
        private const val MAX_QUALIFIED_GPS_ACCURACY_METERS = 100f
        private const val MAX_SPEED_ACCURACY_MS = 5f
        private const val ACTION_START = "com.intercom.segluky.START"
        internal const val ACTION_ALARM_COMPLETE = "com.intercom.segluky.ALARM_COMPLETE"
        internal const val ACTION_WHATSAPP_ADVANCE = "com.intercom.segluky.WHATSAPP_ADVANCE"
        internal const val ACTION_RELOAD_CONFIG = "com.intercom.segluky.RELOAD_CONFIG"
        internal const val ACTION_ESCALATE = "com.intercom.segluky.ESCALATE"
        internal const val ACTION_DISPATCH_WATCHDOG = "com.intercom.segluky.DISPATCH_WATCHDOG"
        private const val ACTION_CONFIRM = "com.intercom.segluky.CONFIRM"
        private const val ACTION_CANCEL = "com.intercom.segluky.CANCEL"
        private const val ACTION_STOP = "com.intercom.segluky.STOP"
        private const val ACTION_SET_SENSITIVITY = "com.intercom.segluky.SET_SENSITIVITY"
        private const val EXTRA_SENSITIVITY_G = "sensitivity_g"
        private const val PREFERENCES = "segu_prefs"
        private const val KEY_SENSITIVITY_G = "sensitivity_g"
        private const val DEFAULT_SENSITIVITY_G = 3.5f
        private const val KEY_FALL_ORIENTATION_GATE = "fall_orientation_gate"
        private const val KEY_VOICE_KEYWORDS_GATE = "voice_keywords_gate"
        private const val KEY_SMS_AUTO = "sms_sos_auto"

        fun start(context: Context) {
            send(context, ACTION_START)
        }

        fun stop(context: Context) {
            SafetyRuntimeRepository(context).apply {
                setMonitoringRequested(false)
                clearActiveState()
            }
            context.stopService(Intent(context, SafetyMonitorService::class.java))
            SafetyStateStore.reset()
        }

        fun confirm(context: Context) = send(context, ACTION_CONFIRM)
        fun cancel(context: Context) = send(context, ACTION_CANCEL)
        fun notifyConfigChanged(context: Context) = send(context, ACTION_RELOAD_CONFIG)

        fun saveSensitivity(context: Context, g: Float) {
            val value = g.coerceIn(AccidentDetector.MIN_SENSITIVITY_G, AccidentDetector.MAX_SENSITIVITY_G)
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putFloat(KEY_SENSITIVITY_G, value).apply()
        }

        fun setSensitivity(context: Context, g: Float) {
            saveSensitivity(context, g)
        }

        fun loadSensitivity(context: Context): Float =
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getFloat(KEY_SENSITIVITY_G, DEFAULT_SENSITIVITY_G)
                .coerceIn(AccidentDetector.MIN_SENSITIVITY_G, AccidentDetector.MAX_SENSITIVITY_G)

        fun canScheduleExactAlarms(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                (context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: false)

        fun loadFallOrientationGate(context: Context): Boolean =
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(KEY_FALL_ORIENTATION_GATE, true)

        fun saveFallOrientationGate(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_FALL_ORIENTATION_GATE, enabled).apply()
        }

        fun loadVoiceKeywordsGate(context: Context): Boolean =
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(KEY_VOICE_KEYWORDS_GATE, false)

        fun loadSmsAuto(context: Context): Boolean =
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(KEY_SMS_AUTO, false)

        fun saveSmsAuto(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_SMS_AUTO, enabled).apply()
        }

        fun saveVoiceKeywordsGate(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_VOICE_KEYWORDS_GATE, enabled).apply()
        }

        fun hasPreciseLocationPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        fun isGpsProviderEnabled(context: Context): Boolean = runCatching {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }.getOrDefault(false)

        private fun remainingSeconds(end: Long, now: Long): Long =
            ((end - now).coerceAtLeast(0L) + 999L) / 1_000L

        private fun send(context: Context, action: String) {
            val intent = Intent(context, SafetyMonitorService::class.java).setAction(action)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (error: RuntimeException) {
                SafetyStateStore.update {
                    it.copy(
                        phase = SafetyPhase.STOPPED,
                        detail = "Android rechazo el inicio: ${error.message.orEmpty()}"
                    )
                }
            }
        }
    }
}
