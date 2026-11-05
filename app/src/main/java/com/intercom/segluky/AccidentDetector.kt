package com.intercom.segluky

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

class AccidentDetector(
    private val listener: Listener,
    validationDurationMs: Long = 5 * 60 * 1000L,
    validationMaxDurationMs: Long = validationDurationMs * 2,
    private val now: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    interface Listener {
        fun onValidationStarted(startedAt: Long)
        fun onValidationExpired()
        fun onAutomaticEmergency()
        fun onManualEmergency(reason: String)
        fun onVibrationDetected()
        fun onMotionDetected()
        fun onFallCandidateArmed() = Unit
        fun onFallCandidateExpired() = Unit
        fun onFallCandidateAborted() = Unit
    }

    data class RuntimeState(
        val phase: SafetyPhase,
        val validationStartedAt: Long?,
        val immobileSince: Long?
    )

    private val validationDurationMs = validationDurationMs.coerceAtLeast(1L)
    private val validationMaxDurationMs = validationMaxDurationMs.coerceAtLeast(this.validationDurationMs)

    private var phase = SafetyPhase.MONITORING
    private var validationStartedAt: Long? = null
    private var immobileSince: Long? = null
    private var candidateDeadlineAt: Long? = null
    private var lastStrongHitAt = UNSET_TIME
    private var fallConfirmationEnabled = false
    private var lastVibrationAt = UNSET_TIME
    private var sideTapG = DEFAULT_SIDE_TAP_G
    private var singleSosG = DEFAULT_SINGLE_SOS_G
    private var lastLocationAt = UNSET_TIME
    private var lastDynamicAccelerationAt = UNSET_TIME
    private var lastAccelerometerAt = UNSET_TIME
    private var lastGyroscopeAt = UNSET_TIME

    private var gravityInitialized = false
    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f
    private var gravityUpdatedAt = UNSET_TIME

    /** Sets all user-facing movement thresholds in dynamic g (gravity removed). */
    fun setGolpeSensitivity(g: Float) {
        if (!g.isFinite()) return
        val sensitivity = g.coerceIn(MIN_SENSITIVITY_G, MAX_SENSITIVITY_G)
        sideTapG = sensitivity
        singleSosG = sensitivity
    }

    /**
     * When enabled, a single isolated strong hit becomes a fall candidate that
     * only escalates to the countdown after a sustained crash tilt is confirmed.
     * A deliberate shake (two strong hits within the shake window) still starts
     * the countdown immediately. When disabled, every strong hit is immediate SOS.
     */
    fun setFallOrientationGateEnabled(enabled: Boolean) {
        fallConfirmationEnabled = enabled
        if (!enabled && phase == SafetyPhase.FALL_CANDIDATE) {
            resumeMonitoring()
        }
    }

    /** Feeds a gravity-based attitude sample used to calibrate the accelerometer fallback. */
    fun onGravitySensor(x: Float, y: Float, z: Float, timestamp: Long = now()) {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite() || timestamp < 0L) return
        gravityX = x
        gravityY = y
        gravityZ = z
        gravityInitialized = true
        gravityUpdatedAt = timestamp
    }

    /**
     * Confirms a fall from the orientation tracker. The sustained-tilt reading is
     * only honoured while a single-hit candidate is armed and inside its window.
     */
    fun onFallTilt(sustained: Boolean, timestamp: Long = now()): Boolean {
        if (!fallConfirmationEnabled || phase != SafetyPhase.FALL_CANDIDATE) return false
        val deadline = candidateDeadlineAt ?: return false
        if (!sustained || timestamp > deadline || timestamp < 0L) return false
        candidateDeadlineAt = null
        lastStrongHitAt = UNSET_TIME
        startCountdown()
        listener.onManualEmergency("caida de moto confirmada")
        return true
    }

    /** Aborts an armed candidate when the phone clearly returns upright or riding resumes. */
    fun abortFallCandidate() {
        if (phase != SafetyPhase.FALL_CANDIDATE) return
        resumeMonitoring()
        listener.onFallCandidateAborted()
    }

    fun onLocation(
        speedKmh: Float?,
        timestamp: Long = now(),
        qualifiedFix: Boolean = true
    ) {
        if (!qualifiedFix || timestamp < 0L) return
        if (lastLocationAt != UNSET_TIME && timestamp < lastLocationAt) return
        lastLocationAt = timestamp

        val validSpeed = speedKmh?.takeIf { it.isFinite() && it >= 0f }
        if (validSpeed != null &&
            phase == SafetyPhase.VALIDATING &&
            validSpeed > IMMOBILE_MAX_SPEED_KMH
        ) {
            markMoving(timestamp)
        }
        if (phase == SafetyPhase.FALL_CANDIDATE &&
            validSpeed != null &&
            validSpeed >= RIDING_RESUME_SPEED_KMH
        ) {
            abortFallCandidate()
        }
    }

    fun clearLocation() {
        lastLocationAt = UNSET_TIME
    }

    /**
     * Fallback for devices without TYPE_LINEAR_ACCELERATION. A low-pass gravity
     * estimate is removed before the sample enters the canonical detector.
     */
    fun onAccelerometer(x: Float, y: Float, z: Float, timestamp: Long = now()): Float? {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite() || timestamp < 0L) return null
        if (lastAccelerometerAt != UNSET_TIME && timestamp < lastAccelerometerAt) return null
        val magnitude = vectorMagnitude(x, y, z) ?: return null
        lastAccelerometerAt = timestamp

        if (!gravityInitialized) {
            val magnitudeG = magnitude / STANDARD_GRAVITY
            if (magnitudeG in GRAVITY_INITIALIZATION_MIN_G..GRAVITY_INITIALIZATION_MAX_G) {
                initializeGravity(x, y, z, timestamp)
                return null
            }
            if (magnitudeG > GRAVITY_INITIALIZATION_MAX_G) {
                val scale = ((magnitude - STANDARD_GRAVITY) / magnitude).coerceAtLeast(0f)
                return onDynamicAcceleration(x * scale, y * scale, z * scale, timestamp)
            }
            return null
        }

        val dynamicX = x - gravityX
        val dynamicY = y - gravityY
        val dynamicZ = z - gravityZ
        val residualMagnitude = vectorMagnitude(dynamicX, dynamicY, dynamicZ) ?: return null
        val gravityMagnitude = vectorMagnitude(gravityX, gravityY, gravityZ) ?: return null
        val stableGravityMagnitude = abs(magnitude - gravityMagnitude) / STANDARD_GRAVITY <=
            GRAVITY_MAGNITUDE_TOLERANCE_G
        val likelyOrientationChange = stableGravityMagnitude &&
            residualMagnitude / STANDARD_GRAVITY > GRAVITY_ADAPTATION_MAX_DYNAMIC_G

        val dynamicG = if (likelyOrientationChange) {
            onDynamicAcceleration(0f, 0f, 0f, timestamp)
        } else {
            onDynamicAcceleration(dynamicX, dynamicY, dynamicZ, timestamp)
        }
        if (stableGravityMagnitude || dynamicG <= GRAVITY_ADAPTATION_MAX_DYNAMIC_G) {
            adaptGravity(x, y, z, timestamp)
        }
        return dynamicG
    }

    fun onLinearAcceleration(x: Float, y: Float, z: Float, timestamp: Long = now()): Float {
        return onDynamicAcceleration(x, y, z, timestamp)
    }

    internal fun onDynamicAcceleration(x: Float, y: Float, z: Float, timestamp: Long = now()): Float {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite() || timestamp < 0L) return 0f
        if (lastDynamicAccelerationAt != UNSET_TIME && timestamp < lastDynamicAccelerationAt) return 0f
        val startedAt = validationStartedAt
        if (phase == SafetyPhase.VALIDATING && (startedAt == null || timestamp < startedAt)) return 0f
        val magnitude = vectorMagnitude(x, y, z) ?: return 0f
        val dynamicG = magnitude / STANDARD_GRAVITY
        if (!dynamicG.isFinite()) return 0f
        lastDynamicAccelerationAt = timestamp

        val thresholdHit = dynamicG >= singleSosG
        if (thresholdHit && (phase == SafetyPhase.MONITORING ||
                phase == SafetyPhase.VALIDATING ||
                phase == SafetyPhase.FALL_CANDIDATE)
        ) {
            val secondHit = lastStrongHitAt != UNSET_TIME &&
                timestamp >= lastStrongHitAt &&
                timestamp - lastStrongHitAt <= SHAKE_WINDOW_MS
            lastStrongHitAt = timestamp

            if (!fallConfirmationEnabled || secondHit) {
                startCountdown()
                listener.onManualEmergency(
                    if (fallConfirmationEnabled) "sacudida de emergencia" else "golpe fuerte detectado"
                )
                return dynamicG
            }

            notifySignificantMovement(timestamp)
            if (phase == SafetyPhase.MONITORING) {
                phase = SafetyPhase.FALL_CANDIDATE
                candidateDeadlineAt = timestamp + FALL_CANDIDATE_WINDOW_MS
                listener.onFallCandidateArmed()
            } else if (phase == SafetyPhase.FALL_CANDIDATE) {
                candidateDeadlineAt = timestamp + FALL_CANDIDATE_WINDOW_MS
            }
            return dynamicG
        }

        if (dynamicG >= sideTapG) notifySignificantMovement(timestamp)
        if (dynamicG > MOTION_THRESHOLD_G) {
            if (phase == SafetyPhase.VALIDATING) markMoving(timestamp)
        } else if (phase == SafetyPhase.VALIDATING && immobileSince == null) {
            immobileSince = timestamp
        }

        return dynamicG
    }

    fun onGyroscope(x: Float, y: Float, z: Float, timestamp: Long = now()) {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite() || timestamp < 0L) return
        if (lastGyroscopeAt != UNSET_TIME && timestamp < lastGyroscopeAt) return
        val magnitude = vectorMagnitude(x, y, z) ?: return
        if (phase == SafetyPhase.VALIDATING &&
            timestamp >= (validationStartedAt ?: return) &&
            magnitude > GYROSCOPE_MOTION_RAD_S
        ) {
            markMoving(timestamp)
        }
        lastGyroscopeAt = timestamp
    }

    fun tick(timestamp: Long = now()) {
        if (phase == SafetyPhase.FALL_CANDIDATE) {
            val deadline = candidateDeadlineAt
            if (deadline == null || timestamp >= deadline) {
                resumeMonitoring()
                listener.onFallCandidateExpired()
            }
            return
        }
        val startedAt = validationStartedAt ?: return
        if (phase != SafetyPhase.VALIDATING) return
        if (timestamp < startedAt) return

        val stillFor = immobileSince?.let { since -> timestamp - since } ?: 0L
        if (stillFor >= validationDurationMs) {
            startCountdown()
            listener.onAutomaticEmergency()
            return
        }
        if (timestamp - startedAt >= validationMaxDurationMs) {
            resumeMonitoring()
            listener.onValidationExpired()
        }
    }

    fun simulateImpact(timestamp: Long = now()): Boolean {
        return startValidation(timestamp)
    }

    fun confirm(reason: String): Boolean {
        if (phase != SafetyPhase.MONITORING &&
            phase != SafetyPhase.VALIDATING &&
            phase != SafetyPhase.FALL_CANDIDATE
        ) return false
        startCountdown()
        listener.onManualEmergency(reason)
        return true
    }

    fun cancel() {
        if (phase == SafetyPhase.VALIDATING ||
            phase == SafetyPhase.COUNTDOWN ||
            phase == SafetyPhase.FALL_CANDIDATE
        ) {
            resumeMonitoring()
        }
    }

    fun resumeMonitoring() {
        phase = SafetyPhase.MONITORING
        validationStartedAt = null
        immobileSince = null
        candidateDeadlineAt = null
        lastStrongHitAt = UNSET_TIME
    }

    fun restoreRuntime(state: RuntimeState) {
        val currentTime = now()
        val restoredValidationStart = state.validationStartedAt
        val canRestoreValidation = state.phase == SafetyPhase.VALIDATING &&
            currentTime >= 0L &&
            restoredValidationStart != null &&
            restoredValidationStart in 0L..currentTime

        phase = when {
            state.phase == SafetyPhase.COUNTDOWN -> SafetyPhase.COUNTDOWN
            canRestoreValidation -> SafetyPhase.VALIDATING
            else -> SafetyPhase.MONITORING
        }
        validationStartedAt = restoredValidationStart.takeIf { phase == SafetyPhase.VALIDATING }
        immobileSince = if (phase == SafetyPhase.VALIDATING) {
            state.immobileSince?.takeIf {
                it >= restoredValidationStart!! && it <= currentTime
            } ?: currentTime
        } else {
            null
        }
        candidateDeadlineAt = null
        lastStrongHitAt = UNSET_TIME
    }

    fun runtimeState(): RuntimeState = RuntimeState(
        phase = phase,
        validationStartedAt = validationStartedAt,
        immobileSince = immobileSince
    )

    private fun startCountdown() {
        phase = SafetyPhase.COUNTDOWN
        validationStartedAt = null
        immobileSince = null
        candidateDeadlineAt = null
        lastStrongHitAt = UNSET_TIME
    }

    private fun markMoving(timestamp: Long) {
        val startedAt = validationStartedAt ?: return
        if (phase != SafetyPhase.VALIDATING || timestamp < startedAt) return
        if (immobileSince != null) {
            immobileSince = null
            listener.onMotionDetected()
        }
    }

    private fun notifySignificantMovement(timestamp: Long) {
        val canNotify = lastVibrationAt == UNSET_TIME ||
            (timestamp >= lastVibrationAt && timestamp - lastVibrationAt >= VIBRATION_COOLDOWN_MS)
        if (canNotify) {
            lastVibrationAt = timestamp
            listener.onVibrationDetected()
        }
    }

    private fun startValidation(timestamp: Long): Boolean {
        if (phase != SafetyPhase.MONITORING || timestamp < 0L) return false
        phase = SafetyPhase.VALIDATING
        validationStartedAt = timestamp
        immobileSince = timestamp
        listener.onValidationStarted(timestamp)
        return true
    }

    private fun initializeGravity(x: Float, y: Float, z: Float, timestamp: Long) {
        gravityX = x
        gravityY = y
        gravityZ = z
        gravityInitialized = true
        gravityUpdatedAt = timestamp
    }

    private fun adaptGravity(x: Float, y: Float, z: Float, timestamp: Long) {
        val elapsedMs = if (gravityUpdatedAt == UNSET_TIME || timestamp <= gravityUpdatedAt) {
            0L
        } else {
            (timestamp - gravityUpdatedAt).coerceAtMost(MAX_GRAVITY_ADAPTATION_INTERVAL_MS)
        }
        val alpha = if (elapsedMs == 0L) {
            1f
        } else {
            exp(-elapsedMs.toDouble() / GRAVITY_FILTER_TIME_CONSTANT_MS).toFloat()
        }
        gravityX = alpha * gravityX + (1f - alpha) * x
        gravityY = alpha * gravityY + (1f - alpha) * y
        gravityZ = alpha * gravityZ + (1f - alpha) * z
        gravityUpdatedAt = timestamp
    }

    private fun vectorMagnitude(x: Float, y: Float, z: Float): Float? {
        val magnitude = sqrt(
            x.toDouble() * x.toDouble() +
                y.toDouble() * y.toDouble() +
                z.toDouble() * z.toDouble()
        ).toFloat()
        return magnitude.takeIf { it.isFinite() }
    }

    companion object {
        const val MIN_SENSITIVITY_G = 0.5f
        const val MAX_SENSITIVITY_G = 7.0f
        const val STANDARD_GRAVITY = 9.80665f
        const val RECENT_LOCATION_WINDOW_MS = 60_000L
        const val FALL_TILT_THRESHOLD_DEGREES = 55f
        const val FALL_UPRIGHT_ABORT_DEGREES = 25f

        private const val DEFAULT_SIDE_TAP_G = 2.0f
        private const val DEFAULT_SINGLE_SOS_G = 99f
        private const val MOTION_THRESHOLD_G = 0.15f
        private const val GYROSCOPE_MOTION_RAD_S = 2.5f
        private const val IMMOBILE_MAX_SPEED_KMH = 3f
        private const val RIDING_RESUME_SPEED_KMH = 30f
        private const val VIBRATION_COOLDOWN_MS = 10_000L
        private const val SHAKE_WINDOW_MS = 1_200L
        private const val FALL_CANDIDATE_WINDOW_MS = 15_000L
        private const val GRAVITY_INITIALIZATION_MIN_G = 0.75f
        private const val GRAVITY_INITIALIZATION_MAX_G = 1.25f
        private const val GRAVITY_MAGNITUDE_TOLERANCE_G = 0.20f
        private const val GRAVITY_ADAPTATION_MAX_DYNAMIC_G = 0.25f
        private const val GRAVITY_FILTER_TIME_CONSTANT_MS = 190.0
        private const val MAX_GRAVITY_ADAPTATION_INTERVAL_MS = 100L
        private const val UNSET_TIME = Long.MIN_VALUE
    }
}
