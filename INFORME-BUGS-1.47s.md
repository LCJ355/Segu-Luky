# Análisis de bugs — Segu-Luky 1.47s

Proyecto: `G:\-IA-\GitHub-repos-\Segu-Luky`
Commit analizado: `173d7a8` (`origin/main`, `versionCode = 39`, `versionName = "1.47s"`)
APK de referencia: `APK´s/Segu-Luky-1.47s.apk`, SHA-256 `0726b6ed…`

> **Estado:** §1, §2, §3 y §4 corregidos en **1.48s** (`versionCode 40`); §7 en **1.49s**
> (`versionCode 41`); §5 y §6 en **1.50s** (`versionCode 42`, `APK´s/Segu-Luky-1.50s.apk`). Cada
> apartado corregido indica qué se cambió. El resto sigue pendiente en el orden de la última
> sección.

## Corrección del análisis anterior

El informe previo partía de que el repositorio no contenía la fuente de la 1.47s. **Era falso**:
la copia local estaba **39 commits por detrás** de `origin` (HEAD en `7cf1c07`, 1.12s /
versionCode 4). Tras `git fetch` y sincronizar a `origin/main`, el árbol contiene la fuente
completa (`TelegramSos.kt`, `UpdateChecker.kt`, `IntercomBridge.kt`, `WhatsAppUnlockActivity.kt`…).

Este documento sustituye al anterior y está verificado **leyendo el código Kotlin**, no
decompilando el APK.

## Verificación del árbol (base de mantenimiento)

| Comprobación | Resultado |
|---|---|
| `gradlew testDebugUnitTest` | **84 tests, 0 fallos** (86 tras las correcciones de 1.48s) |
| `gradlew lintDebug` | 6 warnings, 5 info, **0 errores** |
| `gradlew assembleRelease` | OK: `versionCode 39` / `1.47s` |
| Firma de la release local | `CN=Segu-Luky`, SHA-256 `e0e5a8fd…` = **la misma que el APK publicado** → las actualizaciones siguen siendo instalables encima |
| Reproducibilidad byte a byte | No: el APK local (`EFE96681…`) difiere del publicado (`0726b6ed…`); normal sin builds reproducibles, pero impide verificar el binario publicado contra el árbol |

---

## 1. [CRÍTICO · corregido] `ANSWER_PHONE_CALLS` se declaraba y nunca se pedía: la escalada marcaba durante una llamada activa

**Verificado.** El manifiesto declara el permiso, y en todo el código aparece una sola vez:

```kotlin
// SafetyMonitorService.kt:1502
private fun endCurrentCall(): Boolean {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) !=
        PackageManager.PERMISSION_GRANTED) return false
```

No hay ningún launcher que lo solicite (`MainActivity` pide ubicación, notificaciones, llamada,
SMS y micrófono). Es un permiso peligroso: declararlo no lo concede, así que
`endCurrentCall()` **siempre** devuelve `false`.

**Consecuencia en `onEscalationTimeout`:** al agotarse el presupuesto de 5 min de una llamada
larga, `endCurrentCall()` falla y el flujo cae en `advanceEscalation()`, que **llama al
siguiente contacto mientras la llamada anterior sigue conectada**. En el mejor caso el marcador
rechaza la llamada; en el peor deja en espera a quien sí había atendido.

**Fix aplicado:** se eliminó `endCurrentCall()` y el permiso `ANSWER_PHONE_CALLS` del
manifiesto. La app **nunca cuelga ni marca sobre una llamada activa**: si a los 5 min sigue
conectada, la ronda termina como atendida (`EscalationPolicy.answeredByLongCall`). Así el
comportamiento no depende de un permiso que nunca se concedía.

---

## 2. [CRÍTICO · corregido] Un buzón de voz contaba como "contacto atendido" y cortaba la ronda

**Verificado.** `EscalationPolicy.kt` (igual que en 1.12s):

```kotlin
const val ANSWERED_MIN_CALL_MS = 3_000L
fun isAnswered(offhookAtElapsed: Long?, idleAtElapsed: Long): Boolean =
    offhookAtElapsed != null && idleAtElapsed >= offhookAtElapsed + ANSWERED_MIN_CALL_MS
```

`offhookAt` se fija en el `CALL_STATE_OFFHOOK` de **marcación**, que también cubre el timbrado.
Una llamada que suena 20-30 s y cae al buzón (el caso normal de "no contesta") supera el umbral
→ `onCallEnded()` llama a `finishEscalation(answered = true)` → **los contactos restantes no se
llaman** y se registra "un contacto respondio la llamada".

