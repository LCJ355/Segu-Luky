# Segu-Luky V-1.11s — Informe de análisis, bugs y mejoras

> **Nota de evolución (V-1.12s, 05/11/2026).** Este informe describe la revisión de
> la V-1.11s. La V-1.12s aplica las fases de fiabilidad, batería, limpieza, tests y
> release descritas en `CHANGELOG.md`: despacho SOS persistente con watchdog,
> escalada de llamadas corregida, alarmas exactas, candidato que enciende pantalla,
> voz reactivable, gesto global declarado, 61 tests, lint y toolchain actualizada
> (Gradle 8.9 / AGP 8.6.1). Las secciones siguientes se conservan como histórico.

Fecha del análisis: revisión estática completa de `app/src` (main + test) y verificación
con `gradlew testDebugUnitTest assembleDebug` (50 tests, 0 fallos, APK generado).

---

## 1. Resumen ejecutivo

La app hace lo correcto en su núcleo (cadena `AccidentDetector` → servicio en primer plano →
cuenta atrás → protocolo SOS), pero el **monitor de seguridad de fondo gastaba batería a un
ritmo que no se corresponde con su función**, porque el camino de datos de los sensores no
distinguía entre "muestra nueva" y "estado nuevo".

Se han corregido 7 defectos con impacto directo en consumo y fiabilidad (más un defecto de
idempotencia en el protocolo SOS), y quedan 4 mejoras recomendadas que no se han aplicado por
cambiar comportamiento visible o por depender de decisión de producto (ver §5).

Cuantificación del problema principal: con el monitor activo, `onSensorChanged` se ejecuta a
~50 Hz (movimiento), ~50 Hz (gravedad, sin `fifoMaxEventCount` en muchos equipos) y ~50 Hz
(giroscopio) → **hasta ~150 escrituras a disco por segundo** con `SharedPreferences.commit()`
sincrónico en el hilo principal, porque el GPS cambiaba el objeto comparado en cada fix y
anulaba la caché de escritura. Tras el arreglo, las escrituras quedan ligadas a cambios de
estado reales (fase, evento, temporizadores) o a desplazamientos > ~1 m.

---

## 2. Consumo de batería — hallazgos y estado

### 2.1 [CRÍTICO · corregido] Una escritura a disco por cada muestra de sensor

**Evidencia (antes):** `SafetyMonitorService.onSensorChanged` llamaba a `syncDetectorRuntime()`
en las ramas `TYPE_LINEAR_ACCELERATION`, `TYPE_ACCELEROMETER` y `TYPE_GYROSCOPE`, y esa función
llamaba a `persistRuntime()` sin condición. `persistRuntime()` comparaba el objeto
`PersistedSafetyRuntime` completo, incluidas `latitude`, `longitude` y `locationAccuracyMeters`,
que cambian en **cada fix GPS**. Por tanto la caché `lastPersistedRuntime` nunca acertaba y se
ejecutaba `SharedPreferences.Editor.commit()` (fsync) desde el hilo principal varias veces por
segundo, durante horas.

Además del gasto de flash y CPU, esto **bloquea el hilo principal**, que es también el hilo que
entrega los eventos de sensores y el ticker: es un riesgo de pérdida de muestras en el momento
crítico.

**Corregido:**
- `syncDetectorRuntime()` ya no persiste.
- `persistRuntime()` usa `matchesMeaningfully()` (`SafetyRuntimeRepository.kt`), que tolera
  ruido de GPS: coordenadas con épsilon de 1e-5° (~1 m) y precisión con épsilon de 0,5 m.
- `locationWallTime` sí obliga a escribir, porque es lo que decide si un fix restaurado es
  reciente; si no, un SOS tras un reinicio podría perder la ubicación.
- Cubierto por `PersistedSafetyRuntimeTest` (8 casos).

### 2.2 [ALTO · corregido] La pantalla se encendía con cada golpe fuerte

