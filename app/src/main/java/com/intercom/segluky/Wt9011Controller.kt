package com.intercom.segluky

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.sqrt

enum class Wt9011Status {
    OFF,
    NO_PERMISSION,
    NO_BLUETOOTH,
    NO_DEVICE,
    SCANNING,
    CONNECTING,
    CALIBRATING,
    READY,
    NO_DATA,
    ERROR
}

data class Wt9011Device(
    val name: String,
    val address: String,
    val rssi: Int = 0
) {
    val label: String
        get() = when {
            name.isBlank() -> address
            address.isBlank() -> name
            else -> "$name · $address"
        }
}

data class Wt9011State(
    val status: Wt9011Status = Wt9011Status.OFF,
    val detail: String = "",
    val deviceName: String = "",
    val deviceAddress: String = "",
    val enabled: Boolean = true,
    val thresholdDegrees: Float = Wt9011Settings.DEFAULT_THRESHOLD_DEGREES,
    val tiltDegrees: Float? = null,
    val axisXDegrees: Float? = null,
    val axisYDegrees: Float? = null,
    val ax: Float? = null,
    val ay: Float? = null,
    val calibrated: Boolean = false,
    val hz: Int = 0,
    val frames: Long = 0L,
    val monitoring: Boolean = false,
    val updatedAtMs: Long = 0L
) {
    val connected: Boolean
        get() = status == Wt9011Status.CALIBRATING ||
            status == Wt9011Status.READY ||
            status == Wt9011Status.NO_DATA

    /** Estado del semaforo de inclinacion: 0 correcto, 1 cerca, 2 SOS. */
    val tiltLevel: Int
        get() {
            val tilt = tiltDegrees ?: return 0
            if (tilt >= thresholdDegrees) return 2
            if (tilt >= thresholdDegrees - YELLOW_BAND_DEGREES) return 1
            return 0
        }

    companion object {
        const val YELLOW_BAND_DEGREES = 15f
    }
}

data class Wt9011Fall(
    val tiltDegrees: Float,
    val axisXDegrees: Float,
    val axisYDegrees: Float,
    val thresholdDegrees: Float
)

/**
 * Dueño del enlace BLE con el WT9011DCL-BT50 y del calculo de inclinacion.
 *
 * La interfaz puede conectarse con el monitor parado para comprobar los ejes.
 * El servicio pone [setMonitoring] a true y recibe [onFall] solo cuando la
 * condicion de vuelco esta activa. Este sensor nunca sustituye a los avisos
 * del movil: es una condicion mas.
 */
@SuppressLint("MissingPermission")
object Wt9011Controller {

    private const val SCAN_TIMEOUT_MS = 12_000L
    private const val DESCRIPTOR_TIMEOUT_MS = 2_500L
    private const val NO_DATA_MS = 2_500L
    private const val CONFIG_SETTLE_MS = 350L

    private val SERVICE_UUID: UUID = UUID.fromString("0000ffe5-0000-1000-8000-00805f9a34fb")
    private val NOTIFY_UUID: UUID = UUID.fromString("0000ffe4-0000-1000-8000-00805f9a34fb")
    private val WRITE_UUID: UUID = UUID.fromString("0000ffe9-0000-1000-8000-00805f9a34fb")
    private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val RECONNECT_DELAYS_MS = longArrayOf(2_000L, 5_000L, 10_000L, 30_000L)

    private val _state = MutableStateFlow(Wt9011State())
    val state: StateFlow<Wt9011State> = _state.asStateFlow()

    private val _scanResults = MutableStateFlow<List<Wt9011Device>>(emptyList())
    val scanResults: StateFlow<List<Wt9011Device>> = _scanResults.asStateFlow()

    /** El servicio lo usa para abrir la cuenta atras; se ejecuta en el hilo principal. */
    var onFall: ((Wt9011Fall) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())

    private var applicationContext: Context? = null
    private var device = Wt9011Device("", "")
    private var gatt: BluetoothGatt? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var wanted = false
    private var monitoring = false
    private var connectedNotified = false
    private var scanning = false
    private var reconnectAttempts = 0

    private val parserLock = Any()
    private var parser = Wt9011Parser { SystemClock.elapsedRealtimeNanos() }
    private var tiltWatch = Wt9011TiltWatch()

