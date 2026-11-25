package com.intercom.segluky

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.intercom.segluky.ui.InterSegTheme
import com.intercom.segluky.ui.ThemePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
import java.security.MessageDigest
import java.util.Locale

class MainActivity : ComponentActivity() {
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val activity = this
        setContent {
            var darkTheme by remember { mutableStateOf(ThemePreference.isDark(activity)) }
            SideEffect {
                activity.window.statusBarColor =
                    if (darkTheme) 0xFF12100E.toInt() else 0xFFF4F1EA.toInt()
                activity.window.navigationBarColor =
                    if (darkTheme) 0xFF12100E.toInt() else 0xFFF4F1EA.toInt()
                WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            InterSegTheme(darkTheme = darkTheme) {
                InterSegScreen(
                    darkTheme = darkTheme,
                    onDarkThemeChange = { enabled ->
                        darkTheme = enabled
                        ThemePreference.setDark(activity, enabled)
                    }
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @androidx.compose.runtime.Composable
    private fun InterSegScreen(
        darkTheme: Boolean,
        onDarkThemeChange: (Boolean) -> Unit
    ) {
        val snapshot by SafetyStateStore.snapshot.collectAsStateWithLifecycle()
        val context = this
        val repository = remember { ContactsRepository(context) }
        var contacts by remember { mutableStateOf(repository.load()) }
        var installation by remember { mutableStateOf(IntercomInstallation.inspect(context)) }
        var intercomNoticeFeedback by remember { mutableStateOf<String?>(null) }
        var screen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(AppScreen.HOME) }
        var sensitivityG by remember { mutableStateOf(SafetyMonitorService.loadSensitivity(context)) }
        // Con el monitor detenido nadie publica el perfil: se siembra desde las preferencias.
        LaunchedEffect(Unit) {
            val vehicle = SafetyMonitorService.loadVehicleProfile(context)
            SafetyStateStore.update { it.copy(vehicleProfile = vehicle) }
        }
        var voiceKeywordsGate by remember { mutableStateOf(SafetyMonitorService.loadVoiceKeywordsGate(context)) }
        var fallOrientationGate by remember { mutableStateOf(SafetyMonitorService.loadFallOrientationGate(context)) }
        var whatsappAutoSend by remember { mutableStateOf(WhatsAppAutoSend.isEnabled(context)) }
        var showGpsOffWarning by remember { mutableStateOf(false) }
        var showAbout by remember { mutableStateOf(false) }
        var sosActionsEnabled by remember { mutableStateOf(repository.areSosActionsEnabled()) }
        var homeMountProfile by remember { mutableStateOf(SafetyMonitorService.loadMountProfile(context)) }
        var audioPermissionGranted by remember { mutableStateOf(hasAudioPermission(context)) }
        var callPermissionGranted by remember { mutableStateOf(hasCallPermission(context)) }
        var notificationPermissionGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
        var permissionFeedback by remember { mutableStateOf<String?>(null) }
        var pendingVoiceEnable by remember { mutableStateOf(false) }
        val lifecycleOwner = LocalLifecycleOwner.current
        val intercomScope = rememberCoroutineScope()
        // La consulta al puente de InterCOM es binder a otra app: se hace fuera del
        // hilo de UI para que la pantalla no se congele si InterCOM tarda.
        fun refreshIntercom() {
            intercomScope.launch {
                installation = withContext(Dispatchers.IO) {
                    IntercomInstallation.inspect(context)
                }
            }
        }
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    homeMountProfile = SafetyMonitorService.loadMountProfile(context)
                    audioPermissionGranted = hasAudioPermission(context)
                    callPermissionGranted = hasCallPermission(context)
                    notificationPermissionGranted = hasNotificationPermission(context)
                    refreshIntercom()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        // Sondeo ligero del estado de InterCOM para que la luz pase de naranja a verde al empezar el grupo.
        LaunchedEffect(Unit) {
            while (true) {
                installation = withContext(Dispatchers.IO) {
                    IntercomInstallation.inspect(context)
                }
                delay(5_000)
            }
        }
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            audioPermissionGranted = hasAudioPermission(context)
            callPermissionGranted = hasCallPermission(context)
            notificationPermissionGranted = hasNotificationPermission(context)
            permissionFeedback = permissionDenialMessage(result)
            showGpsOffWarning = !SafetyMonitorService.isGpsProviderEnabled(context)
            if (pendingVoiceEnable) {
                pendingVoiceEnable = false
                if (hasAudioPermission(context)) {
                    audioPermissionGranted = true
                    voiceKeywordsGate = true
                    SafetyMonitorService.saveVoiceKeywordsGate(context, true)
                }
            }
            SafetyMonitorService.start(context)
        }

        val featurePermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            audioPermissionGranted = hasAudioPermission(context)
            callPermissionGranted = hasCallPermission(context)
            notificationPermissionGranted = hasNotificationPermission(context)
            permissionFeedback = permissionDenialMessage(result)
        }

        val voicePermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            audioPermissionGranted = granted && hasAudioPermission(context)
            if (audioPermissionGranted && !SafetyStateStore.snapshot.value.phase.locksCriticalSettings()) {
                voiceKeywordsGate = true
                SafetyMonitorService.saveVoiceKeywordsGate(context, true)
                SafetyMonitorService.notifyConfigChanged(context)
                permissionFeedback = null
            } else if (!audioPermissionGranted) {
                permissionFeedback = "Permiso de microfono denegado. La activacion por voz sigue desactivada."
            }
        }

        val onVoiceKeywordsGateChange: (Boolean) -> Unit = { enabled ->
            if (!SafetyStateStore.snapshot.value.phase.locksCriticalSettings()) {
                when {
                    !enabled -> {
                        voiceKeywordsGate = false
                        SafetyMonitorService.saveVoiceKeywordsGate(context, false)
                        SafetyMonitorService.notifyConfigChanged(context)
                    }
                    !audioPermissionGranted -> {
                        voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    else -> {
                        voiceKeywordsGate = true
                        SafetyMonitorService.saveVoiceKeywordsGate(context, true)
                        SafetyMonitorService.notifyConfigChanged(context)
                    }
                }
            }
        }

        val callPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            callPermissionGranted = granted && hasCallPermission(context)
            permissionFeedback = if (callPermissionGranted) {
                null
            } else {
                "Permiso de llamadas denegado. El SOS no podra iniciar llamadas; WhatsApp sigue disponible."
            }
        }

        // El interruptor de avisos vive ahora en la pantalla principal (S O S ON/OFF);
        // se comporta igual que el que estaba en Configuracion, permiso incluido.
        val onSosActionsEnabledChange: (Boolean) -> Unit = { enabled ->
            if (!SafetyStateStore.snapshot.value.phase.locksCriticalSettings()) {
                sosActionsEnabled = enabled
                repository.setSosActionsEnabled(enabled)
                if (enabled &&
                    contacts.any(EmergencyContact::callEnabled) &&
                    !hasCallPermission(context)
                ) {
                    callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
                }
            }
        }

        // La sensibilidad se cambia en Configuracion > Sensores y se guarda aqui una
        // sola vez, para que las dos pantallas hagan exactamente lo mismo.
        val onSensitivityChange: (Float) -> Unit = { g ->
            sensitivityG = g
            if (SafetyStateStore.snapshot.value.phase == SafetyPhase.STOPPED) {
                SafetyMonitorService.saveSensitivity(context, g)
            }
        }
        val onHitTest: () -> Unit = {
            SensorCalibrationService.testHit(
                context,
                SafetyStateStore.snapshot.value.effectiveThresholdG ?: sensitivityG
            )
        }
        // La ventana de promedio se elige en Configuracion > Sensores; en la pantalla
        // principal solo se muestra cual esta activa.
        val onWindowChange: (AdaptationWindowMode) -> Unit = { mode ->
            SafetyMonitorService.setAdaptationWindow(context, mode)
        }

        fun permissionsNeededForConfiguredFeatures(): Array<String> = buildList {
            if (!SafetyMonitorService.hasPreciseLocationPermission(context)) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (!notificationPermissionGranted && Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (voiceKeywordsGate && !audioPermissionGranted) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (sosActionsEnabled && contacts.any(EmergencyContact::callEnabled) && !callPermissionGranted) {
                add(Manifest.permission.CALL_PHONE)
                add(Manifest.permission.READ_PHONE_STATE)
            }
        }.toTypedArray()

        fun requestConfiguredFeaturePermissions() {
            val permissions = permissionsNeededForConfiguredFeatures()
            if (permissions.isEmpty()) {
                permissionFeedback = null
            } else {
                featurePermissionLauncher.launch(permissions)
            }
        }

        fun startMonitoring() {
            pendingVoiceEnable = !voiceKeywordsGate
            val permissions = buildSet {
                addAll(permissionsNeededForConfiguredFeatures())
                if (pendingVoiceEnable && !hasAudioPermission(context)) {
                    add(Manifest.permission.RECORD_AUDIO)
                }
            }.toTypedArray()

            when {
                permissions.isNotEmpty() -> permissionLauncher.launch(permissions)
                else -> {
                    if (pendingVoiceEnable) {
                        pendingVoiceEnable = false
                        if (hasAudioPermission(context)) {
                            audioPermissionGranted = true
                            voiceKeywordsGate = true
                            SafetyMonitorService.saveVoiceKeywordsGate(context, true)
                        }
                    }
                    showGpsOffWarning = !SafetyMonitorService.isGpsProviderEnabled(context)
                    SafetyMonitorService.start(context)
                }
            }
        }

        val callPermissionRequired = sosActionsEnabled && contacts.any(EmergencyContact::callEnabled)
        val permissionWarnings = buildList {
            permissionFeedback?.let(::add)
            if (!SafetyMonitorService.hasPreciseLocationPermission(context)) {
                add("Ubicacion precisa sin autorizar: los golpes se detectan, pero no habra mapa ni velocidad.")
            }
            if (!notificationPermissionGranted) {
                add("Notificaciones sin autorizar: Android puede ocultar el aviso del monitor activo.")
            }
            if (voiceKeywordsGate && !audioPermissionGranted) {
                add("Voz AYUDA / SOCORRO configurada, pero sin permiso de microfono.")
            }
            if (callPermissionRequired && !callPermissionGranted) {
                add("Hay llamadas SOS configuradas, pero falta el permiso de llamadas.")
            }
        }.distinct()
        val criticalSettingsLocked = snapshot.phase.locksCriticalSettings()
        val onFallOrientationGateChange: (Boolean) -> Unit = { enabled ->
            if (!SafetyStateStore.snapshot.value.phase.locksCriticalSettings()) {
                fallOrientationGate = enabled
                SafetyMonitorService.saveFallOrientationGate(context, enabled)
                SafetyMonitorService.notifyConfigChanged(context)
            }
        }
        val onVehicleChange: (VehicleProfile) -> Boolean = { vehicle ->
            if (SafetyMonitorService.setVehicleProfile(context, vehicle)) {
                SafetyStateStore.update { it.copy(vehicleProfile = vehicle) }
                sensitivityG = SafetyMonitorService.loadSensitivity(context)
                voiceKeywordsGate = SafetyMonitorService.loadVoiceKeywordsGate(context)
                fallOrientationGate = SafetyMonitorService.loadFallOrientationGate(context)
                homeMountProfile = SafetyMonitorService.loadMountProfile(context)
                SafetyMonitorService.notifyConfigChanged(context)
                true
            } else {
                SafetyStateStore.log("Deten el monitor antes de cambiar de vehiculo")
                false
            }
        }
        fun applyQuickMode(mode: QuickMode) {
            if (!onVehicleChange(mode.vehicle)) return
            if (SafetyMonitorService.setMountProfile(context, mode.mount)) {
                homeMountProfile = mode.mount
                if (mode.mount.autoEnablesFallGate) onFallOrientationGateChange(true)
                SafetyStateStore.log("Modo: ${mode.label}")
            } else {
                SafetyStateStore.log("Deten el monitor antes de cambiar de modo")
            }
        }
        val quickMode = QuickMode.from(
            SafetyMonitorService.loadVehicleProfile(context),
            homeMountProfile
        )

        BackHandler(enabled = screen != AppScreen.HOME) { screen = AppScreen.HOME }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                SeguTopBar(
                    screen = screen,
                    onSettings = { screen = AppScreen.SETTINGS },
                    onSensors = { screen = AppScreen.SENSORS },
                    onAbout = { showAbout = true },
                    onThemeToggle = { onDarkThemeChange(!darkTheme) },
                    onBack = { screen = AppScreen.HOME }
                )
            },
            bottomBar = {
                ActiveSafetyCancelBar(
                    snapshot = snapshot,
                    onCancel = { SafetyMonitorService.cancel(context) }
                )
            }
        ) { padding ->
            when (screen) {
                AppScreen.HOME -> {
                    val voiceLevelDb = snapshot.voiceLevelDb
                    val voiceRecognizing = snapshot.voiceRecognizing
                    val audioLevel = AudioLevelReading(
                        progress = if (voiceRecognizing) 1f else voiceLevelDb?.let(::meterProgress) ?: 0f,
                        label = snapshot.voiceDetail.ifBlank { "EN ESPERA" },
                        active = voiceLevelDb != null,
                        fromVoice = voiceRecognizing
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                item { Spacer(Modifier.height(6.dp)) }
                item {
                    PhaseHeroCard(
                        snapshot = snapshot,
                        audioLevel = audioLevel,
                        sensitivityG = sensitivityG,
                        onSensitivityChange = onSensitivityChange,
                        onWindowChange = onWindowChange,
                        onHitTest = onHitTest,
                        sosActionsEnabled = sosActionsEnabled,
                        onSosActionsEnabledChange = onSosActionsEnabledChange,
                        voiceKeywordsGate = voiceKeywordsGate,
                        voicePermissionGranted = audioPermissionGranted,
                        onVoiceKeywordsGateChange = onVoiceKeywordsGateChange,
                        intercomState = installation.state,
                        onStart = { startMonitoring() },
                        onStop = { SafetyMonitorService.stop(context) }
                    )
                }
                if (showGpsOffWarning) {
                    item {
                        GpsOffWarning(
                            onOpenGpsSettings = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                        )
                    }
                }
                if (permissionWarnings.isNotEmpty()) {
                    item {
                        PermissionReadinessWarning(
                            messages = permissionWarnings,
                            onRequestPermissions = ::requestConfiguredFeaturePermissions
                        )
                    }
                }
                item {
                    SosPanel(onSos = { SafetyMonitorService.confirm(context) })
                }
                item {
                    val obd by ObdLinkController.state.collectAsStateWithLifecycle()
                    val obdSpeedKmh = obd.freshSpeed(System.currentTimeMillis())
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (quickMode == QuickMode.COCHE) {
                            SpeedSourceCard(
                                label = "GPS",
                                speedKmh = snapshot.speedKmh,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        StateToggleButton(
                            label = if (quickMode == QuickMode.COCHE) "MODO" else "- M O D O -",
                            stateLabel = quickMode.label,
                            on = true,
                            onColor = StateOnInk,
                            offColor = StateOnInk,
                            modifier = Modifier.weight(1f),
                            enabled = snapshot.phase == SafetyPhase.STOPPED && !criticalSettingsLocked,
                            accessibilityLabel = "Modo ${quickMode.label}; tocar para cambiar",
                            onClick = { applyQuickMode(QuickMode.next(quickMode)) }
                        )
                        if (quickMode == QuickMode.COCHE) {
                            SpeedSourceCard(
                                label = "OBD2",
                                speedKmh = obdSpeedKmh,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                if (sosActionsEnabled.not() && snapshot.phase != SafetyPhase.STOPPED) {
                    item {
                        SosDisabledWarning()
                    }
                }
                item { Spacer(Modifier.height(6.dp)) }
                }
                }
                AppScreen.SENSORS -> SensorsScreen(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    snapshot = snapshot,
                    intercomState = installation.state,
                    sensitivityG = sensitivityG
                )
                AppScreen.SETTINGS -> EmergencySettingsScreen(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    repository = repository,
                    contacts = contacts,
                    onContactsChanged = { updated ->
                        contacts = updated
                        if (sosActionsEnabled &&
                            updated.any(EmergencyContact::callEnabled) &&
                            !hasCallPermission(context)
                        ) {
                            callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
                        }
                    },
                    criticalSettingsLocked = criticalSettingsLocked,
                    snapshot = snapshot,
                    sensitivityG = sensitivityG,
                    onSensitivityChange = onSensitivityChange,
                    onWindowChange = onWindowChange,
                    onHitTest = onHitTest,
                    fallOrientationGate = fallOrientationGate,
                    onFallOrientationGateChange = onFallOrientationGateChange,
                    onVehicleChange = onVehicleChange,
                    sosActionsEnabled = sosActionsEnabled,
                    voiceKeywordsGate = voiceKeywordsGate,
                    audioPermissionGranted = audioPermissionGranted,
                    onVoiceKeywordsGateChange = onVoiceKeywordsGateChange,
                    callPermissionRequired = callPermissionRequired,
                    callPermissionGranted = callPermissionGranted,
                    whatsappAutoSend = whatsappAutoSend,
                    onWhatsappAutoSendChange = { enabled ->
                        if (!SafetyStateStore.snapshot.value.phase.locksCriticalSettings()) {
                            whatsappAutoSend = enabled
                            WhatsAppAutoSend.setEnabled(context, enabled)
                        }
                    },
                    intercomInstallation = installation,
                    intercomNoticeFeedback = intercomNoticeFeedback,
                    onRefreshIntercom = {
                        intercomNoticeFeedback = null
                        refreshIntercom()
                    },
                    onOpenIntercom = { IntercomInstallation.open(context) },
                    onTestIntercomNotice = {
                        // Enviar y refrescar el puente es binder a otra app: fuera del
                        // hilo de UI, o el boton congela la pantalla si InterCOM tarda.
                        intercomNoticeFeedback = null
                        intercomScope.launch {
                            val info = withContext(Dispatchers.IO) {
                                IntercomInstallation.inspect(context)
                            }
                            installation = info
                            val sent = withContext(Dispatchers.IO) {
                                IntercomInstallation.sendNotice(context)
                            }
                            intercomNoticeFeedback = when {
                                !info.installed ->
                                    "InterCOM no instalado: el aviso no suena en ningun sitio."
                                !info.active ->
                                    "InterCOM apagado: el aviso no suena en ningun sitio. " +
                                        "Enciende INTERCOM WiFi/Datos en InterCOM-Luky."
                                sent -> "Aviso enviado a los motoristas conectados."
                                else -> "Aviso lanzado; no habia otros motoristas conectados."
                            }
                            installation = withContext(Dispatchers.IO) {
                                IntercomInstallation.inspect(context)
                            }
                        }
                    }
                )
                AppScreen.INSTRUCTIONS -> InstructionsScreen(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    snapshot = snapshot
                )
            }
        }

        if (showAbout) {
            AboutDialog(
                versionLabel = appVersionLabel(),
                onDismiss = { showAbout = false },
                onOpenInstructions = {
                    showAbout = false
                    screen = AppScreen.INSTRUCTIONS
                }
            )
        }
    }
}

private enum class AppScreen {
    HOME,
    SENSORS,
    SETTINGS,
    INSTRUCTIONS
}

private enum class QuickMode(
    val label: String,
    val vehicle: VehicleProfile,
    val mount: MountProfile
) {
    BOLSILLO("Bolsillo", VehicleProfile.PHONE, MountProfile.POCKET),
    MOTO("Moto", VehicleProfile.MOTO, MountProfile.HANDLEBAR),
    COCHE("Coche", VehicleProfile.CAR, MountProfile.GLOVEBOX);

    companion object {
        fun from(vehicle: VehicleProfile, mount: MountProfile): QuickMode = when {
            vehicle == VehicleProfile.CAR -> COCHE
            vehicle == VehicleProfile.MOTO -> MOTO
            mount == MountProfile.POCKET -> BOLSILLO
            else -> BOLSILLO
        }

        fun next(current: QuickMode): QuickMode =
            entries[(current.ordinal + 1) % entries.size]
    }
}

/** Version that the package manager reports, so the UI never drifts from build.gradle. */
private fun installedVersionName(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.getOrNull() ?: "?"

@androidx.compose.runtime.Composable
private fun appVersionLabel(): String {
    val context = LocalContext.current
    val name = androidx.compose.runtime.remember(context) { installedVersionName(context) }
    return "V-$name"
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
private fun SeguTopBar(
    screen: AppScreen,
    onSettings: () -> Unit,
    onSensors: () -> Unit,
    onAbout: () -> Unit,
    onThemeToggle: () -> Unit,
    onBack: () -> Unit
) {
    when (screen) {
        AppScreen.HOME -> Surface(color = MaterialTheme.colorScheme.surface) {
            Column {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
                        )
                        .height(60.dp)
                        .padding(horizontal = 8.dp)
                ) {
                    val compact = maxWidth < 390.dp
                    val titleSize = if (compact) 20.sp else 22.sp
                    val versionSize = if (compact) 9.sp else 10.sp
                    Row(
                        modifier = Modifier.align(Alignment.CenterStart),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TopBarIcon(
                            contentDescription = "Abrir configuracion",
                            onClick = onSettings
                        ) {
                            CircleIconFrame(tint = MaterialTheme.colorScheme.primary) {
                                Text(
                                    "\u2699",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 22.sp,
                                    lineHeight = 22.sp
                                )
                            }
                        }
                        TopBarIcon(
                            contentDescription = "Abrir sensores",
                            onClick = onSensors
                        ) {
                            CircleIconFrame(tint = MaterialTheme.colorScheme.primary) {
                                SensorGlyph(tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    TopBarIcon(
                        contentDescription = "Informacion de Segu-Luky",
                        onClick = onAbout,
                        modifier = Modifier.align(Alignment.CenterEnd)
                    ) {
                        CircleIconFrame(tint = MaterialTheme.colorScheme.primary) {
                            Text(
                                "i",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 17.sp,
                                lineHeight = 17.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onThemeToggle)
                            .semantics {
                                contentDescription = "Cambiar tema claro u oscuro"
                                role = Role.Button
                            }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Segu-Luky",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            fontSize = titleSize,
                            lineHeight = titleSize * 1.15f,
                            maxLines = 1
                        )
                        Text(
                            appVersionLabel(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = versionSize,
                                lineHeight = versionSize * 1.2f
                            ),
                            maxLines = 1
                        )
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
        }
        AppScreen.SENSORS -> CenteredSeguTopBar(
            title = "Sensores",
            navigation = { TextButton(onClick = onBack) { Text("VOLVER") } }
        )
        AppScreen.SETTINGS -> CenteredSeguTopBar(
            title = "Configuracion",
            navigation = { TextButton(onClick = onBack) { Text("VOLVER") } }
        )
        AppScreen.INSTRUCTIONS -> CenteredSeguTopBar(
            title = "Instrucciones",
            navigation = { TextButton(onClick = onBack) { Text("VOLVER") } }
        )
    }
}

@androidx.compose.runtime.Composable
private fun CenteredSeguTopBar(
    title: String,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    titleSize: TextUnit = MaterialTheme.typography.titleLarge.fontSize,
    navigation: @androidx.compose.runtime.Composable () -> Unit = {},
    action: @androidx.compose.runtime.Composable () -> Unit = {}
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
                    )
                    .height(60.dp)
                    .padding(horizontal = 8.dp)
            ) {
                Text(
                    text = title,
                    modifier = Modifier.align(Alignment.Center),
                    color = titleColor,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = titleSize,
                        lineHeight = titleSize * 1.2f
                    )
                )
                Box(Modifier.align(Alignment.CenterStart)) { navigation() }
                Box(Modifier.align(Alignment.CenterEnd)) { action() }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun TopBarIcon(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @androidx.compose.runtime.Composable androidx.compose.foundation.layout.BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
        content = content
    )
}

/** Circulo naranja comun que enmarca los iconos de la barra superior. */
@androidx.compose.runtime.Composable
private fun CircleIconFrame(
    tint: Color,
    content: @androidx.compose.runtime.Composable androidx.compose.foundation.layout.BoxScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .border(2.dp, tint, CircleShape),
        contentAlignment = Alignment.Center,
        content = content
    )
}

private enum class AboutPage {
    MENU,
    UPDATE,
    PRIVACY,
    VERIFY,
    HISTORY,
    SUGGESTIONS,
    CREDITS
}

private sealed class UpdateState {
    data object Checking : UpdateState()
    data class Done(val status: UpdateChecker.Status) : UpdateState()
}

/** Menu de informacion abierto desde el icono de la derecha. */
@androidx.compose.runtime.Composable
private fun AboutDialog(
    versionLabel: String,
    onDismiss: () -> Unit,
    onOpenInstructions: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(AboutPage.MENU) }
    var updateState by remember { mutableStateOf<UpdateState?>(null) }

    fun checkUpdates() {
        page = AboutPage.UPDATE
        if (updateState is UpdateState.Checking) return
        updateState = UpdateState.Checking
        val current = versionLabel.removePrefix("V-")
        scope.launch {
            val status = withContext(Dispatchers.IO) { UpdateChecker.check(current) }
            updateState = UpdateState.Done(status)
        }
    }

    fun openPage(target: AboutPage) {
        page = target
    }

    val title = when (page) {
        AboutPage.MENU -> "Segu-Luky"
        AboutPage.UPDATE -> "Buscar actualizacion"
        AboutPage.PRIVACY -> "Privacidad"
        AboutPage.VERIFY -> "Verificar instalacion"
        AboutPage.HISTORY -> "Historial"
        AboutPage.SUGGESTIONS -> "Sugerencias"
        AboutPage.CREDITS -> "Creditos"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    if (page == AboutPage.MENU) "Informacion y ayuda" else versionLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (page == AboutPage.MENU) {
                    Text(
                        versionLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                when (page) {
                    AboutPage.MENU -> {
                        AboutRow(
                            R.drawable.ic_refresh, AboutIconGreenBg, AboutIconGreenFg,
                            "Buscar actualizacion", "Comprobar version nueva", ::checkUpdates
                        )
                        AboutRow(
                            R.drawable.ic_help, AboutIconBlueBg, AboutIconBlueFg,
                            "Como usar", "Instrucciones paso a paso", onOpenInstructions
                        )
                        AboutRow(
                            R.drawable.ic_lock, AboutIconOrangeBg, AboutIconOrangeFg,
                            "Privacidad", "Que se guarda y se envia",
                            { openPage(AboutPage.PRIVACY) }
                        )
                        AboutRow(
                            R.drawable.ic_lock, AboutIconGreenBg, AboutIconGreenFg,
                            "Verificar instalacion", "Huella de firma del APK",
                            { openPage(AboutPage.VERIFY) }
                        )
                        AboutRow(
                            R.drawable.ic_history, AboutIconBlueBg, AboutIconBlueFg,
                            "Historial", "Cambios recientes", { openPage(AboutPage.HISTORY) }
                        )
                        AboutRow(
                            R.drawable.ic_mail, AboutIconOrangeBg, AboutIconOrangeFg,
                            "Sugerencias", "Fallos o mejoras", { openPage(AboutPage.SUGGESTIONS) }
                        )
                        AboutRow(
                            R.drawable.ic_share, AboutIconGreenBg, AboutIconGreenFg,
                            "Compartir", "Enlace de Segu-Luky",
                            {
                                shareApp(context)
                                onDismiss()
                            }
                        )
                        AboutRow(
                            R.drawable.ic_route, AboutIconBlueBg, AboutIconBlueFg,
                            "Creditos", "Idea original y autor", { openPage(AboutPage.CREDITS) }
                        )
                    }
                    AboutPage.UPDATE -> UpdatePage(
                        state = updateState,
                        versionLabel = versionLabel,
                        onOpenLink = { openLink(context, it) },
                        onRetry = ::checkUpdates
                    )
                    AboutPage.PRIVACY -> AboutParagraph(privacyText())
                    AboutPage.VERIFY -> VerifyPage(context, versionLabel)
                    AboutPage.HISTORY -> AboutParagraph(historyText(versionLabel))
                    AboutPage.SUGGESTIONS -> SuggestionsPage(context, versionLabel)
                    AboutPage.CREDITS -> CreditsPage()
                }
            }
        },
        confirmButton = {
            Row {
                if (page != AboutPage.MENU) {
                    TextButton(onClick = { page = AboutPage.MENU }) { Text("VOLVER") }
                }
                TextButton(onClick = onDismiss) { Text("CERRAR") }
            }
        }
    )
}

/** Paleta de los iconos del menu de informacion (igual que en Rutas Moteras). */
private val AboutIconGreenBg = Color(0xFFE7F7EE)
private val AboutIconGreenFg = Color(0xFF1E9E57)
private val AboutIconBlueBg = Color(0xFFE8F0FE)
private val AboutIconBlueFg = Color(0xFF1A73E8)
private val AboutIconOrangeBg = Color(0xFFFFF1E6)
private val AboutIconOrangeFg = Color(0xFFC2410C)

// Botones que comparten fila: menos relleno lateral para que la etiqueta quepa en una linea.
private val CompactButtonPadding = androidx.compose.foundation.layout.PaddingValues(
    horizontal = 4.dp,
    vertical = 8.dp
)

// "S O S" con espacios duros: el texto nunca se parte entre las letras al ajustar lineas.
private const val SOS_HARD = "S\u00A0O\u00A0S"

// Etiqueta de boton estrecho: una sola linea y tamano reducido para que nunca se recorte.
@androidx.compose.runtime.Composable
private fun TightButtonLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        maxLines = 1
    )
}

@androidx.compose.runtime.Composable
private fun AboutRow(
    iconRes: Int,
    iconBg: Color,
    iconFg: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(iconBg, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = iconFg,
                modifier = Modifier.size(19.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            ">",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@androidx.compose.runtime.Composable
private fun AboutParagraph(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        lineHeight = 18.sp
    )
}

/**
 * SHA-256 del certificado con el que esta firmado este APK.
 *
 * Es lo unico que no puede falsificar un APK modificado: si alguien reempaqueta
 * la app con otra clave, esta huella cambia. Play Protect avisa de cualquier APK
 * fuera de Play, asi que esta pantalla es la forma de comprobar en el propio
 * movil que lo instalado es el build oficial.
 */
private fun signingFingerprint(context: Context): String? = runCatching {
    val info = context.packageManager.getPackageInfo(
        context.packageName,
        PackageManager.GET_SIGNING_CERTIFICATES
    )
    val signer = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return@runCatching null
    MessageDigest.getInstance("SHA-256")
        .digest(signer.toByteArray())
        .joinToString(":") { "%02X".format(it) }
}.getOrNull()

@androidx.compose.runtime.Composable
private fun VerifyPage(context: Context, versionLabel: String) {
    val fingerprint = remember { signingFingerprint(context) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Huella de firma de esta instalacion",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            fingerprint ?: "No disponible",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AboutParagraph(
            "Debe coincidir, caracter por caracter, con la huella publicada en el repositorio " +
                "(docs/SEGURIDAD.md). Si no coincide, este APK no es el build oficial: " +
                "desinstalalo y vuelve a bajar $versionLabel de la release de GitHub."
        )
        Text(
            "Por que Play Protect avisa",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        AboutParagraph(
            "La app no viene de Play y combina permisos que su detector asocia a troyanos: " +
                "enviar SMS, marcar llamadas, grabar audio y un servicio de accesibilidad que " +
                "lee la pantalla para pulsar Enviar en WhatsApp. Es una alerta automatica de Android, no una " +
                "deteccion de codigo malicioso. El detalle completo, con los destinos de red y " +
                "las comprobaciones, esta en docs/SEGURIDAD.md."
        )
        AboutParagraph(
            "Esta app no tiene cuentas, ni anuncios, ni estadisticas de uso, ni servidor propio: solo habla " +
                "con el bot de Telegram que configures y con GitHub, para mirar si hay " +
                "actualizaciones."
        )
    }
}

@androidx.compose.runtime.Composable
private fun UpdatePage(
    state: UpdateState?,
    versionLabel: String,
    onOpenLink: (String) -> Unit,
    onRetry: () -> Unit
) {
    val done = state as? UpdateState.Done
    val available = done?.status as? UpdateChecker.Status.Available
    AboutParagraph(
        when {
            state is UpdateState.Checking -> "Comprobando la ultima version publicada en GitHub..."
            available != null -> "Hay una version nueva publicada: ${available.tag}.\nTu version instalada es la $versionLabel."
            done?.status == UpdateChecker.Status.UpToDate ->
                "Estas al dia: la $versionLabel es la ultima version publicada."
            done?.status == UpdateChecker.Status.NoReleases ->
                "Todavia no hay ninguna version publicada en GitHub, asi que no hay nada que descargar.\nTu version instalada es la $versionLabel."
            done?.status is UpdateChecker.Status.Failed ->
                "No se pudo comprobar ahora mismo (${(done.status as UpdateChecker.Status.Failed).detail}).\nRevisa la conexion e intentalo otra vez."
            else -> "Pulsa COMPROBAR para mirar si hay una version nueva."
        }
    )
    if (state !is UpdateState.Checking) {
        if (available != null) {
            Button(
                onClick = { onOpenLink(available.url) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("ABRIR DESCARGA") }
        } else {
            OutlinedButton(
                onClick = { onOpenLink(UpdateChecker.RELEASES_PAGE) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("ABRIR GITHUB") }
        }
        OutlinedButton(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth()
        ) { Text("COMPROBAR OTRA VEZ") }
    }
}

@androidx.compose.runtime.Composable
private fun SuggestionsPage(context: Context, versionLabel: String) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AboutParagraph(
            "Para fallos o mejoras: abre una incidencia en GitHub (hace falta cuenta) o envia los datos del movil " +
                "a quien te mantiene la app. Con esos datos se puede reproducir el fallo."
        )
        AboutParagraph(
            "Los datos copiados son version, modelo, Android, sensibilidad y estado del monitor. " +
                "No incluyen contactos, la clave de Telegram ni la ubicacion."
        )
        Button(
            onClick = { openLink(context, UpdateChecker.ISSUES_PAGE) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("ABRIR GITHUB") }
        OutlinedButton(
            onClick = { copyToClipboard(context, deviceInfoText(context, versionLabel)) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("COPIAR DATOS DEL MOVIL") }
    }
}

@androidx.compose.runtime.Composable
private fun CreditsPage() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(listOf(Color(0xFFFFA22B), Color(0xFFDD4E00))))
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "IDEA ORIGINAL DE",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "LUCIANO CJ",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color(0x66FFFFFF))
                )
                Text(
                    "\"No me copies sino es para mejorarme\"",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "Segu-Luky - monitor de seguridad y SOS para moto",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        Image(
            painter = painterResource(R.drawable.credito_moto),
            contentDescription = "Foto de la moto",
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)),
            contentScale = ContentScale.FillWidth
        )
        AboutParagraph(
            "Gracias a quienes prueban la app en moto y avisan de los fallos: cada version corrige algo que se " +
                "detecto usando el movil de verdad."
        )
        AboutParagraph(
            "Sin publicidad, sin cuentas y sin servidores propios. La app no vende ni comparte datos."
        )
    }
}

private fun privacyText(): String = buildString {
    appendLine("Todo lo que configuras (contactos, telefonos, textos de WhatsApp, la clave del bot de Telegram, " +
        "sensibilidad e interruptores) se guarda solo dentro de este movil.")
    appendLine()
    appendLine("Ubicacion: se usa unicamente para el monitor y el SOS. La ultima posicion no se envia a ningun " +
        "servidor de Segu-Luky; viaja en el mensaje de SOS a tus contactos y solo si tiene menos de 5 minutos.")
    appendLine()
    appendLine("Microfono: si activas la voz, el reconocedor del telefono escucha AYUDA y SOCORRO. " +
        "No se graba ni se guarda audio; NIVEL DE AUDIO solo muestra un numero en pantalla.")
    appendLine()
    appendLine("Telegram: si guardas la clave del bot, los avisos se envian a Telegram con esa clave y el numero " +
        "de chat que tu indiques. Sin bot configurado no sale nada de la app.")
    appendLine()
    appendLine("Ninguna otra conexion: no hay estadisticas, ni publicidad, ni cuentas. Buscar actualizacion solo lee la " +
        "pagina publica del proyecto en GitHub.")
    appendLine()
    append("Borrar datos: al desinstalar la app se borran contactos, clave y registro.")
}

private fun historyText(versionLabel: String): String = buildString {
    appendLine("Version instalada: $versionLabel")
    appendLine()
    appendLine("1.98s - El sensor externo WT9011DCL-BT50 solo se activa con TIPO DE VEHICULO en MOTO: " +
        "la tarjeta desaparece en MOVIL y COCHE, y el enlace BLE se corta al cambiar de perfil.")
    appendLine("1.97s - Sensor externo WT9011DCL-BT50 por BLE para detectar vuelco por inclinacion X/Y: umbral " +
        "ajustable 50-60 grados (55 por defecto), 5 s sostenidos y condicion independiente del resto de avisos. " +
        "Flechas X e Y junto al boton SOS y tarjeta de diagnostico para comprobar el montaje.")
    appendLine("1.96s - Si el SOS sale sin GPS (tunel, parking, GPS apagado), la ubicacion se reenvia sola en " +
        "cuanto el movil la consigue: SMS y Telegram salen automaticos, y en WhatsApp o enlace t.me queda el " +
        "aviso en la notificacion para enviarlo con un toque. Se acabo el 'ubicacion GPS no disponible' para " +
        "siempre.")
    appendLine("1.95s - Arreglado un fallo grave del detector: si el telefono se reiniciaba en mitad de la " +
        "cuenta atras, el detector se quedaba dormido para siempre y no volvia a avisar de un golpe. Ahora " +
        "se despierta solo. Ademas, un veredicto viejo de impacto ya no puede pisar al nuevo.")
    appendLine("1.94s - Configurar Telegram ya no pide copiar numeros de chat a mano: cada contacto tiene " +
        "BUSCAR CHATS, que pregunta al bot quien le ha escrito y rellena el numero al tocar el chat. " +
        "ENVIAR PRUEBA manda a todos los contactos con bot y dice cual fallo. Instrucciones nuevas en " +
        "Como usar, pasos 7 y 8.")
    appendLine("1.93s - En modo oscuro las filas de Sensores se leen bien y la barra superior ya no queda " +
        "debajo de los botones del telefono al girarlo.")
    appendLine("1.92s - Los botones de la barra superior se separan entre si y recuperan un area tactil de 48 dp.")
    appendLine("1.91s - El titulo queda centrado con la version debajo e Informacion al extremo derecho; el " +
        "texto se reduce antes de solaparse.")
    appendLine("1.90s - En modo Coche la fila MODO ensena GPS a un lado y OBD2 al otro, con la velocidad de cada uno.")
    appendLine("1.89s - El modo claro u oscuro se cambia tocando Segu-Luky en la barra; TEMA VISUAL sale de " +
        "Configuracion.")
    appendLine("1.88s - Modo visual claro u oscuro, pensado para pantallas AMOLED; la eleccion se guarda y " +
        "aguanta al reiniciar.")
    appendLine("1.87s - La velocidad ya no apaga los sensores: solo limita el muestreo (100 Hz en marcha, 50 Hz " +
        "parado) con histeresis de 15 y 10 km/h.")
    appendLine("1.86s - Compartir apunta siempre a la ultima version y MODO ya no vuelve a Bolsillo al parar el " +
        "monitor.")
    appendLine("1.85s - Boton - M O D O - debajo del SOS, nivel de audio seleccionable y menos lineas en la " +
        "pantalla principal.")
    appendLine("1.84s - Barra superior centrada, sensores mas limpio y el vuelco vuelve a mostrarse en Coche.")
    appendLine("1.83s - Telegram y GPS mas fiables: el aviso del bot ya no se descarta al cerrar el protocolo y " +
        "un GPS apagado ya no deja la app sin red.")
    appendLine("1.82s - Un golpe con el movil en el bolsillo solo cuenta si la velocidad es de los ultimos " +
        "5 segundos; el margen tras el golpe sube a 6 segundos y ya no lo cancela la vibracion del motor. " +
        "Los golpes ya no se clasifican dos veces ni con una caida de velocidad inventada.")
    appendLine("1.81s - La secuencia del golpe se mide tambien a los 5 y a los 15 segundos; el ancho y el giro " +
        "solo puntuan cuando son fiables y los badenes exigen un viaje sin frenazos ni caladas.")
    appendLine("1.80s - Cada golpe se clasifica en ruido, bache, golpe duro o choque mirando la forma del " +
        "impacto, el giro, la caida de velocidad y la recuperacion.")
    appendLine("1.79s - La sacudida brusca (jerk) solo suma cuando no hay dato de velocidad, para no contar " +
        "dos veces el mismo golpe.")
    appendLine("1.78s - En COCHE los soportes son sin definir, bolsillo, guantera o personalizado; el soporte " +
        "Coche pasa a llamarse Guantera.")
    appendLine("1.77s - Al cambiar de vehiculo, la tarjeta de soporte ensena el soporte que se aplica de verdad.")
    appendLine("1.76s - Cada vehiculo ofrece solo los soportes que tienen sentido, asi no se eligen " +
        "combinaciones imposibles.")
    appendLine("1.75s - DIAGNOSTICO > ARCHIVOS DE CALIBRACION dice cual es el bloque guardado, su puntuacion y " +
        "su tamano, y se puede borrar.")
    appendLine("1.74s - Las grabaciones de calibracion se parten en bloques de 15 minutos y solo se conserva el " +
        "de mejor puntuacion.")
    appendLine("1.73s - Cada golpe contado se clasifica en ruido, bache, golpe duro o choque (solo mide: no " +
        "cambia cuando salta el aviso).")
    appendLine("1.72s - En COCHE aparece ENLACE CON EL COCHE para leer velocidad y regimen del motor por " +
        "Bluetooth con un ELM327.")
    appendLine("1.71s - Configuracion > Sensores empieza con TIPO DE VEHICULO: MOVIL, COCHE o MOTO.")
    appendLine("1.70s - Linea nueva: perfil aprendido por soporte (bolsillo, manillar, deposito, mochila, " +
        "personalizado) y calibracion con tasa proporcional a la velocidad (20-100 Hz). El CSV incluye " +
        "soporte, velocidad, magnitud, hora de pared y cambios de tasa.")
    appendLine("1.64s - Textos de toda la app en lenguaje llano: se quitan t.me, chat id, token, umbral, " +
        "API, analitica y otras palabras tecnicas. Ahora se lee nivel de golpe, clave del bot, numero de " +
        "chat y enlace de Telegram.")
    appendLine("1.63s - Texto de ENVIO AUTO MENSAJES reescrito: ahora dice que la app abre el chat de " +
        "WhatsApp o el de Telegram y pulsa Enviar sin preguntar.")
    appendLine("1.62s - Repaso visual de todos los menus: cada tarjeta pone el titulo en una linea y la " +
        "etiqueta de estado debajo, asi \"INTERRUPTOR S O S\" ya no se parte. Los permisos quedan en dos " +
        "lineas legibles y el boton ACTUALIZAR de InterCOM deja de recortarse.")
    appendLine("1.61s - T. PROMEDIO va mas pegada a la sensibilidad y AUTO / 1 / 2 / 3 se muestra en " +
        "negrilla, con el activo en verde.")
    appendLine("1.60s - Cuando AUTO cambia de ventana, el numero de T. PROMEDIO destella tres veces para " +
        "que se note sin mirar fijo.")
    appendLine("1.59s - En T. PROMEDIO se quita el texto de minutos: con AUTO se marcan en verde AUTO y el " +
        "numero de minutos que se aplica; con ventana fija, solo ese numero.")
    appendLine("1.58s - La ventana de promedio (ADAPT.) se elige en Configuracion > Sensores; en la pantalla " +
        "principal solo se ve T. PROMEDIO con la opcion activa en verde.")
    appendLine("1.57s - La pantalla principal se queda con la etiqueta de sensibilidad y la ventana ADAPT.; el " +
        "deslizador, la escala, el pico del viaje y PRUEBA DE GOLPE pasan a Configuracion > Sensores.")
    appendLine("1.56s - La pantalla principal tiene dos botones de estado: MONITOR ON/OFF (verde/naranja) y " +
        "S O S ON/OFF (verde/rojo). El interruptor de avisos SOS sale de Configuracion.")
    appendLine("1.55s - Configuracion se divide en cuatro submenus: Sensores, Contactos SOS, Canales de " +
        "salida SOS y Permisos sistema.")
    appendLine("1.54s - La sensibilidad se afina sola: aprende el ruido y el traqueteo de cada movil, " +
        "adapta el muestreo y el umbral en ventanas de 1-3 minutos, y anade PRUEBA DE GOLPE.")
    appendLine("1.53s - La cola del bot de Telegram ya no pierde avisos si se escribe a la vez, y el historial " +
        "recupera todas las versiones.")
    appendLine("1.52s - Verificar instalacion muestra la huella de la firma para comprobar que la APK es la " +
        "oficial.")
    appendLine("1.51s - Cancelar el SOS ya no abre WhatsApp: queda una notificacion de mensaje pendiente.")
    appendLine("1.50s - Cola del bot de Telegram con reintentos (20 s, 60 s, 120 s, 300 s) fuera del hilo del SOS.")
    appendLine("1.49s - Buscar actualizacion mira releases y etiquetas: la 1.48s solo estaba etiquetada y decia " +
        "que estabas al dia.")
    appendLine("1.48s - La escalada de llamadas no cuelga una llamada en curso y el microfono va en primer plano.")
    appendLine("1.47s - INTERCOM-LUKY pasa a Configuracion con los tres estados (gris y naranja: el aviso no " +
        "suena en ningun sitio).")
    appendLine("1.46s - Luz INTERCOM de tres estados, boton PROBAR AVISO y puente entre apps.")
    appendLine("1.45s - Buscar actualizacion lee el listado de releases en vez de la ultima.")
    appendLine("1.44s - Botoneras con la etiqueta en una sola linea.")
    appendLine("1.43s - Iconos en circulo naranja agrupados y Segu-Luky en la misma linea que la version.")
    appendLine("1.42s - Creditos con la foto de la moto y la etiqueta de version ya no parece un boton.")
    appendLine("1.41s - Boton de informacion con buscar actualizacion, como usar, privacidad, historial, " +
        "sugerencias, compartir y creditos.")
    appendLine("1.40s - Columnas verdes a los lados del boton S O S que se llenan durante los 5 segundos.")
    appendLine("1.39s - Se elimina el gesto de dos dedos dentro de la app.")
    appendLine("1.38s - La voz vuelve tras parar el monitor y el bloqueo de orientacion ya no se reinicia al " +
        "cambiar ajustes.")
    appendLine("1.37s - Revision de bateria: el servicio solo publica datos nuevos.")
    appendLine("1.36s - Todos los interruptores activos en verde.")
    appendLine("1.35s - VELOCIDAD muestra SIN DATO y explica que necesita GPS.")
    appendLine("1.34s - GPS con red: posicion bajo techo y antiguedad legible.")
    appendLine("1.33s - Puntos de estado de los sensores mas grandes.")
    appendLine("1.32s - Barras de audio y movimiento con el grosor del ajuste.")
    append("1.31s - El aviso de avisos SOS desactivados pasa al final de la pantalla.")
}

/** Abre un enlace fuera de la app; si no hay navegador lo dice en vez de cerrarse. */
private fun openLink(context: Context, url: String) {
    val opened = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)
    if (!opened) Toast.makeText(context, "No hay ninguna app para abrir el enlace", Toast.LENGTH_SHORT).show()
}

private fun shareApp(context: Context) {
    val text = "Segu-Luky: monitor de seguridad y SOS para moto. Si el movil detecta un golpe fuerte avisa a tus " +
        "contactos con tu posicion.\nUltima version: ${UpdateChecker.LATEST_RELEASE_PAGE}"
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Segu-Luky")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val opened = runCatching {
        context.startActivity(
            Intent.createChooser(intent, "Compartir Segu-Luky").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)
    if (!opened) Toast.makeText(context, "No hay ninguna app para compartir", Toast.LENGTH_SHORT).show()
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    if (clipboard == null) {
        Toast.makeText(context, "No se pudo copiar", Toast.LENGTH_SHORT).show()
        return
    }
    clipboard.setPrimaryClip(ClipData.newPlainText("Segu-Luky", text))
    Toast.makeText(context, "Datos del movil copiados", Toast.LENGTH_SHORT).show()
}

private fun deviceInfoText(context: Context, versionLabel: String): String = buildString {
    appendLine("Segu-Luky $versionLabel")
    appendLine("Modelo: ${CalibrationRepository.deviceModel()}")
    appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    appendLine("Sensibilidad: ${SafetyMonitorService.loadSensitivity(context)} g")
    append("Monitor: ")
    append(
        if (SafetyStateStore.snapshot.value.phase == SafetyPhase.STOPPED) "detenido" else "en marcha"
    )
}

private fun countdownRemainingSeconds(endAt: Long): Long =
    ((endAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L) + 999L) / 1_000L

@androidx.compose.runtime.Composable
private fun ActiveSafetyCancelBar(snapshot: SafetySnapshot, onCancel: () -> Unit) {
    if (!snapshot.phase.locksCriticalSettings()) return
    val countdown = snapshot.phase == SafetyPhase.COUNTDOWN
    val tone = if (countdown) Tone.BAD else Tone.WARN
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = tone.soft,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 10.dp
    ) {
        Button(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = Tone.BAD.text,
                contentColor = Color.White
            )
        ) {
            Text(
                if (countdown) "CANCELAR SOS" else "CANCELAR VALIDACION",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun PermissionReadinessWarning(
    messages: List<String>,
    onRequestPermissions: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFFFFF8E1),
        contentColor = StatusTextColor
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "PERMISOS PENDIENTES",
                color = Color(0xFFC62828),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            messages.forEach { message ->
                Text(message, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth()) {
                Text("SOLICITAR PERMISOS", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun CriticalSettingsLockedWarning() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFFFFEBEE),
        contentColor = StatusTextColor
    ) {
        Text(
            "CONFIGURACION BLOQUEADA: cancela la validacion o el SOS antes de cambiar avisos y contactos.",
            modifier = Modifier.padding(14.dp),
            color = Color(0xFFC62828),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

/** Bloques de Configuracion: indice y submenus, en el mismo orden del indice. */
private enum class SettingsSection(val title: String) {
    ROOT("Configuracion"),
    SENSORS("Sensores"),
    CONTACTS("Contactos SOS"),
    CHANNELS("Canales de salida SOS"),
    PERMISSIONS("Permisos sistema")
}

@androidx.compose.runtime.Composable
private fun EmergencySettingsScreen(
    modifier: Modifier,
    repository: ContactsRepository,
    contacts: List<EmergencyContact>,
    onContactsChanged: (List<EmergencyContact>) -> Unit,
    criticalSettingsLocked: Boolean,
    snapshot: SafetySnapshot,
    sensitivityG: Float,
    onSensitivityChange: (Float) -> Unit,
    onWindowChange: (AdaptationWindowMode) -> Unit,
    onHitTest: () -> Unit,
    fallOrientationGate: Boolean,
    onFallOrientationGateChange: (Boolean) -> Unit,
    onVehicleChange: (VehicleProfile) -> Boolean,
    sosActionsEnabled: Boolean,
    voiceKeywordsGate: Boolean,
    audioPermissionGranted: Boolean,
    onVoiceKeywordsGateChange: (Boolean) -> Unit,
    callPermissionRequired: Boolean,
    callPermissionGranted: Boolean,
    whatsappAutoSend: Boolean,
    onWhatsappAutoSendChange: (Boolean) -> Unit,
    intercomInstallation: IntercomInstallationInfo,
    intercomNoticeFeedback: String?,
    onRefreshIntercom: () -> Unit,
    onOpenIntercom: () -> Unit,
    onTestIntercomNotice: () -> Unit
) {
    val context = LocalContext.current
    val automationLifecycle = LocalLifecycleOwner.current
    var automationRefresh by remember { mutableStateOf(0) }
    DisposableEffect(automationLifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) automationRefresh++
        }
        automationLifecycle.lifecycle.addObserver(observer)
        onDispose { automationLifecycle.lifecycle.removeObserver(observer) }
    }
    val exactAlarmReady = remember(automationRefresh) {
        SafetyMonitorService.canScheduleExactAlarms(context)
    }
    var addingContact by remember { mutableStateOf(false) }
    var editingContact by remember { mutableStateOf<EmergencyContact?>(null) }
    var smsAuto by remember { mutableStateOf(SafetyMonitorService.loadSmsAuto(context)) }
    var mountProfile by remember { mutableStateOf(SafetyMonitorService.loadMountProfile(context)) }
    var vehicleProfile by remember { mutableStateOf(SafetyMonitorService.loadVehicleProfile(context)) }
    // Al cambiar de vehiculo el soporte puede corregirse solo: se relee.
    LaunchedEffect(vehicleProfile) {
        mountProfile = SafetyMonitorService.loadMountProfile(context)
    }
    val calibrationAnyRecording by CalibrationStateStore.state.collectAsStateWithLifecycle()
    val editorVisible = addingContact || editingContact != null
    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        smsAuto = granted
        SafetyMonitorService.saveSmsAuto(context, granted)
    }
    var section by remember { mutableStateOf(SettingsSection.ROOT) }
    // El boton atras del sistema sube un nivel, no cierra la configuracion entera.
    BackHandler(enabled = section != SettingsSection.ROOT) { section = SettingsSection.ROOT }
    val accessibilityReady = remember(automationRefresh) {
        AutomationPermissions.isAccessibilityEnabled(context)
    }
    val overlayReady = remember(automationRefresh) {
        AutomationPermissions.canDrawOverlays(context)
    }
    val smsPermissionGranted = remember(automationRefresh) { hasSendSmsPermission(context) }
    val notificationsReady = remember(automationRefresh) { hasNotificationPermission(context) }
    val locationReady = remember(automationRefresh) {
        SafetyMonitorService.hasPreciseLocationPermission(context)
    }
    val telegramBotReady = remember(automationRefresh) {
        TelegramSos.normalizeBotToken(ContactsRepository(context).telegramBotToken()) != null
    }
    // Al cambiar de bloque la lista vuelve arriba: los items son distintos y el
    // desplazamiento anterior no tiene sentido en otro submenu.
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(section) { listState.scrollToItem(0) }

    LazyColumn(
        state = listState,
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(Modifier.height(8.dp)) }
        if (criticalSettingsLocked) {
            item { CriticalSettingsLockedWarning() }
        }
        if (section == SettingsSection.ROOT) {
            item {
                Text(
                    "Elige un bloque: los ajustes estan agrupados por tema.",
                    modifier = Modifier.padding(start = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                SettingsEntryRow(
                    title = "SENSORES",
                    status = vehicleProfile.badge,
                    tone = Tone.OK,
                    description = when (vehicleProfile) {
                        VehicleProfile.PHONE -> "Golpes, caidas, voz de ayuda y datos del sensor."
                        VehicleProfile.CAR -> "Coche: nivel propio y enlace OBDII en la siguiente version."
                        VehicleProfile.MOTO -> "Moto: modo moto y voz; bus CAN pendiente."
                    },
                    onClick = { section = SettingsSection.SENSORS }
                )
            }
            item {
                SettingsEntryRow(
                    title = "CONTACTOS SOS",
                    status = if (contacts.isEmpty()) "NINGUNO" else "${contacts.size}",
                    tone = if (contacts.isEmpty()) Tone.BAD else Tone.OK,
                    description = "Quien recibe el aviso y en que orden se le llama.",
                    onClick = { section = SettingsSection.CONTACTS }
                )
            }
            item {
                val canales = buildList {
                    if (callPermissionRequired && callPermissionGranted) add("LLAMADAS")
                    if (smsAuto) add("SMS")
                    if (whatsappAutoSend) add("WHATSAPP")
                    if (telegramBotReady) add("TELEGRAM")
                    if (intercomInstallation.state == IntercomState.ACTIVE) add("VOZ")
                }
                SettingsEntryRow(
                    title = "CANALES DE SALIDA SOS",
                    status = when {
                        !sosActionsEnabled -> "DESACTIVADO"
                        canales.isEmpty() -> "SIN CANALES"
                        else -> "${canales.size} ACTIVOS"
                    },
                    tone = when {
                        !sosActionsEnabled -> Tone.OFF
                        canales.isEmpty() -> Tone.BAD
                        else -> Tone.OK
                    },
                    description = if (sosActionsEnabled && canales.isNotEmpty()) {
                        canales.joinToString(" - ")
                    } else {
                        "SOS, WhatsApp, Telegram, SMS, llamadas y aviso de voz de InterCOM."
                    },
                    onClick = { section = SettingsSection.CHANNELS }
                )
            }
            item {
                val pendientes = buildList {
                    if (!exactAlarmReady) add("alarmas exactas")
                    if (whatsappAutoSend && !accessibilityReady) add("accesibilidad")
                    if (whatsappAutoSend && !overlayReady) add("abrir sobre otras apps")
                    if (sosActionsEnabled && smsAuto && !smsPermissionGranted) add("SMS")
                    if (sosActionsEnabled && callPermissionRequired && !callPermissionGranted) add("llamadas")
                    if (voiceKeywordsGate && !audioPermissionGranted) add("microfono")
                }
                SettingsEntryRow(
                    title = "PERMISOS SISTEMA",
                    status = when {
                        pendientes.isEmpty() -> "TODO OK"
                        pendientes.size == 1 -> "FALTA 1"
                        else -> "FALTAN ${pendientes.size}"
                    },
                    tone = when {
                        pendientes.isEmpty() -> Tone.OK
                        pendientes.size == 1 -> Tone.WARN
                        else -> Tone.BAD
                    },
                    description = if (pendientes.isEmpty()) {
                        "Todo lo que necesita la app esta concedido."
                    } else {
                        "Falta: " + pendientes.joinToString(", ")
                    },
                    onClick = { section = SettingsSection.PERMISSIONS }
                )
            }
        } else {
            item { SettingsBackRow(title = section.title, onBack = { section = SettingsSection.ROOT }) }
        }
        if (section == SettingsSection.CHANNELS) {
            item {
                SectionHeader("AVISOS SOS")
            }
            item {
                val callsBlocked = callPermissionRequired && !callPermissionGranted
                SeguCard(
                    title = "INTERRUPTOR $SOS_HARD",
                    status = if (sosActionsEnabled) "ACTIVADO" else "DESACTIVADO",
                    tone = if (sosActionsEnabled) Tone.OK else Tone.BAD,
                    description = "El interruptor $SOS_HARD ON/OFF esta en la pantalla principal, junto al del " +
                        "monitor. Desactivalo para probar los sensores sin avisar a nadie."
                ) {
                    if (callsBlocked) {
                        Text(
                            "Sin el permiso de llamadas no se puede llamar; los mensajes que tengas activados si salen.",
                            color = Tone.BAD.text,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
        if (section == SettingsSection.PERMISSIONS) {
            item {
                SectionHeader("PERMISOS DEL SISTEMA")
            }
            item {
                SeguCard(
                    title = "ALARMAS EXACTAS",
                    status = if (exactAlarmReady) "AUTORIZADAS" else "NO AUTORIZADAS",
                    tone = if (exactAlarmReady) Tone.OK else Tone.BAD,
                    description = "Si Android no las permite, el SOS puede terminar tarde y tardar mas en pasar al siguiente contacto. " +
                        "Autorizalas para que el aviso sea puntual."
                ) {
                    if (!exactAlarmReady) {
                        OutlinedButton(
                            onClick = { AutomationPermissions.openExactAlarmSettings(context) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("AUTORIZAR ALARMAS EXACTAS")
                        }
                    }
                }
            }
            item {
                SectionHeader("ACCESIBILIDAD")
            }
            item {
                SeguCard(
                    title = "ENVIO AUTOMATICO DE WHATSAPP",
                    status = if (accessibilityReady && overlayReady) "LISTO" else "INCOMPLETO",
                    tone = if (accessibilityReady && overlayReady) Tone.OK else Tone.WARN,
                    description = if (whatsappAutoSend) {
                        "Estos dos permisos son los que permiten que la app pulse Enviar y abra WhatsApp sin que la toques."
                    } else {
                        "Solo hacen falta si activas ENVIO AUTO MENSAJES; sin ellos el mensaje se envia a mano."
                    }
                ) {
                    PermissionRow(
                        label = "Accesibilidad",
                        status = if (accessibilityReady) "ACTIVADA" else "DESACTIVADA",
                        tone = if (accessibilityReady) Tone.OK else Tone.BAD,
                        actionLabel = if (accessibilityReady) null else "ABRIR",
                        onAction = { AutomationPermissions.openAccessibilitySettings(context) }
                    )
                    PermissionRow(
                        label = "Abrir sobre otras apps",
                        status = if (overlayReady) "PERMITIDO" else "DENEGADO",
                        tone = if (overlayReady) Tone.OK else Tone.BAD,
                        actionLabel = if (overlayReady) null else "PERMITIR",
                        onAction = { AutomationPermissions.openOverlaySettings(context) }
                    )
                }
            }
            item {
                SectionHeader("PERMISOS DE ANDROID")
            }
            item {
                SeguCard(
                    title = "ESTADO DE LOS PERMISOS",
                    status = if (notificationsReady && locationReady) "OK" else "REVISAR",
                    tone = if (notificationsReady && locationReady) Tone.OK else Tone.WARN,
                    description = "Se conceden en los avisos de Android. Si alguno esta denegado, la app " +
                        "sigue detectando golpes pero pierde esa parte del aviso."
                ) {
                    PermissionRow(
                        label = "Notificaciones",
                        status = if (notificationsReady) "CONCEDIDO" else "DENEGADO",
                        tone = if (notificationsReady) Tone.OK else Tone.BAD
                    )
                    PermissionRow(
                        label = "Ubicacion precisa",
                        status = if (locationReady) "CONCEDIDA" else "DENEGADA",
                        tone = if (locationReady) Tone.OK else Tone.WARN
                    )
                    PermissionRow(
                        label = "Microfono",
                        status = if (audioPermissionGranted) "CONCEDIDO" else "DENEGADO",
                        tone = if (audioPermissionGranted) Tone.OK else Tone.OFF
                    )
                    PermissionRow(
                        label = "Llamadas",
                        status = when {
                            callPermissionGranted -> "CONCEDIDO"
                            callPermissionRequired -> "DENEGADO"
                            else -> "NO HACE FALTA"
                        },
                        tone = when {
                            callPermissionGranted -> Tone.OK
                            callPermissionRequired -> Tone.BAD
                            else -> Tone.OFF
                        }
                    )
                    PermissionRow(
                        label = "SMS",
                        status = when {
                            smsPermissionGranted -> "CONCEDIDO"
                            smsAuto -> "DENEGADO"
                            else -> "NO HACE FALTA"
                        },
                        tone = when {
                            smsPermissionGranted -> Tone.OK
                            smsAuto -> Tone.BAD
                            else -> Tone.OFF
                        }
                    )
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:${context.packageName}")
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("ABRIR AJUSTES DE LA APP")
                    }
                }
            }
        }
        if (section == SettingsSection.SENSORS) {
            item {
                SectionHeader("SENSORES", centered = true)
            }
            item {
                val monitorStopped = snapshot.phase == SafetyPhase.STOPPED
                SeguCard(
                    title = "TIPO DE VEHICULO",
                    status = vehicleProfile.label,
                    tone = Tone.OK,
                    description = vehicleProfile.description
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            VehicleProfile.selectable.forEach { profile ->
                                val selected = vehicleProfile == profile
                                OutlinedButton(
                                    onClick = { if (onVehicleChange(profile)) vehicleProfile = profile },
                                    modifier = Modifier.weight(1f),
                                    enabled = monitorStopped && !calibrationAnyRecording.recording &&
                                        !criticalSettingsLocked,
                                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                        containerColor = if (selected) {
                                            StrongGreen.copy(alpha = 0.16f)
                                        } else {
                                            Color.Transparent
                                        },
                                        contentColor = if (selected) {
                                            StrongGreen
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        }
                                    ),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                        horizontal = 4.dp,
                                        vertical = 4.dp
                                    )
                                ) {
                                    Text(
                                        profile.badge,
                                        maxLines = 1,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                        if (!monitorStopped || calibrationAnyRecording.recording) {
                            Text(
                                if (!monitorStopped) "Deten el monitor para cambiar de vehiculo."
                                else "Deten la grabacion de sensores para cambiar de vehiculo.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            item {
                val monitorStopped = snapshot.phase == SafetyPhase.STOPPED
                SeguCard(
                    title = "SOPORTE DEL MOVIL",
                    status = mountProfile.label,
                    tone = if (mountProfile.isDefined) Tone.OK else Tone.WARN,
                    description = "El aprendizaje de ruido y traqueteo se guarda por separado para cada soporte. " +
                        "Las opciones cambian con el vehiculo: en coche no tiene sentido el deposito. " +
                        "Elige donde viaja el telefono antes de iniciar el monitor."
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        vehicleProfile.allowedMounts.chunked(2).forEach { rowItems ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                rowItems.forEach { profile ->
                                    val selected = mountProfile == profile
                                    OutlinedButton(
                                        onClick = {
                                            if (SafetyMonitorService.setMountProfile(context, profile)) {
                                                mountProfile = profile
                                                if (profile.autoEnablesFallGate) {
                                                    onFallOrientationGateChange(true)
                                                    SafetyStateStore.log(
                                                        "Soporte Bolsillo: CAIDA / ORIENTACION activada"
                                                    )
                                                }
                                            } else {
                                                SafetyStateStore.log("Deten el monitor antes de cambiar el soporte")
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        enabled = monitorStopped && !calibrationAnyRecording.recording,
                                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                            containerColor = if (selected) {
                                                StrongGreen.copy(alpha = 0.16f)
                                            } else {
                                                Color.Transparent
                                            },
                                            contentColor = if (selected) {
                                                StrongGreen
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            }
                                        ),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                            horizontal = 6.dp,
                                            vertical = 4.dp
                                        )
                                    ) {
                                        Text(
                                            profile.label,
                                            maxLines = 1,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (!monitorStopped || calibrationAnyRecording.recording) {
                        Text(
                            if (!monitorStopped) "Deten el monitor para cambiar el soporte."
                            else "Deten la grabacion de sensores para cambiar el soporte.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            item {
                val monitorStopped = snapshot.phase == SafetyPhase.STOPPED
                SeguCard(
                    title = "SENSIBILIDAD A LOS GOLPES",
                    status = if (monitorStopped) "AJUSTABLE" else "MONITOR EN MARCHA",
                    tone = if (monitorStopped) Tone.OK else Tone.WARN,
                    description = if (monitorStopped) {
                        "Sube el nivel para exigir un golpe mas fuerte. Aqui estan tambien el deslizador y, " +
                            "en ADAPT., cada cuanto se recalcula. El interruptor MONITOR esta en la pantalla principal."
                    } else {
                        "Para cambiar el nivel o cada cuanto se recalcula, para antes el monitor."
                    }
                ) {
                    SensitivityControl(
                        sensitivityG = sensitivityG,
                        effectiveG = snapshot.effectiveThresholdG ?: sensitivityG,
                        livePeakG = snapshot.ridePeakG,
                        activeWindowMinutes = snapshot.adaptationWindowMinutes,
                        windowAuto = snapshot.adaptationWindowAuto,
                        hitTestRunning = snapshot.hitTestRunning,
                        hitTestVerdict = snapshot.hitTestVerdict,
                        enabled = monitorStopped,
                        onSensitivityChange = onSensitivityChange,
                        onWindowChange = onWindowChange,
                        onHitTest = onHitTest,
                        valueLabel = "NIVEL DE GOLPE ="
                    )
                }
            }
            if (vehicleProfile.showsFallGate) {
                item {
                    SeguCard(
                        title = "CAIDA / ORIENTACION",
                    status = when {
                        !fallOrientationGate -> "GOLPE DIRECTO"
                        vehicleProfile == VehicleProfile.CAR -> "VOLCADO"
                        else -> "MODO MOTO"
                    },
                    tone = if (fallOrientationGate) Tone.OK else Tone.OFF,
                    trailing = {
                        Switch(
                            checked = fallOrientationGate,
                            enabled = !criticalSettingsLocked,
                            onCheckedChange = onFallOrientationGateChange,
                            colors = activeSwitchColors()
                        )
                    }
                ) {
                    Text(
                        if (fallOrientationGate) {
                            if (vehicleProfile == VehicleProfile.CAR) {
                                "Un golpe fuerte abre un aviso en espera de 15 s: si el coche vuelca o el movil queda " +
                                    "inclinado 55 grados o mas durante 4 s, salta el SOS. Un bache no vuelca el coche y " +
                                    "no avisa; la sacudida y los botones son inmediatos."
                            } else {
                                "Un golpe fuerte abre un aviso en espera de 15 s: si el movil queda inclinado 55 grados o mas " +
                                    "durante 4 s, salta el SOS. Un bache no tumba la moto y no avisa; la sacudida y los botones son inmediatos."
                            }
                        } else {
                            "Cualquier golpe al nivel elegido inicia la cuenta atras al momento, aunque muevas el movil a mano: " +
                                "no se comprueba la inclinacion."
                        } + " Se aplica al iniciar el monitor.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            }
            item {
                val voiceBlocked = voiceKeywordsGate && !audioPermissionGranted
                SeguCard(
                    title = "VOZ AYUDA / SOCORRO",
                    status = when {
                        voiceBlocked -> "SIN MICROFONO"
                        voiceKeywordsGate -> "ACTIVADA"
                        else -> "DESACTIVADA"
                    },
                    tone = when {
                        voiceBlocked -> Tone.BAD
                        voiceKeywordsGate -> Tone.OK
                        else -> Tone.OFF
                    },
                    trailing = {
                        Switch(
                            checked = voiceKeywordsGate,
                            enabled = !criticalSettingsLocked,
                            onCheckedChange = onVoiceKeywordsGateChange,
                            colors = activeSwitchColors()
                        )
                    }
                ) {
                    Text(
                        if (voiceKeywordsGate) {
                            "Con el monitor activo y el permiso de microfono, decir AYUDA o SOCORRO inicia la cuenta atras " +
                                "aunque la pantalla este apagada. No se graba ni se guarda sonido."
                        } else {
                            "No escucha palabras de emergencia; el microfono queda libre."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (vehicleProfile == VehicleProfile.MOTO) {
                item { Wt9011Card() }
            }
            if (vehicleProfile == VehicleProfile.CAR) {
                item { ObdLinkCard() }
            }
            if (vehicleProfile == VehicleProfile.MOTO) {
                item {
                    SeguCard(
                        title = "BUS CAN DE LA MOTO",
                        status = "PENDIENTE",
                        tone = Tone.WARN,
                        description = "La Softail 2018 publica la velocidad por el bus CAN (conector DLC de 6 " +
                            "pines: pin 1 CAN alta, pin 3 CAN baja). Falta el cable para leerlo; el analisis " +
                            "esta en docs/MOTO-CAN.md."
                    )
                }
            }
        }
        if (section == SettingsSection.CHANNELS) {
            item {
                SectionHeader("ENVIO AUTOMATICO")
            }
            item {
                SeguCard(
                    title = "ENVIO AUTO MENSAJES",
                    status = if (whatsappAutoSend) "ACTIVADO" else "DESACTIVADO",
                    tone = if (whatsappAutoSend) Tone.OK else Tone.OFF,
                    trailing = {
                        Switch(
                            checked = whatsappAutoSend,
                            enabled = !criticalSettingsLocked,
                            onCheckedChange = onWhatsappAutoSendChange,
                            colors = activeSwitchColors()
                        )
                    },
                    description = if (whatsappAutoSend) {
                        "Al terminar el SOS, la app abre el chat de WhatsApp o el de Telegram y " +
                            "pulsa Enviar sin preguntar."
                    } else {
                        "La app abre WhatsApp o Telegram con el mensaje escrito; el envio lo pulsas tu."
                    }
                ) {
                    if (whatsappAutoSend) {
                        val whatsappLifecycle = LocalLifecycleOwner.current
                        var whatsappRefresh by remember { mutableStateOf(0) }
                        DisposableEffect(whatsappLifecycle) {
                            val observer = LifecycleEventObserver { _, event ->
                                if (event == Lifecycle.Event.ON_RESUME) whatsappRefresh++
                            }
                            whatsappLifecycle.lifecycle.addObserver(observer)
                            onDispose { whatsappLifecycle.lifecycle.removeObserver(observer) }
                        }
                        val autoAccessibilityReady = remember(whatsappRefresh) {
                            AutomationPermissions.isAccessibilityEnabled(context)
                        }
                        val autoOverlayReady = remember(whatsappRefresh) {
                            AutomationPermissions.canDrawOverlays(context)
                        }
                        PermissionRow(
                            label = "Accesibilidad",
                            status = if (autoAccessibilityReady) "ACTIVADA" else "DESACTIVADA",
                            tone = if (autoAccessibilityReady) Tone.OK else Tone.BAD,
                            actionLabel = if (autoAccessibilityReady) null else "ABRIR",
                            onAction = { AutomationPermissions.openAccessibilitySettings(context) }
                        )
                        PermissionRow(
                            label = "Abrir sobre otras apps",
                            status = if (autoOverlayReady) "PERMITIDO" else "DENEGADO",
                            tone = if (autoOverlayReady) Tone.OK else Tone.BAD,
                            actionLabel = if (autoOverlayReady) null else "PERMITIR",
                            onAction = { AutomationPermissions.openOverlaySettings(context) }
                        )
                        if (!autoAccessibilityReady || !autoOverlayReady) {
                            Text(
                                "Sin los dos permisos no puede enviarse solo: la app te dejara el mensaje escrito para que lo envies tu.",
                                color = Tone.BAD.text,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
            item {
                val telegramRepository = remember { ContactsRepository(context) }
                var telegramToken by remember { mutableStateOf(telegramRepository.telegramBotToken()) }
                var telegramFeedback by remember { mutableStateOf("") }
                var telegramTesting by remember { mutableStateOf(false) }
                val telegramContacts = remember(automationRefresh) {
                    telegramRepository.load().filter { it.telegramEnabled }
                }
                val telegramInstalled = remember(automationRefresh) { TelegramSos.isInstalled(context) }
                val botConfigured = TelegramSos.normalizeBotToken(telegramToken) != null
                val botTargets = telegramContacts.filter { it.telegramReadyForBot }
                val linkTargets = telegramContacts.filter {
                    !it.telegramReadyForBot && it.telegramUsername.isNotEmpty()
                }
                val telegramScope = androidx.compose.runtime.rememberCoroutineScope()
                SeguCard(
                    title = "TELEGRAM SOS",
                    status = when {
                        botConfigured -> "BOT LISTO"
                        telegramInstalled -> "SIN BOT"
                        else -> "SIN TELEGRAM"
                    },
                    tone = when {
                        botConfigured -> Tone.OK
                        telegramInstalled -> Tone.WARN
                        else -> Tone.BAD
                    },
                    description = when {
                        botConfigured -> "El aviso sale solo, sin abrir Telegram ni desbloquear el movil."
                        telegramInstalled -> "La app abrira Telegram y pulsara Enviar; hace falta ENVIO AUTO MENSAJES."
                        else -> "No hay bot ni app de Telegram: ahora mismo el aviso no llegaria."
                    }
                ) {
                    Text(
                        "Bot conectado: ${botTargets.size} de ${telegramContacts.size} contactos listos. " +
                            "Enlace de Telegram: ${linkTargets.size} contacto(s) por Accesibilidad.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = telegramToken,
                        onValueChange = { telegramToken = it.trim() },
                        label = { Text("Clave del bot (@BotFather)") },
                        singleLine = true,
                        enabled = !criticalSettingsLocked,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = !criticalSettingsLocked,
                            modifier = Modifier.weight(1f),
                            contentPadding = CompactButtonPadding,
                            onClick = {
                                val normalized = TelegramSos.normalizeBotToken(telegramToken)
                                if (normalized == null) {
                                    telegramFeedback = "Clave no valida: debe ser 123456789:AAAA..."
                                } else {
                                    telegramRepository.setTelegramBotToken(normalized)
                                    telegramToken = normalized
                                    telegramFeedback = "Clave guardada"
                                }
                            }
                        ) { TightButtonLabel("GUARDAR") }
                        OutlinedButton(
                            enabled = !criticalSettingsLocked && !telegramTesting && botTargets.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                            contentPadding = CompactButtonPadding,
                            onClick = {
                                val token = TelegramSos.normalizeBotToken(telegramToken)
                                if (token == null) {
                                    telegramFeedback = "Guarda primero la clave del bot"
                                    return@OutlinedButton
                                }
                                val targets = botTargets.toList()
                                telegramTesting = true
                                telegramFeedback = "Enviando prueba a ${targets.size} contacto(s)..."
                                telegramScope.launch {
                                    val results = withContext(Dispatchers.IO) {
                                        targets.map { target ->
                                            target.name to TelegramSos.sendViaBot(
                                                token,
                                                target.telegramChatId,
                                                "Segu-Luky: mensaje de prueba para ${target.name}"
                                            )
                                        }
                                    }
                                    telegramTesting = false
                                    val sent = results.filter { it.second.ok }
                                    val failed = results.filter { !it.second.ok }
                                    telegramFeedback = when {
                                        failed.isEmpty() ->
                                            "Prueba enviada a ${sent.size} contacto(s)"
                                        sent.isEmpty() ->
                                            "Fallo: " + failed.joinToString("; ") { "${it.first} (${it.second.detail})" }
                                        else ->
                                            "Enviada a ${sent.size} de ${results.size}. Fallo: " +
                                                failed.joinToString("; ") { "${it.first} (${it.second.detail})" }
                                    }
                                }
                            }
                        ) { TightButtonLabel("ENVIAR PRUEBA") }
                    }
                    if (telegramToken.isNotBlank()) {
                        TextButton(
                            enabled = !criticalSettingsLocked,
                            onClick = {
                                telegramRepository.setTelegramBotToken("")
                                telegramToken = ""
                                telegramFeedback = "Clave borrada"
                            }
                        ) { Text("BORRAR CLAVE") }
                    }
                    if (telegramFeedback.isNotEmpty()) {
                        Text(telegramFeedback, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "1) Crea el bot en @BotFather y copia la clave que te da (GUARDAR). " +
                            "2) Cada contacto debe escribir /start al bot. " +
                            "3) Abre el contacto, pulsa BUSCAR CHATS y toca su chat: el numero se pone solo. " +
                            "4) Pulsa ENVIAR PRUEBA para comprobar la entrega a todos. " +
                            "El detalle esta en el menu i > Como usar, pasos 7 y 8.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (telegramContacts.isNotEmpty() && botTargets.isEmpty()) {
                        Text(
                            "Ningun contacto tiene el numero de chat: ahora mismo solo funcionaria su enlace de Telegram.",
                            color = Tone.BAD.text,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            item {
                SeguCard(
                    title = "SMS SOS AUTOMATICO",
                    status = if (smsAuto) "ACTIVADO" else "DESACTIVADO",
                    tone = if (smsAuto) Tone.OK else Tone.OFF,
                    trailing = {
                        Switch(
                            checked = smsAuto,
                            enabled = !criticalSettingsLocked,
                            colors = activeSwitchColors(),
                            onCheckedChange = { enabled ->
                                if (!SafetyStateStore.snapshot.value.phase.locksCriticalSettings()) {
                                    if (enabled && !hasSendSmsPermission(context)) {
                                        smsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
                                    } else {
                                        smsAuto = enabled
                                        SafetyMonitorService.saveSmsAuto(context, enabled)
                                    }
                                }
                            }
                        )
                    },
                    description = "Envia el enlace de Maps por SMS a TODOS los contactos, sin confirmacion y con la " +
                        "pantalla bloqueada. Puede tener coste de operador."
                )
            }
        }
        if (section == SettingsSection.CHANNELS) {
            item {
                IntercomCard(
                    installation = intercomInstallation,
                    noticeFeedback = intercomNoticeFeedback,
                    onRefreshIntercom = onRefreshIntercom,
                    onOpenIntercom = onOpenIntercom,
                    onTestIntercomNotice = onTestIntercomNotice
                )
            }
        }
        if (section == SettingsSection.CONTACTS) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "CONTACTOS",
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                    StatusPill(
                        if (contacts.isEmpty()) "NINGUNO" else "${contacts.size}",
                        if (contacts.isEmpty()) Tone.BAD else Tone.OK
                    )
                }
            }
            item {
                Text(
                    "La lista marca el orden de llamada: 1o, luego 2o si no responde en 1 minuto, luego 3o. " +
                        "El mensaje de WhatsApp se enviara a todos los contactos que lo tengan activado.",
                    modifier = Modifier.padding(start = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (editorVisible) {
                item {
                    EmergencyContactEditor(
                        contact = editingContact,
                        editable = !criticalSettingsLocked,
                        onSave = { updated ->
                            val saved = if (SafetyStateStore.snapshot.value.phase.locksCriticalSettings()) {
                                false
                            } else if (addingContact) {
                                repository.add(updated)
                            } else {
                                editingContact?.let { repository.update(it, updated) } ?: false
                            }
                            if (saved) onContactsChanged(repository.load())
                            saved
                        },
                        onDone = {
                            addingContact = false
                            editingContact = null
                        }
                    )
                }
            } else {
                item {
                    Button(
                        onClick = { addingContact = true },
                        enabled = !criticalSettingsLocked,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("ANADIR CONTACTO")
                    }
                }
                if (contacts.isEmpty()) {
                    item {
                        SeguCard(
                            title = "Sin contactos",
                            status = "OBLIGATORIO",
                            tone = Tone.BAD,
                            description = "Anade al menos un contacto antes de usar el SOS: sin contactos no hay aviso."
                        )
                    }
                }
                items(contacts, key = { "${it.name}|${it.phone}" }) { contact ->
                    EmergencyContactCard(
                        contact = contact,
                        editable = !criticalSettingsLocked,
                        onEdit = { editingContact = contact },
                        onRemove = {
                            if (!SafetyStateStore.snapshot.value.phase.locksCriticalSettings()) {
                                repository.remove(contact)
                                onContactsChanged(repository.load())
                            }
                        }
                    )
                }
            }
            item {
                SeguCard(
                    title = "COMO SE ENVIA LA UBICACION",
                    description = "El texto {MAPA} se cambia por un enlace de Google Maps con la ultima posicion GPS " +
                        "precisa y reciente. El interruptor $SOS_HARD ON/OFF de la pantalla principal debe estar en " +
                        "ON: si esta en OFF el aviso no sale."
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** Fila del indice de Configuracion: titulo, estado y una linea de resumen. */
@androidx.compose.runtime.Composable
private fun SettingsEntryRow(
    title: String,
    status: String,
    tone: Tone,
    description: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CardHeader(
                title = title,
                status = status,
                tone = tone,
                titleStyle = MaterialTheme.typography.titleMedium,
                titleWeight = FontWeight.Bold,
                trailing = {
                    Text(
                        "\u203A",
                        fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Cabecera de un submenu: volver al indice y el titulo del bloque. */
@androidx.compose.runtime.Composable
private fun SettingsBackRow(title: String, onBack: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        TextButton(
            onClick = onBack,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 4.dp,
                vertical = 2.dp
            )
        ) {
            Text(
                "\u2039 CONFIGURACION",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            title,
            modifier = Modifier.padding(start = 4.dp),
            style = MaterialTheme.typography.titleLarge
        )
    }
}

@androidx.compose.runtime.Composable
private fun EmergencyContactEditor(
    contact: EmergencyContact?,
    editable: Boolean,
    onSave: (EmergencyContact) -> Boolean,
    onDone: () -> Unit
) {
    var name by remember(contact) { mutableStateOf(contact?.name.orEmpty()) }
    var phone by remember(contact) { mutableStateOf(contact?.phone.orEmpty()) }
    var callEnabled by remember(contact) { mutableStateOf(contact?.callEnabled ?: true) }
    var whatsappEnabled by remember(contact) { mutableStateOf(contact?.whatsappEnabled ?: true) }
    var whatsappMessage by remember(contact) {
        mutableStateOf(contact?.whatsappMessage ?: DEFAULT_WHATSAPP_MESSAGE)
    }
    var telegramEnabled by remember(contact) { mutableStateOf(contact?.telegramEnabled ?: false) }
    var telegramUsername by remember(contact) { mutableStateOf(contact?.telegramUsername.orEmpty()) }
    var telegramChatId by remember(contact) { mutableStateOf(contact?.telegramChatId.orEmpty()) }
    var saveAttempted by remember(contact) { mutableStateOf(false) }
    var saveError by remember(contact) { mutableStateOf<String?>(null) }
    var chatSearching by remember(contact) { mutableStateOf(false) }
    var chatOptions by remember(contact) { mutableStateOf<List<TelegramSos.TelegramChat>>(emptyList()) }
    var chatSearchNote by remember(contact) { mutableStateOf("") }
    val editorScope = rememberCoroutineScope()
    // La clave se lee al abrir el editor: es la que ya esta guardada en TELEGRAM SOS.
    val editorContext = LocalContext.current
    val botToken = remember(editorContext) { ContactsRepository(editorContext).telegramBotToken() }
    val phoneValid = isValidEmergencyPhone(phone)
    val telegramUsernameInvalid = telegramEnabled &&
        telegramUsername.isNotBlank() &&
        TelegramSos.normalizeUsername(telegramUsername) == null
    val telegramChatIdInvalid = telegramEnabled &&
        telegramChatId.isNotBlank() &&
        TelegramSos.normalizeChatId(telegramChatId) == null
    val telegramInputValid = !telegramUsernameInvalid && !telegramChatIdInvalid
    val actionSelected = callEnabled || whatsappEnabled || telegramEnabled

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (contact == null) "NUEVO CONTACTO" else "EDITAR CONTACTO",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (!editable) {
                Text(
                    "Edicion bloqueada mientras hay una validacion o cuenta atras activa.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                enabled = editable,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Nombre") },
                isError = saveAttempted && name.isBlank(),
                supportingText = if (saveAttempted && name.isBlank()) ({ Text("Indica un nombre") }) else null,
                singleLine = true
            )
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                enabled = editable,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Telefono con prefijo internacional") },
                isError = saveAttempted && !phoneValid,
                supportingText = if (saveAttempted && !phoneValid) ({ Text("Numero no valido") }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true
            )
            EmergencyActionSwitch(
                label = "LLAMAR POR TELEFONO",
                detail = "Incluye este numero en la lista de llamadas SOS.",
                checked = callEnabled,
                enabled = editable,
                onCheckedChange = { callEnabled = it }
            )
            EmergencyActionSwitch(
                label = "ENVIAR WHATSAPP",
                detail = "Prepara un mensaje personalizado para este numero.",
                checked = whatsappEnabled,
                enabled = editable,
                onCheckedChange = { whatsappEnabled = it }
            )
            if (whatsappEnabled) {
                OutlinedTextField(
                    value = whatsappMessage,
                    onValueChange = { whatsappMessage = it },
                    enabled = editable,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Mensaje WhatsApp") },
                    supportingText = {
                        Text("{MAPA} se convierte en un enlace de Google Maps al producirse el SOS.")
                    },
                    minLines = 3
                )
                Text("VISTA PREVIA", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text(
                    ensureMapLinkToken(whatsappMessage).replace(MAP_LINK_TOKEN, "enlace de Google Maps"),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            EmergencyActionSwitch(
                label = "ENVIAR TELEGRAM",
                detail = "Por el bot (con numero de chat y clave) o por su enlace de Telegram.",
                checked = telegramEnabled,
                enabled = editable,
                onCheckedChange = { telegramEnabled = it }
            )
            if (telegramEnabled) {
                OutlinedTextField(
                    value = telegramUsername,
                    onValueChange = { telegramUsername = it },
                    enabled = editable,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Usuario de Telegram (opcional)") },
                    placeholder = { Text("@nombre o enlace de Telegram") },
                    isError = saveAttempted && telegramUsernameInvalid,
                    supportingText = {
                        Text(
                            if (telegramUsernameInvalid) "Usuario no valido"
                            else "Respaldo: abre el chat y pulsa Enviar por accesibilidad."
                        )
                    },
                    singleLine = true
                )
                OutlinedTextField(
                    value = telegramChatId,
                    onValueChange = { telegramChatId = it },
                    enabled = editable,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Numero de chat del bot (opcional)") },
                    placeholder = { Text("123456789") },
                    isError = saveAttempted && telegramChatIdInvalid,
                    supportingText = {
                        Text(
                            if (telegramChatIdInvalid) "Numero de chat no valido (solo digitos)"
                            else "Vacio = sin envio automatico por bot para este contacto."
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        enabled = editable && !chatSearching,
                        contentPadding = CompactButtonPadding,
                        onClick = {
                            val token = TelegramSos.normalizeBotToken(botToken)
                            if (token == null) {
                                chatOptions = emptyList()
                                chatSearchNote = "Primero guarda la clave del bot en Configuracion > TELEGRAM SOS."
                            } else {
                                chatSearching = true
                                chatOptions = emptyList()
                                chatSearchNote = "Buscando chats..."
                                editorScope.launch {
                                    val search = withContext(Dispatchers.IO) {
                                        TelegramSos.discoverChats(token)
                                    }
                                    chatSearching = false
                                    when (search) {
                                        is TelegramSos.ChatSearch.Failed -> {
                                            chatSearchNote = "No se pudo buscar: ${search.detail}"
                                        }
                                        is TelegramSos.ChatSearch.Found -> {
                                            chatOptions = search.chats
                                            chatSearchNote = if (search.chats.isEmpty()) {
                                                "El bot no ha recibido ningun mensaje. Que cada contacto abra " +
                                                    "el bot y pulse INICIAR/START, y vuelve a buscar."
                                            } else {
                                                "Toca el chat de este contacto para poner su numero."
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    ) { TightButtonLabel(if (chatSearching) "BUSCANDO..." else "BUSCAR CHATS") }
                    Text(
                        "Pregunta al bot quien le ha escrito.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (chatSearchNote.isNotEmpty()) {
                    Text(
                        chatSearchNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                chatOptions.forEach { chat ->
                    OutlinedButton(
                        enabled = editable,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = CompactButtonPadding,
                        onClick = {
                            telegramChatId = chat.id
                            chatOptions = emptyList()
                            chatSearchNote = "Numero puesto: ${chat.label}. Pulsa GUARDAR."
                        }
                    ) { TightButtonLabel(chat.label) }
                }
            }
            if (saveAttempted && !actionSelected) {
                Text("Activa llamada, WhatsApp, Telegram o una combinacion.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            saveError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDone, modifier = Modifier.weight(1f)) { Text("CANCELAR") }
                Button(
                    onClick = {
                        saveAttempted = true
                        saveError = null
                        if (name.isNotBlank() && phoneValid && actionSelected && telegramInputValid) {
                            val saved = onSave(
                                EmergencyContact(
                                    name = name,
                                    phone = phone,
                                    callEnabled = callEnabled,
                                    whatsappEnabled = whatsappEnabled,
                                    whatsappMessage = whatsappMessage,
                                    telegramEnabled = telegramEnabled,
                                    telegramUsername = telegramUsername,
                                    telegramChatId = telegramChatId
                                )
                            )
                            if (saved) onDone() else saveError = "Ya existe un contacto con este telefono."
                        }
                    },
                    enabled = editable,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("GUARDAR")
                }
            }
        }
    }
}

// Verde = activo: el naranja del tema nunca debe marcar un control seleccionado.
@androidx.compose.runtime.Composable
private fun activeSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = StrongGreen,
    checkedBorderColor = StrongGreen
)

@androidx.compose.runtime.Composable
private fun EmergencyActionSwitch(
    label: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = activeSwitchColors()
        )
    }
}

@androidx.compose.runtime.Composable
private fun EmergencyContactCard(
    contact: EmergencyContact,
    editable: Boolean,
    onEdit: () -> Unit,
    onRemove: () -> Unit
) {
    val actions = buildList {
        if (contact.callEnabled) add("Llamada")
        if (contact.whatsappEnabled) add("WhatsApp")
        if (contact.telegramEnabled) {
            add(if (contact.telegramReadyForBot) "Telegram bot" else "Telegram enlace")
        }
    }.joinToString(" + ")
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CardHeader(
                title = contact.name,
                subtitle = contact.phone,
                status = if (actions.isBlank()) "SIN ACCIONES" else actions,
                tone = if (actions.isBlank()) Tone.BAD else Tone.OK
            )
            if (contact.whatsappEnabled) {
                Text(
                    ensureMapLinkToken(contact.whatsappMessage).replace(MAP_LINK_TOKEN, "enlace de Google Maps"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit, enabled = editable, modifier = Modifier.weight(1f)) { Text("EDITAR") }
                OutlinedButton(onClick = onRemove, enabled = editable, modifier = Modifier.weight(1f)) { Text("QUITAR") }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun InstructionsScreen(
    modifier: Modifier,
    snapshot: SafetySnapshot
) {
    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(Modifier.height(4.dp))
            Text(
                "Segu-Luky ${appVersionLabel()}",
                modifier = Modifier.padding(start = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            UsageInstruction(
                title = "1. Configura los contactos",
                text = "Activa ${SOS_HARD} ON/OFF en la pantalla principal para habilitar todos los avisos. Para cada telefono elige llamada, WhatsApp, Telegram o una combinacion. Puedes cambiar el texto del mensaje: {MAPA} se cambia por el enlace de Google Maps y el mismo texto se usa en Telegram."
            )
        }
        item {
            UsageInstruction(
                title = "2. Inicia el monitor",
                text = "Concede la ubicacion precisa para tener mapa y velocidad. Pulsa INICIAR: el monitor arranca tambien sin GPS, solo pierde el enlace del mapa. Si das permiso al microfono, NIVEL DE AUDIO mide el ruido del ambiente; con VOZ AYUDA / SOCORRO activada, gritar AYUDA o SOCORRO empieza la cuenta atras aunque la pantalla este apagada y el monitor activo. No se graba ni se guarda sonido: la voz la entiende el propio telefono."
            )
        }
        item {
            UsageInstruction(
                title = "3. Ajusta la sensibilidad",
                text = "La app mide los golpes en g (1 g es el peso del movil parado) y aprende el ruido y el " +
                    "traqueteo de tu telefono. En Configuracion > Sensores estan el deslizador, PICO (el golpe mas " +
                    "fuerte del viaje), EFECTIVO (el nivel que se aplica), SUGERIDO (lo que propone la app) y PRUEBA " +
                    "DE GOLPE. En la pantalla principal queda el valor y cada cuanto se recalcula (ADAPT.: AUTO, 1, 2 " +
                    "o 3 min). La app tambien ajusta sola cuantas veces por segundo mira el sensor. Con MODO MOTO, un " +
                    "golpe suelto deja el aviso en espera; dos golpes seguidos (una sacudida) empiezan la cuenta atras " +
                    "al instante. GPS y VELOCIDAD solo informan y nunca bloquean un golpe. PRUEBA DE GOLPE comprueba en " +
                    "15 s que el movil nota una sacudida, sin avisar a nadie."
            )
        }
        item {
            UsageInstruction(
                title = "4. Caida, orientacion y sensor de vuelco",
                text = "Con MODO MOTO, un golpe fuerte deja el aviso en espera 15 s y la pantalla se enciende para que puedas cancelarlo. El SOS salta solo si el movil queda inclinado 55 grados o mas durante 4 s seguidos. Se cancela si vuelve a estar vertical, si el GPS marca 30 km/h o mas (sigues rodando) o si pasan esos 15 s. Si lo desactivas, cualquier golpe empieza la cuenta atras directamente. " +
                    "El sensor WT9011DCL-BT50 solo aparece y funciona con TIPO DE VEHICULO en MOTO. Si lo tienes montado, entra en Configuracion > Sensores, pulsa BUSCAR, elige WT901BLE67 y CONECTAR. Con la moto vertical y quieta aprende la referencia (CALIBRAR); si esta en la pata de cabra espera a enderezarla. Cuando la inclinacion X/Y supera el umbral ajustable (50-60 grados; 55 por defecto) durante 5 s, abre la misma cuenta atras. Es una condicion independiente: no sustituye a la caida del movil, ni a la voz, ni al SOS manual. Las dos columnas junto al SOS llevan una flecha X a la izquierda y Y a la derecha; debajo, cada etiqueta muestra el eje y los grados. Verde: correcto; amarillo: faltan 15 grados o menos; rojo: umbral alcanzado."
            )
        }
        item {
            UsageInstruction(
                title = "5. SOS manual y pruebas",
                text = "Mantener pulsado el boton SOS durante 5 segundos inicia la cuenta atras real. Antes de probar, deja ${SOS_HARD} en OFF para que la cuenta atras no llame ni envie mensajes a los contactos."
            )
        }
        item {
            UsageInstruction(
                title = "6. Avisos por WhatsApp y Telegram",
                text = "SMS SOS sale aunque la pantalla este bloqueada. WhatsApp y el enlace de Telegram necesitan el permiso de Accesibilidad para poder pulsar Enviar; si el movil tiene PIN, la pantalla se enciende y pide desbloqueo. El bot de Telegram no necesita pantalla ni Accesibilidad: guarda su clave en la tarjeta TELEGRAM SOS, pon el numero de chat en cada contacto y el aviso sale solo con el movil bloqueado. Los pasos 7 y 8 explican como prepararlo."
            )
        }
        item {
            UsageInstruction(
                title = "7. Telegram: crear el bot y guardar la clave",
                text = "1) En Telegram abre @BotFather y envia /newbot. " +
                    "2) Elige un nombre visible y un usuario que termine en bot. " +
                    "3) BotFather te devuelve una clave larga (el token), con forma 123456789:AAAA... Copiala entera. " +
                    "4) En Segu-Luky pulsa el engranaje, baja hasta la tarjeta TELEGRAM SOS y pegala en Clave del bot. " +
                    "5) Pulsa GUARDAR: la tarjeta confirmara Clave guardada y el estado pasa a BOT LISTO. " +
                    "6) ENVIAR PRUEBA manda un mensaje a todos los contactos que ya tengan su numero de chat y dice cual fallo. " +
                    "Si la clave es incorrecta, la tarjeta mostrara el motivo que devuelve Telegram."
            )
        }
        item {
            UsageInstruction(
                title = "8. Telegram: numero de chat de cada contacto",
                text = "Cada contacto debe abrir el bot y enviarle /start una vez; sin eso Telegram no deja escribirle. " +
                    "Despues no hay que copiar numeros a mano: en Configuracion > CONTACTOS SOS abre el contacto, " +
                    "activa ENVIAR TELEGRAM y pulsa BUSCAR CHATS. La app le pregunta al bot quien le ha escrito y ensena " +
                    "la lista de chats; toca el de ese contacto y el numero se rellena solo (los grupos empiezan por -). " +
                    "Si la lista sale vacia, nadie ha escrito al bot todavia. " +
                    "Si prefieres no usar bot, deja ese campo vacio y escribe solo el usuario (@nombre): se usara su enlace de Telegram, que necesita Accesibilidad y el interruptor ENVIO AUTO MENSAJES. " +
                    "La clave se guarda sin cifrar dentro de este movil: si prestas el telefono, pulsa BORRAR CLAVE y desactiva ENVIAR TELEGRAM en los contactos."
            )
        }
        item {
            UsageInstruction(
                title = "Estados GPS",
                text = "DETENIDO: monitor apagado. BUSCANDO FIX: aun sin posicion; bajo techo el GPS puede no llegar y se usa la ubicacion por red o la ultima conocida. FIX CADUCADO (LENTO): la posicion supera 60 s y solo sirve de referencia. OK: posicion reciente. El SOS manual (boton 5 s o voz) si funciona sin GPS; el enlace de ubicacion solo se envia si la posicion tiene menos de 5 min."
            )
        }
        item {
            UsageInstruction(
                title = "Importante",
                text = "Al completar la cuenta atras y con ${SOS_HARD} en ON, Segu-Luky inicia llamadas a los contactos con llamada habilitada y abre WhatsApp o Telegram con su mensaje y el enlace Google Maps de la ultima posicion. Comprueba siempre contactos, GPS, bateria y permisos antes de circular."
            )
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("REGISTRO RECIENTE", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (snapshot.eventLog.isEmpty()) {
                        Text("Sin eventos", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        snapshot.eventLog.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@androidx.compose.runtime.Composable
private fun IntercomCard(
    installation: IntercomInstallationInfo,
    noticeFeedback: String?,
    onRefreshIntercom: () -> Unit,
    onOpenIntercom: () -> Unit,
    onTestIntercomNotice: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(
                            when (installation.state) {
                                IntercomState.ACTIVE -> StrongGreen
                                IntercomState.INACTIVE -> Color(0xFFFF9800)
                                IntercomState.MISSING -> Color(0xFF9AA0A6)
                            }
                        )
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "INTERCOM-LUKY",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                when {
                    !installation.installed -> "NO INSTALADA (gris): el aviso no suena en ningun sitio"
                    installation.active -> buildString {
                        append("ACTIVA (verde): InterCOM encendido")
                        if (installation.group.isNotBlank()) append(" - grupo ${installation.group}")
                        if (installation.peers > 0) append(" - ${installation.peers} motoristas")
                    }
                    else -> "INSTALADA, NO ACTIVA (naranja): el aviso no suena en ningun sitio"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (installation.installed) {
                Text(
                    "Version ${installation.versionName ?: "desconocida"} (${installation.versionCode ?: 0})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "Al terminar la cuenta atras del SOS, Segu-Luky pide a InterCOM-Luky que avise por " +
                    "voz: \"${IntercomInstallation.SOS_NOTICE_TEXT}\". En verde el aviso suena en el casco " +
                    "de todos los motoristas del grupo (datos) o del INTERCOM WiFi, y tambien en este movil. " +
                    "En naranja o gris no suena en ningun sitio.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            noticeFeedback?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = StrongGreen
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onRefreshIntercom,
                    modifier = Modifier.weight(1f),
                    contentPadding = CompactButtonPadding
                ) { TightButtonLabel("ACTUALIZAR") }
                if (installation.installed) {
                    Button(
                        onClick = onOpenIntercom,
                        modifier = Modifier.weight(1f),
                        contentPadding = CompactButtonPadding
                    ) { TightButtonLabel("ABRIR") }
                }
            }
            if (installation.installed) {
                OutlinedButton(
                    onClick = onTestIntercomNotice,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = CompactButtonPadding
                ) { TightButtonLabel("PROBAR AVISO") }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun UsageInstruction(title: String, text: String) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** One semantic tone per status (ok / attention / danger / idle). */
private enum class Tone(val text: Color, val soft: Color, val line: Color) {
    OK(Color(0xFF146C2E), Color(0xFFEAF6EC), Color(0xFFA9D9B6)),
    WARN(Color(0xFF8A5A00), Color(0xFFFFF6E3), Color(0xFFE5C37E)),
    BAD(Color(0xFFB3261E), Color(0xFFFFF0EE), Color(0xFFE7B0AB)),
    OFF(Color(0xFF6B6259), Color(0xFFF3EEE8), Color(0xFFE0D7CC))
}

private fun toneOf(state: IndicatorState): Tone = when (state) {
    IndicatorState.GOOD -> Tone.OK
    IndicatorState.PENDING -> Tone.WARN
    IndicatorState.STANDBY -> Tone.WARN
    IndicatorState.ERROR -> Tone.BAD
    IndicatorState.OFF -> Tone.OFF
}

/** Naranja = desactivado / se puede ajustar; verde = activado / ajuste fijado. */
private val StateOffInk = Color(0xFFE2660C)
private val StateOnInk = Color(0xFF1E8E3E)

@androidx.compose.runtime.Composable
private fun StatusPill(text: String, tone: Tone) {
    Surface(shape = RoundedCornerShape(50), color = tone.soft, contentColor = tone.text) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
    }
}

@androidx.compose.runtime.Composable
private fun SectionHeader(text: String, centered: Boolean = false) {
    Text(
        text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (centered) 0.dp else 4.dp, top = 4.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
        textAlign = if (centered) TextAlign.Center else TextAlign.Start
    )
}

/**
 * Cabecera comun de tarjetas y filas: el titulo ocupa todo el ancho y la
 * etiqueta de estado va debajo. Asi ningun titulo se parte a mitad de frase ni
 * empuja la etiqueta contra el borde.
 */
@androidx.compose.runtime.Composable
private fun CardHeader(
    title: String? = null,
    status: String? = null,
    tone: Tone = Tone.OFF,
    subtitle: String? = null,
    leading: (@androidx.compose.runtime.Composable () -> Unit)? = null,
    trailing: (@androidx.compose.runtime.Composable () -> Unit)? = null,
    titleStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleSmall,
    titleWeight: FontWeight? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (title != null) {
                Text(title, style = titleStyle, fontWeight = titleWeight)
            }
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (status != null) StatusPill(status, tone)
        }
        trailing?.invoke()
    }
}

/**
 * Sensor externo de vuelco. Es una condicion independiente: no sustituye a
 * CAIDA / ORIENTACION ni a los golpes del movil. Se puede conectar con el
 * monitor parado para comprobar los ejes con el sensor en la mano.
 */
@Composable
private fun Wt9011Card() {
    val context = LocalContext.current
    val state by Wt9011Controller.state.collectAsStateWithLifecycle()
    val scanResults by Wt9011Controller.scanResults.collectAsStateWithLifecycle()
    var showList by remember { mutableStateOf(false) }
    var threshold by remember(state.thresholdDegrees) { mutableStateOf(state.thresholdDegrees) }

    LaunchedEffect(Unit) { Wt9011Controller.refreshSettings(context) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) {
            showList = true
            Wt9011Controller.startScan(context)
        } else {
            SafetyStateStore.log("Sin permisos de Bluetooth no se puede buscar el sensor de vuelco")
        }
    }

    fun missingPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!Wt9011Controller.hasScanPermission(context)) {
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (!Wt9011Controller.hasConnectPermission(context)) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else if (!Wt9011Controller.hasScanPermission(context)) {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun scan() {
        val missing = missingPermissions()
        if (missing.isEmpty()) {
            showList = true
            Wt9011Controller.startScan(context)
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    fun connectSavedOrScan() {
        val saved = Wt9011Settings.loadDevice(context)
        if (saved.address.isBlank()) {
            scan()
            return
        }
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        Wt9011Controller.connect(context, saved)
    }

    val statusLabel = when (state.status) {
        Wt9011Status.OFF -> "APAGADO"
        Wt9011Status.NO_PERMISSION -> "SIN PERMISO"
        Wt9011Status.NO_BLUETOOTH -> "SIN BLUETOOTH"
        Wt9011Status.NO_DEVICE -> "SIN SENSOR"
        Wt9011Status.SCANNING -> "BUSCANDO"
        Wt9011Status.FOUND -> "ENCONTRADOS"
        Wt9011Status.CONNECTING -> "CONECTANDO"
        Wt9011Status.CALIBRATING -> "CALIBRANDO"
        Wt9011Status.READY -> if (state.monitoring) "VIGILANDO" else "LISTO"
        Wt9011Status.NO_DATA -> "SIN DATOS"
        Wt9011Status.ERROR -> "ERROR"
    }
    val tone = when (state.status) {
        Wt9011Status.READY,
        Wt9011Status.FOUND -> Tone.OK
        Wt9011Status.CALIBRATING,
        Wt9011Status.CONNECTING,
        Wt9011Status.SCANNING,
        Wt9011Status.NO_DATA -> Tone.WARN
        Wt9011Status.NO_PERMISSION,
        Wt9011Status.NO_BLUETOOTH,
        Wt9011Status.ERROR -> Tone.BAD
        Wt9011Status.OFF,
        Wt9011Status.NO_DEVICE -> Tone.OFF
    }
    val saved = Wt9011Settings.loadDevice(context)

    SeguCard(
        title = "SENSOR DE VUELCO (WT9011)",
        status = if (state.enabled) statusLabel else "DESACTIVADO",
        tone = if (state.enabled) tone else Tone.OFF,
        description = "Solo se activa en MODO MOTO. Condicion independiente: no sustituye a " +
            "CAIDA / ORIENTACION ni a los golpes del movil. Se monta en la moto con el eje Z hacia " +
            "arriba; la inclinacion se mide con X e Y.",
        trailing = {
            Switch(
                checked = state.enabled,
                onCheckedChange = { enabled ->
                    Wt9011Controller.setEnabled(context, enabled)
                    SafetyStateStore.log(
                        if (enabled) "Sensor de vuelco activado" else "Sensor de vuelco desactivado"
                    )
                },
                colors = activeSwitchColors()
            )
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (saved.address.isBlank()) "Ningun sensor elegido" else saved.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                state.detail.ifBlank {
                    "50 Hz · 6 ejes · sin magnetometro. El rumbo no se usa."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { scan() },
                    modifier = Modifier.weight(1f),
                    contentPadding = CompactButtonPadding
                ) { TightButtonLabel("BUSCAR") }
                Button(
                    onClick = { connectSavedOrScan() },
                    modifier = Modifier.weight(1f),
                    contentPadding = CompactButtonPadding
                ) { TightButtonLabel("CONECTAR") }
                OutlinedButton(
                    onClick = { Wt9011Controller.disconnect() },
                    modifier = Modifier.weight(1f),
                    contentPadding = CompactButtonPadding
                ) { TightButtonLabel("CORTAR") }
            }
            OutlinedButton(
                onClick = {
                    Wt9011Controller.calibrate(force = true)
                    SafetyStateStore.log("Calibrando sensor de vuelco: moto vertical y quieta")
                },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = CompactButtonPadding,
                enabled = state.connected
            ) { TightButtonLabel("CALIBRAR CON LA MOTO VERTICAL") }

            if (showList) {
                if (scanResults.isEmpty()) {
                    Text(
                        if (state.status == Wt9011Status.SCANNING) {
                            "Buscando sensores WT901..."
                        } else {
                            "No se encontro ningun WT901. Enciende el sensor y pulsa BUSCAR."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    scanResults.forEach { device ->
                        OutlinedButton(
                            onClick = {
                                showList = false
                                Wt9011Controller.connect(context, device)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = CompactButtonPadding
                        ) {
                            TightButtonLabel("${device.label}    ${device.rssi} dBm")
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SensorAxisReading("X", state.ax, Modifier.weight(1f))
                SensorAxisReading("Y", state.ay, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SensorMetric(
                    label = "INCLINACION X/Y",
                    value = state.tiltDegrees?.let { "${it.toInt()}°" } ?: "—",
                    modifier = Modifier.weight(1f)
                )
                SensorMetric(
                    label = "UMBRAL",
                    value = "${threshold.toInt()}°",
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                "UMBRAL DE VUELCO",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = threshold,
                onValueChange = { threshold = it.roundToInt().coerceIn(50, 60).toFloat() },
                valueRange = Wt9011Settings.MIN_THRESHOLD_DEGREES..Wt9011Settings.MAX_THRESHOLD_DEGREES,
                steps = 9,
                onValueChangeFinished = {
                    Wt9011Controller.setThreshold(context, threshold)
                    SafetyStateStore.log("Umbral de vuelco: ${threshold.toInt()} grados")
                }
            )
            Text(
                "Verde hasta 15° antes del umbral; amarillo en esos 15°; rojo al alcanzarlo. " +
                    "La condicion se evalua con 5 s sostenidos y no depende de CAIDA / ORIENTACION.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SensorAxisReading(label: String, value: Float?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value?.let { String.format(Locale.US, "%+.2f g", it) } ?: "—",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun SensorMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Tarjeta del perfil COCHE: elegir el adaptador emparejado, conectar y ver los
 * datos en vivo. Nada de aqui participa en el SOS: si el adaptador falla, la app
 * sigue detectando golpes con el movil y el GPS.
 */
@androidx.compose.runtime.Composable
private fun ObdLinkCard() {
    val context = LocalContext.current
    val obd by ObdLinkController.state.collectAsStateWithLifecycle()
    var devices by remember { mutableStateOf<List<ObdDevice>>(emptyList()) }
    var showList by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(ObdLinkController.savedDevice(context)) }
    val bluetoothPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            devices = ObdLinkController.bondedDevices(context)
            showList = true
        } else {
            SafetyStateStore.log("Sin permiso de Bluetooth no se puede enlazar con el coche")
        }
    }
    fun listDevices() {
        if (ObdLinkController.hasPermission(context)) {
            devices = ObdLinkController.bondedDevices(context)
            showList = true
        } else {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    SeguCard(
        title = "ENLACE CON EL COCHE",
        status = when (obd.status) {
            ObdStatus.OFF -> "APAGADO"
            ObdStatus.NO_PERMISSION -> "SIN PERMISO"
            ObdStatus.NO_DEVICE -> "SIN ADAPTADOR"
            ObdStatus.CONNECTING -> "CONECTANDO"
            ObdStatus.READY -> "EN MARCHA"
            ObdStatus.NO_DATA -> "SIN DATOS"
            ObdStatus.ERROR -> "ERROR"
        },
        tone = when (obd.status) {
            ObdStatus.OFF -> Tone.OFF
            ObdStatus.NO_PERMISSION, ObdStatus.NO_DEVICE, ObdStatus.ERROR -> Tone.BAD
            ObdStatus.CONNECTING, ObdStatus.NO_DATA -> Tone.WARN
            ObdStatus.READY -> Tone.OK
        },
        description = obd.detail.ifBlank {
            "Adaptador ELM327 por Bluetooth. La velocidad y las revoluciones salen del coche; si no " +
                "hay adaptador se usa el GPS."
        }
    ) {
        Text(
            if (saved.address.isBlank()) "Ningun adaptador elegido" else saved.label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { listDevices() },
                modifier = Modifier.weight(1f),
                contentPadding = CompactButtonPadding
            ) { TightButtonLabel("BUSCAR") }
            Button(
                onClick = { ObdLinkController.connect(context, saved) },
                modifier = Modifier.weight(1f),
                contentPadding = CompactButtonPadding
            ) { TightButtonLabel("CONECTAR") }
            OutlinedButton(
                onClick = { ObdLinkController.disconnect() },
                modifier = Modifier.weight(1f),
                contentPadding = CompactButtonPadding
            ) { TightButtonLabel("CORTAR") }
        }
        if (showList) {
            if (devices.isEmpty()) {
                Text(
                    "No hay adaptadores emparejados. Empareja el ELM327 en los ajustes de Bluetooth " +
                        "del movil y vuelve a pulsar BUSCAR.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                devices.forEach { device ->
                    OutlinedButton(
                        onClick = {
                            saved = device
                            ObdLinkController.saveDevice(context, device)
                            showList = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = CompactButtonPadding
                    ) { TightButtonLabel(device.label) }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ObdReading("VELOCIDAD", obd.speedKmh?.let { String.format(Locale.US, "%.0f", it) }, "km/h", Modifier.weight(1f))
            ObdReading("RPM", obd.rpm?.toString(), "rpm", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ObdReading("ACELERADOR", obd.throttlePercent?.toString(), "%", Modifier.weight(1f))
            ObdReading("REFRIGERANTE", obd.coolantC?.toString(), "C", Modifier.weight(1f))
        }
        val extra = buildList {
            if (obd.protocol.isNotBlank()) add(obd.protocol)
            if (obd.readingsPerSecond > 0) add("${obd.readingsPerSecond} lecturas/s")
            obd.voltage?.let { add(String.format(Locale.US, "%.1f V", it)) }
            obd.dtcCount?.let { add(if (it == 0) "sin codigos" else "$it codigos") }
        }
        if (extra.isNotEmpty()) {
            Text(
                extra.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Un valor del coche con su unidad; "—" cuando el adaptador no lo da. */
@androidx.compose.runtime.Composable
private fun ObdReading(label: String, value: String?, unit: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            if (value == null) "—" else "$value $unit",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Shared card used by the home screen and the settings list: one title, one
 * status pill and one short line of copy, so every block reads the same way.
 */
@androidx.compose.runtime.Composable
private fun SeguCard(
    title: String? = null,
    status: String? = null,
    tone: Tone = Tone.OFF,
    description: String? = null,
    trailing: (@androidx.compose.runtime.Composable () -> Unit)? = null,
    content: (@androidx.compose.runtime.Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit)? = null
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (title != null || status != null) {
                CardHeader(title = title, status = status, tone = tone, trailing = trailing)
            }
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            content?.invoke(this)
        }
    }
}

/** Compact permission / capability line: name, state and an optional shortcut. */
@androidx.compose.runtime.Composable
private fun PermissionRow(
    label: String,
    status: String,
    tone: Tone,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(tone.text))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(
                status,
                style = MaterialTheme.typography.labelSmall,
                color = tone.text,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp)
            ) {
                Text(actionLabel, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

/**
 * Home header: the only place that shows the state, the countdown and the
 * start/stop control. Cancelling lives in the bottom bar.
 */
/**
 * Boton de estado ON/OFF: nunca se ven las dos palabras, solo la que toca.
 * Verde = activo; naranja (monitor) o rojo (avisos SOS) = desactivado.
 */
@androidx.compose.runtime.Composable
private fun StateToggleButton(
    label: String,
    stateLabel: String,
    on: Boolean,
    onColor: Color,
    offColor: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accessibilityLabel: String? = null,
    onClick: () -> Unit
) {
    val ink = if (on) onColor else offColor
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .height(52.dp)
            .semantics {
                contentDescription = accessibilityLabel
                    ?: "$label $stateLabel: ${if (on) "activado" else "desactivado"}"
            },
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(2.dp, ink),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = ink
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Text(
                stateLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

/** Velocidad de una fuente concreta, junto al selector de modo en el coche. */
@androidx.compose.runtime.Composable
private fun SpeedSourceCard(
    label: String,
    speedKmh: Float?,
    modifier: Modifier = Modifier
) {
    val available = speedKmh != null
    val value = speedKmh?.let { String.format(Locale.US, "%.0f", it) } ?: "--"
    val accent = MaterialTheme.colorScheme.tertiary
    val ink = if (available) accent else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = modifier
            .height(52.dp)
            .semantics {
                contentDescription = if (available) {
                    "$label $value kilometros por hora"
                } else {
                    "$label sin dato de velocidad"
                }
            },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            2.dp,
            if (available) accent.copy(alpha = 0.65f) else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = ink,
                    maxLines = 1
                )
                Text(
                    "km/h",
                    style = MaterialTheme.typography.labelSmall,
                    color = ink,
                    maxLines = 1
                )
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun PhaseHeroCard(
    snapshot: SafetySnapshot,
    audioLevel: AudioLevelReading,
    sensitivityG: Float,
    onSensitivityChange: (Float) -> Unit,
    onWindowChange: (AdaptationWindowMode) -> Unit,
    onHitTest: () -> Unit,
    sosActionsEnabled: Boolean,
    onSosActionsEnabledChange: (Boolean) -> Unit,
    voiceKeywordsGate: Boolean,
    voicePermissionGranted: Boolean,
    onVoiceKeywordsGateChange: (Boolean) -> Unit,
    intercomState: IntercomState,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val visual = phaseVisual(snapshot.phase)
    val tone = toneOf(visual.state)
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val cardColor = if (isDark) MaterialTheme.colorScheme.surface else visual.background
    val dangerInk = if (isDark) MaterialTheme.colorScheme.error else Tone.BAD.text
    val phase = snapshot.phase
    val countdownEndsAt = snapshot.countdownEndsAt
    var seconds by remember(countdownEndsAt) {
        mutableStateOf(countdownEndsAt?.let(::countdownRemainingSeconds) ?: 0L)
    }
    LaunchedEffect(countdownEndsAt) {
        if (countdownEndsAt == null) return@LaunchedEffect
        while (isActive) {
            seconds = countdownRemainingSeconds(countdownEndsAt)
            if (countdownEndsAt - SystemClock.elapsedRealtime() <= 0L) break
            delay(200L)
        }
    }
    val gpsPill: Pair<String, Tone>? = when {
        phase == SafetyPhase.STOPPED -> null
        snapshot.gpsAvailable -> "GPS OK" to Tone.OK
        snapshot.gpsFixAgeSeconds != null -> "GPS LENTO" to Tone.WARN
        else -> "BUSCANDO GPS" to Tone.WARN
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = cardColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            1.dp,
            if (isDark) MaterialTheme.colorScheme.outlineVariant else tone.line
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val monitoring = phase != SafetyPhase.STOPPED
            // La fase solo se anuncia cuando aporta algo: "APAGADO" y "OK" ya los
            // indica el color del boton del monitor.
            if (monitoring && phase != SafetyPhase.MONITORING) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(visual.state.color))
                    Spacer(Modifier.size(10.dp))
                    Text(
                        "ESTADO ${visual.title}",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f)
                    )
                    if (gpsPill != null) StatusPill(gpsPill.first, gpsPill.second)
                }
            }
            val detailText = snapshot.detail.takeIf {
                it.isNotBlank() && (countdownEndsAt == null || !it.startsWith("Cuenta atras"))
            }
            if (detailText != null) {
                Text(
                    detailText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (snapshot.dispatchInProgress) {
                Text(
                    "AVISOS SOS EN CURSO",
                    color = dangerInk,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            if (countdownEndsAt != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        String.format(Locale.US, "%02d", seconds),
                        fontSize = 62.sp,
                        lineHeight = 62.sp,
                        fontWeight = FontWeight.Bold,
                        color = dangerInk
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "SEGUNDOS",
                            style = MaterialTheme.typography.labelSmall,
                            color = dangerInk
                        )
                        Text(
                            "para cancelar el SOS. Cancela abajo.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (!phase.locksCriticalSettings()) {
                val monitorOn = phase != SafetyPhase.STOPPED
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StateToggleButton(
                        label = "MONITOR",
                        stateLabel = if (monitorOn) "ON" else "OFF",
                        on = monitorOn,
                        onColor = StateOnInk,
                        offColor = StateOffInk,
                        modifier = Modifier.weight(1f),
                        onClick = if (monitorOn) onStop else onStart
                    )
                    StateToggleButton(
                        label = SOS_HARD,
                        stateLabel = if (sosActionsEnabled) "ON" else "OFF",
                        on = sosActionsEnabled,
                        onColor = StateOnInk,
                        offColor = Tone.BAD.text,
                        modifier = Modifier.weight(1f),
                        onClick = { onSosActionsEnabledChange(!sosActionsEnabled) }
                    )
                }
            }
            SensorStatusLine(
                snapshot = snapshot,
                intercomState = intercomState
            )
            AudioLevelLine(
                audioLevel = audioLevel,
                voiceActive = voiceKeywordsGate && voicePermissionGranted &&
                    snapshot.phase != SafetyPhase.STOPPED,
                onToggle = { onVoiceKeywordsGateChange(!voiceKeywordsGate) }
            )
            // Etiqueta y T. PROMEDIO van juntas: menos aire entre ellas que entre el
            // resto de bloques de la tarjeta.
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SensitivityControl(
                    sensitivityG = sensitivityG,
                    effectiveG = snapshot.effectiveThresholdG ?: sensitivityG,
                    livePeakG = snapshot.ridePeakG,
                    activeWindowMinutes = snapshot.adaptationWindowMinutes,
                    windowAuto = snapshot.adaptationWindowAuto,
                    hitTestRunning = snapshot.hitTestRunning,
                    hitTestVerdict = snapshot.hitTestVerdict,
                    enabled = phase == SafetyPhase.STOPPED,
                    onSensitivityChange = onSensitivityChange,
                    onWindowChange = onWindowChange,
                    onHitTest = onHitTest,
                    showSlider = false,
                    showWindow = false,
                    showPeak = false
                )
                AdaptationWindowInfo(
                    activeWindowMinutes = snapshot.adaptationWindowMinutes,
                    windowAuto = snapshot.adaptationWindowAuto
                )
            }
        }
    }
}

/**
 * Luces de estado de los sensores: gris = apagado, naranja = no funcional,
 * verde = funcional. Se muestran siempre bajo el boton del monitor.
 */
@androidx.compose.runtime.Composable
private fun SensorStatusLine(snapshot: SafetySnapshot, intercomState: IntercomState) {
    val running = snapshot.phase != SafetyPhase.STOPPED
    val orientationDisabled = snapshot.orientationDetail.startsWith("Desactivada") ||
        snapshot.orientationDetail.startsWith("No necesaria")
    val lights = listOf(
        "GOLPE" to when {
            !running -> IndicatorState.OFF
            snapshot.sensorAvailable -> IndicatorState.GOOD
            else -> IndicatorState.PENDING
        },
        "INCLIN." to when {
            !running -> IndicatorState.OFF
            snapshot.orientationAvailable -> IndicatorState.GOOD
            orientationDisabled -> IndicatorState.OFF
            else -> IndicatorState.PENDING
        },
        "GPS" to when {
            !running -> IndicatorState.OFF
            snapshot.gpsAvailable -> IndicatorState.GOOD
            else -> IndicatorState.PENDING
        },
        "VELOC." to when {
            !running -> IndicatorState.OFF
            snapshot.speedKmh != null -> IndicatorState.GOOD
            else -> IndicatorState.PENDING
        },
        "INTERCOM" to when (intercomState) {
            IntercomState.ACTIVE -> IndicatorState.GOOD
            IntercomState.INACTIVE -> IndicatorState.STANDBY
            IntercomState.MISSING -> IndicatorState.OFF
        }
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        lights.forEach { (label, state) ->
            val description = if (label == "INTERCOM") {
                "INTERCOM ${intercomStateLabel(intercomState)}"
            } else {
                "$label ${sensorStateLabel(state)}"
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = description },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(state.color)
                )
                Text(
                    label,
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun sensorStateLabel(state: IndicatorState): String = when (state) {
    IndicatorState.GOOD -> "funcional"
    IndicatorState.PENDING -> "no funcional"
    IndicatorState.ERROR -> "no funcional"
    IndicatorState.STANDBY -> "no activo"
    IndicatorState.OFF -> "apagado"
}

/** Etiqueta de los tres estados de la luz INTERCOM. */
private fun intercomStateLabel(state: IntercomState): String = when (state) {
    IntercomState.ACTIVE -> "activo"
    IntercomState.INACTIVE -> "instalado, no activo"
    IntercomState.MISSING -> "no instalado"
}

@androidx.compose.runtime.Composable
private fun StatusRingOrDot(active: Boolean) {
    if (active) {
        Box(
            Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(StrongGreen)
        )
    } else {
        Box(
            Modifier
                .size(20.dp)
                .border(2.dp, StateOffInk, CircleShape)
        )
    }
}

/** Linea de nivel de audio; va justo debajo de las luces de los sensores. */
@androidx.compose.runtime.Composable
private fun AudioLevelLine(
    audioLevel: AudioLevelReading,
    voiceActive: Boolean,
    onToggle: () -> Unit
) {
    val circleAction = if (voiceActive) {
        "Voz activa; tocar para desactivar"
    } else {
        "Voz disponible; tocar para activar"
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "NIVEL DE AUDIO",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            Text(
                audioLevel.label,
                color = when {
                    audioLevel.label.equals("Voz desactivada", ignoreCase = true) -> StateOffInk
                    audioLevel.active || audioLevel.fromVoice -> StrongGreen
                    else -> Color(0xFF6B7280)
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onToggle)
                    .semantics {
                        contentDescription = circleAction
                        role = Role.Switch
                    },
                contentAlignment = Alignment.Center
            ) {
                StatusRingOrDot(active = voiceActive)
            }
            Spacer(Modifier.size(8.dp))
            LevelBar(audioLevel.progress, Modifier.weight(1f))
        }
    }
}

/** Barra de nivel; mismo grosor y redondeo que la barra del ajuste de golpes. */
@androidx.compose.runtime.Composable
private fun LevelBar(progress: Float, modifier: Modifier = Modifier) {
    val trackColor = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        Color(0xFFB9F6CA)
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(SENSITIVITY_TRACK_HEIGHT)
            .clip(RoundedCornerShape(SENSITIVITY_TRACK_HEIGHT / 2))
            .background(trackColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(SENSITIVITY_TRACK_HEIGHT / 2))
                .background(StrongGreen)
        )
    }
}

@androidx.compose.runtime.Composable
private fun SosPanel(onSos: () -> Unit) {
    var holdProgress by remember { mutableStateOf(0f) }
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val sosHalo = if (isDark) MaterialTheme.colorScheme.errorContainer else Tone.BAD.soft
    val wt9011 by Wt9011Controller.state.collectAsStateWithLifecycle()
    val showTilt = wt9011.enabled &&
        wt9011.monitoring &&
        wt9011.calibrated &&
        wt9011.status == Wt9011Status.READY
    val tiltColor = when (wt9011.tiltLevel) {
        2 -> Tone.BAD.text
        1 -> Tone.WARN.text
        else -> Tone.OK.text
    }
    val tiltDescription = when (wt9011.tiltLevel) {
        2 -> "SOS"
        1 -> "faltan menos de 15 grados"
        else -> "correcto"
    }
    val fill by animateFloatAsState(
        targetValue = holdProgress,
        animationSpec = tween(durationMillis = 90, easing = LinearEasing)
    )
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(HOLD_COLUMN_GAP)
        ) {
            HoldProgressColumn(
                fill = fill,
                axisAngle = wt9011.axisXDegrees,
                threshold = wt9011.thresholdDegrees,
                showTilt = showTilt,
                tiltColor = tiltColor,
                direction = 1,
                description = if (showTilt) {
                    "Eje X: ${wt9011.axisXDegrees?.toInt() ?: 0} grados, $tiltDescription"
                } else {
                    "Eje X sin datos"
                }
            )
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size((SOS_BUTTON_SIZE + 24).dp)
                        .clip(CircleShape)
                        .background(sosHalo)
                )
                RoundActionButton(
                    mainText = SOS_HARD,
                    subText = "5 s",
                    color = Tone.BAD.text,
                    buttonSize = SOS_BUTTON_SIZE.dp,
                    onTap = { /* A short tap is intentionally harmless. */ },
                    onLongPress = onSos,
                    onHoldProgress = { holdProgress = it }
                )
            }
            HoldProgressColumn(
                fill = fill,
                axisAngle = wt9011.axisYDegrees,
                threshold = wt9011.thresholdDegrees,
                showTilt = showTilt,
                tiltColor = tiltColor,
                direction = -1,
                description = if (showTilt) {
                    "Eje Y: ${wt9011.axisYDegrees?.toInt() ?: 0} grados, $tiltDescription"
                } else {
                    "Eje Y sin datos"
                }
            )
        }
        if (showTilt) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TiltValueBadge(
                    axis = "X",
                    value = wt9011.axisXDegrees,
                    color = tiltColor
                )
                TiltValueBadge(
                    axis = "Y",
                    value = wt9011.axisYDegrees,
                    color = tiltColor
                )
            }
        }
        Text(
            "MANTEN PULSADO 5 SEGUNDOS",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Columna lateral del SOS.
 *
 * El relleno verde de abajo arriba sigue siendo el progreso de los 5 segundos.
 * Encima se dibuja una flecha con la inclinacion del eje (X a la izquierda, Y a
 * la derecha): sube con el angulo y cambia a ambar/rojo cerca del umbral. Las
 * dos cosas se ven a la vez, tambien mientras se mantiene pulsado el SOS.
 */
@androidx.compose.runtime.Composable
private fun HoldProgressColumn(
    fill: Float,
    axisAngle: Float?,
    threshold: Float,
    showTilt: Boolean,
    tiltColor: Color,
    direction: Int,
    description: String
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val trackColor = if (isDark) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        HoldTrackGreen
    }
    val outlineColor = if (isDark) Color.White else Color.Black
    Box(
        modifier = Modifier
            .width(HOLD_COLUMN_WIDTH)
            .height(HOLD_COLUMN_HEIGHT)
            .semantics { contentDescription = description }
            .clip(RoundedCornerShape(HOLD_COLUMN_WIDTH / 2))
            .background(trackColor),
        contentAlignment = Alignment.BottomCenter
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(fill.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(HOLD_COLUMN_WIDTH / 2))
                .background(StrongGreen)
        )
        Canvas(Modifier.fillMaxSize()) {
            if (!showTilt || axisAngle == null) return@Canvas
            val fraction = (abs(axisAngle) / threshold.coerceAtLeast(1f)).coerceIn(0f, 1f)
            val centerY = size.height * (1f - 0.06f - 0.88f * fraction)
            val arrowHeight = 10.dp.toPx()
            val width = size.width
            val path = Path().apply {
                if (direction > 0) {
                    moveTo(width * 0.10f, centerY - arrowHeight / 2f)
                    lineTo(width * 0.90f, centerY)
                    lineTo(width * 0.10f, centerY + arrowHeight / 2f)
                } else {
                    moveTo(width * 0.90f, centerY - arrowHeight / 2f)
                    lineTo(width * 0.10f, centerY)
                    lineTo(width * 0.90f, centerY + arrowHeight / 2f)
                }
                close()
            }
            // Barra + punta: se lee tambien sobre el relleno verde del SOS.
            drawLine(
                color = outlineColor,
                start = Offset(0f, centerY),
                end = Offset(width, centerY),
                strokeWidth = 5.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawLine(
                color = tiltColor,
                start = Offset(0f, centerY),
                end = Offset(width, centerY),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawPath(path, color = outlineColor, style = Stroke(width = 3.dp.toPx()))
            drawPath(path, color = tiltColor)
        }
    }
}

/**
 * Valor del eje, centrado bajo su columna. Va en una fila propia para que el
 * tamano de letra del sistema no lo meta encima de la barra ni lo recorte.
 */
@Composable
private fun TiltValueBadge(
    axis: String,
    value: Float?,
    color: Color
) {
    Box(
        modifier = Modifier
            .heightIn(min = 24.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .border(1.dp, color.copy(alpha = 0.65f), RoundedCornerShape(7.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "$axis ${value?.let { "${it.toInt()}°" } ?: "—"}",
            style = MaterialTheme.typography.labelSmall,
            fontSize = 15.sp,
            lineHeight = 17.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

/** Icono de "Sensores" dibujado con barras para no depender de glifos de fuente. */
@androidx.compose.runtime.Composable
private fun SensorGlyph(tint: Color) {
    val heights = listOf(10.dp, 16.dp, 23.dp)
    Row(
        modifier = Modifier.size(width = 24.dp, height = 23.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        heights.forEach { barHeight ->
            Box(
                modifier = Modifier
                    .size(width = 6.dp, height = barHeight)
                    .background(tint, RoundedCornerShape(2.dp))
            )
        }
    }
}

private fun formatFixAge(seconds: Long?): String? {
    if (seconds == null) return null
    return when {
        seconds < 60L -> "${seconds}s"
        seconds < 3_600L -> "${seconds / 60L} min"
        else -> "${seconds / 3_600L} h ${(seconds % 3_600L) / 60L} min"
    }
}

@androidx.compose.runtime.Composable
private fun SensorsScreen(
    modifier: Modifier,
    snapshot: SafetySnapshot,
    intercomState: IntercomState,
    sensitivityG: Float
) {
    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(Modifier.height(12.dp)) }
        item {
            MonitorCard(
                snapshot = snapshot,
                intercomState = intercomState,
                sensitivityG = sensitivityG
            )
        }
        item { Spacer(Modifier.height(6.dp)) }
    }
}

@androidx.compose.runtime.Composable
private fun MonitorCard(
    snapshot: SafetySnapshot,
    intercomState: IntercomState,
    sensitivityG: Float
) {
    val running = snapshot.phase != SafetyPhase.STOPPED
    val gpsLiveText = when {
        !running -> "--"
        snapshot.gpsAvailable -> formatFixAge(snapshot.gpsFixAgeSeconds)?.let { "OK ($it)" } ?: "OK"
        snapshot.gpsFixAgeSeconds != null -> "LENTO (${formatFixAge(snapshot.gpsFixAgeSeconds)})"
        else -> "BUSCANDO"
    }
    val gpsLiveState = when {
        !running -> IndicatorState.OFF
        snapshot.gpsAvailable -> IndicatorState.GOOD
        else -> IndicatorState.PENDING
    }
    val gpsStatusNote = when {
        !running -> "Se activa al iniciar el monitor."
        snapshot.gpsAvailable -> "Posicion precisa y reciente. Solo aporta el mapa."
        snapshot.gpsFixAgeSeconds != null ->
            "Ultima posicion hace ${formatFixAge(snapshot.gpsFixAgeSeconds)}. " +
                "Solo cuenta si es de los ultimos 5 min."
        else -> "Sin posicion todavia: al aire libre tarda unos segundos, bajo techo puede no llegar."
    }
    SeguCard(
        title = "MONITOR",
        status = if (running) "ACTIVO" else "DETENIDO",
        tone = if (running) Tone.OK else Tone.OFF,
        description = "Los avisos nunca dependen del GPS: solo del golpe, la voz o el boton."
    ) {
        val effectiveSensitivity = snapshot.effectiveThresholdG ?: sensitivityG
        ServiceRow(
            "SENSOR",
            "${snapshot.movementG?.let { String.format(Locale.US, "%.2f g", it) } ?: "--"} / " +
                String.format(Locale.US, "%.1f g", effectiveSensitivity),
            if (snapshot.sensorAvailable) IndicatorState.GOOD else IndicatorState.PENDING,
            buildString {
                append("Lectura actual / nivel aplicado (tu ajuste: ")
                append(String.format(Locale.US, "%.1f", sensitivityG))
                append(" g). ")
                append(snapshot.sensorDetail)
                snapshot.deviceNoiseG?.let {
                    append(" · ruido ").append(String.format(Locale.US, "%.2f", it)).append(" g")
                }
                snapshot.deviceP99G?.let {
                    append(" · traqueteo ").append(String.format(Locale.US, "%.2f", it)).append(" g")
                }
                snapshot.samplingHz?.let { append(" · ").append(it).append(" Hz") }
                snapshot.ridePeakG?.takeIf { it > 0f }?.let {
                    append(" · pico ").append(String.format(Locale.US, "%.2f", it)).append(" g")
                }
                append(" · ventana ").append(snapshot.adaptationWindowMinutes).append(" min")
                if (snapshot.adaptationWindowAuto) append(" (auto)")
            }
        )
        ServiceRow(
            "ORIENTACION",
            when {
                snapshot.orientationAvailable -> snapshot.orientationTiltDegrees?.let {
                    String.format(Locale.US, "%.0f grados", it)
                } ?: "OK"
                snapshot.orientationDetail.startsWith("Desactivada") ||
                    snapshot.orientationDetail.startsWith("No necesaria") -> "DESACT."
                else -> "PENDIENTE"
            },
            when {
                snapshot.orientationAvailable -> IndicatorState.GOOD
                snapshot.orientationDetail.startsWith("Desactivada") ||
                    snapshot.orientationDetail.startsWith("No necesaria") -> IndicatorState.OFF
                else -> IndicatorState.PENDING
            },
            snapshot.orientationDetail
        )
        ServiceRow("GPS", gpsLiveText, gpsLiveState, gpsStatusNote)
        ServiceRow(
            "VELOCIDAD",
            snapshot.speedKmh?.let { String.format(Locale.US, "%.1f km/h", it) }
                ?: if (running) "SIN DATO" else "--",
            if (snapshot.speedKmh != null) IndicatorState.GOOD else IndicatorState.PENDING,
            when {
                !running -> "Se activa al iniciar el monitor."
                snapshot.speedKmh != null -> "Informativa; nunca bloquea golpes."
                else -> "Solo la aporta el GPS con cielo visible; la posicion por red no la trae. " +
                    "Informativa: nunca bloquea golpes."
            }
        )
        ServiceRow(
            "INTERCOM",
            when (intercomState) {
                IntercomState.ACTIVE -> "ACTIVO"
                IntercomState.INACTIVE -> "INSTALADO"
                IntercomState.MISSING -> "--"
            },
            when (intercomState) {
                IntercomState.ACTIVE -> IndicatorState.GOOD
                IntercomState.INACTIVE -> IndicatorState.STANDBY
                IntercomState.MISSING -> IndicatorState.OFF
            },
            when (intercomState) {
                IntercomState.ACTIVE ->
                    "InterCOM encendido: el aviso de voz llega a los motoristas del grupo o del INTERCOM WiFi."
                IntercomState.INACTIVE ->
                    "InterCOM instalado pero apagado: el aviso no suena en ningun sitio. " +
                        "Enciendelo en InterCOM-Luky para que llegue a los demas."
                IntercomState.MISSING ->
                    "InterCOM no instalado: el aviso no suena en ningun sitio. " +
                        "Instala InterCOM-Luky para avisar por voz a los demas motoristas."
            }
        )
    }
}

private data class PhaseVisual(
    val title: String,
    val background: Color,
    val state: IndicatorState
)

@androidx.compose.runtime.Composable
private fun SosDisabledWarning() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Tone.BAD.soft,
        contentColor = Tone.BAD.text,
        border = BorderStroke(1.dp, Tone.BAD.line)
    ) {
        Text(
            "AVISOS SOS DESACTIVADOS: pulsa $SOS_HARD OFF en la tarjeta del monitor para activarlos.",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            color = Tone.BAD.text,
            style = MaterialTheme.typography.titleSmall
        )
    }
}

@androidx.compose.runtime.Composable
private fun GpsOffWarning(onOpenGpsSettings: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Tone.BAD.soft,
        contentColor = Tone.BAD.text
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "GPS APAGADO: el SOS saldra sin mapa ni velocidad.",
                color = Tone.BAD.text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            OutlinedButton(
                onClick = onOpenGpsSettings,
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = Tone.BAD.text)
            ) {
                Text("ABRIR AJUSTES GPS", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private enum class IndicatorState(val color: Color) {
    GOOD(StrongGreen),
    PENDING(Color(0xFFF9A825)),
    STANDBY(Color(0xFFFF9800)),
    ERROR(Color(0xFFE53935)),
    OFF(Color(0xFF9AA0A6))
}

private val StrongGreen = Color(0xFF00C853)

private fun phaseVisual(phase: SafetyPhase): PhaseVisual = when (phase) {
    SafetyPhase.STOPPED -> PhaseVisual("APAGADO", Tone.OFF.soft, IndicatorState.OFF)
    SafetyPhase.MONITORING -> PhaseVisual("OK", Tone.OK.soft, IndicatorState.GOOD)
    SafetyPhase.VALIDATING -> PhaseVisual("PENDIENTE", Tone.WARN.soft, IndicatorState.PENDING)
    SafetyPhase.FALL_CANDIDATE -> PhaseVisual("POSIBLE CAIDA", Color(0xFFFFF0E0), IndicatorState.PENDING)
    SafetyPhase.COUNTDOWN -> PhaseVisual("SOS ACTIVO", Tone.BAD.soft, IndicatorState.ERROR)
}

@androidx.compose.runtime.Composable
private fun ServiceRow(label: String, value: String, state: IndicatorState, note: String = "") {
    val rowColor = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        Color(0xFFF8F5F1)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = rowColor,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(state.color))
                Spacer(Modifier.size(8.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.widthIn(min = 56.dp),
                    maxLines = 1
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    value,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End
                )
            }
            if (note.isNotBlank()) {
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private val HarleyOrange = Color(0xFFF58220)
private val StatusTextColor = Color(0xFF1F2937)

private enum class PressEnd {
    RELEASED,
    CANCELLED,
    LONG_PRESS
}

private const val HOLD_DURATION_MS = 5_000L
private const val SOS_BUTTON_SIZE = 210
private const val HOLD_PROGRESS_STEPS = 100f

/** Destellos del numero de T. PROMEDIO cuando AUTO cambia de ventana. */
private const val BLINK_COUNT = 3
private const val BLINK_ON_MS = 320L
private const val BLINK_OFF_MS = 220L
private val SENSITIVITY_TRACK_HEIGHT = 8.dp
private val HOLD_COLUMN_WIDTH = 12.dp
private val HOLD_COLUMN_GAP = 4.dp
private val HOLD_COLUMN_HEIGHT = (SOS_BUTTON_SIZE + 24).dp
private val HoldTrackGreen = Color(0xFFB9F6CA)
private const val SOS_MAIN_FONT_SIZE = 40
private const val SOS_SUB_FONT_SIZE = 20

@androidx.compose.runtime.Composable
private fun RoundActionButton(
    mainText: String,
    color: Color,
    buttonSize: androidx.compose.ui.unit.Dp,
    subText: String? = null,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onHoldProgress: (Float) -> Unit = {}
) {
    var pressed by remember { mutableStateOf(false) }
    val toneGenerator = remember { ToneGenerator(AudioManager.STREAM_ALARM, 100) }
    DisposableEffect(Unit) {
        onDispose { runCatching { toneGenerator.release() } }
    }
    Surface(
        modifier = Modifier
            .size(buttonSize)
            .semantics {
                role = Role.Button
                onClick(label = mainText) { onTap(); true }
                onLongClick(label = "$mainText durante 5 segundos") { onLongPress(); true }
            }
            .graphicsLayer {
                scaleX = if (pressed) 0.9f else 1f
                scaleY = if (pressed) 0.9f else 1f
                alpha = if (pressed) 0.85f else 1f
            }
            .pointerInput(onTap, onLongPress) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    try {
                        pressed = true
                        val pressStartAt = SystemClock.uptimeMillis()
                        var lastBeepIndex = -1
                        var lastProgressStep = -1
                        var result: PressEnd? = null

                        while (result == null) {
                            val elapsed = SystemClock.uptimeMillis() - pressStartAt
                            val remaining = HOLD_DURATION_MS - elapsed
                            if (remaining <= 0L) {
                                result = PressEnd.LONG_PRESS
                                break
                            }

                            val progressStep =
                                (elapsed.toFloat() / HOLD_DURATION_MS * HOLD_PROGRESS_STEPS).toInt()
                            if (progressStep != lastProgressStep) {
                                lastProgressStep = progressStep
                                onHoldProgress(progressStep / HOLD_PROGRESS_STEPS)
                            }

                            val beepWindowStart = HOLD_DURATION_MS - 3_000L
                            if (elapsed >= beepWindowStart) {
                                val beepIndex = ((elapsed - beepWindowStart) / 1_000L).toInt()
                                if (beepIndex > lastBeepIndex) {
                                    lastBeepIndex = beepIndex
                                    runCatching { toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 90) }
                                }
                            }

                            val eventResult = withTimeoutOrNull(minOf(50L, remaining)) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: return@withTimeoutOrNull PressEnd.CANCELLED
                                if (!change.pressed) {
                                    return@withTimeoutOrNull if (change.isConsumed) {
                                        PressEnd.CANCELLED
                                    } else {
                                        PressEnd.RELEASED
                                    }
                                }
                                val position = change.position
                                val outside = position.x < 0f || position.y < 0f ||
                                    position.x > size.width.toFloat() || position.y > size.height.toFloat()
                                if (change.isConsumed || outside) PressEnd.CANCELLED else null
                            }
                            if (eventResult != null) result = eventResult
                        }

                        when (result) {
                            PressEnd.RELEASED -> onTap()
                            PressEnd.LONG_PRESS -> {
                                onLongPress()
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                    if (change == null || !change.pressed) break
                                }
                            }
                            PressEnd.CANCELLED, null -> Unit
                        }
                    } finally {
                        pressed = false
                        onHoldProgress(0f)
                    }
                }
            },
        shape = CircleShape,
        color = color,
        contentColor = Color.White,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                mainText,
                fontWeight = FontWeight.Bold,
                fontSize = SOS_MAIN_FONT_SIZE.sp,
                textAlign = TextAlign.Center,
                letterSpacing = 2.sp
            )
            if (subText != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    subText,
                    fontWeight = FontWeight.Bold,
                    fontSize = SOS_SUB_FONT_SIZE.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun SegmentedSensitivitySlider(
    value: Float,
    enabled: Boolean,
    onValueChange: (Float) -> Unit
) {
    val v = value.coerceIn(0.5f, 7f)
    Box(Modifier.fillMaxWidth().height(28.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(SENSITIVITY_TRACK_HEIGHT)
                .align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            SegTrack(1.5f, Color(0xFF00C853))
            SegTrack(2.0f, Color(0xFFF9A825))
            SegTrack(3.5f, Color(0xFFE53935))
        }
        Slider(
            value = v,
            enabled = enabled,
            onValueChange = onValueChange,
            valueRange = 0.5f..7f,
            steps = 12,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = StateOffInk,
                disabledThumbColor = StateOnInk,
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent,
                disabledActiveTrackColor = Color.Transparent,
                disabledInactiveTrackColor = Color.Transparent
            )
        )
    }
}

@androidx.compose.runtime.Composable
private fun RowScope.SegTrack(weight: Float, color: Color) {
    Box(
        Modifier
            .weight(weight)
            .fillMaxHeight()
            .clip(RoundedCornerShape(4.dp))
            .background(color)
    )
}

@androidx.compose.runtime.Composable
private fun SensitivityControl(
    sensitivityG: Float,
    effectiveG: Float,
    livePeakG: Float?,
    activeWindowMinutes: Int,
    windowAuto: Boolean,
    hitTestRunning: Boolean,
    hitTestVerdict: String?,
    enabled: Boolean,
    onSensitivityChange: (Float) -> Unit,
    onWindowChange: (AdaptationWindowMode) -> Unit,
    onHitTest: () -> Unit,
    showSlider: Boolean = true,
    showWindow: Boolean = true,
    showPeak: Boolean = true,
    valueLabel: String = "SENSIBILIDAD A LOS GOLPES ="
) {
    val context = LocalContext.current
    // Con el monitor detenido se enseña el pico del ultimo viaje guardado; mientras
    // corre, el pico en vivo que publica el servicio.
    val storedPeak = remember(enabled, livePeakG) {
        if (livePeakG == null) SafetyMonitorService.loadRidePeak(context) else 0f
    }
    val peak = livePeakG?.takeIf { it >= SensitivityAdvisor.MIN_PEAK_G }
        ?: storedPeak.takeIf { it >= SensitivityAdvisor.MIN_PEAK_G }
    val suggestion = peak?.let {
        SensitivityAdvisor.suggest(peakG = it, currentG = sensitivityG, rideMs = Long.MAX_VALUE)
    }
    val canApply = enabled && suggestion != null &&
        suggestion.verdict != SensitivityAdvisor.Verdict.CORRECT
    val infoText = buildString {
        if (peak != null) {
            append(if (livePeakG != null) "PICO " else "ULTIMO ")
            append(SensitivityAdvisor.formatG(peak))
            append(" g")
        }
        if (effectiveG > sensitivityG + 0.05f) {
            if (isNotEmpty()) append(" · ")
            append("EFECT ")
            append(SensitivityAdvisor.formatG(effectiveG))
        }
        if (suggestion != null && suggestion.verdict != SensitivityAdvisor.Verdict.CORRECT) {
            if (isNotEmpty()) append(" · ")
            append("SUG ")
            append(SensitivityAdvisor.formatG(suggestion.recommendedG))
        }
        if (isEmpty()) append(if (enabled) "Sin datos del viaje" else "Midiendo...")
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                valueLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${String.format(Locale.US, "%.1f", sensitivityG)} g",
                color = thumbColorFor(sensitivityG),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
        if (showSlider) {
            Spacer(Modifier.height(3.dp))
            SegmentedSensitivitySlider(
                value = sensitivityG,
                enabled = enabled,
                onValueChange = onSensitivityChange
            )
            Row(Modifier.fillMaxWidth()) {
                (0..7).forEach { g ->
                    Text("$g", modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (showWindow) {
            AdaptationWindowSelector(
                activeWindowMinutes = activeWindowMinutes,
                windowAuto = windowAuto,
                enabled = enabled,
                onWindowChange = onWindowChange
            )
        }
        if (showPeak) {
            SensitivityPeakRow(
                infoText = infoText,
                suggestion = suggestion,
                canApply = canApply,
                hitTestRunning = hitTestRunning,
                hitTestVerdict = hitTestVerdict,
                enabled = enabled,
                onSensitivityChange = onSensitivityChange,
                onHitTest = onHitTest
            )
        }
    }
}

/** Informacion de la ventana de promedio en la pantalla principal: solo lectura. */
@androidx.compose.runtime.Composable
private fun AdaptationWindowInfo(
    activeWindowMinutes: Int,
    windowAuto: Boolean
) {
    val context = LocalContext.current
    val storedMode = remember(windowAuto, activeWindowMinutes) {
        SafetyMonitorService.loadAdaptationWindowMode(context)
    }
    // Cuando AUTO cambia de ventana (por ejemplo de 3 a 2 minutos por un camino
    // distinto) el numero destella unos segundos para que se note sin mirar fijo.
    // La primera composicion no destella: solo los cambios reales.
    val lastMinutes = remember { mutableStateOf(activeWindowMinutes) }
    var emphasised by remember { mutableStateOf(false) }
    LaunchedEffect(activeWindowMinutes) {
        if (activeWindowMinutes == lastMinutes.value) return@LaunchedEffect
        lastMinutes.value = activeWindowMinutes
        if (!windowAuto) return@LaunchedEffect
        repeat(BLINK_COUNT) {
            emphasised = true
            delay(BLINK_ON_MS)
            emphasised = false
            delay(BLINK_OFF_MS)
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "T. PROMEDIO",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AdaptiveWindowInfoChip("AUTO", selected = windowAuto)
        AdaptationWindowMode.fixed.forEach { mode ->
            val selected = if (windowAuto) {
                mode.minutes == activeWindowMinutes
            } else {
                storedMode == mode
            }
            AdaptiveWindowInfoChip(
                label = "${mode.minutes}",
                // En AUTO se marca tambien el numero que se esta aplicando; con una
                // ventana fija solo se marca esa.
                selected = selected,
                emphasised = selected && emphasised
            )
        }
    }
}

/** Chip de solo lectura: todo en negrilla y el elegido en verde. */
@androidx.compose.runtime.Composable
private fun AdaptiveWindowInfoChip(
    label: String,
    selected: Boolean,
    emphasised: Boolean = false
) {
    Text(
        text = label,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    emphasised -> StrongGreen.copy(alpha = 0.45f)
                    selected -> StrongGreen.copy(alpha = 0.16f)
                    else -> Color.Transparent
                }
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = if (selected) StrongGreen else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Fila ADAPT.: AUTO o 1/2/3 minutos, con lo elegido en verde. */
@androidx.compose.runtime.Composable
private fun AdaptationWindowSelector(
    activeWindowMinutes: Int,
    windowAuto: Boolean,
    enabled: Boolean,
    onWindowChange: (AdaptationWindowMode) -> Unit
) {
    val context = LocalContext.current
    val storedMode = remember(windowAuto, activeWindowMinutes) {
        SafetyMonitorService.loadAdaptationWindowMode(context)
    }
    Spacer(Modifier.height(5.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "ADAPT.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AdaptationWindowChip("AUTO", selected = windowAuto, enabled = enabled) {
            onWindowChange(AdaptationWindowMode.AUTO)
        }
        AdaptationWindowMode.fixed.forEach { mode ->
            AdaptationWindowChip(
                label = "${mode.minutes}",
                selected = !windowAuto && storedMode == mode,
                enabled = enabled
            ) {
                onWindowChange(mode)
            }
        }
        Text(
            "MIN",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (windowAuto) {
        Text(
            "AUTO aplica $activeWindowMinutes min ahora",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Pico del viaje, umbral efectivo, sugerencia y PRUEBA DE GOLPE. */
@androidx.compose.runtime.Composable
private fun SensitivityPeakRow(
    infoText: String,
    suggestion: SensitivityAdvisor.Suggestion?,
    canApply: Boolean,
    hitTestRunning: Boolean,
    hitTestVerdict: String?,
    enabled: Boolean,
    onSensitivityChange: (Float) -> Unit,
    onHitTest: () -> Unit
) {
    Spacer(Modifier.height(3.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            infoText,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        if (canApply && suggestion != null) {
            TextButton(onClick = { onSensitivityChange(suggestion.recommendedG) }) {
                Text(
                    "USAR",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        if (enabled) {
            TextButton(onClick = onHitTest, enabled = !hitTestRunning) {
                Text(
                    if (hitTestRunning) "MIDIENDO..." else "PROBAR GOLPE",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
    if (hitTestVerdict != null) {
        Text(
            hitTestVerdict,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Chip pequeno de la ventana de aprendizaje: AUTO, 1, 2 o 3 minutos. */
@androidx.compose.runtime.Composable
private fun AdaptationWindowChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val foreground = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        selected -> StrongGreen
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = label,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) StrongGreen.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = foreground
    )
}

private data class AudioLevelReading(
    val progress: Float,
    val label: String,
    val active: Boolean = false,
    val fromVoice: Boolean = false
)

private fun hasAudioPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private fun hasCallPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun hasSendSmsPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

private fun SafetyPhase.locksCriticalSettings(): Boolean =
    this == SafetyPhase.VALIDATING || this == SafetyPhase.COUNTDOWN

private fun permissionDenialMessage(result: Map<String, Boolean>): String? {
    val denied = result.filterValues { it == false }
    if (denied.isEmpty()) return null
    val messages = mutableListOf<String>()
    denied.keys.forEach { perm ->
        when {
            perm.contains("LOCATION") -> messages.add("Sin ubicacion se detectan golpes, pero no habra mapa ni velocidad")
            perm.contains("CALL_PHONE") -> messages.add("Sin permiso de llamadas, el SOS no podra solicitar la llamada")
            perm.contains("RECORD_AUDIO") -> messages.add("Sin microfono la activacion por voz no funcionara")
            perm.contains("POST_NOTIFICATIONS") -> messages.add("Sin notificaciones no podras ver el estado del monitor")
        }
    }
    return messages.joinToString(". ").ifEmpty { null }
}


private fun meterProgress(db: Float): Float =
    ((db.toDouble() - AUDIO_MIN_DECIBELS) / -AUDIO_MIN_DECIBELS).toFloat().coerceIn(0f, 1f)

private const val AUDIO_MIN_DECIBELS = -60.0

private fun thumbColorFor(g: Float): Color = when {
    g < 1.5f -> Color(0xFF00C853)
    g < 3.5f -> Color(0xFFF9A825)
    else -> Color(0xFFE53935)
}

private fun isValidEmergencyPhone(value: String): Boolean {
    val normalized = value.trim()
    if (!normalized.matches(Regex("^[+]?[0-9 ()-]+$"))) return false
    val digitCount = normalized.count(Char::isDigit)
    return digitCount in 7..15
}