**Evidencia (antes):** `AccidentDetector.onDynamicAcceleration` llama a
`listener.onVibrationDetected()` cuando `dynamicG >= sideTapG`, con `sideTapG` = sensibilidad
del usuario (por defecto 3,5 g; ajustable a 1,5 g). El servicio reaccionaba con
`wakeScreenController.wakeFor()`, que adquiere `SCREEN_BRIGHT_WAKE_LOCK |
ACQUIRE_CAUSES_WAKEUP | ON_AFTER_RELEASE` durante 15 s. Con `ON_AFTER_RELEASE`, el sistema
reinicia el temporizador de pantalla, así que en la práctica la pantalla queda encendida
mucho más de 15 s. En una moto (baches, arcén, badenes) esto son **ciclos de encendido de
pantalla cada 10 s** (cooldown `VIBRATION_COOLDOWN_MS`), que es uno de los mayores consumos
posibles en un móvil.

**Corregido:** el aviso de pantalla solo ocurre si el detector está en `FALL_CANDIDATE` o
`COUNTDOWN`, es decir si el golpe se está tratando como posible caída y el usuario debe poder
cancelar. El brillo/encendido por un golpe aislado ya no existe. Ventana ajustada a 20 s
(`CANDIDATE_SCREEN_WAKE_MS`), alineada con la ventana de candidato.

### 2.3 [MEDIO · corregido] Ticker a 1 Hz permanente

**Evidencia (antes):** `tickRunnable` se reprogramaba cada `TICK_MONITOR_INTERVAL_MS = 1000`
mientras el servicio vivía, y en cada tick llamaba a `publishTelemetry(now)` **sin `force`**,
que solo publica si pasó `SNAPSHOT_INTERVAL_MS = 1000`. Resultado: un despertar de CPU y una
publicación de estado (con recomposición de Compose si la pantalla está encendida) **cada
segundo para siempre**, sin que nada se decidiera en ese tick durante `MONITORING`.

**Corregido:** el ticker separa sus dos modos. En `MONITORING` late cada 5 s
(`TICK_MONITOR_INTERVAL_MS = 5_000`) y solo refresca telemetría; en `COUNTDOWN` mantiene los
100 ms que necesitan el pitido y el segundero. Nada se pierde: la detección de inmovilidad la
mueven los callbacks de sensores y de GPS, y la expiración de un candidato a caída se resuelve
en el tick siguiente (±5 s sobre una ventana de 15 s de `FALL_CANDIDATE_WINDOW_MS`), mientras
que la cuenta atrás conserva su precisión de 100 ms.

Como el tick de monitor ya no es inmediato, la notificación se fuerza al armar/descartar un
candidato y su texto ahora refleja `POSIBLE CAIDA` en lugar de "Monitor de seguridad activo"
(`updateNotification`).

### 2.4 [MEDIO · corregido] Giroscopio a ~50 Hz durante toda la conducción

**Evidencia (antes):** `registerSensors()` registraba `TYPE_GYROSCOPE` a
`SENSOR_DELAY_GAME` y nunca lo daba de baja. El giroscopio solo se consume en
`AccidentDetector.onGyroscope` **y solo si la fase es `VALIDATING`**, por lo que todo el
streaming fuera de esa fase era gasto puro.

**Corregido:** `syncGyroscopeListener()` lo registra solo en `VALIDATING`/`FALL_CANDIDATE` y lo
da de baja al salir (llamado desde `syncDetectorRuntime`, `activateMonitoring` y las
transiciones del listener). `unregisterSensors()` limpia el estado.

### 2.5 [MEDIO · corregido] `SensorCalibrationService` volcaba el CSV cada segundo

Un `flush()` sobre el `BufferedWriter` cada 1 s mientras cuatro sensores escriben a 50 Hz
fuerza escrituras de flash innecesarias. Ahora se vuelca cada 5 s
(`FLUSH_INTERVAL_MS`), manteniendo el refresco de la UI cada segundo. En el peor caso se
pierden los últimos 5 s de una grabación de calibración.

### 2.6 [INFO] Otros consumos revisados y considerados correctos o inevitables