    private var descriptorTimeout: Runnable? = null
    private var scanTimeout: Runnable? = null
    private val configQueue = ArrayDeque<ByteArray>()
    private var configInFlight = false

    @Volatile
    private var sessionGeneration = 0
    private var lastSampleAt = 0L
    private var connectedAt = 0L
    private var noDataLogged = false
    private var fallEmitted = false

    private var framesWindowStart = 0L
    private var framesWindowCount = 0
    private var lastUiPublishAt = 0L

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = runCatching { result.device.name }.getOrNull()
                ?: result.scanRecord?.deviceName
                ?: ""
            if (!name.startsWith("WT901", ignoreCase = true)) return
            val candidate = Wt9011Device(
                name = name,
                address = result.device.address,
                rssi = result.rssi
            )
            val current = _scanResults.value
            val merged = (current.filterNot { it.address == candidate.address } + candidate)
                .sortedByDescending { it.rssi }
                .take(20)
            _scanResults.value = merged
            _state.value = _state.value.copy(
                status = Wt9011Status.SCANNING,
                detail = if (merged.size == 1) "1 sensor encontrado" else "${merged.size} sensores encontrados"
            )
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            publish(Wt9011Status.ERROR, "No se pudo buscar por BLE (codigo $errorCode)")
        }
    }

    private val reconnectRunnable = Runnable {
        val context = applicationContext ?: return@Runnable
        if (!wanted || gatt != null) return@Runnable
        openGatt(context, device, resetAttempts = false)
    }

    private val noDataCheck = object : Runnable {
        override fun run() {
            if (!wanted) return
            val now = SystemClock.elapsedRealtime()
            val age = if (lastSampleAt > 0L) now - lastSampleAt else now - connectedAt
            if (age >= NO_DATA_MS && _state.value.connected) {
                if (!noDataLogged) {
                    noDataLogged = true
                    _state.value = _state.value.copy(
                        status = Wt9011Status.NO_DATA,
                        detail = "Conectado, pero sin datos del sensor"
                    )
                    logIfMonitoring("SENSOR DE VUELCO: conectado sin datos")
                }
            }
            handler.postDelayed(this, 1_000L)
        }
    }

    fun hasScanPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }

    fun hasConnectPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    fun hasPermissions(context: Context): Boolean =
        hasScanPermission(context) && hasConnectPermission(context)

    fun bluetoothEnabled(context: Context): Boolean =
        runCatching { adapter(context)?.isEnabled == true }.getOrDefault(false)

    fun locationEnabled(context: Context): Boolean =
        runCatching {
            (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)
                ?.isLocationEnabled == true
        }.getOrDefault(false)

    fun refreshSettings(context: Context) {
        applicationContext = context.applicationContext
        val saved = Wt9011Settings.loadDevice(context)
        device = saved
        _state.value = _state.value.copy(
            deviceName = saved.name,
            deviceAddress = saved.address,
            enabled = Wt9011Settings.loadEnabled(context),
            thresholdDegrees = Wt9011Settings.loadThreshold(context)
        )
        tiltWatch.setThreshold(Wt9011Settings.loadThreshold(context))
    }

    @Synchronized
    fun startScan(context: Context): Boolean {
        applicationContext = context.applicationContext
        refreshSettings(context)
        if (!hasPermissions(context)) {
            publish(Wt9011Status.NO_PERMISSION, "Concede los permisos de Bluetooth para buscar el sensor.")
            return false
        }
        val adapter = adapter(context)
        if (adapter == null) {
            publish(Wt9011Status.NO_BLUETOOTH, "Este movil no tiene Bluetooth.")
            return false
        }
        if (!adapter.isEnabled) {
            publish(Wt9011Status.NO_BLUETOOTH, "Activa el Bluetooth del movil.")
            return false
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R && !locationEnabled(context)) {
            publish(Wt9011Status.ERROR, "Activa la ubicacion del sistema: sin ella Android no entrega BLE.")
            return false
        }
        stopScanInternal()
        _scanResults.value = emptyList()
        scanning = true
        _state.value = _state.value.copy(
            status = Wt9011Status.SCANNING,
            detail = "Buscando sensores WT901..."
        )
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            scanning = false
            publish(Wt9011Status.NO_BLUETOOTH, "El Bluetooth no ofrece busqueda BLE.")
            return false
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        return try {
            scanner.startScan(null, settings, scanCallback)
            scanTimeout = Runnable {
                stopScanInternal()
                if (_state.value.status == Wt9011Status.SCANNING) {
                    if (_scanResults.value.isEmpty()) {
                        publish(Wt9011Status.NO_DEVICE, "No se encontro ningun WT901. Enciende el sensor.")
                    } else {
                        _state.value = _state.value.copy(detail = "Busqueda terminada")
                    }
                }
            }
            handler.postDelayed(scanTimeout!!, SCAN_TIMEOUT_MS)
            true
        } catch (error: SecurityException) {
            scanning = false
            publish(Wt9011Status.NO_PERMISSION, "Android denego el permiso de busqueda BLE.")
            false
        }
    }

    @Synchronized
    fun stopScan() {
        stopScanInternal()
        if (_state.value.status == Wt9011Status.SCANNING) {
            _state.value = _state.value.copy(status = Wt9011Status.OFF, detail = "")
        }
    }

    @Synchronized
    fun connect(context: Context, target: Wt9011Device = Wt9011Settings.loadDevice(context)): Boolean {
        applicationContext = context.applicationContext
        refreshSettings(context)
        if (!hasPermissions(context)) {
            publish(Wt9011Status.NO_PERMISSION, "Concede los permisos de Bluetooth para conectar.")
            return false
        }
        val adapter = adapter(context)
        if (adapter == null) {
            publish(Wt9011Status.NO_BLUETOOTH, "Este movil no tiene Bluetooth.")
            return false
        }
        if (!adapter.isEnabled) {
            publish(Wt9011Status.NO_BLUETOOTH, "Activa el Bluetooth del movil.")
            return false
        }
        if (target.address.isBlank()) {
            publish(Wt9011Status.NO_DEVICE, "Elige el sensor en la lista.")
            return false
        }
        device = target
        Wt9011Settings.saveDevice(context, target)
        _state.value = _state.value.copy(
            deviceName = target.name,
            deviceAddress = target.address
        )
        wanted = true
        reconnectAttempts = 0
        stopScanInternal()
        closeGatt()
        resetSession()
        publish(Wt9011Status.CONNECTING, "Conectando con ${target.label}...")
        return openGatt(context, target, resetAttempts = true)
    }

    @Synchronized
    fun disconnect() {
        wanted = false
        handler.removeCallbacks(reconnectRunnable)
        handler.removeCallbacks(noDataCheck)
        stopScanInternal()
        closeGatt()
        resetSession()
        _state.value = _state.value.copy(
            status = Wt9011Status.OFF,
            detail = "Desconectado",
            tiltDegrees = null,
            axisXDegrees = null,
            axisYDegrees = null,
            ax = null,
            ay = null,
            calibrated = false,
            hz = 0,
            frames = 0L,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    /** El servicio lo llama al arrancar o detener el monitor. */
    @Synchronized
    fun setMonitoring(context: Context, active: Boolean) {
        applicationContext = context.applicationContext
        refreshSettings(context)
        monitoring = active
        _state.value = _state.value.copy(monitoring = active)
        if (active) {
            tiltWatch.calibrate()
            if (_state.value.enabled && device.address.isNotBlank() && gatt == null) {
                connect(context, device)
            }
        } else {
            disconnect()
        }
    }

    @Synchronized
    fun setEnabled(context: Context, enabled: Boolean) {
        Wt9011Settings.saveEnabled(context, enabled)
        _state.value = _state.value.copy(enabled = enabled)
        if (!enabled && monitoring) {
            disconnect()
        } else if (enabled && monitoring && device.address.isNotBlank() && gatt == null) {
            connect(context, device)
        }
    }

    @Synchronized
    fun setThreshold(context: Context, degrees: Float) {
        val value = degrees.coerceIn(
            Wt9011Settings.MIN_THRESHOLD_DEGREES,
            Wt9011Settings.MAX_THRESHOLD_DEGREES
        )
        Wt9011Settings.saveThreshold(context, value)
        tiltWatch.setThreshold(value)
        _state.value = _state.value.copy(thresholdDegrees = value)
    }

    @Synchronized
    fun calibrate() {
        tiltWatch.calibrate()
        fallEmitted = false
        _state.value = _state.value.copy(
            status = if (wanted) Wt9011Status.CALIBRATING else _state.value.status,
            detail = if (wanted) "Calibrando: deja la moto quieta y vertical" else _state.value.detail,
            calibrated = false,
            tiltDegrees = null,
            axisXDegrees = null,
            axisYDegrees = null
        )
    }

    private fun openGatt(context: Context, target: Wt9011Device, resetAttempts: Boolean): Boolean {
        val adapter = adapter(context) ?: run {
            publish(Wt9011Status.NO_BLUETOOTH, "Este movil no tiene Bluetooth.")
            return false
        }
        val remote = try {
            adapter.getRemoteDevice(target.address)
        } catch (error: IllegalArgumentException) {
            publish(Wt9011Status.ERROR, "Direccion BLE no valida.")
            return false
        }
        if (resetAttempts) reconnectAttempts = 0
        connectedNotified = false
        gatt = remote.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) {
            publish(Wt9011Status.ERROR, "No se pudo abrir la conexion con el sensor.")
            scheduleReconnect()
            return false
        }
        return true
    }

    private fun resetSession() {
        val context = applicationContext ?: return
        sessionGeneration++
        synchronized(parserLock) {
            parser = Wt9011Parser { SystemClock.elapsedRealtimeNanos() }
        }
        tiltWatch = Wt9011TiltWatch(Wt9011Settings.loadThreshold(context))
        tiltWatch.setThreshold(_state.value.thresholdDegrees)
        configQueue.clear()
        configInFlight = false
        lastSampleAt = 0L
        connectedAt = 0L
        noDataLogged = false
        fallEmitted = false
        framesWindowStart = 0L
        framesWindowCount = 0
        lastUiPublishAt = 0L
    }

    private fun closeGatt() {
        descriptorTimeout?.let { handler.removeCallbacks(it) }
        descriptorTimeout = null
        handler.removeCallbacks(noDataCheck)
        connectedNotified = false
        notifyChar = null
        writeChar = null
        val current = gatt
        gatt = null
        if (current != null) {
            runCatching { current.disconnect() }
            runCatching { current.close() }
        }
    }

    private fun scheduleReconnect() {
        if (!wanted) return
        val delay = RECONNECT_DELAYS_MS[reconnectAttempts.coerceIn(0, RECONNECT_DELAYS_MS.lastIndex)]
        reconnectAttempts++
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, delay)
    }

    private fun stopScanInternal() {
        scanTimeout?.let { handler.removeCallbacks(it) }
        scanTimeout = null
        if (!scanning) return
        scanning = false
        runCatching {
            adapter(applicationContext)?.bluetoothLeScanner?.stopScan(scanCallback)
        }
    }

    private fun adapter(context: Context?): BluetoothAdapter? {
        if (context == null) return null
        val fromManager = runCatching {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        }.getOrNull()
        return fromManager ?: runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
    }

    private fun publish(status: Wt9011Status, detail: String) {
        _state.value = _state.value.copy(
            status = status,
            detail = detail,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    private fun logIfMonitoring(message: String) {
        if (monitoring) SafetyStateStore.log(message)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== gatt) return
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                reconnectAttempts = 0
                connectedAt = SystemClock.elapsedRealtime()
                lastSampleAt = 0L
                noDataLogged = false
                handler.postDelayed(noDataCheck, 1_000L)
                val mtuRequested = runCatching { g.requestMtu(247) }.getOrDefault(false)
                if (!mtuRequested) discoverServices(g, 0)
                return
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val wasConnected = connectedNotified
                closeGatt()
                if (wanted) {
                    if (wasConnected) logIfMonitoring("SENSOR DE VUELCO: enlace perdido; reintentando")
                    publish(Wt9011Status.CONNECTING, "Sin enlace; reintentando con el sensor...")
                    scheduleReconnect()
                } else {
                    publish(Wt9011Status.OFF, "Desconectado")
                }
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logIfMonitoring("SENSOR DE VUELCO: estado GATT $status")
            }
        }

        private fun discoverServices(g: BluetoothGatt, attempt: Int) {
            if (attempt > 5) {
                publish(Wt9011Status.ERROR, "No se pudieron descubrir los servicios BLE.")
                return
            }
            val started = runCatching { g.discoverServices() }.getOrDefault(false)
            if (!started) {
                handler.postDelayed({ discoverServices(g, attempt + 1) }, 600L)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                publish(Wt9011Status.ERROR, "Descubrimiento de servicios fallido ($status)")
                return
            }
            var service = g.getService(SERVICE_UUID)
            if (service == null) {
                service = g.services?.firstOrNull { candidate ->
                    candidate.characteristics.any {
                        it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                    }
                }
            }
            if (service == null) {
                publish(Wt9011Status.ERROR, "El sensor no expone el servicio FFE5.")
                return
            }
            val notify = service.getCharacteristic(NOTIFY_UUID)
                ?: service.characteristics.firstOrNull {
                    it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                }
            val write = service.getCharacteristic(WRITE_UUID)
                ?: service.characteristics.firstOrNull {
                    it.properties and (
                        BluetoothGattCharacteristic.PROPERTY_WRITE or
                            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                        ) != 0
                }
            if (notify == null) {
                publish(Wt9011Status.ERROR, "El sensor no tiene notificaciones.")
                return
            }
            notifyChar = notify
            writeChar = write
            val enabled = runCatching { g.setCharacteristicNotification(notify, true) }.getOrDefault(false)
            val descriptor = notify.getDescriptor(CCCD_UUID)
            if (enabled && descriptor != null) {
                @Suppress("DEPRECATION")
                runCatching {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(descriptor)
                }
                val fallback = Runnable {
                    if (!connectedNotified) {
                        markConnected(g)
                    }
                }
                descriptorTimeout?.let { handler.removeCallbacks(it) }
                descriptorTimeout = fallback
                handler.postDelayed(fallback, DESCRIPTOR_TIMEOUT_MS)
            } else {
                markConnected(g)
            }
        }

        private fun markConnected(g: BluetoothGatt) {
            if (connectedNotified) return
            connectedNotified = true
            descriptorTimeout?.let { handler.removeCallbacks(it) }
            descriptorTimeout = null
            connectedAt = SystemClock.elapsedRealtime()
            _state.value = _state.value.copy(
                status = Wt9011Status.CALIBRATING,
                detail = "Sensor conectado; configurando y calibrando",
                deviceName = device.name,
                deviceAddress = device.address
            )
            logIfMonitoring("SENSOR DE VUELCO: conectado (${device.label})")
            // La cola de comandos y el temporizador viven en el hilo principal.
            handler.post { sendConfigPlan(g) }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (g !== gatt) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                markConnected(g)
            } else {
                publish(Wt9011Status.ERROR, "No se pudieron activar las notificaciones ($status)")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (g !== gatt) return
            discoverServices(g, 0)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (g !== gatt) return
            @Suppress("DEPRECATION")
            val data = characteristic.value ?: return
            if (data.isEmpty()) return
            val generation = sessionGeneration
            synchronized(parserLock) {
                parser.feed(data) { sample ->
                    // La politica de vuelco y el detector se tocan en el hilo
                    // principal; el parser se queda en el hilo del callback.
                    handler.post {
                        if (generation == sessionGeneration) onSample(sample)
                    }
                }
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (g !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logIfMonitoring("SENSOR DE VUELCO: escritura con estado $status")
            }
        }
    }

    private fun sendConfigPlan(g: BluetoothGatt) {
        configQueue.clear()
        Wt9011Config.plan().forEach { configQueue.addLast(it) }
        configInFlight = false
        pumpConfig(g)
    }

    private fun pumpConfig(g: BluetoothGatt) {
        if (configInFlight) return
        val characteristic = writeChar
        if (characteristic == null) {
            configQueue.clear()
            logIfMonitoring("SENSOR DE VUELCO: el sensor no acepta escritura")
            return
        }
        val next = configQueue.pollFirst() ?: run {
            handler.postDelayed({
                if (_state.value.status == Wt9011Status.CALIBRATING) {
                    _state.value = _state.value.copy(
                        detail = "Sensor configurado a 50 Hz; esperando calibracion"
                    )
                }
            }, CONFIG_SETTLE_MS)
            return
        }
        configInFlight = true
        val sent = writeBytes(g, characteristic, next)
        if (!sent) {
            logIfMonitoring("SENSOR DE VUELCO: no se pudo escribir ${Wt9011Config.hex(next)}")
        }
        handler.postDelayed({
            configInFlight = false
            pumpConfig(g)
        }, Wt9011Config.COMMAND_GAP_MS)
    }

    // El resultado de writeCharacteristic es BluetoothStatusCodes.SUCCESS (0).
    // Se compara con 0 para no cargar esa clase en Android 12 o anterior.
    @SuppressLint("WrongConstant")
    @Suppress("DEPRECATION")
    private fun writeBytes(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        bytes: ByteArray
    ): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // BluetoothStatusCodes.SUCCESS es 0; no se referencia la clase
                // para no cargarla en Android 12 o anterior.
                g.writeCharacteristic(
                    characteristic,
                    bytes,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                ) == 0
            } else {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                characteristic.value = bytes
                g.writeCharacteristic(characteristic)
            }
        } catch (error: SecurityException) {
            false
        }
    }

    private fun onSample(sample: Wt9011Sample) {
        val now = SystemClock.elapsedRealtime()
        lastSampleAt = now
        if (noDataLogged) {
            noDataLogged = false
            _state.value = _state.value.copy(
                status = if (_state.value.calibrated) Wt9011Status.READY else Wt9011Status.CALIBRATING
            )
        }

        if (framesWindowStart == 0L) framesWindowStart = now
        framesWindowCount++
        if (now - framesWindowStart >= 1_000L) {
            val hz = (framesWindowCount * 1_000L / (now - framesWindowStart)).toInt()
            framesWindowStart = now
            framesWindowCount = 0
            if (_state.value.hz != hz) {
                _state.value = _state.value.copy(hz = hz)
            }
        }

        val reading = tiltWatch.feed(
            ax = sample.ax,
            ay = sample.ay,
            az = sample.az,
            gx = sample.gx,
            gy = sample.gy,
            gz = sample.gz,
            timestamp = now
        )
        val levelStatus = when {
            !reading.calibrated -> Wt9011Status.CALIBRATING
            else -> Wt9011Status.READY
        }
        val newLevel = when {
            reading.tiltDegrees == null -> 0
            reading.tiltDegrees >= tiltWatch.thresholdDegrees -> 2
            reading.tiltDegrees >= tiltWatch.thresholdDegrees - Wt9011State.YELLOW_BAND_DEGREES -> 1
            else -> 0
        }
        val current = _state.value
        val shouldPublish = now - lastUiPublishAt >= 200L ||
            reading.calibrated != current.calibrated ||
            newLevel != current.tiltLevel ||
            levelStatus != current.status
        if (shouldPublish) {
            lastUiPublishAt = now
            _state.value = current.copy(
                status = levelStatus,
                detail = if (reading.calibrated) {
                    "Enlace correcto; inclinacion X/Y en vivo"
                } else {
                    "Calibrando: deja la moto quieta y vertical"
                },
                tiltDegrees = reading.tiltDegrees,
                axisXDegrees = reading.axisXDegrees,
                axisYDegrees = reading.axisYDegrees,
                ax = sample.ax,
                ay = sample.ay,
                calibrated = reading.calibrated,
                frames = parser.frames,
                updatedAtMs = System.currentTimeMillis()
            )
        }

        if (reading.triggered) {
            if (!fallEmitted && monitoring && _state.value.enabled) {
                fallEmitted = true
                val fall = Wt9011Fall(
                    tiltDegrees = reading.tiltDegrees ?: 0f,
                    axisXDegrees = reading.axisXDegrees ?: 0f,
                    axisYDegrees = reading.axisYDegrees ?: 0f,
                    thresholdDegrees = tiltWatch.thresholdDegrees
                )
                onFall?.invoke(fall)
            }
        } else {
            fallEmitted = false
        }
    }
}
