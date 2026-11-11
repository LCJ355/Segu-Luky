package com.intercom.segluky

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Trampoline used when the SOS has to open WhatsApp with the screen locked.
 *
 * Shows nothing, turns the screen on and asks the system to dismiss the keyguard:
 * with no PIN/pattern the screen unlocks by itself and the accessibility service
 * can tap SEND. With a secure lock the request is refused and the WhatsApp intent
 * is started anyway, so the chat is already on screen when the user unlocks.
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

        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true) {
            val secureLock = keyguard.isDeviceSecure
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = launchTarget(target)

                override fun onDismissError() = launchTarget(target)

                override fun onDismissCancelled() {
                    if (secureLock) {
                        SafetyStateStore.log(
                            "WhatsApp autoeenvio: pantalla bloqueada con PIN, el mensaje queda abierto"
                        )
                    }
                    launchTarget(target)
                }
            })
            return
        }
        launchTarget(target)
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
        private const val TAG = "SeguLukyWhatsAppUnlock"
        private const val FINISH_DELAY_MS = 400L
    }
}
