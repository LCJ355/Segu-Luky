package com.intercom.segluky

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class CalibrationActivity(val id: String, val label: String) {
    WALKING("andando", "Andando"),
    RUNNING("corriendo", "Corriendo"),
    MOTO_HIGHWAY("moto_autovia", "Moto autovia"),
    MOTO_ROAD("moto_camino", "Moto camino"),
    CAR_HIGHWAY("coche_autovia", "Coche autovia"),
    CAR_ROAD("coche_camino", "Coche camino");

    companion object {
        fun fromId(id: String?): CalibrationActivity =
            entries.firstOrNull { it.id == id } ?: WALKING
    }
}

data class CalibrationFile(
    val name: String,
    val sizeBytes: Long,
    val modifiedAt: Long
)

data class CalibrationState(
    val recording: Boolean = false,
    val activity: CalibrationActivity = CalibrationActivity.WALKING,
    val elapsedMs: Long = 0L,
    val samples: Long = 0L,
    val fileName: String? = null,
    val files: List<CalibrationFile> = emptyList()
)

object CalibrationStateStore {
    private val _state = MutableStateFlow(CalibrationState())
    val state: StateFlow<CalibrationState> = _state.asStateFlow()

    fun update(transform: (CalibrationState) -> CalibrationState) {
        _state.value = transform(_state.value)
    }

    fun reset() {
        _state.value = CalibrationState(files = _state.value.files)
    }
}

object CalibrationRepository {
    const val PREFERENCES = "segu_prefs"
    private const val KEY_LAST_ACTIVITY = "calibration_last_activity"
    private const val DIR_NAME = "calibracion"

    fun directory(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun newFile(context: Context, activity: CalibrationActivity): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val model = sanitize(deviceModel())
        return File(directory(context), "segu_calib_${model}_${activity.id}_$stamp.csv")
    }

    fun deviceModel(): String {
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
        val model = Build.MODEL?.trim().orEmpty()
        return when {
            manufacturer.isEmpty() -> model.ifEmpty { "desconocido" }
            model.startsWith(manufacturer, ignoreCase = true) -> model
            else -> "$manufacturer $model"
        }
    }

    private fun sanitize(value: String): String {
        val cleaned = value.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-')
        return cleaned.ifEmpty { "movil" }.take(28)
    }

    fun listFiles(context: Context): List<CalibrationFile> =
        directory(context).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".csv") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { CalibrationFile(it.name, it.length(), it.lastModified()) }
            .orEmpty()

    fun file(context: Context, name: String): File? {
        val f = File(directory(context), name)
        return if (f.isFile) f else null
    }

    fun delete(context: Context, name: String): Boolean =
        file(context, name)?.delete() ?: false

    fun loadLastActivity(context: Context): CalibrationActivity =
        CalibrationActivity.fromId(
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(KEY_LAST_ACTIVITY, null)
        )

    fun saveLastActivity(context: Context, activity: CalibrationActivity) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST_ACTIVITY, activity.id).apply()
    }

    fun share(context: Context, name: String) {
        val f = file(context, name) ?: return
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
        }.getOrNull() ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, name)
            putExtra(Intent.EXTRA_TEXT, "Calibracion Segu-Luky (${deviceModel()}): $name")
            clipData = ClipData.newUri(context.contentResolver, name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Compartir $name"))
    }

    /** Envia el archivo directamente por WhatsApp (o abre el selector si no esta instalado). */
    fun shareWhatsApp(context: Context, name: String) {
        val f = file(context, name) ?: return
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
        }.getOrNull() ?: return
        val whatsapp = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { pkg ->
            runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, name)
            putExtra(Intent.EXTRA_TEXT, "Calibracion Segu-Luky (${deviceModel()}): $name")
            clipData = ClipData.newUri(context.contentResolver, name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (whatsapp != null) {
            val direct = Intent(intent).setPackage(whatsapp)
            val opened = runCatching { context.startActivity(direct); true }.getOrDefault(false)
            if (opened) return
        }
        context.startActivity(Intent.createChooser(intent, "Enviar por WhatsApp"))
    }

    /** Empaqueta todos los CSV + un resumen de ajustes en un ZIP listo para enviar. */
    fun exportAll(context: Context): File? {
        val dir = directory(context)
        val csvs = dir.listFiles { f -> f.isFile && f.name.endsWith(".csv") }.orEmpty()
        if (csvs.isEmpty()) return null
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val zip = File(dir, "segu_calib_${sanitize(deviceModel())}_$stamp.zip")
        runCatching {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zip))).use { zos ->
                csvs.forEach { csv ->
                    zos.putNextEntry(ZipEntry(csv.name))
                    csv.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
                zos.putNextEntry(ZipEntry("ajustes.txt"))
                zos.write(settingsSummary(context).toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }.onFailure { return null }
        return zip
    }

    private fun settingsSummary(context: Context): String = buildString {
        val profile = SafetyMonitorService.loadDeviceProfile(context)
        val sensitivity = SafetyMonitorService.loadSensitivity(context)
        val effective = if (profile.usageMs >= SensitivityMath.PROFILE_MIN_USAGE_MS) {
            maxOf(sensitivity, profile.floorG)
        } else {
            sensitivity
        }
        appendLine("Segu-Luky - resumen de ajustes")
        appendLine("dispositivo=${deviceModel()}")
        appendLine("sensibilidad_g=$sensitivity")
        appendLine("umbral_efectivo_g=$effective")
        appendLine("ventana_adaptacion=${SafetyMonitorService.adaptationWindowLabel(SafetyMonitorService.loadAdaptationWindowMode(context))}")
        appendLine("ruido_movil_g=${profile.noiseG}")
        appendLine("marcha_p99_g=${profile.p99G}")
        appendLine("muestreo_hz=${profile.deliveredHz}")
        appendLine("uso_acumulado_min=${profile.usageMs / 60_000L}")
        appendLine("pico_ultimo_viaje_g=${SafetyMonitorService.loadRidePeak(context)}")
        appendLine("modo_moto_caida=${SafetyMonitorService.loadFallOrientationGate(context)}")
        appendLine("voz_ayuda_socorro=${SafetyMonitorService.loadVoiceKeywordsGate(context)}")
        appendLine("contactos=${ContactsRepository(context).load().size}")
    }

    fun shareExport(context: Context, zip: File, viaWhatsApp: Boolean) {
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
        }.getOrNull() ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, zip.name)
            putExtra(Intent.EXTRA_TEXT, "Calibracion Segu-Luky (${deviceModel()}): ${zip.name}")
            clipData = ClipData.newUri(context.contentResolver, zip.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (viaWhatsApp) {
            val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull {
                runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess
            }
            if (pkg != null && runCatching { context.startActivity(Intent(intent).setPackage(pkg)); true }.getOrDefault(false)) {
                return
            }
        }
        context.startActivity(Intent.createChooser(intent, "Exportar calibracion"))
    }

    /** Copia el ZIP a la carpeta publica Descargas/Segu-Luky. Devuelve la ruta visible o null. */
    fun exportToDownloads(context: Context, zip: File): String? = runCatching {
        val relative = Environment.DIRECTORY_DOWNLOADS + "/Segu-Luky"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, zip.name)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, relative)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { out -> zip.inputStream().use { it.copyTo(out) } }
        "Descargas/Segu-Luky/${zip.name}"
    }.getOrNull()
}
