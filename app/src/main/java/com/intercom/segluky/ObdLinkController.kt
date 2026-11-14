package com.intercom.segluky

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Enlace con el adaptador: solo se puede pedir un comando cada vez. */
interface ObdLink {
    fun connect()
    fun command(command: String, timeoutMs: Long): String
    fun close()
}

/**
 * Bluetooth clasico (RFCOMM) con el UUID de puerto serie que usa cualquier ELM327
 * y que aparece en el APK de referencia de Torque.
 */
@SuppressLint("MissingPermission")
class SppObdLink(private val address: String) : ObdLink {
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override fun connect() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: throw IOException("sin bluetooth")
        val device = adapter.getRemoteDevice(address)
        runCatching { adapter.cancelDiscovery() }
        val created = device.createRfcommSocketToServiceRecord(SPP_UUID)
        created.connect()
        socket = created
        input = created.inputStream
        output = created.outputStream
    }

    override fun command(command: String, timeoutMs: Long): String {
        val out = output ?: throw IOException("sin conexion")
        val inp = input ?: throw IOException("sin conexion")
        out.write((command + "\r").toByteArray())
        out.flush()
        val builder = StringBuilder()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (inp.available() > 0) {
                val value = inp.read()
                if (value < 0) break
                val char = value.toChar()
                if (char == '>') break
                builder.append(char)
            } else {
                Thread.sleep(8L)
            }
        }
        return builder.toString()
    }

    override fun close() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

/**
 * Dueño del enlace con el coche. Un solo hilo hace la ronda de PIDs y publica el
 * estado; la interfaz solo lee [state]. Nada de esto toca el camino del SOS: los
 * fallos del adaptador se quedan en la tarjeta.
 */
object ObdLinkController {

    private const val PREFERENCES = "obd_prefs"
    private const val KEY_MAC = "obd_mac"
    private const val KEY_NAME = "obd_name"
    private const val SPEED_PERIOD_MS = 200L
    private const val SLOW_PERIOD_MS = 1_000L
    private const val RECONNECT_DELAY_MS = 3_000L
    private const val MAX_SILENT_ROUNDS = 6

    private val _state = MutableStateFlow(ObdState())
    val state: StateFlow<ObdState> = _state.asStateFlow()

    private var worker: Thread? = null
    private var link: ObdLink? = null
    private var wanted = false
    private var device = ObdDevice("", "")

    fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun bluetoothEnabled(): Boolean =
        runCatching { BluetoothAdapter.getDefaultAdapter()?.isEnabled ?: false }.getOrDefault(false)

    /** Adaptadores ya emparejados; en Android 12+ hace falta BLUETOOTH_CONNECT. */
    @SuppressLint("MissingPermission")
    fun bondedDevices(context: Context): List<ObdDevice> {
        if (!hasPermission(context)) return emptyList()
        val application = context.applicationContext
        val adapter = runCatching {
            (application.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        }.getOrNull() ?: runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
        if (adapter == null || !adapter.isEnabled) return emptyList()
        return runCatching {
            adapter.bondedDevices.orEmpty().map { ObdDevice(it.name.orEmpty(), it.address) }
        }.getOrDefault(emptyList())
    }

    fun savedDevice(context: Context): ObdDevice {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return ObdDevice(
            preferences.getString(KEY_NAME, "").orEmpty(),
            preferences.getString(KEY_MAC, "").orEmpty()
        )
    }

    fun saveDevice(context: Context, device: ObdDevice) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString(KEY_MAC, device.address).putString(KEY_NAME, device.name).apply()
        _state.value = _state.value.copy(deviceName = device.name, deviceAddress = device.address)
    }

    /** true si habia algo que arrancar. */
    @Synchronized
    fun connect(context: Context, device: ObdDevice = savedDevice(context)): Boolean {
        if (!hasPermission(context)) {
            publish(ObdStatus.NO_PERMISSION, "Concede el permiso de Bluetooth a la app.")
            return false
        }
        if (!bluetoothEnabled()) {
            publish(ObdStatus.ERROR, "Activa el Bluetooth del movil.")
            return false
        }
        if (device.address.isBlank()) {
            publish(ObdStatus.NO_DEVICE, "Elige el adaptador emparejado.")
            return false
        }
        if (worker?.isAlive == true) return true
        saveDevice(context, device)
        this.device = device
        wanted = true
        publish(ObdStatus.CONNECTING, "Conectando con ${device.label}...")
        val thread = Thread({ runLoop(context.applicationContext) }, "obd-link")
        thread.isDaemon = true
        worker = thread
        thread.start()
        return true
    }

    @Synchronized
    fun disconnect() {
        wanted = false
        worker?.interrupt()
        worker = null
        runCatching { link?.close() }
        link = null
        _state.value = ObdState(deviceName = device.name, deviceAddress = device.address)
    }

