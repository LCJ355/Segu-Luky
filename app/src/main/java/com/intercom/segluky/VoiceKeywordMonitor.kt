package com.intercom.segluky

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Listens for the emergency words AYUDA / SOCORRO using the microphone while the
 * foreground service is running, so it works with the screen off. The capture runs
 * on a dedicated thread; SpeechRecognizer callbacks arrive on the main handler.
 *
 * The capture thread is started lazily and can be recreated after a device error
 * or after the user enables the feature while the monitor is already running.
 */
class VoiceKeywordMonitor(
    private val onKeyword: (canonical: String) -> Unit,
    private val onState: (levelDb: Float?, recognizing: Boolean, detail: String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recorderHolder = AtomicReference<AudioRecord?>(null)
    private val captureLock = Any()
    private var recognizer: SpeechRecognizer? = null
    private var applicationContext: Context? = null
    private var started = false
    private var captureActive = false

    @Volatile private var destroyed = false
    @Volatile private var watcherPaused = false
    @Volatile private var levelDb: Float? = null
    @Volatile private var recognizing = false
    @Volatile private var nextSessionAllowedAt = 0L
    @Volatile private var enabledByService = false
    @Volatile private var permissionGranted = false

    private var lastTriggerAt = 0L
    private var lastRmsUiAt = 0L
    private var lastStateAt = 0L
    private var sessionTimeout: Runnable? = null

    fun start(context: Context, enabled: Boolean, hasMicPermission: Boolean) {
        applicationContext = context.applicationContext
        if (started) {
            updateGates(enabled, hasMicPermission)
            return
        }
        started = true
        destroyed = false
        enabledByService = enabled
        permissionGranted = hasMicPermission
        ensureRecognizer(context)
        publish(enabled, hasMicPermission)
        if (enabled && hasMicPermission) ensureCaptureThread()
        publishState()
    }

    /**
     * One microphone session. Returns true when it ended because the monitor was
     * switched off, false when a device error ended it and a retry is warranted.
     * An unhandled throw here would silently kill the only voice watchdog thread,
     * which is why the body is wrapped by the caller.
     */
    @SuppressLint("MissingPermission")
    private fun captureLoop(): Boolean {
        if (!enabledByService || !permissionGranted) return true
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (bufferSize <= 0) return false
        val recorder = runCatching {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        }.getOrNull() ?: return false
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return false
        }
        recorderHolder.set(recorder)
        try {
            val startedRecording = runCatching {
                recorder.startRecording()
                recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING
            }.getOrDefault(false)
            if (!startedRecording) return false

            // Reading a fixed window keeps the per-sample work predictable even
            // when a device reports a very large minimum buffer.
            val samples = ShortArray(((bufferSize / 2).coerceIn(1, MAX_READ_SAMPLES)))
            var wasPaused = false
            var shoutSince: Long? = null
            while (!destroyed && enabledByService && permissionGranted) {
                if (watcherPaused != wasPaused) {
                    wasPaused = watcherPaused
                    val switched = runCatching {
                        if (wasPaused) recorder.stop() else recorder.startRecording()
                    }.isSuccess
                    if (!switched) return false
                }
                if (wasPaused) {
                    Thread.sleep(100L)
                    continue
                }
                val count = recorder.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                if (count < 0) {
                    // The recorder is gone or in an invalid state: let the outer
                    // loop release it and start a clean session.
                    return false
                }
                if (count == 0) {
                    Thread.sleep(50L)
                    continue
                }
                var sum = 0.0
                repeat(count) { index -> val s = samples[index].toDouble(); sum += s * s }
                val rms = sqrt(sum / count)
                val db = (20.0 * log10((rms / Short.MAX_VALUE.toDouble()).coerceAtLeast(MIN_RATIO)))
                    .coerceIn(MIN_DB, 0.0).toFloat()
                levelDb = db
                val now = SystemClock.uptimeMillis()
                if (!recognizing && now >= nextSessionAllowedAt && db >= SHOUT_THRESHOLD_DB) {
                    if (shoutSince == null) shoutSince = now
                    if (now - shoutSince >= SHOUT_HOLD_MS) {
                        shoutSince = null
                        mainHandler.post { beginRecognitionSession() }
                    }
                } else {
                    shoutSince = null
                }
                if (now - lastStateAt >= STATE_THROTTLE_MS) {
                    lastStateAt = now
                    publishState()
                }
            }
            // Gated off, not broken: do not burn retries on a deliberate stop.
            return true
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            recorderHolder.set(null)
            levelDb = null
        }
    }

    fun updateGates(enabled: Boolean, hasMicPermission: Boolean) {
        enabledByService = enabled
        permissionGranted = hasMicPermission
        if (!started) return
        ensureRecognizer(applicationContext)
        if (!enabled || !hasMicPermission) {
            mainHandler.post { endSession() }
        } else {
            ensureCaptureThread()
        }
        publish(enabled, hasMicPermission)
    }

    fun stop() {
        destroyed = true
        watcherPaused = true
        started = false
        mainHandler.removeCallbacksAndMessages(null)
        sessionTimeout?.let(mainHandler::removeCallbacks)
        sessionTimeout = null
        runCatching { recognizer?.cancel() }
        recognizer?.destroy()
        recognizer = null
        recorderHolder.get()?.let { recorder ->
            runCatching { recorder.stop() }
            recorder.release()
            recorderHolder.set(null)
        }
        levelDb = null
    }

    private fun ensureRecognizer(context: Context?) {
        if (recognizer != null || context == null) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return
        recognizer = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
        recognizer?.setRecognitionListener(recognitionListener)
    }

    private fun ensureCaptureThread() {
        synchronized(captureLock) {
            if (captureActive || destroyed || !started) return
            captureActive = true
        }
        Thread {
            var attempt = 0
            while (!destroyed) {
                val captured = runCatching { captureLoop() }
                    .onFailure { error ->
                        SafetyStateStore.log(
                            "Voz: captura interrumpida (${error.javaClass.simpleName}); reintentando"
                        )
                    }
                    .getOrDefault(false)
                if (destroyed || captured) break
                attempt++
                if (attempt > MAX_CAPTURE_RETRIES) {
                    SafetyStateStore.log("Voz: microfono no disponible; activacion por voz detenida")
                    onState(null, false, "NO DISPONIBLE")
                    break
                }
                Thread.sleep(CAPTURE_RETRY_DELAY_MS)
            }
            levelDb = null
            synchronized(captureLock) { captureActive = false }
        }.apply {
            name = "segu-luky-voice"
            isDaemon = true
            start()
        }
    }

    private fun beginRecognitionSession() {
        val activeRecognizer = recognizer ?: run {
            publishState()
            return
        }
        if (destroyed || recognizing || !enabledByService || !permissionGranted) return
        if (!SafetyStateStore.snapshot.value.phase.acceptsVoiceEmergency()) return
        recognizing = true
        watcherPaused = true
        publishState()
        mainHandler.postDelayed({
            if (destroyed || !recognizing) return@postDelayed
            try {
                activeRecognizer.startListening(voiceIntent())
                sessionTimeout = Runnable { endSession() }.also {
                    mainHandler.postDelayed(it, SESSION_TIMEOUT_MS)
                }
            } catch (_: RuntimeException) {
                endSession()
            }
        }, MIC_RELEASE_DELAY_MS)
    }

    private fun endSession() {
        if (!recognizing) return
        recognizing = false
        nextSessionAllowedAt = SystemClock.uptimeMillis() + SESSION_COOLDOWN_MS
        watcherPaused = false
        sessionTimeout?.let(mainHandler::removeCallbacks)
        sessionTimeout = null
        runCatching { recognizer?.cancel() }
        publishState()
    }

    private fun detectKeyword(bundle: Bundle?): Boolean {
        if (bundle == null || destroyed || !enabledByService || !permissionGranted) return false
        if (!SafetyStateStore.snapshot.value.phase.acceptsVoiceEmergency()) return false
        val canonical = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            .orEmpty()
            .asSequence()
            .mapNotNull(::emergencyWordCanonical)
            .firstOrNull()
            ?: return false
        val now = SystemClock.uptimeMillis()
        if (now - lastTriggerAt < TRIGGER_COOLDOWN_MS) return true
        lastTriggerAt = now
        SafetyStateStore.log("Voz: \"$canonical\" detectada")
        onKeyword(canonical)
        return true
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsDb: Float) {
            if (!recognizing) return
            val now = SystemClock.uptimeMillis()
            if (now - lastRmsUiAt < RMS_UI_THROTTLE_MS) return
            lastRmsUiAt = now
            onState(levelDb, true, "${rmsDb.toInt()} dB")
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partial: Bundle?) {
            if (detectKeyword(partial)) endSession()
        }

        override fun onResults(results: Bundle?) {
            detectKeyword(results)
            mainHandler.postDelayed({ endSession() }, 250L)
        }

        override fun onError(error: Int) {
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                recognizing = false
                watcherPaused = false
                onState(null, false, "SIN PERMISO DE MICROFONO")
                return
            }
            endSession()
        }
    }

    private fun publish(enabled: Boolean, hasMicPermission: Boolean) {
        val detail = when {
            !enabled -> "Voz desactivada"
            !hasMicPermission -> "SIN PERMISO DE MICROFONO"
            recognizer == null && started -> "NO DISPONIBLE"
            else -> "ESCUCHANDO AYUDA / SOCORRO"
        }
        onState(levelDb, false, detail)
    }

    private fun publishState() {
        val detail = when {
            !enabledByService -> "Voz desactivada"
            !permissionGranted -> "SIN PERMISO DE MICROFONO"
            recognizing -> "ESCUCHANDO..."
            recognizer == null -> "NO DISPONIBLE"
            else -> levelDb?.let { "${it.toInt()} dB" } ?: "EN ESPERA"
        }
        onState(levelDb, recognizing, detail)
    }

    private fun voiceIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val MIN_RATIO = 0.000001
        private const val MIN_DB = -60.0
        private const val SHOUT_THRESHOLD_DB = -22f
        private const val SHOUT_HOLD_MS = 300L
        private const val SESSION_COOLDOWN_MS = 2_500L
        private const val TRIGGER_COOLDOWN_MS = 20_000L
        private const val SESSION_TIMEOUT_MS = 8_000L
        private const val MIC_RELEASE_DELAY_MS = 250L
        private const val STATE_THROTTLE_MS = 150L
        private const val RMS_UI_THROTTLE_MS = 120L
        private const val MAX_READ_SAMPLES = 8_192
        private const val CAPTURE_RETRY_DELAY_MS = 3_000L
        private const val MAX_CAPTURE_RETRIES = 5
        private val ACCEPTED = setOf("ayuda", "socorro")

        private fun normalize(text: String): List<String> {
            val cleaned = text.lowercase(Locale.ROOT)
                .let { Normalizer.normalize(it, Normalizer.Form.NFD) }
                .replace(Regex("\\p{Mn}+"), "")
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()
            if (cleaned.isEmpty()) return emptyList()
            return cleaned.split(' ').filter { it.isNotEmpty() }
        }

        /** Returns the canonical keyword ("ayuda"|"socorro") if the phrase contains it as a whole word. */
        fun emergencyWordCanonical(text: String): String? {
            if (text.isBlank()) return null
            val tokens = normalize(text)
            tokens.forEach { token ->
                ACCEPTED.firstOrNull { it == token }?.let { return it }
                if (token == "socoro") return "socorro"
            }
            return null
        }
    }
}

private fun SafetyPhase.acceptsVoiceEmergency(): Boolean =
    this == SafetyPhase.MONITORING || this == SafetyPhase.VALIDATING || this == SafetyPhase.FALL_CANDIDATE
