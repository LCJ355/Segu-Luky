package com.intercom.segluky

import android.content.Context

data class IntercomInstallationInfo(
    val installed: Boolean,
    val versionName: String? = null,
    val versionCode: Long? = null
)

object IntercomInstallation {
    private const val PACKAGE_NAME = "com.intercom.lukymoto"

    fun inspect(context: Context): IntercomInstallationInfo {
        return try {
            val info = context.packageManager.getPackageInfo(PACKAGE_NAME, 0)
            IntercomInstallationInfo(
                installed = true,
                versionName = info.versionName,
                versionCode = info.longVersionCode
            )
        } catch (_: Exception) {
            IntercomInstallationInfo(installed = false)
        }
    }

    fun open(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE_NAME) ?: return false
        context.startActivity(intent)
        return true
    }
}
