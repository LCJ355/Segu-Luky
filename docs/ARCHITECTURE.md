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

## Testing

- Pure detector, policy, contact and WhatsApp-contract tests run on the JVM.
- `gradlew testDebugUnitTest`, `gradlew lintDebug`, `gradlew assembleDebug`.
- Device validation is still required for background FGS rules, exact alarms,
  accessibility clicking and carrier call/SMS behaviour.