**Fix aplicado:** `isAnswered()` ya no decide nada: es solo evidencia para el registro. Cualquier
llamada que termine (contestada, no contestada o buzón) **pasa al siguiente contacto**, de modo
que toda la lista recibe el aviso; solo una llamada conectada que supere los 5 min cierra la
ronda (`answeredByLongCall`). Cubierto por tres tests nuevos en `EscalationPolicyTest`
(84 tests en total, 0 fallos).

---

## 3. [ALTO · corregido] El micrófono en segundo plano sigue sin tipo de servicio en primer plano

**Verificado.** `AndroidManifest.xml:63`: `android:foregroundServiceType="location|specialUse"`,
y no aparece `FOREGROUND_SERVICE_MICROPHONE` (sí `RECORD_AUDIO`).

`VoiceKeywordMonitor` captura con `AudioRecord` desde ese servicio. Desde Android 11 el
micrófono es un recurso *while-in-use*: un FGS solo puede usarlo en segundo plano si declara el
tipo `microphone` (y desde Android 14, además, el permiso). El README promete la escucha "even
with the screen off", que en ese estado no podía cumplirse.

**Corregido en 1.48s.** El manifiesto declara `FOREGROUND_SERVICE_MICROPHONE` y el servicio pasa
a `location|microphone|specialUse`; el servicio pide el tipo `microphone` solo cuando la voz está
activa y hay permiso (`withMicrophoneType`), lo re-aplica al cambiar el interruptor de voz
(`refreshForegroundType`) y, si Android lo rechaza, arranca sin ese tipo y lo anota en el
registro en vez de quedarse sin monitor.

---

## 4. [ALTO] Las instrucciones del APK anuncian el gesto de dos dedos que se eliminó

**Verificado.** El gesto se eliminó a propósito (`a12bf11` dentro de la app, `2b99cd6` global) y
el README lo explica: "There is no two-finger gesture, in-app or global, in this build", con el
motivo (en algunos OPPO/ColorOS el filtro de accesibilidad dejaba la pantalla sin táctil).

Pero el documento que se entrega **junto al APK**, `APK´s/INSTRUCCIONES-SEGU-LUKY.txt`, sigue
diciendo:
- línea 54: "Accesibilidad: solo para el envio automatico de WhatsApp y para el **gesto global
  de dos dedos** (Android 12+)";
- línea 77: "**Gesto de dos dedos 5 s** (si esta activado), dentro de la app o en cualquier
  pantalla."

**Fix:** borrar ambas menciones. Es el desajuste más visible para el usuario final.

---

## 5. [ALTO · corregido] El aviso a InterCOM (dos IPC a otra app) se ejecuta en la ruta crítica del SOS

**Verificado.** `SafetyMonitorService.completeProtocol()` llama a `dispatchIntercomNotice()`
(línea 1153), que en el mismo hilo hace:

```kotlin
val info = IntercomInstallation.inspect(this)        // → ContentResolver.query()
...
val broadcast = IntercomInstallation.sendNotice(this) // → ContentResolver.call()
```

`query` y `call` son **binder síncronos a otra aplicación**. Si InterCOM-Luky está congelado
(habitual con MIUI/ColorOS en segundo plano) o tarda, el protocolo SOS se retrasa justo cuando
toca llamar/avisar. Los `try/catch` evitan el crash, no la espera.

Además la UI consulta lo mismo **en el hilo principal y en bucle**: `MainActivity` llama a
`IntercomInstallation.inspect(context)` dentro de `remember { }`, en `ON_RESUME` y en un
`LaunchedEffect` con `while (true) { inspect(); delay(5_000) }`. Cada iteración es un binder
bloqueante en main.

**Fix:** mover a E/S con timeout (la caché negativa de 30 s ya existe) y, en el SOS, no esperar
a InterCOM: lanzar el aviso y seguir con SMS/WhatsApp/llamadas.

**Corregido en 1.50s.** El aviso sale en un hilo propio
(`segluky-intercom-notice`): `completeProtocol()` sigue con SMS, WhatsApp y llamadas sin esperar y
el resultado se anota cuando llega (`IntercomNotice.detail`, con 4 tests). El sondeo de la
pantalla y «PROBAR AVISO» también pasan a `Dispatchers.IO`, así que la UI ya no se congela si
InterCOM no contesta. La lectura inicial de la pantalla (`remember { inspect }`) se deja síncrona
a propósito: es una sola vez, fuera del SOS, y hacerla diferida mostraría un estado falso
(naranja→verde) cada vez que se abre Configuración.

