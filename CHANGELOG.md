# Changelog

## Sin publicar

### Aviso a InterCOM fuera del hilo del SOS
- `dispatchIntercomNotice()` hacía dos llamadas binder a otra app (`query` del estado y `call`
  del aviso de voz) **en el hilo principal**, justo antes de lanzar SMS, WhatsApp y llamadas: si
  InterCOM tardaba, el SOS empezaba tarde, y si se atascaba el servicio se quedaba sin responder
  (ANR) con el protocolo a medias. Ahora el aviso sale en su propio hilo, el protocolo no lo
  espera y el resultado se anota en el registro cuando llega (`IntercomNotice.detail`).
- La misma consulta se hacía en la pantalla cada 5 s y al pulsar «PROBAR AVISO»: ambas pasan a
  un hilo de fondo, así que la UI no se congela si InterCOM no contesta.
- 4 tests nuevos (94 en total) sobre el texto del registro.

## 1.49s (versionCode 41)

Publicada con APK, mapping, protocolo e instrucciones: `APK´s/Segu-Luky-1.49s.apk`,
`APK´s/mapping/Segu-Luky-1.49s-mapping.txt.gz`, release `V-1.49s` con el APK adjunto.

### Buscar actualización (crítico para distribuir)
- `UpdateChecker` solo miraba las etiquetas cuando el listado de releases estaba vacío. Con
  releases antiguas ya publicadas, una versión etiquetada pero sin su release quedaba invisible
  y la app respondía "estás al día" (le pasó a la 1.48s, que hubo que publicar como release).
  Ahora se leen las dos listas y se elige la versión más alta: la release manda para el enlace de
  descarga y una etiqueta sin release apunta a la carpeta `APK´s/`.
- 4 tests nuevos (90 en total, 0 fallos).

## 1.48s (versionCode 40)

Publicada con APK, mapping, protocolo e instrucciones: `APK´s/Segu-Luky-1.48s.apk`,
`APK´s/mapping/Segu-Luky-1.48s-mapping.txt.gz`.

### Escalada de llamadas (crítico)
- Se elimina la dependencia de `ANSWER_PHONE_CALLS`: el permiso se declaraba pero nunca se
  concedía, así que `endCurrentCall()` siempre fallaba y, al agotarse los 5 min, se marcaba al
  siguiente contacto **con la llamada anterior todavía activa**. Ahora la app nunca cuelga ni
  marca sobre una llamada en curso: si sigue conectada al agotarse el presupuesto, la ronda
  termina y se da por atendida (`EscalationPolicy.answeredByLongCall`).
- Una llamada que termina ya no cierra la ronda por la inferencia de "contestada" (3 s desde el
  OFFHOOK): Android no distingue una respuesta de un timbrado o un buzón, así que se pasa al
  siguiente contacto y el registro anota "posible respuesta". Antes, un buzón de voz detenía el
  protocolo y los demás contactos no recibían la llamada.
- Un aviso de fin de llamada que llega antes de que el marcado nuevo se establezca se ignora
  (`EscalationPolicy.idleArrivedWhileDialing`): es el del intento anterior y saltarse un contacto
  por él sería peor que esperar al minuto siguiente.

### Micrófono en primer plano (crítico)
- El servicio declara y solicita el tipo `microphone` (`FOREGROUND_SERVICE_MICROPHONE`) mientras
  la activación por voz está activa y hay permiso, así que Android 14+ deja de entregar silencio
  a `AudioRecord` con la pantalla apagada. Si el sistema rechaza el tipo, el servicio se inicia
  sin él y lo anota en el registro, de modo que el monitor nunca se queda sin arrancar.

### Verificación
- 86 tests unitarios, 0 fallos; `lintDebug` 0 errores; APK firmado con la misma clave
  (`CN=Segu-Luky`) que las versiones anteriores.

## 1.13s – 1.47s (versionCode 5 → 39)

Resumen generado del historial de git (`7cf1c07..173d7a8`). Los mensajes de commit no siempre
indican la versión, así que se agrupa por tema y se cita el commit.

### Canales de aviso
- Canal de Telegram (`3700f99`): bot API (token global + `chat_id` por contacto) y respaldo por
  enlace `t.me`; el enlace reutiliza la cola duradera de WhatsApp, el bot envía en un hilo
  propio.
- Envío automático de WhatsApp ampliado (`df5080c`) e instrucciones de uso con Telegram
  (`57c71c0`).

### Detección, GPS y voz
- GPS que se quedaba sin posición bajo techo (`2ffcad8`).
- Fila de velocidad aclarada y reutiliza la última velocidad conocida (`52c241a`).
- Escucha por voz tras reiniciar y calibración de caída conservada (`319c88e`).

### Batería
- Optimización del gasto del servicio y menos publicaciones repetidas (`7660b50`).

### Gesto de dos dedos (eliminado)
- Activación fiable (`c4ee5b5`) y corrección del bloqueo de táctil en algunas ROM (`8a1ed7f`).
- Eliminado el gesto dentro de la app (`a12bf11`) y después el global (`2b99cd6`): en algunos
  OPPO/ColorOS el filtro de accesibilidad dejaba la pantalla sin respuesta al tacto.

### Interfaz
- Rediseño visual (`b8b98d7`), pantalla e icono de Sensores (`0eac4f0`), luces de estado de los
  sensores (`7fee183`), aviso SOS solo al monitorizar y color del botón (`d184098`), niveles de
  audio y movimiento (`b26854b`), sensibilidad compacta y dentro de la tarjeta (`270e237`,
  `f55b982`, `016e6ff`, `51aa5ca`, `56765fd`, `8d56071`, `ff99dfd`), interruptores verdes
  (`5944fe1`), etiquetas en una línea (`0b55d67`), círculos naranjas y menú con iconos
  (`cfb59ed`), columnas de progreso junto al botón SOS (`d317abd`).
- Menú de información con «Buscar actualización» y créditos (`2711c72`), foto en créditos
  (`f232217`).

### Actualizaciones
- «Buscar actualización» consulta releases y etiquetas de GitHub (`2ca84a2`), elige la release
  más alta con independencia del orden de publicación (`91c0e86`), verificado en dispositivo
  (`83abe3d`).

### InterCOM-Luky
- Luz de tres estados y aviso de voz al grupo (`f27847b`); tarjeta INTERCOM-LUKY en
  Configuración (`173d7a8`).

### Infraestructura
- `versionCode 5` / 1.13s (`06c60f7`), versión real en la interfaz (`cea3690`), endurecimiento
  del desmontaje del SOS y de los plazos de aviso (`f5d7ac8`).
- Publicación de la APK en `APK´s/` (`40e5616`); el CI silencia el aviso de permiso de la
  ubicación cacheada (`81d2eec`).

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
