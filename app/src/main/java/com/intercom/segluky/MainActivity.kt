package com.intercom.segluky

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.intercom.segluky.ui.InterSegTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            InterSegTheme {
                InterSegScreen()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @androidx.compose.runtime.Composable
    private fun InterSegScreen() {
        val snapshot by SafetyStateStore.snapshot.collectAsStateWithLifecycle()
        val context = this
        val repository = remember { ContactsRepository(context) }
        var contacts by remember { mutableStateOf(repository.load()) }
        var installation by remember { mutableStateOf(IntercomInstallation.inspect(context)) }
        var screen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(AppScreen.HOME) }
        var sensitivityG by remember { mutableStateOf(SafetyMonitorService.loadSensitivity(context)) }
        var voiceKeywordsGate by remember { mutableStateOf(SafetyMonitorService.loadVoiceKeywordsGate(context)) }
        var fallOrientationGate by remember { mutableStateOf(SafetyMonitorService.loadFallOrientationGate(context)) }
        var whatsappAutoSend by remember { mutableStateOf(WhatsAppAutoSend.isEnabled(context)) }
        var showGpsOffWarning by remember { mutableStateOf(false) }
        var sosActionsEnabled by remember { mutableStateOf(repository.areSosActionsEnabled()) }
        var audioPermissionGranted by remember { mutableStateOf(hasAudioPermission(context)) }
        var callPermissionGranted by remember { mutableStateOf(hasCallPermission(context)) }
        var notificationPermissionGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
        var permissionFeedback by remember { mutableStateOf<String?>(null) }
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    audioPermissionGranted = hasAudioPermission(context)
                    callPermissionGranted = hasCallPermission(context)
                    notificationPermissionGranted = hasNotificationPermission(context)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            audioPermissionGranted = hasAudioPermission(context)
            callPermissionGranted = hasCallPermission(context)
            notificationPermissionGranted = hasNotificationPermission(context)
            permissionFeedback = permissionDenialMessage(result)
            showGpsOffWarning = !SafetyMonitorService.isGpsProviderEnabled(context)
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
            val permissions = permissionsNeededForConfiguredFeatures()

            when {
                permissions.isNotEmpty() -> permissionLauncher.launch(permissions)
                else -> {
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

        BackHandler(enabled = screen != AppScreen.HOME) { screen = AppScreen.HOME }

        Scaffold(
            topBar = {
                SeguTopBar(
                    screen = screen,
                    onSettings = { screen = AppScreen.SETTINGS },
                    onInstructions = { screen = AppScreen.INSTRUCTIONS },
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
                            .padding(horizontal = 16.dp)
                            .multiTouchEmergency { SafetyMonitorService.confirm(context) },
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (snapshot.phase == SafetyPhase.STOPPED) {
                                    startMonitoring()
                                } else {
                                    SafetyMonitorService.stop(context)
                                }
                            },
                            enabled = snapshot.phase == SafetyPhase.STOPPED ||
                                snapshot.phase == SafetyPhase.MONITORING ||
                                snapshot.phase == SafetyPhase.FALL_CANDIDATE ||
                                snapshot.phase == SafetyPhase.COUNTDOWN,
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = when (snapshot.phase) {
                                    SafetyPhase.STOPPED -> "INICIAR"
                                    SafetyPhase.MONITORING -> "DETENER"
                                    SafetyPhase.FALL_CANDIDATE -> "DETENER"
                                    SafetyPhase.COUNTDOWN -> "DETENER"
                                    else -> "MONITOR ACTIVO"
                                },
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (snapshot.phase == SafetyPhase.COUNTDOWN) {
                            CountdownNumbers(
                                countdownEndsAt = snapshot.countdownEndsAt,
                                onCancel = { SafetyMonitorService.cancel(context) }
                            )
                        }
                        if (showGpsOffWarning) {
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
                        if (permissionWarnings.isNotEmpty()) {
                            PermissionReadinessWarning(
                                messages = permissionWarnings,
                                onRequestPermissions = ::requestConfiguredFeaturePermissions
                            )
                        }
                        SensitivityControl(
                            sensitivityG = sensitivityG,
                            movementLevel = snapshot.movementLevel,
                            audioLevel = audioLevel,
                            enabled = snapshot.phase == SafetyPhase.STOPPED,
                            onSensitivityChange = { g ->
                                sensitivityG = g
                                if (snapshot.phase == SafetyPhase.STOPPED) {
                                    SafetyMonitorService.saveSensitivity(context, g)
                                }
                            }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        RoundActionButton(
                            mainText = "S O S",
                            subText = "5 s",
                            color = Color(0xFFB3261E),
                            buttonSize = SOS_BUTTON_SIZE.dp,
                            onTap = { /* A short tap is intentionally harmless. */ },
                            onLongPress = { SafetyMonitorService.confirm(context) }
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    StatusCard(
                        snapshot = snapshot,
                        intercomInstalled = installation.installed,
                        sosActionsEnabled = sosActionsEnabled,
                        sensitivityG = sensitivityG,
                        onCancel = { SafetyMonitorService.cancel(context) }
                    )
                }
                }
                }
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
                    fallOrientationGate = fallOrientationGate,
                    onFallOrientationGateChange = { enabled ->
                        if (!SafetyStateStore.snapshot.value.phase.locksCriticalSettings()) {
                            fallOrientationGate = enabled
                            SafetyMonitorService.saveFallOrientationGate(context, enabled)
                            SafetyMonitorService.notifyConfigChanged(context)
                        }
                    },
                    sosActionsEnabled = sosActionsEnabled,
                    onSosActionsEnabledChange = { enabled ->
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
                    },
                    voiceKeywordsGate = voiceKeywordsGate,
                    audioPermissionGranted = audioPermissionGranted,
                    onVoiceKeywordsGateChange = { enabled ->
                        if (!SafetyStateStore.snapshot.value.phase.locksCriticalSettings()) {
                            when {
                                !enabled -> {
                                    voiceKeywordsGate = false
                                    SafetyMonitorService.saveVoiceKeywordsGate(context, false)
                                    SafetyMonitorService.notifyConfigChanged(context)
                                }
                                hasAudioPermission(context) -> {
                                    audioPermissionGranted = true
                                    voiceKeywordsGate = true
                                    SafetyMonitorService.saveVoiceKeywordsGate(context, true)
                                    SafetyMonitorService.notifyConfigChanged(context)
                                }
                                else -> voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    },
                    callPermissionRequired = callPermissionRequired,
                    callPermissionGranted = callPermissionGranted,
                    whatsappAutoSend = whatsappAutoSend,
                    onWhatsappAutoSendChange = { enabled ->
                        if (!SafetyStateStore.snapshot.value.phase.locksCriticalSettings()) {
                            whatsappAutoSend = enabled
                            WhatsAppAutoSend.setEnabled(context, enabled)
                        }
                    }
                )
                AppScreen.INSTRUCTIONS -> InstructionsScreen(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    snapshot = snapshot,
                    installation = installation,
                    onRefreshIntercom = { installation = IntercomInstallation.inspect(context) },
                    onOpenIntercom = { IntercomInstallation.open(context) }
                )
            }
        }
    }
}

private enum class AppScreen {
    HOME,
    SETTINGS,
    INSTRUCTIONS
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
private fun SeguTopBar(
    screen: AppScreen,
    onSettings: () -> Unit,
    onInstructions: () -> Unit,
    onBack: () -> Unit
) {
    when (screen) {
        AppScreen.HOME -> CenteredSeguTopBar(
            title = "Segu-Luky",
            titleColor = HarleyOrange,
            navigation = {
                IconButton(
                    onClick = onSettings,
                    modifier = Modifier.semantics { contentDescription = "Abrir configuracion" }
                ) {
                    Text("\u2699", color = HarleyOrange, fontSize = 26.sp, lineHeight = 26.sp)
                }
            },
            action = {
                TextButton(onClick = onInstructions) {
                        Text("V-1.12s", fontWeight = FontWeight.Bold)
                }
            }
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
    navigation: @androidx.compose.runtime.Composable () -> Unit = {},
    action: @androidx.compose.runtime.Composable () -> Unit = {}
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(64.dp)
        ) {
            Text(
                text = title,
                modifier = Modifier.align(Alignment.Center),
                color = titleColor,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            Box(Modifier.align(Alignment.CenterStart)) { navigation() }
            Box(Modifier.align(Alignment.CenterEnd)) { action() }
        }
    }
}

@androidx.compose.runtime.Composable
private fun CountdownNumbers(countdownEndsAt: Long?, onCancel: () -> Unit) {
    if (countdownEndsAt == null) return
    var seconds by remember { mutableStateOf(countdownRemainingSeconds(countdownEndsAt)) }
    LaunchedEffect(countdownEndsAt) {
        while (isActive) {
            seconds = countdownRemainingSeconds(countdownEndsAt)
            delay(200L)
            if (countdownEndsAt - SystemClock.elapsedRealtime() <= 0L) break
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFFFFEBEE),
        contentColor = Color(0xFFC62828)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "$seconds",
                style = MaterialTheme.typography.displayLarge,
                fontSize = 88.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFC62828),
                textAlign = TextAlign.Center
            )
            Text(
                text = if (seconds == 1L) "segundo para el SOS" else "segundos para el SOS",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFB3261E)
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(52.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFC62828),
                    contentColor = Color.White
                )
            ) {
                Text("CANCELAR SOS", fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun countdownRemainingSeconds(endAt: Long): Long =
    ((endAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L) + 999L) / 1_000L

@androidx.compose.runtime.Composable
private fun ActiveSafetyCancelBar(snapshot: SafetySnapshot, onCancel: () -> Unit) {
    if (!snapshot.phase.locksCriticalSettings()) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (snapshot.phase == SafetyPhase.COUNTDOWN) Color(0xFFFFEBEE) else Color(0xFFFFF8E1),
        contentColor = StatusTextColor,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (snapshot.phase == SafetyPhase.VALIDATING) {
                Text(
                    "VALIDACION DE SEGURIDAD ACTIVA",
                    color = Color(0xFFC62828),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Button(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFC62828),
                    contentColor = Color.White
                )
            ) {
                Text(
                    if (snapshot.phase == SafetyPhase.COUNTDOWN) "CANCELAR SOS" else "CANCELAR VALIDACION",
                    fontWeight = FontWeight.Bold
                )
            }
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

@androidx.compose.runtime.Composable
private fun EmergencySettingsScreen(
    modifier: Modifier,
    repository: ContactsRepository,
    contacts: List<EmergencyContact>,
    onContactsChanged: (List<EmergencyContact>) -> Unit,
    criticalSettingsLocked: Boolean,
    fallOrientationGate: Boolean,
    onFallOrientationGateChange: (Boolean) -> Unit,
    sosActionsEnabled: Boolean,
    onSosActionsEnabledChange: (Boolean) -> Unit,
    voiceKeywordsGate: Boolean,
    audioPermissionGranted: Boolean,
    onVoiceKeywordsGateChange: (Boolean) -> Unit,
    callPermissionRequired: Boolean,
    callPermissionGranted: Boolean,
    whatsappAutoSend: Boolean,
    onWhatsappAutoSendChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val calibrationScope = androidx.compose.runtime.rememberCoroutineScope()
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
    val globalGestureReady = remember(automationRefresh) {
        GlobalTouchAccessibilityService.isEnabled(context)
    }
    var addingContact by remember { mutableStateOf(false) }
    var editingContact by remember { mutableStateOf<EmergencyContact?>(null) }
    var smsAuto by remember { mutableStateOf(SafetyMonitorService.loadSmsAuto(context)) }
    val editorVisible = addingContact || editingContact != null
    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        smsAuto = granted
        SafetyMonitorService.saveSmsAuto(context, granted)
    }

    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (criticalSettingsLocked) {
            item {
                Spacer(Modifier.height(8.dp))
                CriticalSettingsLockedWarning()
            }
        }
        item {
            if (!criticalSettingsLocked) Spacer(Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (sosActionsEnabled) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("FUNCIONALIDAD SOS", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                !sosActionsEnabled -> "AVISOS SOS DESACTIVADOS"
                                callPermissionRequired && !callPermissionGranted -> "LLAMADAS SOS SIN PERMISO"
                                else -> "AVISOS SOS ACTIVADOS"
                            },
                            color = when {
                                callPermissionRequired && !callPermissionGranted -> MaterialTheme.colorScheme.error
                                sosActionsEnabled -> StrongGreen
                                else -> Color(0xFF6B7280)
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (sosActionsEnabled) {
                                "Las llamadas y WhatsApp configurados quedan habilitados para un SOS."
                            } else {
                                "No se realizaran llamadas ni WhatsApp a ningun contacto."
                            } + " Desactivalos para probar sensores sin avisar a contactos.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (callPermissionRequired && !callPermissionGranted) {
                            Text(
                                "Las llamadas no estan listas hasta conceder el permiso; los mensajes configurados no se bloquean.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Switch(
                        checked = sosActionsEnabled,
                        enabled = !criticalSettingsLocked,
                        onCheckedChange = onSosActionsEnabledChange
                    )
                }
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (exactAlarmReady) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("ALARMAS EXACTAS", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (exactAlarmReady) "AUTORIZADAS" else "NO AUTORIZADAS",
                        color = if (exactAlarmReady) StrongGreen else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Android puede retrasar la finalizacion del SOS y el paso al siguiente contacto si no permite alarmas exactas. " +
                            "Autorizalas para que el respaldo sea puntual.",
                        style = MaterialTheme.typography.bodySmall
                    )
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
        }
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (globalGestureReady) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("GESTO GLOBAL 2 DEDOS", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    val supported = GlobalTouchAccessibilityService.isSupported()
                    Text(
                        when {
                            !supported -> "NO DISPONIBLE (Android 12 o superior)"
                            globalGestureReady -> "ACTIVADO"
                            else -> "DESACTIVADO"
                        },
                        color = when {
                            !supported -> Color(0xFF6B7280)
                            globalGestureReady -> StrongGreen
                            else -> MaterialTheme.colorScheme.error
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Mantener dos dedos 5 s en cualquier pantalla inicia el SOS. Android exige activarlo a mano en Accesibilidad; " +
                            "no lee el contenido de otras apps.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (supported && !globalGestureReady) {
                        OutlinedButton(
                            onClick = { GlobalTouchAccessibilityService.openSettings(context) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("ABRIR ACCESIBILIDAD")
                        }
                    }
                }
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (fallOrientationGate) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("CAIDA / ORIENTACION", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            if (fallOrientationGate) "MODO MOTO ACTIVADO" else "GOLPE DIRECTO",
                            color = if (fallOrientationGate) StrongGreen else Color(0xFF6B7280),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (fallOrientationGate) {
                                "Un golpe fuerte inicia un candidato de 15 s: si el movil queda inclinado >= 55 grados durante 4 s (gravedad a baja tasa), salta el SOS. Un bache que no tumba la moto no avisa; la sacudida y los botones siguen siendo inmediatos."
                            } else {
                                "Todo golpe al nivel elegido inicia la cuenta atras directamente (como agitar el movil), sin confirmar inclinacion."
                            } + " Se aplica al iniciar el monitor.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = fallOrientationGate,
                        enabled = !criticalSettingsLocked,
                        onCheckedChange = onFallOrientationGateChange
                    )
                }
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (voiceKeywordsGate) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("VOZ AYUDA / SOCORRO", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                voiceKeywordsGate && !audioPermissionGranted -> "SIN PERMISO DE MICROFONO"
                                voiceKeywordsGate -> "ACTIVADA"
                                else -> "DESACTIVADA"
                            },
                            color = when {
                                voiceKeywordsGate && !audioPermissionGranted -> MaterialTheme.colorScheme.error
                                voiceKeywordsGate -> StrongGreen
                                else -> Color(0xFF6B7280)
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (voiceKeywordsGate) {
                                "Con el monitor activo (INICIAR) y el microfono autorizado, decir AYUDA o SOCORRO inicia la cuenta atras aunque la pantalla este apagada."
                            } else {
                                "No se escuchan palabras de emergencia; el microfono queda libre."
                            } + " Requiere permiso de microfono.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = voiceKeywordsGate,
                        enabled = !criticalSettingsLocked,
                        onCheckedChange = onVoiceKeywordsGateChange
                    )
                }
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (whatsappAutoSend) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("ENVIO AUTO WHATSAPP", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                if (whatsappAutoSend) "ACTIVADO" else "DESACTIVADO",
                                color = if (whatsappAutoSend) StrongGreen else Color(0xFF6B7280),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Switch(
                            checked = whatsappAutoSend,
                            enabled = !criticalSettingsLocked,
                            onCheckedChange = onWhatsappAutoSendChange
                        )
                    }
                    Text(
                        if (whatsappAutoSend) {
                            "Al completar el SOS, WhatsApp se abre y Enviar se pulsa automaticamente, sin confirmacion."
                        } else {
                            "WhatsApp se abre con el mensaje y el enlace listos; el envio es manual."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
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
                        val accessibilityReady = remember(whatsappRefresh) {
                            AutomationPermissions.isAccessibilityEnabled(context)
                        }
                        val overlayReady = remember(whatsappRefresh) {
                            AutomationPermissions.canDrawOverlays(context)
                        }
                        Text(
                            "Accesibilidad (pulsar Enviar): " + if (accessibilityReady) "ACTIVADA" else "DESACTIVADA",
                            color = if (accessibilityReady) StrongGreen else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Abrir WhatsApp en 2o plano (sobre otras apps): " + if (overlayReady) "PERMITIDO" else "DENEGADO",
                            color = if (overlayReady) StrongGreen else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (!accessibilityReady || !overlayReady) {
                            Text(
                                "Sin ambos permisos el envio no puede ser automatico; se ofrecera WhatsApp manual como respaldo.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        OutlinedButton(
                            onClick = { AutomationPermissions.openAccessibilitySettings(context) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("ABRIR ACCESIBILIDAD")
                        }
                        OutlinedButton(
                            onClick = { AutomationPermissions.openOverlaySettings(context) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("PERMITIR SOBRE OTRAS APPS")
                        }
                    }
                }
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (smsAuto) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("SMS SOS AUTOMATICO", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            if (smsAuto) "ACTIVADO" else "DESACTIVADO",
                            color = if (smsAuto) StrongGreen else Color(0xFF6B7280),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Envia por SMS el mensaje con el enlace de Maps a TODOS los contactos, sin " +
                                "confirmacion y con la pantalla apagada o bloqueada. Puede tener coste de operador.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = smsAuto,
                        enabled = !criticalSettingsLocked,
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
                }
            }
        }
        item {
            val calibration by CalibrationStateStore.state.collectAsStateWithLifecycle()
            var calibrationActivity by remember { mutableStateOf(CalibrationRepository.loadLastActivity(context)) }
            LaunchedEffect(calibration.recording) {
                CalibrationStateStore.update {
                    it.copy(files = CalibrationRepository.listFiles(context))
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("CALIBRACION DE SENSORES", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Graba datos reales etiquetados por actividad para ajustar la deteccion. Elige que estas " +
                            "haciendo, pulsa INICIAR REGISTRO y al terminar COMPARTE el archivo CSV.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Dispositivo: ${CalibrationRepository.deviceModel()}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                    CalibrationActivity.entries.chunked(3).forEach { rowItems ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            rowItems.forEach { act ->
                                val selected = calibrationActivity == act
                                Button(
                                    onClick = {
                                        calibrationActivity = act
                                        CalibrationRepository.saveLastActivity(context, act)
                                    },
                                    modifier = Modifier.weight(1f),
                                    enabled = !calibration.recording,
                                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                        containerColor = if (selected) StrongGreen else Color(0xFFE0E0E0),
                                        contentColor = if (selected) Color.White else StatusTextColor
                                    ),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        act.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 2,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                            repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    if (calibration.recording) {
                        Button(
                            onClick = { SensorCalibrationService.stop(context) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFC62828),
                                contentColor = Color.White
                            )
                        ) {
                            Text("DETENER REGISTRO", fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "Grabando ${calibration.activity.label}: ${calibration.samples} muestras, " +
                                "${calibration.elapsedMs / 1000}s\nArchivo: ${calibration.fileName}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Button(
                            onClick = { SensorCalibrationService.start(context, calibrationActivity) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("INICIAR REGISTRO", fontWeight = FontWeight.Bold)
                        }
                    }
                    Text("ARCHIVOS DE CALIBRACION", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                calibrationScope.launch {
                                    val zip = withContext(Dispatchers.IO) {
                                        CalibrationRepository.exportAll(context)
                                    }
                                    if (zip == null) {
                                        SafetyStateStore.log("Calibracion: no hay archivos para exportar")
                                    } else {
                                        val path = withContext(Dispatchers.IO) {
                                            CalibrationRepository.exportToDownloads(context, zip)
                                        }
                                        if (path != null) {
                                            SafetyStateStore.log("Calibracion exportada: $path")
                                            android.widget.Toast.makeText(
                                                context, "Exportado en $path", android.widget.Toast.LENGTH_LONG
                                            ).show()
                                        } else {
                                            CalibrationRepository.shareExport(context, zip, viaWhatsApp = false)
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("EXPORTAR", fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = {
                                calibrationScope.launch {
                                    val zip = withContext(Dispatchers.IO) {
                                        CalibrationRepository.exportAll(context)
                                    }
                                    if (zip == null) {
                                        SafetyStateStore.log("Calibracion: no hay archivos para enviar")
                                    } else {
                                        withContext(Dispatchers.IO) {
                                            CalibrationRepository.exportToDownloads(context, zip)
                                        }
                                        CalibrationRepository.shareExport(context, zip, viaWhatsApp = true)
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF25D366),
                                contentColor = Color.White
                            )
                        ) {
                            Text("WHATSAPP", fontWeight = FontWeight.Bold)
                        }
                    }
                    if (calibration.files.isEmpty()) {
                        Text("Sin archivos todavia.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        calibration.files.forEach { f ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(f.name, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                    Text("${f.sizeBytes / 1024} KB", style = MaterialTheme.typography.labelSmall)
                                }
                                TextButton(
                                    onClick = {
                                        CalibrationRepository.delete(context, f.name)
                                        CalibrationStateStore.update {
                                            it.copy(files = CalibrationRepository.listFiles(context))
                                        }
                                    }
                                ) {
                                    Text("BORRAR")
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Text("CONTACTOS SOS", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "La lista marca el orden de llamada: 1Âº, luego 2Âº si no responde en 1 minuto, luego 3Âº. " +
                    "El mensaje de WhatsApp se enviara a todos los contactos que lo tengan activado.",
                style = MaterialTheme.typography.bodyMedium
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
                    Text("AÃ‘ADIR CONTACTO")
                }
            }
            if (contacts.isEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Text(
                            "No hay contactos configurados. AÃ±ade al menos un contacto antes de usar el SOS.",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
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
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("UBICACION EN GOOGLE MAPS", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "El marcador {MAPA} se sustituye por un enlace Google Maps con la ultima posicion GPS precisa y reciente.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "El interruptor FUNCIONALIDAD SOS debe estar activado para usar las acciones configuradas. Desactivalo para probar los sensores reales sin avisar a contactos.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
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
    var saveAttempted by remember(contact) { mutableStateOf(false) }
    var saveError by remember(contact) { mutableStateOf<String?>(null) }
    val phoneValid = isValidEmergencyPhone(phone)
    val actionSelected = callEnabled || whatsappEnabled

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
            if (saveAttempted && !actionSelected) {
                Text("Activa llamada, WhatsApp o ambas acciones.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
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
                        if (name.isNotBlank() && phoneValid && actionSelected) {
                            val saved = onSave(
                                EmergencyContact(
                                    name = name,
                                    phone = phone,
                                    callEnabled = callEnabled,
                                    whatsappEnabled = whatsappEnabled,
                                    whatsappMessage = whatsappMessage
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
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
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
    }.joinToString(" + ")
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(contact.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(contact.phone, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (actions.isBlank()) "Sin acciones activas" else actions,
                color = if (actions.isBlank()) MaterialTheme.colorScheme.error else StrongGreen,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            if (contact.whatsappEnabled) {
                Text(
                    ensureMapLinkToken(contact.whatsappMessage).replace(MAP_LINK_TOKEN, "enlace de Google Maps"),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2
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
    snapshot: SafetySnapshot,
    installation: IntercomInstallationInfo,
    onRefreshIntercom: () -> Unit,
    onOpenIntercom: () -> Unit
) {
    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Text("INSTRUCCIONES DE USO", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Segu-Luky V-1.12s", style = MaterialTheme.typography.bodyMedium)
        }
        item {
            UsageInstruction(
                title = "1. Configura los contactos",
                text = "Pulsa el engranaje y activa FUNCIONALIDAD SOS para habilitar todos los avisos. Para cada telefono elige llamada, WhatsApp o ambas acciones. El texto de WhatsApp puede personalizarse y {MAPA} insertara el enlace Google Maps."
            )
        }
        item {
            UsageInstruction(
                title = "2. Inicia el monitor",
                text = "Concede ubicacion precisa para tener mapa y velocidad. Pulsa INICIAR: el monitor arranca tambien sin GPS, solo pierde el enlace de mapa. Si autorizas el microfono, NIVEL DE AUDIO mide el ambiente; con VOZ AYUDA / SOCORRO activada, gritar AYUDA o SOCORRO inicia la cuenta atras incluso con la pantalla apagada mientras el monitor este activo. No se graba ni guarda sonido; la voz la procesa el reconocedor del telefono."
            )
        }
        item {
            UsageInstruction(
                title = "3. Ajusta la sensibilidad",
                text = "El control usa aceleracion dinamica entre 0.5 y 7 g. Con MODO MOTO activo, un golpe aislado arma un candidato; dos picos fuertes en 1,2 s (sacudida) inician la cuenta atras al instante. GPS y VELOCIDAD son informativos y nunca bloquean un golpe. Prueba la sensibilidad con seguridad antes de usarlo en moto."
            )
        }
        item {
            UsageInstruction(
                title = "4. Caida y orientacion",
                text = "Con MODO MOTO, un golpe fuerte arma un candidato de 15 s y la pantalla se enciende para que puedas cancelar. El SOS salta solo si el movil queda inclinado 55 grados o mas durante 4 s seguidos. Se cancela si vuelve a vertical, si el GPS marca 30 km/h o mas (sigues rodando) o si expira la ventana. Desactivado, todo golpe inicia la cuenta atras directamente."
            )
        }
        item {
            UsageInstruction(
                title = "5. SOS manual y pruebas",
                text = "Mantener pulsado el boton SOS durante 5 segundos inicia la cuenta atras real. Dentro de Segu-Luky tambien puedes mantener dos dedos 5 segundos. Antes de probar, desactiva FUNCIONALIDAD SOS para que la cuenta atras no llame ni envie mensajes a los contactos."
            )
        }
        item {
            UsageInstruction(
                title = "Estados GPS",
                text = "DETENIDO: monitor apagado. PENDIENTE: ajuste guardado. BUSCANDO FIX: espera la primera posicion. FIX CADUCADO: supera 60 s. OK: posicion GPS precisa y reciente. El SOS manual (boton 5 s, dos dedos o voz) si funciona sin GPS; el enlace de ubicacion indicara que no hay posicion reciente."
            )
        }
        item {
            UsageInstruction(
                title = "Importante",
                text = "Al completar la cuenta atras y con FUNCIONALIDAD SOS activada, Segu-Luky inicia llamadas a los contactos con llamada habilitada y abre WhatsApp con su mensaje y el enlace Google Maps de la ultima posicion. Comprueba siempre contactos, GPS, bateria y permisos antes de circular."
            )
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("INTERCOM-LUKY", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (installation.installed) {
                            "Instalada: ${installation.versionName ?: "version desconocida"} (${installation.versionCode ?: 0})"
                        } else {
                            "No instalada"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRefreshIntercom) { Text("ACTUALIZAR") }
                        if (installation.installed) {
                            Button(onClick = onOpenIntercom) { Text("ABRIR INTERCOM") }
                        }
                    }
                }
            }
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
private fun UsageInstruction(title: String, text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@androidx.compose.runtime.Composable
private fun StatusCard(
    snapshot: SafetySnapshot,
    intercomInstalled: Boolean,
    sosActionsEnabled: Boolean,
    sensitivityG: Float,
    onCancel: () -> Unit
) {
    val visual = phaseVisual(snapshot.phase)
    val now = SystemClock.elapsedRealtime()
    val countdown = snapshot.countdownEndsAt?.let {
        ((it - now).coerceAtLeast(0L) + 999L) / 1_000L
    }
    val gpsLiveText = when {
        snapshot.phase == SafetyPhase.STOPPED -> "DETENIDO"
        snapshot.gpsAvailable -> snapshot.gpsFixAgeSeconds?.let { "OK (${it}s)" } ?: "OK"
        snapshot.gpsFixAgeSeconds != null -> "FIX CADUCADO (${snapshot.gpsFixAgeSeconds}s)"
        else -> "BUSCANDO FIX"
    }
    val gpsLiveState = when {
        snapshot.phase == SafetyPhase.STOPPED -> IndicatorState.OFF
        snapshot.gpsAvailable -> IndicatorState.GOOD
        snapshot.gpsFixAgeSeconds != null -> IndicatorState.PENDING
        else -> IndicatorState.GOOD
    }
    val gpsStatusNote = when {
        snapshot.phase == SafetyPhase.STOPPED -> "Inicia el monitor para solicitar una posicion GPS."
        snapshot.gpsAvailable -> "Posicion GPS precisa y reciente."
        snapshot.gpsFixAgeSeconds != null -> "El ultimo fix supera 60 s; esperando uno nuevo."
        else -> "Esperando la primera posicion GPS precisa al aire libre."
    }
    Card(colors = CardDefaults.cardColors(containerColor = visual.background, contentColor = StatusTextColor)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!sosActionsEnabled) {
                SosDisabledWarning()
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(18.dp).clip(CircleShape).background(visual.state.color))
                Column {
                    Text("ESTADO ${visual.title}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
            }
            if (snapshot.detail.isNotBlank()) {
                Text(snapshot.detail, style = MaterialTheme.typography.bodyLarge)
            }
            if (snapshot.dispatchInProgress) {
                Text(
                    "AVISOS SOS EN CURSO: llamadas y mensajes configurados en ejecucion.",
                    color = Color(0xFFC62828),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            if (countdown != null) {
                Text(
                    String.format(Locale.US, "%02d", countdown),
                    fontSize = COUNTDOWN_FONT_SIZE.sp,
                    lineHeight = COUNTDOWN_LINE_HEIGHT.sp,
                    fontWeight = FontWeight.Bold,
                    color = visual.state.color
                )
                Text("segundos para cancelar el protocolo", style = MaterialTheme.typography.labelLarge)
                Button(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFC62828),
                        contentColor = Color.White
                    )
                ) {
                    Text("CANCELAR SOS", fontWeight = FontWeight.Bold)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ServiceRow(
                    "SENSOR",
                    "${snapshot.movementG?.let { String.format(Locale.US, "%.2f g", it) } ?: "--"} / " +
                        String.format(Locale.US, "%.1f g", sensitivityG),
                    if (snapshot.sensorAvailable) IndicatorState.GOOD else IndicatorState.PENDING,
                    "Actual / ajuste Â· ${snapshot.sensorDetail}"
                )
                ServiceRow(
                    "ORIENTACION",
                    when {
                        snapshot.orientationAvailable -> snapshot.orientationTiltDegrees?.let {
                            String.format(Locale.US, "%.0f grados", it)
                        } ?: "OK"
                        snapshot.orientationDetail.startsWith("Desactivada") || snapshot.orientationDetail.startsWith("No necesaria") -> "DESACT."
                        else -> "PENDIENTE"
                    },
                    when {
                        snapshot.orientationAvailable -> IndicatorState.GOOD
                        snapshot.orientationDetail.startsWith("Desactivada") || snapshot.orientationDetail.startsWith("No necesaria") -> IndicatorState.OFF
                        else -> IndicatorState.PENDING
                    },
                    snapshot.orientationDetail
                )
                ServiceRow("GPS", gpsLiveText, gpsLiveState, "$gpsStatusNote Solo aporta mapa; nunca bloquea golpes.")
                ServiceRow(
                    "VELOCIDAD",
                    snapshot.speedKmh?.let { String.format(Locale.US, "%.1f km/h", it) } ?: "--",
                    if (snapshot.speedKmh != null) IndicatorState.GOOD else IndicatorState.PENDING,
                    "Dato informativo; nunca bloquea golpes."
                )
                ServiceRow("INTERCOM", if (intercomInstalled) "OK" else "--", if (intercomInstalled) IndicatorState.GOOD else IndicatorState.PENDING)
            }
            if (snapshot.phase == SafetyPhase.VALIDATING) {
                Text("Validacion de seguridad activa.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text("CANCELAR VALIDACION")
                }
            }
        }
    }
}

private data class PhaseVisual(
    val title: String,
    val background: Color,
    val state: IndicatorState
)

@androidx.compose.runtime.Composable
private fun SosDisabledWarning() {
    val blinkAlpha by rememberInfiniteTransition(label = "sosDisabledBlink").animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 600), RepeatMode.Reverse),
        label = "sosDisabledAlpha"
    )
    Text(
        "AVISOS SOS DESACTIVADOS",
        modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = blinkAlpha },
        textAlign = TextAlign.Center,
        color = SosWarningRed,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
}

private val SosWarningRed = Color(0xFFC62828)

@androidx.compose.runtime.Composable
private fun GpsOffWarning(onOpenGpsSettings: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFFFFEBEE),
        contentColor = StatusTextColor
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "GPS APAGADO: el monitor detecta golpes, pero el SOS no incluira mapa ni velocidad.",
                color = Color(0xFFC62828),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            OutlinedButton(
                onClick = onOpenGpsSettings,
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC62828))
            ) {
                Text("ABRIR AJUSTES GPS", fontWeight = FontWeight.Bold)
            }
        }
    }
}

private enum class IndicatorState(val color: Color) {
    GOOD(StrongGreen),
    PENDING(Color(0xFFF9A825)),
    ERROR(Color(0xFFC62828)),
    OFF(Color(0xFF6B7280))
}

private val StrongGreen = Color(0xFF00C853)

private fun phaseVisual(phase: SafetyPhase): PhaseVisual = when (phase) {
    SafetyPhase.STOPPED -> PhaseVisual("APAGADO", Color(0xFFE5E7EB), IndicatorState.OFF)
    SafetyPhase.MONITORING -> PhaseVisual("OK", Color(0xFFE8F5E9), IndicatorState.GOOD)
    SafetyPhase.VALIDATING -> PhaseVisual("PENDIENTE", Color(0xFFFFF8E1), IndicatorState.PENDING)
    SafetyPhase.FALL_CANDIDATE -> PhaseVisual("POSIBLE CAIDA", Color(0xFFFFF3E0), IndicatorState.PENDING)
    SafetyPhase.COUNTDOWN -> PhaseVisual("SOS ACTIVO", Color(0xFFFFEBEE), IndicatorState.ERROR)
}

@androidx.compose.runtime.Composable
private fun ServiceRow(label: String, value: String, state: IndicatorState, note: String = "") {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Color.White.copy(alpha = 0.82f),
        contentColor = StatusTextColor
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(state.color))
                Spacer(Modifier.size(8.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private const val COUNTDOWN_FONT_SIZE = 120
private const val COUNTDOWN_LINE_HEIGHT = 132
private val HarleyOrange = Color(0xFFF58220)
private val StatusTextColor = Color(0xFF1F2937)

private enum class PressEnd {
    RELEASED,
    CANCELLED,
    LONG_PRESS
}

private const val HOLD_DURATION_MS = 5_000L
private const val SOS_BUTTON_SIZE = 210
private const val SOS_MAIN_FONT_SIZE = 40
private const val SOS_SUB_FONT_SIZE = 20

@androidx.compose.runtime.Composable
private fun RoundActionButton(
    mainText: String,
    color: Color,
    buttonSize: androidx.compose.ui.unit.Dp,
    subText: String? = null,
    onTap: () -> Unit,
    onLongPress: () -> Unit
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
                        var result: PressEnd? = null

                        while (result == null) {
                            val elapsed = SystemClock.uptimeMillis() - pressStartAt
                            val remaining = HOLD_DURATION_MS - elapsed
                            if (remaining <= 0L) {
                                result = PressEnd.LONG_PRESS
                                break
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
    Box(Modifier.fillMaxWidth().height(44.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(8.dp)
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
                thumbColor = if (enabled) Color(0xFF263238) else Color(0xFF9E9E9E),
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
    movementLevel: Float,
    audioLevel: AudioLevelReading,
    enabled: Boolean,
    onSensitivityChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "SENSIBILIDAD A LOS GOLPES",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            "AJUSTE: ${String.format(Locale.US, "%.1f", sensitivityG)} g",
            color = thumbColorFor(sensitivityG),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
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
        Text(
            "MOVIMIENTO DEL MOVIL",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
        LinearProgressIndicator(
            progress = { movementLevel },
            modifier = Modifier.fillMaxWidth(),
            color = StrongGreen,
            trackColor = Color(0xFFB9F6CA)
        )
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
                    audioLevel.fromVoice -> StrongGreen
                    audioLevel.active -> HarleyOrange
                    else -> Color(0xFF6B7280)
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        }
        LinearProgressIndicator(
            progress = { audioLevel.progress },
            modifier = Modifier.fillMaxWidth(),
            color = if (audioLevel.fromVoice) StrongGreen else HarleyOrange,
            trackColor = if (audioLevel.fromVoice) Color(0xFFB9F6CA) else Color(0xFFFFD7B0)
        )
    }
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

private fun Modifier.multiTouchEmergency(onEmergency: () -> Unit): Modifier = pointerInput(Unit) {
    val detector = MultiTouchEmergencyDetector()
    awaitEachGesture {
        val firstDown = awaitFirstDown(requireUnconsumed = false)
        var pointerCount = 1
        detector.update(pointerCount, firstDown.uptimeMillis)
        while (true) {
            val now = SystemClock.uptimeMillis()
            val deadline = detector.nextDeadline()
            val waitMs = deadline?.let { (it - now).coerceAtLeast(1L) }
            val event = if (waitMs == null) {
                awaitPointerEvent(PointerEventPass.Initial)
            } else {
                withTimeoutOrNull(waitMs) { awaitPointerEvent(PointerEventPass.Initial) }
            }

            if (event == null) {
                if (detector.update(pointerCount, SystemClock.uptimeMillis())) onEmergency()
                continue
            }

            val newPointerCount = event.changes.count { it.pressed }
            if (pointerCount >= 2 || newPointerCount >= 2) {
                event.changes.forEach { it.consume() }
            }
            pointerCount = newPointerCount
            val timestamp = event.changes.maxOfOrNull { it.uptimeMillis } ?: SystemClock.uptimeMillis()
            if (detector.update(pointerCount, timestamp)) onEmergency()
            if (pointerCount == 0) break
        }
        detector.reset()
    }
}

private fun isValidEmergencyPhone(value: String): Boolean {
    val normalized = value.trim()
    if (!normalized.matches(Regex("^[+]?[0-9 ()-]+$"))) return false
    val digitCount = normalized.count(Char::isDigit)
    return digitCount in 7..15
}
