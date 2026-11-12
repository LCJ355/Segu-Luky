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
7. Publish the release (`gh release create V-<versionName> "APK´s/Segu-Luky-<versionName>.apk" ...`).

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
  bot send does not retry in 1.47s).
- InterCOM: absent, installed-but-off, and installed-and-on (voice notice).
- Update check: offline, with no releases, and with a newer tag but no newer release.
