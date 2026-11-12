# Análisis de bugs — Segu-Luky 1.47s

Proyecto: `G:\-IA-\GitHub-repos-\Segu-Luky`
Commit analizado: `173d7a8` (`origin/main`, `versionCode = 39`, `versionName = "1.47s"`)
APK de referencia: `APK´s/Segu-Luky-1.47s.apk`, SHA-256 `0726b6ed…`

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
| `gradlew testDebugUnitTest` | **81 tests, 0 fallos** |
| `gradlew lintDebug` | 6 warnings, 4 info, **0 errores** |
| `gradlew assembleRelease` | OK: `versionCode 39` / `1.47s` |
| Firma de la release local | `CN=Segu-Luky`, SHA-256 `e0e5a8fd…` = **la misma que el APK publicado** → las actualizaciones siguen siendo instalables encima |
| Reproducibilidad byte a byte | No: el APK local (`EFE96681…`) difiere del publicado (`0726b6ed…`); normal sin builds reproducibles, pero impide verificar el binario publicado contra el árbol |

---

## 1. [CRÍTICO] `ANSWER_PHONE_CALLS` se declara y nunca se pide: la escalada marca durante una llamada activa

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

**Fix:** pedir el permiso junto a `CALL_PHONE` o, si no se quiere pedir, no avanzar cuando
`endCurrentCall()` falle: mantener la espera o cerrar la ronda.

---

## 2. [CRÍTICO] Un buzón de voz cuenta como "contacto atendido" y corta la ronda

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

Android no ofrece señal fiable de "contestada" en llamadas salientes. Opciones, por orden de
seguridad: (a) no cerrar la ronda por inferencia y llamar a todos; (b) subir el umbral a 25-30 s
y rotularlo como "posible respuesta"; (c) asumir la ambigüedad y documentarla.

---

## 3. [ALTO] El micrófono en segundo plano sigue sin tipo de servicio en primer plano

**Verificado.** `AndroidManifest.xml:63`: `android:foregroundServiceType="location|specialUse"`,
y no aparece `FOREGROUND_SERVICE_MICROPHONE` (sí `RECORD_AUDIO`).

`VoiceKeywordMonitor` captura con `AudioRecord` desde ese servicio. Desde Android 11 el
micrófono es un recurso *while-in-use*: un FGS solo puede usarlo en segundo plano si declara el
tipo `microphone` (y desde Android 14, además, el permiso). El README sigue prometiendo la
escucha "even with the screen off".

**Fix:** añadir `FOREGROUND_SERVICE_MICROPHONE` y pasar el tipo combinado en `startForeground`
(`location|microphone` con GPS; `microphone` sin GPS). Ojo: `specialUse` no se puede combinar.

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

## 5. [ALTO] El aviso a InterCOM (dos IPC a otra app) se ejecuta en la ruta crítica del SOS

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

---

## 6. [ALTO] El envío por bot de Telegram es "dispara y olvida"

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

---

## 7. [MEDIO] «Buscar actualización» no ve una versión publicada solo como etiqueta

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

1. §1 y §2 (pueden dejar sin aviso a los contactos o marcar en mitad de una llamada).
2. §3 (voz con pantalla apagada) y §4 (instrucciones del APK).
3. §5 (no bloquear el SOS por InterCOM) y §6 (Telegram bot durable).
4. §7 y §8.
