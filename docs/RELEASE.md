# Release checklist

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Run:
   ```text
   gradlew.bat testDebugUnitTest
   gradlew.bat lintDebug
   gradlew.bat assembleDebug
   gradlew.bat assembleRelease
   ```
3. The signed APK is at `app/build/outputs/apk/release/app-release.apk`.
4. Archive the R8 mapping, compressed, with the version in the name:
   `APK´s/mapping/Segu-Luky-<versionName>-mapping.txt.gz`. Without it the stack
   traces of the published build cannot be read; `app/build/` is git-ignored and
   `gradlew clean` deletes the original. See `APK´s/mapping/LEEME.txt`.
5. Copy the APK to `APK´s/` as `Segu-Luky-<versionName>.apk` and update
   `APK´s/PROTOCOLO-SEGU-LUKY.txt` and `APK´s/INSTRUCCIONES-SEGU-LUKY.txt` so
   they describe the version being released (version, channels, permissions).
6. Verify the signature before publishing:
   `apksigner verify --print-certs app-release.apk` must show the certificate
   SHA-256 listed in [SIGNING.md](SIGNING.md).
7. Check that the APK carries no personal data:
   ```text
   powershell -File tools/comprobar-apk.ps1 -Apk "APK´s/Segu-Luky-<versionName>.apk"
   ```
   The script fails if the package contains anything that is not code, resources
   or signatures (a CSV, JSON, database or preferences file that slipped into
   `assets/`), if it finds Spanish phone numbers, Telegram bot tokens or
   coordinate pairs, if it finds the names passed in `-Nombres` (by default
   `Pilar` and `Antonio`, the contacts used for the on-phone tests), or if the
   signing certificate is not the published one. Contacts, phone numbers, the bot
   key and the position exist only in the phone's `SharedPreferences` and the app
   declares `allowBackup="false"`, so no user data can reach the published file.
8. **Create the GitHub release, not just the tag**:
   `gh release create V-<versionName> "APK´s/Segu-Luky-<versionName>.apk" ...`.
   A tag alone is not enough: **Buscar actualizacion** only falls back to tags when there is
   *no release at all*, so with older releases published a tag-only version is invisible and the
   app reports "you are up to date" (this happened with 1.48s). Without `gh`, create it in the web
   UI and attach the APK — the tag must already be pushed.
9. Commit and push the APK, the mapping, the tag and the document updates together, so
   `APK´s/`, `README.md`, `CHANGELOG.md`, `Instrucciones/Estado.txt` and the bug report agree
   with the version installed on the phone.
10. Check the result as the app does: `GET /repos/LCJ355/Segu-Luky/releases` must list the new
    version first with the APK asset attached.
11. Refresh `APK´s/SHA256SUMS.txt` (all published APKs plus the certificate fingerprint) and put
    the new APK's SHA-256 in the release notes: it is how anyone verifies a download, together
    with `docs/SEGURIDAD.md`.

## Signing material

`keystore.properties` and `segu-luky-release.jks` are local-only and ignored by
`.gitignore`. Do not share or commit them. The certificate fingerprint used to
verify a published build, the restore steps and the backup/rotation rules are in
[SIGNING.md](SIGNING.md); `keystore.properties.example` is the versioned template.

For CI or another machine, set the environment variables instead of storing the
secrets in a file:

- `SEGU_STORE_FILE`
- `SEGU_STORE_PASSWORD`
- `SEGU_KEY_ALIAS`
- `SEGU_KEY_PASSWORD`

The Gradle build reads the environment variables first and falls back to
`keystore.properties`.

If the keystore is ever exposed, rotate the store/key passwords, keep the same
signing key if updates must remain installable, and update the secret storage.
Changing the key itself changes the app signature and requires uninstalling the
previous build.

## Device verification before shipping

- Unit tests and lint are green.
- Safe countdown with `FUNCIONALIDAD SOS` disabled.
- Real countdown with test contacts and `FUNCIONALIDAD SOS` enabled.
- WhatsApp queue with several contacts and accessibility enabled.
- Ordered calls: unanswered, answered, and app killed during the round.
- `am crash` during the countdown and during the dispatch.
- Reboot with monitoring requested.
- Android 12+ background start and exact-alarm behaviour.
- Telegram: bot with valid token and chat id, and the same flow with no data (the
  bot send still does not retry).
- Voice activation with the screen off and the phone in a pocket (the microphone
  foreground type must be accepted; look for "Android rechazo el tipo microfono"
  in the log if it is not).
- InterCOM: absent, installed-but-off, and installed-and-on (voice notice).
- Update check: offline, with no releases, and with a newer tag but no newer release.
