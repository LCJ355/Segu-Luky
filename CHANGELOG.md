# Changelog

## 1.12s (versionCode 4)

### SOS reliability
- Persisted delivery pipeline now has explicit `WHATSAPP` / `CALLS` steps and keeps
  working when no contact has calls enabled.
- Added a dispatch watchdog alarm and resume-on-start. A WhatsApp head left in
  `PROCESSING`, `SENT`, `FAILED` or `EXPIRED` no longer blocks the queue.
- Stale `COMPLETE_SOS`, `ESCALATE_CALL` and `WHATSAPP_ADVANCE` alarms are now
  no-ops; they can no longer stop a healthy monitor or an in-flight delivery.
- Call escalation no longer treats `CALL_STATE_OFFHOOK` as answered. It waits for
  the call to end, infers an answer from a connected call of 3 s or more, does not
  interrupt a call before 5 minutes, and optionally ends it with
  `ANSWER_PHONE_CALLS` before advancing.
- Added `SCHEDULE_EXACT_ALARM` and exact-alarm status/authorisation UI. The
  fallback to inexact alarms is logged once.
- Location foreground service falls back to `specialUse` when Android rejects a
  background location start, so a recovered SOS can still complete without GPS.
- A duplicate or late SOS alarm cannot tear down monitoring or delivery.
- Manual WhatsApp fallback now posts one notification per configured contact
  (up to five).

### Detection and voice
- The fall candidate now wakes the screen and switches GPS to 2 s; expiry and
  abort restore the routine interval.
- Enabling voice while the monitor is already running now starts the capture
  thread instead of showing a false "listening" state.
- Audio capture checks `recordingState`, exits on recorder errors instead of
  spinning, and can restart after a configuration change.

### Battery and persistence
- Routine GPS is 20 s / 25 m; candidate and countdown use 2 s / 0 m.
- The map link accepts a stationary qualified fix up to 5 minutes old.
- Location wall-clock persistence is coalesced into 15 s buckets.
- Calibration ZIP export runs on `Dispatchers.IO` instead of the main thread.

### UI and platform
- The global two-finger accessibility service is now declared in the manifest and
  has a status/activation card. It is limited to Android 12+.
- Forced a light high-contrast scheme until all cards use Material tokens.
- Dispatch progress is visible in the status card.
- Removed the stale five-minute validation UI and unreachable phase values.
- Fixed duplicated/incorrect instruction texts and updated the README, protocol
  and safe device-test steps.

### Cleanup and quality
- Removed ~500 lines of dead code (duplicate voice/audio meter, unused gate rows,
  legacy SOS conditions, unused WhatsApp opener).
- Added `EscalationPolicy`, `ContactsRepository` and `WhatsAppAutoSend` unit tests.
- Added an Android CI workflow (unit tests, lint, debug assembly).
- Toolchain upgraded to Gradle 8.9 / AGP 8.6.1 with `compileSdk 35` (no
  `suppressUnsupportedCompileSdk`).
- Signing values can now come from `SEGU_*` environment variables while keeping the
  local `keystore.properties` fallback.
- Version bumped to `1.12s` / `versionCode 4`.
