# Segu-Luky V-1.70s (en desarrollo)

Independent Android safety prototype for accident validation, designed to coexist
with InterCOM-Luky. It keeps its own package (`com.intercom.segluky`) and signing
key; InterCOM is a separate app reached only through its exported bridge (see
[InterCOM bridge](#intercom-bridge)).

## Safety scope

- Uses a wake-up motion sensor when available (so it keeps working with the screen
  off), the gravity sensor for the fall attitude, the gyroscope only while a
  candidate or countdown needs it, and the platform GPS provider.
- A single strong hit arms a 15 s **fall candidate**. The countdown starts only
  when the gravity tracker confirms a sustained crash tilt (55 degrees for 4 s),
  when a hit at 20 km/h or more leaves the phone perfectly still through the whole
  window, or when two strong hits arrive inside the 1.2 s shake window after a
  dip. With `CAIDA / ORIENTACION` disabled, every configured strong hit starts the
  countdown immediately.
- The candidate wakes the screen and asks GPS for 2 s fixes so the rider can see
  and cancel the event. A candidate aborts when the phone returns upright, when
  GPS reports 30 km/h or more, or when the 15 s window expires while moving.
- The hit threshold is **not** the slider value alone: `AdaptiveSensitivity`
  learns each device's rest noise, normal riding level and measured sample rate,
  and derives an effective threshold that is always shown on screen. A short
  window (`ADAPT.` auto 1/2/3 min) follows the current road and a long profile
  refines itself with usage. See [docs/SENSIBILIDAD.md](docs/SENSIBILIDAD.md).
- A peak only counts after re-arming (0.6x the threshold) and at least 250 ms
  apart, so the rebound of a single hit is no longer read as a deliberate shake.
- `PROBAR GOLPE` runs a 15 s shake test that says whether the phone would have
  fired, without recording files and without notifying anyone.
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

## Configuracion

The gear opens an index with four blocks and their live status; each block shows
only its own settings and a `‹ CONFIGURACION` header to go back (the system back
button goes up one level, not straight to the home screen).

- **Sensores**: sensitivity slider with its 0..7 scale, the `ADAPT.` averaging window, `PICO/EFECTIVO/SUGERIDO`, `PROBAR GOLPE`, fall/orientation confirmation, voice trigger and diagnostics
  (device profile, reset learning, optional CSV/ZIP recording). On the home card only the
  `SENSIBILIDAD A LOS GOLPES = x,x g` label and a read-only `T. PROMEDIO` row (AUTO/1/2/3; in AUTO both AUTO and the applied minute number are green, and that number blinks three times whenever AUTO changes the window).
- **Contactos SOS**: contact list in call order, editor, and the map-link note.
- **Canales de salida SOS**: auto WhatsApp (with its two
  permissions), Telegram bot, automatic SMS and the INTERCOM voice notice. The SOS master switch now lives on the home screen as `S O S ON/OFF`.
- **Permisos sistema**: exact alarms, accessibility/overlay and the state of
  notifications, location, microphone, calls and SMS with a shortcut to the app
  settings.

## Info menu

- The top bar groups the **Ajuste**, **Sensores** and **Informacion** icons on the
  left, each inside an orange ring, with `Segu-Luky` (26 sp) and the `V-1.xxs`
  label aligned on the same baseline at the right.
- The `(i)` icon opens the Segu-Luky dialog with **Buscar actualizacion**,
  **Como usar**, **Privacidad**, **Verificar instalacion**, **Historial**,
  **Sugerencias**, **Compartir** and **Creditos**. Each row carries its own vector
  icon in a tinted 34 dp circle, copied from RUTAS-MOTERAS. **Verificar
  instalacion** prints the SHA-256 of the signing certificate of the installed
  APK so it can be compared with the published fingerprint without a PC; the
  permission inventory, network endpoints and the Play Protect explanation are in
  [docs/SEGURIDAD.md](docs/SEGURIDAD.md).
- `UpdateChecker` reads `releases/latest` from the public GitHub repository on
  `Dispatchers.IO`, with 6 s timeouts and an unconditional `disconnect()`. When a
  release exists it compares tags part by part and links the attached APK; if the
  repository only has tags (no release) it falls back to `releases` and then
  `tags`, and when there is really nothing published it says so and offers the
  releases page instead of a download link.
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

The shareable build of every released version is published in `APK´s/` as
`Segu-Luky-<version>.apk`. The last stable release is `Segu-Luky-1.64s.apk`;
the `1.70s` line is still in development and is not published.

Only the newest version stays published as a GitHub release: currently `V-1.64s`
with `Segu-Luky-1.64s.apk` attached. Older releases are deleted so the download page
always shows a single APK (the git tags stay, so the source of every version is
still reachable). **Buscar actualizacion** reads the GitHub API and compares
against that one release:

```text
gh release create V-1.70s "APK´s/Segu-Luky-1.70s.apk" --title "Segu-Luky V-1.70s" --notes "..."
```

The published APK carries **no personal data**: contacts, phone numbers, the bot
key and the position live only in the phone's `SharedPreferences` (the app
declares `allowBackup="false"`). Before uploading, check the file itself:

```text
powershell -File tools/comprobar-apk.ps1 -Apk "APK´s/Segu-Luky-1.70s.apk"
```

It fails if the package contains anything that is not code, resources or
signatures, if it finds Spanish phone numbers, Telegram bot tokens, coordinate
pairs or the names passed in `-Nombres`, or if the signature does not match
`docs/SIGNING.md`.

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

## InterCOM bridge

InterCOM-Luky (`com.intercom.lukymoto`) exposes a read-only `ContentProvider`
that Segu-Luky uses to reach the riders' group:

- `content://com.intercom.lukymoto.bridge/status` → one row with `active`,
  `group`, `peers` and `version`. `active=1` means the intercom service is
  running and can broadcast; it does not require an open group.
- `call("notice", text)` → broadcasts `Posible accidente de motorista` over both
  WebRTC (data channel `notice`) and the INTERCOM WiFi (UDP type `6`). Returns
  `sent`, `peers` and the echoed notice text.

The three-state indicator in `Configuracion` (the INTERCOM-LUKY card, right above
`CONTACTOS SOS`) reads that provider every 5 s: grey = not installed, orange =
installed but the intercom is off, green = installed and running. In green the
SOS notice reaches the other riders; in orange or grey it does not sound
anywhere. The SOS countdown triggers the same notice once it completes. Contract
details live in `Instrucciones/INTEROPERABILIDAD-SEGU-INTERCOM.txt`.

See `INFORME-ANALISIS.md` for the earlier review and `CHANGELOG.md` for this
version's changes. The package is `com.intercom.segluky`.