    private fun publish(status: ObdStatus, detail: String, protocol: String = _state.value.protocol) {
        _state.value = _state.value.copy(status = status, detail = detail, protocol = protocol)
    }

    private fun runLoop(context: Context) {
        var attempts = 0
        while (wanted && attempts < 3) {
            attempts++
            val current = SppObdLink(device.address)
            try {
                current.connect()
                link = current
                val protocol = handshake(current)
                publish(ObdStatus.NO_DATA, "Conectado, esperando datos del coche", protocol.orEmpty())
                poll(current)
            } catch (error: Exception) {
                if (!wanted) break
                publish(ObdStatus.ERROR, error.message ?: "fallo del adaptador")
                SafetyStateStore.log("OBD: ${error.message ?: "fallo del adaptador"}")
                runCatching { current.close() }
                link = null
                if (wanted) Thread.sleep(RECONNECT_DELAY_MS)
            } finally {
                runCatching { current.close() }
            }
        }
        if (wanted) publish(ObdStatus.ERROR, "No se pudo enlazar con el adaptador")
        link = null
    }

    /** Secuencia de arranque; devuelve el protocolo negociado. */
    private fun handshake(current: ObdLink): String? {
        ObdPids.init.forEach { command ->
            current.command(command, ObdPids.INIT_TIMEOUT_MS)
            if (command == "ATZ") Thread.sleep(400L)
        }
        return ObdParser.protocol(current.command(ObdPids.PROTOCOL, ObdPids.COMMAND_TIMEOUT_MS))
    }

    private fun poll(current: ObdLink) {
        var lastSlow = 0L
        var silent = 0
        var speed: Float? = null
        var rpm: Int? = null
        var throttle: Int? = null
        var coolant: Int? = null
        var voltage: Float? = null
        var dtcs: Int? = null
        var protocol = _state.value.protocol
        var windowStart = SystemClock.elapsedRealtime()
        var windowReads = 0
        while (wanted) {
            val now = SystemClock.elapsedRealtime()
            val speedRaw = current.command(ObdPids.SPEED, ObdPids.COMMAND_TIMEOUT_MS)
            val newSpeed = ObdParser.speedKmh(speedRaw)
            if (newSpeed != null) {
                speed = newSpeed
                silent = 0
            } else {
                silent++
            }
            windowReads++
            if (now - lastSlow >= SLOW_PERIOD_MS) {
                lastSlow = now
                rpm = ObdParser.rpm(current.command(ObdPids.RPM, ObdPids.COMMAND_TIMEOUT_MS)) ?: rpm
                throttle = ObdParser.throttlePercent(
                    current.command(ObdPids.THROTTLE, ObdPids.COMMAND_TIMEOUT_MS)
                ) ?: throttle
                coolant = ObdParser.coolantC(
                    current.command(ObdPids.COOLANT, ObdPids.COMMAND_TIMEOUT_MS)
                ) ?: coolant
                voltage = ObdParser.voltage(current.command(ObdPids.VOLTAGE, ObdPids.COMMAND_TIMEOUT_MS))
                    ?: voltage
                dtcs = ObdParser.dtcCount(current.command(ObdPids.MONITORS, ObdPids.COMMAND_TIMEOUT_MS))
                    ?: dtcs
                ObdParser.protocol(current.command(ObdPids.PROTOCOL, ObdPids.COMMAND_TIMEOUT_MS))
                    ?.let { protocol = it }
            }
            val elapsed = SystemClock.elapsedRealtime() - windowStart
            val hz = if (elapsed >= 1_000L) {
                (windowReads * 1_000L / elapsed).toInt()
            } else {
                _state.value.readingsPerSecond
            }
            if (elapsed >= 1_000L) {
                windowStart = SystemClock.elapsedRealtime()
                windowReads = 0
            }
            _state.value = ObdState(
                status = if (speed != null) ObdStatus.READY else ObdStatus.NO_DATA,
                detail = if (speed != null) {
                    "Datos del coche en vivo"
                } else {
                    "Sin respuesta al PID de velocidad (contacto apagado?)"
                },
                protocol = protocol,
                speedKmh = speed,
                rpm = rpm,
                throttlePercent = throttle,
                coolantC = coolant,
                voltage = voltage,
                dtcCount = dtcs,
                readingsPerSecond = hz,
                updatedAtMs = if (speed != null) System.currentTimeMillis() else _state.value.updatedAtMs,
                deviceName = device.name,
                deviceAddress = device.address
            )
            if (silent >= MAX_SILENT_ROUNDS) {
                throw IOException("el adaptador dejo de responder")
            }
            Thread.sleep(SPEED_PERIOD_MS)
        }
    }
}
