package com.intercom.segluky

import android.content.Context

/** Los tres estados de la luz INTERCOM. */
enum class IntercomState {
    /** Gris: InterCOM-Luky no esta instalado. */
    MISSING,

    /** Naranja: instalado, pero el intercom esta apagado. */
    INACTIVE,

    /** Verde: instalado y con el intercom encendido (grupo o INTERCOM WiFi). */
    ACTIVE
}

data class IntercomInstallationInfo(
    val installed: Boolean,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val bridgeAvailable: Boolean = false,
    val active: Boolean = false,
    val group: String = "",
    val peers: Int = 0
) {
    val state: IntercomState
        get() = when {
            !installed -> IntercomState.MISSING
            active -> IntercomState.ACTIVE
            else -> IntercomState.INACTIVE
        }
}

object IntercomInstallation {
    private const val PACKAGE_NAME = "com.intercom.lukymoto"

    /** Aviso que InterCOM difunde por voz a los demas motoristas. */
    const val SOS_NOTICE_TEXT = "Posible accidente de motorista"

    fun inspect(context: Context): IntercomInstallationInfo {
        val info = try {
            context.packageManager.getPackageInfo(PACKAGE_NAME, 0)
        } catch (_: Exception) {
            null
        } ?: return IntercomInstallationInfo(installed = false)

        val bridge = IntercomBridge.status(context)
        return IntercomInstallationInfo(
            installed = true,
            versionName = info.versionName,
            versionCode = info.longVersionCode,
            bridgeAvailable = bridge != null,
            active = bridge?.active == true,
            group = bridge?.group.orEmpty(),
            peers = bridge?.peers ?: 0
        )
    }

    /** Manda el aviso de voz al grupo. false si InterCOM no esta activo o sin puente. */
    fun sendNotice(context: Context, text: String = SOS_NOTICE_TEXT): Boolean =
        IntercomBridge.sendNotice(context, text)

    fun open(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE_NAME) ?: return false
        context.startActivity(intent)
        return true
    }
}