| Elemento | Estado |
|---|---|
| `PARTIAL_WAKE_LOCK` del servicio | No existe: solo se adquiere durante la cuenta atrás (`COUNTDOWN_MS + WAKE_LOCK_GRACE_MS`). Correcto. |
| Buffer de sensores | `SENSOR_BATCH_LATENCY_US = 250 ms` cuando `fifoMaxEventCount > 0`. Correcto. |
| Intervalo GPS rutinario | 10 s con `minDistance = 0`. Razonable para el propósito; proponer 20 s en §5. |
| `SafetyStateStore` a 50 Hz | `updateMovementLevel` está limitado a cada 200 ms. Aceptable. |
| Micrófono (voz AYUDA/SOCORRO) | Sesión `AudioRecord` permanente cuando el usuario activa la opción: es el precio de la función, y está desactivada por defecto (`loadVoiceKeywordsGate` → `false`). |
| `MODE_BACKGROUND_ACTIVITY_START_ALLOWED` para abrir WhatsApp | Solo en el SOS. Correcto. |

---

## 3. Bugs funcionales encontrados

### 3.1 [ALTO · corregido] El hilo de escucha por voz moría en silencio y no volvía

`VoiceKeywordMonitor.start()` lanzaba un `Thread` con todo el cuerpo dentro de
`try { ... } catch (_: Throwable) { }`. Cualquier fallo del dispositivo (por ejemplo
`IllegalStateException` al llamar a `AudioRecord.stop()`/`startRecording()` sobre un recorder
que ya no está en el estado esperado, algo que el propio código provoca en la transición
`watcherPaused`) mataba el **único** hilo vigía. El bucle exterior no existía, así que la
detección por voz quedaba muerta hasta reiniciar el servicio, sin ningún aviso al usuario: la
UI seguía mostrando "ESCUCHANDO AYUDA / SOCORRO".

**Corregido:** la captura se extrajo a `captureLoop()`, que devuelve `true` si terminó porque
las puertas se cerraron (parada deliberada) y `false` si fue un error del dispositivo. El hilo
externo reintenta con espera de 3 s hasta `MAX_CAPTURE_RETRIES = 5` y, si no se recupera,
deja de reintentar, registra el evento y publica `NO DISPONIBLE` en la UI. Además:
- las transiciones de estado del recorder comprueban el resultado y provocan un reintento
  limpio en vez de una excepción;
- el buffer de lectura se limita a `MAX_READ_SAMPLES = 8192` para que el coste por muestra no
  dependa de lo que devuelva `getMinBufferSize` en cada fabricante.

### 3.2 [MEDIO · corregido] La puerta de orientación se quedaba desactivada tras un error de precisión

`onAccuracyChanged` ponía `orientationAccuracyReliable = false` y llamaba a
`setOrientationGateActive(false)` cuando el sensor reportaba `SENSOR_STATUS_UNRELIABLE`. Nunca
volvía a `true`, porque la comprobación vivía en la ruta del sensor de rotación
(`onOrientationChanged`), que **no se registra en ningún punto del servicio**
(`registerOrientationSensor()` no se llamaba desde ningún sitio). Consecuencia: tras un
`UNRELIABLE` transitorio, la confirmación de caída por inclinación quedaba desactivada para
siempre y el móvil volvía al comportamiento de "un golpe = SOS" sin avisar.

**Corregido:** la comprobación de precisión se hace ahora sobre `gravitySensor`, que es el
sensor que realmente alimenta la actitud (`onGravityAttitude`), y el estado del gate se
recalcula en cada cambio de precisión.

### 3.3 [MEDIO · corregido] La puerta de orientación podía no activarse nunca

`fallConfirmationEnabled` dentro de `AccidentDetector` empieza en `false` y solo se activa
desde `setOrientationGateActive(true)`. Ese camino dependía de que llegara un
`onAccuracyChanged`, que no es obligatorio en todos los equipos. En un dispositivo que no
reporte precisión, el ajuste "modo moto / caída" (`KEY_FALL_ORIENTATION_GATE`, por defecto
`true`) quedaba silenciosamente ignorado.

**Corregido:** `registerSensors()` activa el gate en cuanto el sensor de gravedad queda
registrado (`gravitySensor != null`) y lo desactiva cuando no hay sensor o el ajuste está
apagado.

