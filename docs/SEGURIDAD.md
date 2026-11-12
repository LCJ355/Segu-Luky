# Seguridad e integridad de Segu-Luky

Qué hace esta app con los permisos que pide, a dónde se conecta, cómo comprobar que
el APK instalado es el oficial y por qué Google Play Protect avisa al instalarlo.

## Resumen

Segu-Luky es una app que, para poder avisar en una emergencia, necesita capacidades que
Android considera peligrosas: enviar SMS, marcar llamadas, escuchar el micrófono, saber
dónde estás, dibujar sobre otras apps y pulsar botones dentro de WhatsApp. Combinadas, son
el mismo perfil que usan los troyanos bancarios, así que Play Protect la marca como
"sospechosa" aunque no encuentre código malicioso. Este documento enumera cada capacidad,
su motivo y su alcance real, para que la decisión de instalarla sea informada.

La app **no** tiene cuentas, ni anuncios, ni analítica, ni servidor propio.

## Firma del APK

| Dato | Valor |
|---|---|
| Sujeto | `CN=Segu-Luky, OU=Personal, O=Segu-Luky, C=ES` |
| Alias de la clave | `segu` (RSA 2048) |
| SHA-256 del certificado | `E0:E5:A8:FD:9B:42:EC:DA:38:22:B3:B1:BC:05:AD:D7:CA:02:DC:B3:DE:B8:77:1B:AF:8F:64:08:CF:E2:10:9E` |
| Validez | 06/09/2026 → 22/01/2054 |
| Esquemas | v2 y v3 (sin v1) |

Los detalles de restauración, copia de seguridad y rotación están en
[SIGNING.md](SIGNING.md). La clave privada **no** está en el repositorio.

## Cómo comprobar que lo instalado es el build oficial

Tres vías, de la más cómoda a la más estricta:

1. **En el móvil, dentro de la app**: Acerca de → **Verificar instalación**. Muestra la
   huella SHA-256 del certificado de la instalación actual. Debe coincidir con la de esta
   tabla.
2. **En el PC, con el APK descargado**:
   ```text
   certutil -hashfile "Segu-Luky-<version>.apk" SHA256
   ```
   y compara con `APK´s/SHA256SUMS.txt`, que se actualiza en cada versión.
3. **Firma del archivo** (requiere el SDK):
   ```text
   apksigner verify --print-certs "Segu-Luky-<version>.apk"
   ```
   Debe imprimir `CN=Segu-Luky` y el SHA-256 de arriba.

Un APK reempaquetado con otra clave cambia esa huella. El SHA-256 del archivo solo
demuestra integridad (que no se ha modificado); la firma demuestra autoría.

## Permisos y para qué se usan

| Permiso | Uso real en el código | Cuándo se pide |
|---|---|---|
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Enlace de Google Maps en el aviso. Sin posición se avisa igual, sin mapa | Al activar el monitor |
| `CALL_PHONE` | Marcar a los contactos en orden durante el SOS | Al activar FUNCIONALIDAD SOS |
| `READ_PHONE_STATE` | Saber cuándo termina una llamada para pasar al siguiente contacto | Al activar FUNCIONALIDAD SOS |
| `SEND_SMS` | SMS de emergencia a cada contacto (opcional, se activa aparte) | Solo si se habilita SMS |
| `RECORD_AUDIO` | Medir el nivel de audio y reconocer "AYUDA"/"SOCORRO". **No graba ni guarda sonido** | Solo si se activa NIVEL DE AUDIO o VOZ |
| `FOREGROUND_SERVICE` + `_LOCATION` + `_MICROPHONE` + `_SPECIAL_USE` | Mantener la vigilancia con la pantalla apagada (tipos declarados en el manifiesto) | Al arrancar el monitor |
| `POST_NOTIFICATIONS` | Notificación del monitor y avisos SOS | Al activar el monitor |
| `SYSTEM_ALERT_WINDOW` | Ventana de cuenta atrás sobre otras apps | Al activar el monitor |
| `RECEIVE_BOOT_COMPLETED` | Rearmar el monitor tras reiniciar si lo pediste | Reinicio del móvil |
| `SCHEDULE_EXACT_ALARM` | Puntualidad del final del SOS y del cambio de contacto | Al activar FUNCIONALIDAD SOS |
| `WAKE_LOCK` / `VIBRATE` | Despertar la pantalla y avisar en la cuenta atrás | Durante el SOS |
| `INTERNET` / `ACCESS_NETWORK_STATE` | Bot de Telegram y comprobar actualizaciones | Al usarlos |

La app **no** pide `READ_SMS`, no pide `QUERY_ALL_PACKAGES`, no pide
`REQUEST_INSTALL_PACKAGES` (no puede autoinstalarse) y no lee el portapapeles ni los
contactos del sistema (los contactos SOS se escriben a mano).

## El servicio de accesibilidad

Es la capacidad que más pesa en el veredicto de Play Protect, así que conviene saber
exactamente qué puede y qué no.

