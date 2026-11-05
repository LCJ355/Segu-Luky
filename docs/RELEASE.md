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
4. Keep `app/build/outputs/mapping/release/mapping.txt` for the release.
5. Copy the APK and the current protocol to `APK´s/`.

## Signing material

`keystore.properties` and `segu-luky-release.jks` are local-only and ignored by
`.gitignore`. Do not share or commit them.

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
- Global gesture on Android 12+.