### 3.4 [BAJO · corregido] Código muerto que inducía a error

- `registerOrientationSensor()`, `onOrientationChanged()`, la rama de `TYPE_ROTATION_VECTOR`
  en `onSensorChanged` y los campos `orientationSensor`, `orientationRegistered`,
  `lastOrientationEventAt`: inalcanzables. Eliminados. La actitud real se calcula con
  `TYPE_GRAVITY` → `FallOrientationDetector.onGravity` → `AccidentDetector.onFallTilt`.
- Se conserva `FallOrientationDetector.onRotationVector` porque tiene tests propios
  (`FallOrientationDetectorTest`) y sirve como implementación de respaldo documentada.
- `MainActivity`: `rememberAudioLevel()` (líneas ~2039+) y `rememberVoiceGateMeter()` (~2174+)
  están definidas pero no se llaman desde ninguna pantalla; `activityResumed` se escribe y no
  se lee. `rememberVoiceGateMeter` contiene una segunda implementación completa del detector de
  voz (hilo `AudioRecord` + `SpeechRecognizer`). Si algún día se invoca, habrá **dos**
  capturas simultáneas del micrófono. Recomendación en §5.

### 3.5 [ALTO · corregido] `completeProtocol()` podía repetir el protocolo SOS completo

**Evidencia (antes):**

```kotlin
private fun completeProtocol() {
    val completedEventId = activeEventId ?: UUID.randomUUID().toString()
    ...
    if (runtimeRepository.wasEventCompleted(completedEventId)) { finishProtocolState(); return }
```

Dos problemas encadenados:

1. La guarda de idempotencia se evaluaba **después** de anular `countdownEndsAt` y de que
   `activeEventId` pudiera haber quedado en `null` al final del protocolo, y usaba
   `?: UUID.randomUUID()`. Una segunda entrada generaba un identificador **nuevo**, que por
   definición nunca está en `wasEventCompleted()`, de modo que la guarda no protegía nada.
2. Las dos entradas reales son el ticker (`now >= countdownEndsAt`) y el respaldo de
   `AlarmManager` (`SosAlarmReceiver` → `ACTION_ALARM_COMPLETE`), que están programados para el
   **mismo instante**. Si ambas coinciden, la segunda repite la descarga completa:
   `sendSosSms()` a todos los contactos y el reencolado de WhatsApp.

**Corregido:** la guarda se evalúa al principio, sobre el identificador de evento real, y no se
inventa ninguno: sin evento activo no hay protocolo que completar. El camino duplicado solo
ejecuta la limpieza de estado.

### 3.6 [BAJO · README corregido] Documentación desincronizada del código

El README afirmaba tres comportamientos que el código ya no tiene: que un golpe enciende la
pantalla "at least five minutes", que se exige velocidad válida de 10 km/h antes de un impacto
automático, y que el candidato se valida con cinco minutos de inmovilidad. En el código actual:

- `AccidentDetector.setSosConditions()` es un no-op y `onSustainedFall()` devuelve `false`:
  **la velocidad y el GPS ya no condicionan ningún SOS** (los tests lo confirman:
  `configuredHitStartsImmediateCountdownWithoutSpeed`).
- La validación de 5 minutos solo puede alcanzarse por `simulateImpact()`; el camino de golpe
  real va a `FALL_CANDIDATE` y de ahí a cuenta atrás.
- La pantalla se enciende un máximo de 20 s, no 5 minutos.

Se ha actualizado el README (§4) y el "Three acceleration peaks" se ha corregido a los dos
impactos dentro de la ventana de 1,2 s que realmente implementa el detector.

**Nota de mantenimiento:** `setSosConditions()` y `onSustainedFall()` siguen existiendo solo por
compatibilidad de API y no hacen nada, igual que los ajustes `KEY_GPS_GATE` / `KEY_SPEED_GATE`
que la UI todavía presenta pero que ya no tienen efecto. Conviene decidir si se eliminan o se
vuelven a cablear: un ajuste visible que no hace nada es un riesgo de falsa confianza en un
producto de seguridad.

---

## 4. Cambios aplicados (archivos)