- **Alcance limitado por configuración** (`res/xml/whatsapp_auto_send_config.xml`): solo
  recibe eventos de `com.whatsapp`, `com.whatsapp.w4b` y `org.telegram.messenger`. No ve
  el contenido de ninguna otra app.
- **Qué hace**: cuando el SOS abre un chat con el mensaje ya escrito, busca el botón de
  enviar y lo pulsa. Solo lo pulsa si coinciden **paquete, destinatario y texto
  normalizado**; si algo no cuadra, no toca nada y lo anota en el registro.
- **Además**: escribe el mensaje en el chat cuando el envío va por el enlace `t.me`.
- **Qué no hace**: no lee datos bancarios, no intercepta teclas, no hace capturas, no
  actúa fuera de esos tres paquetes y no envía nada a ningún sitio.

Si no usas el envío automático de WhatsApp, puedes dejar el servicio **desactivado** en
Ajustes → Accesibilidad: el resto del SOS (llamadas, SMS, Telegram bot, aviso a InterCOM)
funciona igual. La declaración en el manifiesto sigue ahí aunque el servicio esté apagado.

## Destinos de red

Son los únicos que aparecen en el código fuente (`app/src/main/java`):

| Destino | Para qué | Cuándo |
|---|---|---|
| `api.telegram.org` | Enviar el mensaje por el bot que tú configures | Solo si hay token de bot y chat id |
| `api.github.com` / `github.com` | Buscar actualizaciones y abrir la release | Manual, desde "Buscar actualizacion" |
| `api.whatsapp.com` / `www.google.com` | Enlaces que **WhatsApp** abre al recibir el intent (`wa.me`, Maps). La app solo construye el intent | Al abrir el chat con el mensaje |
| `t.me` | Enlace público del contacto de Telegram (respaldo sin bot) | Al enviar por enlace |
| `content://com.intercom.lukymoto.bridge` | Puente con InterCOM-Luky: estado del grupo y aviso de voz. Es local, entre apps del mismo móvil | Durante el SOS |

No hay endpoint propio, ni CDN, ni servicio de terceros. La comprobación de actualización
manda una petición GET anónima a la API pública de GitHub y lee el JSON de releases.

## Dependencias

Solo AndroidX y Jetpack Compose (BOM 2024.06.00, `core-ktx`, `activity-compose`,
`lifecycle-*`, Material 3). En tests, JUnit 4. **No hay SDKs de terceros, publicidad ni
analítica**, y por eso no hace falta ningún consentimiento de seguimiento.

## Qué datos guarda en el móvil

Todo en `SharedPreferences` locales de la app, sin cifrar (no hay backend que los reciba):

- contactos SOS (nombre, teléfono, usuario/chat de Telegram, mensajes),
- token y chat id del bot de Telegram,
- cola de envíos de WhatsApp y cola de reintentos de Telegram,
- estado del monitor, el evento SOS en curso y el registro reciente.

`allowBackup="false"`: no se copian a la nube de Google ni salen del móvil al hacer copia
de seguridad. Al desinstalar la app se borran.

## Por qué Play Protect avisa

Android muestra "Es posible que esta aplicación sea dañina" cuando el APK **no viene de
Play** y su detector encuentra una combinación de riesgo alta. En este caso:

1. Firma autofirmada y desconocida para Google: no hay reputación de desarrollador.
2. `SEND_SMS` + `CALL_PHONE` + `RECORD_AUDIO`: el trío clásico de malware de suscripción.
3. Servicio de accesibilidad que lee la pantalla y pulsa botones: el vector principal de
   los troyanos bancarios.
4. `SYSTEM_ALERT_WINDOW` (superposición) y `RECEIVE_BOOT_COMPLETED` (autoarranque).

Es un aviso **heurístico**: no dice que se haya encontrado código malicioso, dice que no
puede descartarlo. Con estos permisos, cualquier app —incluida ésta— dispara el mismo
mensaje. Al no haber revisión de Play, la confianza tiene que venir de la verificación de
firma descrita arriba.

Notas prácticas:

- El aviso reaparece en **cada versión nueva**: cambia el binario y Play Protect vuelve a
  ver un APK desconocido. Se instala con "Instalar de todos modos".
- No hace falta desactivar Play Protect en el móvil; solo afecta a este archivo.
- Si en algún momento la huella de firma no coincide con la de este documento, **no lo
  instales**: no es un build oficial.

## Qué no hace la app

- No se autoinstala ni descarga APKs (`REQUEST_INSTALL_PACKAGES` ausente).
- No lee SMS ni notificaciones de otras apps.
- No accede a contactos, calendario, archivos ni portapapeles.
- No envía datos a servidores propios ni a terceros (solo al bot que configures).
- No graba ni almacena audio: mide nivel y reconoce dos palabras.

## Límites de este documento

La app no está revisada por Play, no tiene auditoría externa y depende de que el móvil no
esté comprometido. La verificación de firma y el listado de destinos son la base para
confiar en ella; si el código cambia, este documento debe cambiar con él.
