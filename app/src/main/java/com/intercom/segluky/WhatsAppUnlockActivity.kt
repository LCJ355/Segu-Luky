package com.intercom.segluky

import android.app.Activity
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

/** Resultado de la peticion al sistema para quitar el bloqueo. */
internal enum class KeyguardOutcome {
    /** Se desbloqueo: el servicio de accesibilidad puede pulsar Enviar. */
    DISMISSED,

    /** El sistema no pudo atender la peticion (no es una decision del usuario). */
    FAILED,

    /** El usuario rechazo el desbloqueo. */
    CANCELLED
}

internal object WhatsAppUnlockPolicy {
    /**
     * Abrir el chat despues de que el usuario cancele seria hacer algo que acaba de
     * rechazar, y el mensaje quedaria encima de la pantalla de bloqueo. Se respeta
     * la cancelacion; para no perder el aviso se deja una notificacion manual.
     */
    fun launchTarget(outcome: KeyguardOutcome): Boolean = outcome != KeyguardOutcome.CANCELLED

    /** Motivo con el que se cierra el aviso en la cola de autoeenvio. */
    const val CANCELLED_DETAIL = "desbloqueo cancelado por el usuario"
}

/**
 * Trampoline used when the SOS has to open WhatsApp (or Telegram) with the screen locked.
 *
 * Shows nothing, turns the screen on and asks the system to dismiss the keyguard: with no
 * PIN/pattern the screen unlocks by itself and the accessibility service can tap SEND. With a
 * secure lock the request is refused and the chat opens anyway, so it is already on screen when
 * the user unlocks.
 *
 * Si el usuario **cancela**, el chat no se abre ([WhatsAppUnlockPolicy]): se cierra el aviso en la
 * cola de autoeenvio para que la ronda siga con el resto de contactos y se deja una notificacion
 * manual, que es lo que el usuario encontrara al desbloquear.
 */
class WhatsAppUnlockActivity : Activity() {
    private var launched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        @Suppress("DEPRECATION")
        val target = intent.getParcelableExtra(EXTRA_TARGET) as? Intent
        if (target == null) {
            finish()
            return
        }
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)

        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true) {
            keyguard.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() =
                        onOutcome(KeyguardOutcome.DISMISSED, target, requestId)

                    override fun onDismissError() =
                        onOutcome(KeyguardOutcome.FAILED, target, requestId)

                    override fun onDismissCancelled() =
                        onOutcome(KeyguardOutcome.CANCELLED, target, requestId)
                }
            )
            return
        }
        launchTarget(target)
    }

    private fun onOutcome(outcome: KeyguardOutcome, target: Intent, requestId: String?) {
        if (WhatsAppUnlockPolicy.launchTarget(outcome)) {
            launchTarget(target)
            return
        }
        SafetyStateStore.log(
            "WhatsApp autoeenvio: ${WhatsAppUnlockPolicy.CANCELLED_DETAIL}; no se abre el chat"
        )
        // La cola y la notificacion se tocan fuera del hilo de UI y la actividad se
        // cierra en el acto: no hay ventana que mostrar.
        val appContext = applicationContext
        Thread {
            if (requestId != null) {
                runCatching {
                    val result = WhatsAppAutoSend.markFailed(
                        appContext,
                        requestId,
                        WhatsAppUnlockPolicy.CANCELLED_DETAIL
                    )
                    if (result != WhatsAppQueueMutationResult.UPDATED) {
                        SafetyStateStore.log(
                            "WhatsApp autoeenvio: el aviso cancelado ya no estaba en la cola ($result)"
                        )
                    }
                }.onFailure {
                    Log.w(TAG, "No se pudo cerrar el aviso en la cola: ${it.message.orEmpty()}")
                }
            }
            runCatching { postManualNotice(appContext, target) }
                .onFailure { Log.w(TAG, "No se pudo dejar el aviso manual: ${it.message.orEmpty()}") }
        }.apply {
            isDaemon = true
            name = "segluky-whatsapp-cancelled"
        }.start()
        finish()
    }

    private fun postManualNotice(context: Context, target: Intent) {
        val pending = PendingIntent.getActivity(
            context,
            MANUAL_NOTICE_REQUEST_CODE,
            target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, SafetyMonitorService.EMERGENCY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Segu-Luky: mensaje SOS pendiente")
            .setContentText("Pulsa para abrir el chat y enviar el aviso de emergencia")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pending)
            .addAction(android.R.drawable.ic_menu_send, "ABRIR CHAT", pending)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(MANUAL_NOTICE_ID, notification)
    }

    private fun launchTarget(target: Intent) {
        if (launched) return
        launched = true
        try {
            startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (error: Exception) {
            Log.w(TAG, "No se pudo abrir WhatsApp: ${error.message.orEmpty()}")
        }
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, FINISH_DELAY_MS)
    }

    companion object {
        const val EXTRA_TARGET = "extra_target"
        const val EXTRA_REQUEST_ID = "extra_request_id"
        private const val TAG = "SeguLukyWhatsAppUnlock"
        private const val FINISH_DELAY_MS = 400L
        private const val MANUAL_NOTICE_REQUEST_CODE = 2015
        private const val MANUAL_NOTICE_ID = 1020
    }
}