---

## 6. [ALTO · corregido] El envío por bot de Telegram es "dispara y olvida"

**Verificado.** `dispatchTelegramBot()` lanza un hilo daemon con `PARTIAL_WAKE_LOCK`, recorre
los contactos con `TelegramSos.sendViaBot()` (15 s de conexión + 15 s de lectura **por
contacto**, secuencial) y al final solo escribe una línea de log con el resumen y los errores.
**No hay cola, reintento ni persistencia**: sin datos —el escenario típico de una caída— el
aviso se pierde. Con 3 contactos sin red el hilo puede tardar ~90 s y el wake lock debe cubrirlo.

Matiz importante: los contactos que solo tienen **usuario** (`t.me`) sí pasan por la cola
duradera de `WhatsAppAutoSend` con `expectedPackage = TelegramSos.PACKAGE`. El agujero es solo
la vía bot.

**Fix:** integrar el bot en el pipeline (`DispatchStep.TELEGRAM` con cola persistida por
contacto, TTL y reintentos), reutilizando el patrón de `WhatsAppAutoSend`.

**Corregido en 1.50s.** `TelegramBotOutbox` guarda un trabajo por
`(evento, chatId)` en SharedPreferences y `TelegramBotRetry` define la espera creciente (20 s,
60 s, 120 s, 300 s; máximo 5 intentos; la suma cabe en los 10 min del despacho). Los reintentos
se disparan en cada vuelta del vigilante del despacho y al reanudar un SOS interrumpido; los
fallos definitivos (token, chat, bot bloqueado) se marcan agotados sin esperar. Al cerrar la
ronda el registro resume lo entregado y lo que quedó pendiente, y la cola se limpia para que un
mensaje viejo no salga en el SOS siguiente. 5 tests nuevos.

---

## 7. [MEDIO · corregido] «Buscar actualización» no ve una versión publicada solo como etiqueta

**Verificado.** `UpdateChecker.kt`:

```kotlin
val releases = get(RELEASES_API)       // /releases
return if (newest != null) {
    compare(currentVersion, newest)     // ← retorno temprano
} else {
    publishedFromTags(currentVersion)   // /tags solo si no hay releases
}
```

Si existe una release antigua y la versión nueva se publica solo como **tag**, la app responde
"estás al día". En este proyecto las APK viven en `APK´s/` y las versiones se marcan con tags,
así que el caso es realista.

**Fix:** comparar contra el máximo entre el mejor release y el mejor tag.

**Corregido en 1.49s.** `UpdateChecker.newestOf(releases, tags)` une las dos listas (la release
manda para su enlace de descarga; una etiqueta sin release apunta a `APK´s/`) y compara la más
alta. Se comprobó en real: la 1.48s se publicó solo como tag y la app respondía "estás al día"
hasta que se creó su release.

---

## 8. [MEDIO] `WhatsAppUnlockActivity` abre WhatsApp aunque el usuario cancele el desbloqueo

**Verificado.** `onCreate` pide `requestDismissKeyguard` y en **los tres** callbacks llama a
`launchTarget(target)`; `onDismissCancelled` solo añade un log si el bloqueo es seguro. El
mensaje de emergencia (con el enlace de Maps) queda abierto sobre la pantalla de bloqueo tras
cancelar.

**Fix:** en `onDismissCancelled`, no lanzar el destino; dejar la notificación manual.

---

## 9. [NOTA] Lo que está bien y conviene no tocar

- Idempotencia del protocolo (`eventId` + `wasEventCompleted`), alarmas tardías ignoradas,
  watchdog de despacho y reanudación al arrancar.
- El respaldo `t.me` reutiliza la cola durable; WhatsApp exige coincidencia exacta de paquete,
  destinatario y texto normalizado antes de pulsar Enviar.
- Telegram solo HTTPS, token/chat validados, hilo propio con wake lock.
- Sin `REQUEST_INSTALL_PACKAGES`: la app no puede autoinstalarse.
- Firma correcta y `allowBackup="false"`; componentes internos `exported="false"`.

## Prioridad sugerida

1. ~~§1 y §2~~ (corregidos: no se cuelga ni se marca sobre una llamada activa; la ronda ya no se
   corta por un timbrado).
2. ~~§3 (voz con pantalla apagada) y §4 (instrucciones del APK)~~ (corregidos en 1.48s).
3. ~~§5 y §6~~ (corregidos en 1.50s).
4. ~~§7~~ (corregido en 1.49s) y §8.
