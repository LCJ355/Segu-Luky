# Segu-Luky V-1.43s

Independent Android safety prototype for accident validation, designed to coexist
with InterCOM-Luky. It keeps its own package (`com.intercom.segluky`) and signing
key; InterCOM is only inspected, never modified.

## Safety scope

- Uses a wake-up motion sensor when available (so it keeps working with the screen
  off), the gravity sensor for the fall attitude, the gyroscope only while a
  candidate or countdown needs it, and the platform GPS provider.
- A single strong hit arms a 15 s **fall candidate**. The countdown starts only
  when the gravity tracker confirms a sustained crash tilt (55 degrees for 4 s)
  or when two strong hits arrive inside the 1.2 s shake window. With
  `CAIDA / ORIENTACION` disabled, every configured strong hit starts the countdown
  immediately.
- The candidate wakes the screen and asks GPS for 2 s fixes so the rider can see
  and cancel the event. A candidate aborts when the phone returns upright, when
  GPS reports 30 km/h or more, or when the 15 s window expires.
- The screen only wakes for a fall candidate, a countdown or an SOS; a simple
  bump no longer lights the display.
- GPS speed and signal never gate a configured impact. They refine the report and
  the map link. Missing GPS is neutral and the last valid fix is retained.
- Long-press on the visible controls confirms a pending event. There is no
  two-finger gesture, in-app or global, in this build.
- Optional voice trigger: saying AYUDA or SOCORRO starts the countdown while the
  monitor is active, even with the screen off. It can be enabled before or after
  starting the monitor.
- **This build performs real emergency actions.** Once `FUNCIONALIDAD SOS` is
  enabled and contacts are configured, a completed countdown sends SMS when
  enabled, queues WhatsApp messages, and calls contacts in order. Verify the
  configured contacts before enabling it.

## Info menu

- The top bar groups the **Ajuste**, **Sensores** and **Informacion** icons on the
  left, each inside an orange ring, with `Segu-Luky` (26 sp) and the `V-1.xxs`
  label aligned on the same baseline at the right.
- The `(i)` icon opens the Segu-Luky dialog with **Buscar actualizacion**,
  **Como usar**, **Privacidad**, **Historial**, **Sugerencias**, **Compartir** and
  **Creditos**. Each row carries its own vector icon in a tinted 34 dp circle,
  copied from RUTAS-MOTERAS.
- `UpdateChecker` reads `releases/latest` from the public GitHub repository on
  `Dispatchers.IO`, with 6 s timeouts and an unconditional `disconnect()`. The
  repository has no published release yet, so the dialog reports that and offers
  the releases page instead of a download link.
- **Sugerencias** can copy a short device report (version, model, Android,
  sensitivity, monitor state). It never contains contacts, the bot token or the
  location.

## Delivery pipeline

A completed countdown leaves a persisted dispatch (`WHATSAPP` -> `CALLS`) that
survives process death. A watchdog alarm retries a stuck WhatsApp queue, the
accessibility service advances every terminal head, and the service resumes a
pending dispatch on restart. Call escalation waits for the current call to end
before placing the next one; an answered call is inferred from a connected call
lasting at least 3 s, and a call is never interrupted before 5 minutes. Exact
alarm permission is requested so the SOS finish and call timeout are punctual.

## Build

```text
gradlew.bat testDebugUnitTest
gradlew.bat lintDebug
gradlew.bat assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Safe device test

1. Install the debug APK without uninstalling InterCOM-Luky.
2. **Disable `FUNCIONALIDAD SOS` before testing.** The countdown then runs without
   calling or messaging anyone.
3. Grant location and notification permissions. GPS is optional: without it the
   monitor still detects impacts but the map link is unavailable.
4. Start monitoring and confirm the persistent notification appears.
5. Long-press the `S O S` control to start the real 30-second countdown.
6. Cancel the countdown and inspect the recent event log.
7. Re-enable `FUNCIONALIDAD SOS` only when the contacts are verified. There is no
   debug-only simulation of the contact protocol.

The two-finger gesture (two fingers anywhere on screen) was removed: on some
OPPO/ColorOS builds its accessibility input filter left the whole screen
unresponsive to touch, and the in-app variant was removed as well so the only
manual paths stay explicit. SOS is triggered by the in-app `S O S` control, the
shake sensor and the voice keyword.

## Battery notes

- Routine GPS is requested every 20 s or 25 m; it switches to 2 s during a
  candidate, countdown or validation.
- A stationary last fix remains usable for the map link for up to 5 minutes.
- Persisted state is only written when phase, event, timers or meaningful movement
  change; the location wall clock is coalesced into 15 s buckets.
- The monitoring heartbeat is 5 s; the countdown heartbeat is 100 ms.
- The gyroscope is only registered during candidate/validation.
- Calibration flushes the CSV every 5 s.

See `INFORME-ANALISIS.md` for the earlier review and `CHANGELOG.md` for this
version's changes. The package is `com.intercom.segluky`.
