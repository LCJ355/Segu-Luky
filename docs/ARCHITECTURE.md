# Architecture

## Toolchain

- Gradle 8.9, Android Gradle Plugin 8.6.1, JDK 17, Kotlin 1.9.22, Compose BOM
  2024.06.00, `minSdk 29`, `compileSdk`/`targetSdk 35`.

## Process and state

- `MainActivity` hosts the Compose UI and permission flows.
- `SafetyMonitorService` is the foreground orchestrator: sensors, location,
  countdown, notifications, voice and SOS delivery.
- `AccidentDetector` and `FallOrientationDetector` are pure Kotlin state machines
  (unit tested).
- `AdaptiveSensitivity` (window statistics, per-device profile, effective
  threshold and sampling tiers) and `SensitivityAdvisor` (recommended setting)
  are pure Kotlin as well, so the whole detection chain is JVM tested.
- `SafetyMonitorService` feeds every motion sample to `AdaptiveSensitivity` and
  applies the resulting floor to `AccidentDetector`; it re-registers only the
  motion sensor when the sampling tier changes, never the gravity one.
- `SafetyStateStore` is an in-process `StateFlow<SafetySnapshot>` observed by
  Compose.
- `SafetyRuntimeRepository` persists crash-recovery state in SharedPreferences,
  guarded by the Android boot count.
- `EmergencyDispatchRepository` persists the SOS delivery pipeline
  (`WHATSAPP` -> `CALLS`) across process death.

## SOS flow

```
sensors/GPS/voice/gesture
          |
          v
   AccidentDetector  --candidate-->  FallOrientationDetector
          |                               |
          +---------- COUNTDOWN <---------+
                         |
                 completeProtocol()
                         |
        SMS -> WhatsApp queue -> calls -> DONE
                         ^
        dispatch watchdog + alarm recovery
```

`WhatsAppAutoSend` is a durable FIFO authorization contract. The accessibility
service may only click Send when the head request exists, the package matches and
the normalized message matches exactly.

## Invariants

- A configured impact is never gated by GPS or speed.
- A completed event is executed once (`eventId` + persisted completed id).
- The ticker and backup alarm may race, but all protocol entry points are
  idempotent.
- A stale alarm may never clear monitoring or a pending dispatch.
- Every background start that can be denied by Android degrades to a safer
  foreground type instead of aborting the safety function.

## Perfil por soporte y calibracion (1.70s)

- `MountProfile` guarda un `DeviceProfile` distinto por soporte; `UNDEFINED` usa
  el perfil global heredado.
- `CalibrationRateController` y `CalibrationRatePolicy` calculan la tasa de
  calibracion (20-100 Hz) segun la velocidad, con mediana, estabilidad y un
  intervalo minimo entre rebindings.
- `SensorCalibrationService` escribe el CSV desde un hilo de sensores dedicado y
  protege el writer con un lock; el formato es aditivo para no romper CSV viejos.

## Sensor externo de vuelco (1.97s)

- `Wt9011Controller` es el dueno del enlace BLE con el WT9011DCL-BT50: escaneo,
  GATT serializado, MTU 247, CCCD con respaldo, reconexion y publicacion del
  estado a 5 Hz. La interfaz puede conectarlo con el monitor parado para
  comprobar los ejes.
- `Wt9011Protocol` y `Wt9011Config` son Kotlin puro y estan cubiertos por
  pruebas JVM: tramas `55 61` de 20 bytes, variante clasica de 11 bytes y plan
  de configuracion a 9 ejes / 50 Hz / mascara `0x07`; el magnetometro da el
  rumbo del sensor y la inclinacion sigue calculandose con la gravedad.
- `Wt9011TiltWatch` calcula la inclinacion X/Y contra una referencia aprendida
  con la moto vertical, con pasa-bajos de gravedad, umbral 50-60 grados, 5 s
  sostenidos, histeresis y rearme. No usa el magnetometro ni el rumbo.
- `SafetyMonitorService` solo consume el evento de vuelco: comprueba que el
  monitor este activo y que no haya SOS o envio en curso, y llama a
  `AccidentDetector.confirmExternalFall`, que reutiliza la cuenta atras y el
  protocolo completos. Es una condicion mas, nunca exclusiva.
- La temperatura interna se pide aparte, por BLE, con `FF AA 27 40 00` (registro
  `0x40`) cada 5 s; la respuesta `55 71 40` no interfiere con las tramas `0x61`.
  Con el monitor parado, la pantalla de MOTO mantiene el enlace para comprobar
  la conexion y ver `TEMP`; a su lado se muestra el rumbo magnetico del sensor.
  El angulo Z del WT9011 crece en sentido antihorario, asi que se invierte para
  que el rumbo suba al girar a la derecha, como en una brujula.
  Ambos datos son informativos y no entran en el detector ni en el SOS.
- Si el sensor guardado falla dos veces seguidas al conectar, el controlador
  deja de reintentar (`wanted=false`, `savedDeviceSuspended=true`) y muestra
  `BUSCAR OTRO`. El contador se reinicia al conectar, al elegir otro sensor, al
  arrancar el monitor o al cortar.

## Testing

- Pure detector, policy, contact and WhatsApp-contract tests run on the JVM.
- `gradlew testDebugUnitTest`, `gradlew lintDebug`, `gradlew assembleDebug`.
- Device validation is still required for background FGS rules, exact alarms,
  accessibility clicking and carrier call/SMS behaviour.