| Archivo | Cambio |
|---|---|
| `SafetyMonitorService.kt` | Protocolo SOS idempotente; persistencia por cambio significativo; ticker adaptativo (5 s monitor / 100 ms cuenta atrás); notificación forzada y con texto de candidato; aviso de pantalla solo en candidato/cuenta atrás; giroscopio bajo demanda; precisión del sensor de gravedad conectada al gate; eliminación de la ruta de rotación inalcanzable |
| `SafetyRuntimeRepository.kt` | `PersistedSafetyRuntime` deja de ser `internal`; nueva función pura `matchesMeaningfully()` con tolerancias de GPS |
| `VoiceKeywordMonitor.kt` | Bucle de captura reiniciable, comprobación de estado del recorder, límite de buffer, informe de fallo a la UI |
| `SensorCalibrationService.kt` | Volcado del CSV cada 5 s en lugar de cada segundo |
| `README.md` | Comportamiento de seguridad descrito según el código real |
| `app/src/test/.../PersistedSafetyRuntimeTest.kt` | Nuevo: 8 tests de la regla de escritura |

Total de tests: **50, 0 fallos**. `assembleDebug` genera `app-debug.apk` correctamente.

---

## 5. Mejoras recomendadas (no aplicadas)

Ordenadas por relación beneficio/riesgo. Las tres primeras son de bajo riesgo.

1. **GPS: alargar el intervalo rutinario y usar `minDistance`.** Hoy son 10 s con
   `minDistance = 0f`, lo que mantiene el chip GPS activo de forma continua. Para el caso de
   uso (velocidad + última posición conocida) `20 s` y `minDistance = 25 m` reducen el consumo
   del GPS ~30-40 % sin degradar el SOS, porque en `VALIDATING`/`COUNTDOWN` se vuelve a 2 s.
   Cambio de una constante + `setLocationUpdateInterval` ya existente.
   Alternativa más agresiva: `PASSIVE_PROVIDER` mientras la pantalla está apagada.

2. **`stopTicker()` cuando la pantalla está apagada y no hay cuenta atrás.** Con
   `PowerManager.isInteractive` se puede evitar por completo el despertar de 5 s en
   `MONITORING`; la detección por sensores sigue funcionando porque `isWakeUpSensor` despierta
   la CPU. Requiere comprobar antes que la expiración de candidatos no dependa del tick
   (hoy sí depende: la ventana de 15 s necesita el tick, así que habría que programarla con
   `AlarmManager`).

3. **Eliminar o cablear `rememberAudioLevel` / `rememberVoiceGateMeter`.** O se borran, o se
   unifican con `VoiceKeywordMonitor` para que exista una sola implementación del detector de
   voz. Hoy son ~370 líneas de código muerto que duplican lógica sensible y pueden provocar
   dos sesiones de micrófono simultáneas si alguien las invoca.

4. **`persistRuntime()` desde el hilo principal.** Ya no es un problema de frecuencia, pero
   `commit()` bloquea. Un `apply()` dejaría la escritura en un hilo de background; se mantiene
   `commit()` a propósito porque el estado debe estar en disco antes de que el proceso pueda
   morir (SOS tras `low memory kill`).

---

## 6. Cómo verificar

```text
gradlew.bat testDebugUnitTest     # 50 tests
gradlew.bat assembleDebug         # app\build\outputs\apk\debug\app-debug.apk
```

Prueba en dispositivo sugerida para medir el efecto de §2:

1. `adb shell dumpsys batterystats --reset`, iniciar el monitor, dejar la pantalla apagada
   30 min sin tocar el móvil.
2. `adb shell dumpsys batterystats com.intercom.segluky | Select-String "Wakeups|Sensor|ms partial"`.
   Antes del cambio el contador de `Wakeups` del servicio crecía ~1/s; ahora debe crecer ~1/5 s
   y las escrituras de estado deben aparecer solo al cambiar de fase o al desplazarse.
3. Repetir en moto con la sensibilidad en 1,5 g: la pantalla ya no debe encenderse en baches
   aislados, solo si se arma un candidato de caída.
