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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.unit.TextUnit
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
        var intercomNoticeFeedback by remember { mutableStateOf<String?>(null) }
        var screen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(AppScreen.HOME) }
        var sensitivityG by remember { mutableStateOf(SafetyMonitorService.loadSensitivity(context)) }
        var voiceKeywordsGate by remember { mutableStateOf(SafetyMonitorService.loadVoiceKeywordsGate(context)) }
        var fallOrientationGate by remember { mutableStateOf(SafetyMonitorService.loadFallOrientationGate(context)) }
        var whatsappAutoSend by remember { mutableStateOf(WhatsAppAutoSend.isEnabled(context)) }
        var showGpsOffWarning by remember { mutableStateOf(false) }
        var showAbout by remember { mutableStateOf(false) }
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
                    installation = IntercomInstallation.inspect(context)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        // Sondeo ligero del estado de InterCOM para que la luz pase de naranja a verde al empezar el grupo.
        LaunchedEffect(Unit) {
            while (true) {
                installation = IntercomInstallation.inspect(context)
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
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                SeguTopBar(
                    screen = screen,
                    onSettings = { screen = AppScreen.SETTINGS },
                    onSensors = { screen = AppScreen.SENSORS },
                    onAbout = { showAbout = true },
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
                        onSensitivityChange = { g ->
                            sensitivityG = g
                            if (snapshot.phase == SafetyPhase.STOPPED) {
                                SafetyMonitorService.saveSensitivity(context, g)
                            }
                        },
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
                if (sosActionsEnabled.not() && snapshot.phase != SafetyPhase.STOPPED) {
                    item {
                        SosDisabledWarning(onOpenSettings = { screen = AppScreen.SETTINGS })
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
                    noticeFeedback = intercomNoticeFeedback,
                    onRefreshIntercom = {
                        installation = IntercomInstallation.inspect(context)
                        intercomNoticeFeedback = null
                    },
                    onOpenIntercom = { IntercomInstallation.open(context) },
                    onTestIntercomNotice = {
                        val sent = IntercomInstallation.sendNotice(context)
                        intercomNoticeFeedback = when {
                            !installation.installed ->
                                "InterCOM no instalado: el aviso solo sono en este movil."
                            !installation.active ->
                                "InterCOM apagado: el aviso solo sono en este movil. " +
                                    "Enciende INTERCOM WiFi/Datos en InterCOM-Luky."
                            sent -> "Aviso enviado a los motoristas conectados."
                            else -> "Aviso lanzado; no habia otros motoristas conectados."
                        }
                        installation = IntercomInstallation.inspect(context)
                    }
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
    onBack: () -> Unit
) {
    when (screen) {
        AppScreen.HOME -> Surface(color = MaterialTheme.colorScheme.surface) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(60.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onSettings,
                        modifier = Modifier.semantics { contentDescription = "Abrir configuracion" }
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
                    IconButton(
                        onClick = onSensors,
                        modifier = Modifier.semantics { contentDescription = "Abrir sensores" }
                    ) {
                        CircleIconFrame(tint = MaterialTheme.colorScheme.primary) {
                            SensorGlyph(tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    IconButton(
                        onClick = onAbout,
                        modifier = Modifier.semantics { contentDescription = "Informacion de Segu-Luky" }
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
                    Spacer(Modifier.weight(1f))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Text(
                            "Segu-Luky",
                            modifier = Modifier.alignByBaseline(),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 26.sp,
                            lineHeight = 30.sp
                        )
                        Text(
                            appVersionLabel(),
                            modifier = Modifier.alignByBaseline(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Spacer(Modifier.width(6.dp))
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
                    .height(60.dp)
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
    HISTORY,
    SUGGESTIONS,
    CREDITS
}

private sealed class UpdateState {
    data object Checking : UpdateState()
    data class Done(val status: UpdateChecker.Status) : UpdateState()
}

/** Menu de informacion abierto desde el icono a la izquierda de la version. */
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
                    if (page == AboutPage.MENU) "Informacion, ayuda y creditos - $versionLabel" else versionLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                "No incluyen contactos, token de Telegram ni ubicacion."
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
    appendLine("Todo lo que configuras (contactos, telefonos, textos de WhatsApp, token del bot de Telegram, " +
        "sensibilidad e interruptores) se guarda solo dentro de este movil.")
    appendLine()
    appendLine("Ubicacion: se usa unicamente para el monitor y el SOS. La ultima posicion no se envia a ningun " +
        "servidor de Segu-Luky; viaja en el mensaje de SOS a tus contactos y solo si tiene menos de 5 minutos.")
    appendLine()
    appendLine("Microfono: con PALABRAS CLAVE activado, el reconocedor del telefono escucha AYUDA y SOCORRO. " +
        "No se graba ni se guarda audio; NIVEL DE AUDIO solo muestra un numero en pantalla.")
    appendLine()
    appendLine("Telegram: si guardas un token, los avisos se envian a la API de Telegram con el token y el chat id " +
        "que tu indiques. Sin bot ni chat id configurados no sale nada de la app.")
    appendLine()
    appendLine("Ninguna otra conexion: no hay analitica, ni publicidad, ni cuentas. Buscar actualizacion solo lee la " +
        "pagina publica del proyecto en GitHub.")
    appendLine()
    append("Borrar datos: al desinstalar la app se borran contactos, token y registro.")
}

private fun historyText(versionLabel: String): String = buildString {
    appendLine("Version instalada: $versionLabel")
    appendLine()
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
        "contactos con tu posicion.\nhttps://github.com/${UpdateChecker.REPOSITORY}"
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
            SectionHeader("AVISOS SOS")
        }
        item {
            val callsBlocked = callPermissionRequired && !callPermissionGranted
            SeguCard(
                title = "FUNCIONALIDAD SOS",
                status = when {
                    !sosActionsEnabled -> "DESACTIVADO"
                    callsBlocked -> "LLAMADAS SIN PERMISO"
                    else -> "ACTIVADO"
                },
                tone = when {
                    callsBlocked -> Tone.BAD
                    sosActionsEnabled -> Tone.OK
                    else -> Tone.OFF
                },
                trailing = {
                    Switch(
                        checked = sosActionsEnabled,
                        enabled = !criticalSettingsLocked,
                        onCheckedChange = onSosActionsEnabledChange,
                        colors = activeSwitchColors()
                    )
                }
            ) {
                Text(
                    if (sosActionsEnabled) {
                        "Las llamadas y los mensajes configurados quedan habilitados para un SOS."
                    } else {
                        "No se realizaran llamadas ni mensajes a ningun contacto."
                    } + " Desactivalo para probar los sensores sin avisar a nadie.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (callsBlocked) {
                    Text(
                        "Las llamadas no estan listas hasta conceder el permiso; los mensajes configurados no se bloquean.",
                        color = Tone.BAD.text,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        item {
            SectionHeader("PERMISOS DEL SISTEMA")
        }
        item {
            SeguCard(
                title = "ALARMAS EXACTAS",
                status = if (exactAlarmReady) "AUTORIZADAS" else "NO AUTORIZADAS",
                tone = if (exactAlarmReady) Tone.OK else Tone.BAD,
                description = "Android puede retrasar el final del SOS y el paso al siguiente contacto si no las permite. " +
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
            SectionHeader("SENSORES")
        }
        item {
            SeguCard(
                title = "CAIDA / ORIENTACION",
                status = if (fallOrientationGate) "MODO MOTO" else "GOLPE DIRECTO",
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
                        "Un golpe fuerte abre un candidato de 15 s: si el movil queda inclinado 55 grados o mas durante 4 s, " +
                            "salta el SOS. Un bache no tumba la moto y no avisa; la sacudida y los botones siguen siendo inmediatos."
                    } else {
                        "Todo golpe al nivel elegido inicia la cuenta atras directamente, como agitar el movil, " +
                            "sin confirmar inclinacion."
                    } + " Se aplica al iniciar el monitor.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                        "Con el monitor activo y el microfono autorizado, decir AYUDA o SOCORRO inicia la cuenta atras " +
                            "aunque la pantalla este apagada. No se graba ni se guarda sonido."
                    } else {
                        "No se escuchan palabras de emergencia; el microfono queda libre."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
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
                    "Al terminar el SOS, WhatsApp y los enlaces t.me se abren y se pulsa Enviar sin confirmacion."
                } else {
                    "WhatsApp y Telegram se abren con el mensaje listo; el envio es manual."
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
                    val accessibilityReady = remember(whatsappRefresh) {
                        AutomationPermissions.isAccessibilityEnabled(context)
                    }
                    val overlayReady = remember(whatsappRefresh) {
                        AutomationPermissions.canDrawOverlays(context)
                    }
                    PermissionRow(
                        label = "Accesibilidad (pulsar Enviar)",
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
                    if (!accessibilityReady || !overlayReady) {
                        Text(
                            "Sin ambos permisos no puede ser automatico; se ofrecera WhatsApp manual como respaldo.",
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
                    telegramInstalled -> "Se abrira Telegram y se pulsara Enviar; necesita ENVIO AUTO MENSAJES."
                    else -> "Ni bot ni app de Telegram: ahora mismo no se enviaria nada."
                }
            ) {
                Text(
                    "Bot conectado: ${botTargets.size} de ${telegramContacts.size} contactos con chat id. " +
                        "Enlace t.me: ${linkTargets.size} contacto(s) por accesibilidad.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = telegramToken,
                    onValueChange = { telegramToken = it.trim() },
                    label = { Text("Token del bot (@BotFather)") },
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
                                telegramFeedback = "Token no valido: debe ser 123456789:AAAA..."
                            } else {
                                telegramRepository.setTelegramBotToken(normalized)
                                telegramToken = normalized
                                telegramFeedback = "Token guardado"
                            }
                        }
                    ) { TightButtonLabel("GUARDAR") }
                    OutlinedButton(
                        enabled = !criticalSettingsLocked && !telegramTesting && botTargets.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        contentPadding = CompactButtonPadding,
                        onClick = {
                            val target = botTargets.firstOrNull() ?: return@OutlinedButton
                            val token = TelegramSos.normalizeBotToken(telegramToken)
                            if (token == null) {
                                telegramFeedback = "Guarda primero un token valido"
                                return@OutlinedButton
                            }
                            telegramTesting = true
                            telegramFeedback = "Enviando prueba..."
                            telegramScope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    TelegramSos.sendViaBot(
                                        token,
                                        target.telegramChatId,
                                        "Segu-Luky: mensaje de prueba para ${target.name}"
                                    )
                                }
                                telegramTesting = false
                                telegramFeedback = if (result.ok) {
                                    "Prueba enviada a ${target.name}"
                                } else {
                                    "Fallo: ${result.detail}"
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
                            telegramFeedback = "Token borrado"
                        }
                    ) { Text("BORRAR TOKEN") }
                }
                if (telegramFeedback.isNotEmpty()) {
                    Text(telegramFeedback, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
                Text(
                    "1) Crea el bot en @BotFather y copia el token. " +
                        "2) Cada contacto debe escribir /start al bot. " +
                        "3) Pega su chat id en el contacto (o el enlace t.me como respaldo). " +
                        "4) Pulsa ENVIAR PRUEBA para comprobar la entrega. " +
                        "El detalle esta en INSTRUCCIONES DE USO, pasos 7 y 8.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (telegramContacts.isNotEmpty() && botTargets.isEmpty()) {
                    Text(
                        "Ningun contacto de Telegram tiene chat id: ahora mismo solo funcionaria el enlace t.me.",
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
                description = "Envia por SMS el mensaje con el enlace de Maps a TODOS los contactos, sin confirmacion y " +
                    "con la pantalla apagada o bloqueada. Puede tener coste de operador."
            )
        }
        item {
            SectionHeader("CALIBRACION")
        }
        item {
            val calibration by CalibrationStateStore.state.collectAsStateWithLifecycle()
            var calibrationActivity by remember { mutableStateOf(CalibrationRepository.loadLastActivity(context)) }
            LaunchedEffect(calibration.recording) {
                CalibrationStateStore.update {
                    it.copy(files = CalibrationRepository.listFiles(context))
                }
            }
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
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
                            modifier = Modifier.weight(1f),
                            contentPadding = CompactButtonPadding
                        ) {
                            TightButtonLabel("EXPORTAR")
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
                            contentPadding = CompactButtonPadding,
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF25D366),
                                contentColor = Color.White
                            )
                        ) {
                            TightButtonLabel("WHATSAPP")
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
            SectionHeader("CONTACTOS")
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "CONTACTOS SOS",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                StatusPill(
                    if (contacts.isEmpty()) "NINGUNO" else "${contacts.size}",
                    if (contacts.isEmpty()) Tone.BAD else Tone.OK
                )
            }
            Text(
                "La lista marca el orden de llamada: 1o, luego 2o si no responde en 1 minuto, luego 3o. " +
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
                description = "El marcador {MAPA} se sustituye por un enlace Google Maps con la ultima posicion GPS " +
                    "precisa y reciente. FUNCIONALIDAD SOS debe estar activada: desactivala para probar los sensores " +
                    "reales sin avisar a nadie."
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
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
                detail = "Bot (si hay chat id y token) o enlace t.me como respaldo.",
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
                    placeholder = { Text("@nombre o https://t.me/nombre") },
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
                    label = { Text("Chat id del bot (opcional)") },
                    placeholder = { Text("123456789") },
                    isError = saveAttempted && telegramChatIdInvalid,
                    supportingText = {
                        Text(
                            if (telegramChatIdInvalid) "Chat id no valido (solo digitos)"
                            else "Vacio = sin envio automatico por bot para este contacto."
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(contact.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        contact.phone,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusPill(
                    if (actions.isBlank()) "SIN ACCIONES" else actions,
                    if (actions.isBlank()) Tone.BAD else Tone.OK
                )
            }
            if (contact.whatsappEnabled) {
                Text(
                    ensureMapLinkToken(contact.whatsappMessage).replace(MAP_LINK_TOKEN, "enlace de Google Maps"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    noticeFeedback: String?,
    onRefreshIntercom: () -> Unit,
    onOpenIntercom: () -> Unit,
    onTestIntercomNotice: () -> Unit
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
                text = "Pulsa el engranaje y activa FUNCIONALIDAD SOS para habilitar todos los avisos. Para cada telefono elige llamada, WhatsApp, Telegram o una combinacion. El texto de WhatsApp puede personalizarse y {MAPA} insertara el enlace Google Maps; ese mismo texto se usa en Telegram."
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
                text = "Mantener pulsado el boton SOS durante 5 segundos inicia la cuenta atras real. Antes de probar, desactiva FUNCIONALIDAD SOS para que la cuenta atras no llame ni envie mensajes a los contactos."
            )
        }
        item {
            UsageInstruction(
                title = "6. Avisos por WhatsApp y Telegram",
                text = "SMS SOS sale aunque la pantalla este bloqueada. WhatsApp y el enlace t.me necesitan la Accesibilidad activada para pulsar Enviar; si el movil tiene PIN, la pantalla se enciende y pide desbloqueo. El bot de Telegram no necesita ni pantalla ni accesibilidad: guarda el token en la tarjeta TELEGRAM SOS, pon el chat id en cada contacto y el aviso sale solo con el movil bloqueado. Los pasos 7 y 8 explican como prepararlo."
            )
        }
        item {
            UsageInstruction(
                title = "7. Telegram: crear el bot y guardar el token",
                text = "1) En Telegram abre @BotFather y envia /newbot. " +
                    "2) Elige un nombre visible y un usuario que termine en bot. " +
                    "3) BotFather devuelve un token con forma 123456789:AAAA... Copialo entero. " +
                    "4) En Segu-Luky pulsa el engranaje, baja hasta la tarjeta TELEGRAM SOS y pega el token en Token del bot. " +
                    "5) Pulsa GUARDAR TOKEN: la tarjeta confirmara Token guardado. " +
                    "6) En cuanto un contacto tenga chat id, pulsa ENVIAR PRUEBA para comprobar que el mensaje llega. " +
                    "Si el token es incorrecto la tarjeta mostrara el motivo devuelto por Telegram."
            )
        }
        item {
            UsageInstruction(
                title = "8. Telegram: chat id de cada contacto",
                text = "Cada contacto debe abrir el bot y enviarle /start una vez; sin eso Telegram no deja escribirle. " +
                    "Para obtener el numero abre en el navegador https://api.telegram.org/bot<TOKEN>/getUpdates y busca chat:{id:...}. " +
                    "Pega ese numero en Chat id del bot del contacto (en grupos empieza por -). " +
                    "Si prefieres no usar bot, deja el chat id vacio y escribe solo el usuario (@nombre): se usara el enlace t.me, que necesita Accesibilidad y el interruptor ENVIO AUTO MENSAJES. " +
                    "El token se guarda en texto plano dentro de este movil: si prestas el telefono, pulsa BORRAR TOKEN y desactiva ENVIAR TELEGRAM en los contactos."
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
                text = "Al completar la cuenta atras y con FUNCIONALIDAD SOS activada, Segu-Luky inicia llamadas a los contactos con llamada habilitada y abre WhatsApp o Telegram con su mensaje y el enlace Google Maps de la ultima posicion. Comprueba siempre contactos, GPS, bateria y permisos antes de circular."
            )
        }
        item {
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
                            !installation.installed -> "NO INSTALADA (gris): sin aviso de voz"
                            installation.active -> buildString {
                                append("ACTIVA (verde): InterCOM encendido")
                                if (installation.group.isNotBlank()) append(" - grupo ${installation.group}")
                                if (installation.peers > 0) append(" - ${installation.peers} motoristas")
                            }
                            else -> "INSTALADA, NO ACTIVA (naranja): InterCOM apagado"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (installation.installed) {
                        Text(
                            "Version ${installation.versionName ?: "desconocida"} (${installation.versionCode ?: 0})",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        "Al terminar la cuenta atras del SOS, Segu-Luky pide a InterCOM-Luky que avise por " +
                            "voz: \"${IntercomInstallation.SOS_NOTICE_TEXT}\". El aviso suena en el casco de " +
                            "todos los motoristas del grupo (datos) o del INTERCOM WiFi, y siempre en este movil. " +
                            "En verde llega a los demas; en naranja o gris solo suena aqui.",
                        style = MaterialTheme.typography.bodySmall
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
                            OutlinedButton(
                                onClick = onTestIntercomNotice,
                                modifier = Modifier.weight(1f),
                                contentPadding = CompactButtonPadding
                            ) { TightButtonLabel("PROBAR AVISO") }
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
private fun SectionHeader(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall
    )
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
                if (trailing == null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (title != null) {
                            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        }
                        if (status != null) StatusPill(status, tone)
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (title != null) Text(title, style = MaterialTheme.typography.titleSmall)
                            if (status != null) StatusPill(status, tone)
                        }
                        trailing()
                    }
                }
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
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(tone.text))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(status, style = MaterialTheme.typography.bodySmall, color = tone.text, fontWeight = FontWeight.Bold)
        }
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(actionLabel, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * Home header: the only place that shows the state, the countdown and the
 * start/stop control. Cancelling lives in the bottom bar.
 */
@androidx.compose.runtime.Composable
private fun PhaseHeroCard(
    snapshot: SafetySnapshot,
    audioLevel: AudioLevelReading,
    sensitivityG: Float,
    onSensitivityChange: (Float) -> Unit,
    intercomState: IntercomState,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val visual = phaseVisual(snapshot.phase)
    val tone = toneOf(visual.state)
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
        color = visual.background,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, tone.line)
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
                    color = Tone.BAD.text,
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
                        color = Tone.BAD.text
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "SEGUNDOS",
                            style = MaterialTheme.typography.labelSmall,
                            color = Tone.BAD.text
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
                val starting = phase == SafetyPhase.STOPPED
                val stateInk = if (starting) StateOffInk else StateOnInk
                Button(
                    onClick = if (starting) onStart else onStop,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(2.dp, stateInk),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = stateInk
                    )
                ) {
                    Text(
                        if (starting) "INICIAR MONITOR" else "DETENER MONITOR",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            SensorStatusLine(
                snapshot = snapshot,
                intercomState = intercomState
            )
            AudioLevelLine(audioLevel)
            MovementLevelLine(snapshot.movementLevel)
            SensitivityControl(
                sensitivityG = sensitivityG,
                enabled = phase == SafetyPhase.STOPPED,
                onSensitivityChange = onSensitivityChange
            )
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

/** Linea de nivel de audio; va justo debajo de las luces de los sensores. */
@androidx.compose.runtime.Composable
private fun AudioLevelLine(audioLevel: AudioLevelReading) {
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
                color = if (audioLevel.active || audioLevel.fromVoice) StrongGreen else Color(0xFF6B7280),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        }
        LevelBar(audioLevel.progress)
    }
}

/** Linea de movimiento del movil; va justo debajo de la linea de audio. */
@androidx.compose.runtime.Composable
private fun MovementLevelLine(movementLevel: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "MOVIMIENTO DEL MOVIL",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
        LevelBar(movementLevel)
    }
}

/** Barra de nivel; mismo grosor y redondeo que la barra del ajuste de golpes. */
@androidx.compose.runtime.Composable
private fun LevelBar(progress: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(SENSITIVITY_TRACK_HEIGHT)
            .clip(RoundedCornerShape(SENSITIVITY_TRACK_HEIGHT / 2))
            .background(Color(0xFFB9F6CA))
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
    val fill by animateFloatAsState(
        targetValue = holdProgress,
        animationSpec = tween(durationMillis = 90, easing = LinearEasing)
    )
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(HOLD_COLUMN_GAP)
        ) {
            HoldProgressColumn(fill)
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size((SOS_BUTTON_SIZE + 24).dp)
                        .clip(CircleShape)
                        .background(Tone.BAD.soft)
                )
                RoundActionButton(
                    mainText = "S O S",
                    subText = "5 s",
                    color = Tone.BAD.text,
                    buttonSize = SOS_BUTTON_SIZE.dp,
                    onTap = { /* A short tap is intentionally harmless. */ },
                    onLongPress = onSos,
                    onHoldProgress = { holdProgress = it }
                )
            }
            HoldProgressColumn(fill)
        }
        Text(
            "MANTEN PULSADO 5 SEGUNDOS",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Columna lateral del SOS: se rellena de abajo arriba con los 5 segundos de pulsacion. */
@androidx.compose.runtime.Composable
private fun HoldProgressColumn(fill: Float) {
    Box(
        modifier = Modifier
            .width(HOLD_COLUMN_WIDTH)
            .height(HOLD_COLUMN_HEIGHT)
            .clip(RoundedCornerShape(HOLD_COLUMN_WIDTH / 2))
            .background(HoldTrackGreen),
        contentAlignment = Alignment.BottomCenter
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(fill.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(HOLD_COLUMN_WIDTH / 2))
                .background(StrongGreen)
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
        ServiceRow(
            "SENSOR",
            "${snapshot.movementG?.let { String.format(Locale.US, "%.2f g", it) } ?: "--"} / " +
                String.format(Locale.US, "%.1f g", sensitivityG),
            if (snapshot.sensorAvailable) IndicatorState.GOOD else IndicatorState.PENDING,
            "Lectura actual / ajuste. ${snapshot.sensorDetail}"
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
                    "InterCOM instalado pero apagado: sin encenderlo el aviso solo suena en este movil."
                IntercomState.MISSING ->
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
private fun SosDisabledWarning(onOpenSettings: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Tone.BAD.soft,
        contentColor = Tone.BAD.text,
        border = BorderStroke(1.dp, Tone.BAD.line)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "AVISOS SOS DESACTIVADOS",
                modifier = Modifier.weight(1f),
                color = Tone.BAD.text,
                style = MaterialTheme.typography.titleSmall
            )
            TextButton(onClick = onOpenSettings) {
                Text("REVISAR", style = MaterialTheme.typography.labelSmall)
            }
        }
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFFF8F5F1),
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(state.color))
                Spacer(Modifier.size(8.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
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
private val SENSITIVITY_TRACK_HEIGHT = 8.dp
private val HOLD_COLUMN_WIDTH = 12.dp
private val HOLD_COLUMN_GAP = 10.dp
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
    enabled: Boolean,
    onSensitivityChange: (Float) -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "SENSIBILIDAD A LOS GOLPES =",
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
