# MotoGuard V1

Prototipo Android nativo en Kotlin para adquisición y registro de sensores durante un trayecto.

## Qué hace
- Servicio en primer plano de tipo `location`.
- Captura acelerómetro, giroscopio, gravedad, vector de rotación y magnetómetro.
- Solicita GPS mientras el servicio está activo.
- Registra las muestras en CSV en el almacenamiento privado externo de la app:
  `Android/data/com.motoguard.v1/files/records/`
- Marca anomalías exploratorias de aceleración/jerk y las refleja en una notificación.
- Permite seleccionar perfil: bolsillo, manillar, depósito, mochila o personalizado.

## Qué NO hace todavía
- No clasifica accidentes de forma validada.
- No envía SMS ni mensajes por Internet.
- No implementa el modo automático de inicio de viaje.
- No garantiza funcionamiento tras cierre forzado, apagado, reinicio o restricciones agresivas del fabricante.
- No debe utilizarse como sistema de seguridad único ni sustituir asistencia de emergencia.

## Requisitos
- Android Studio reciente.
- JDK 17.
- Android SDK 36.
- Gradle descargado por Android Studio.

Abrir la carpeta del proyecto en Android Studio y sincronizar Gradle.

## Prueba inicial
1. Instalar en un teléfono Android físico.
2. Conceder ubicación precisa y notificaciones.
3. Abrir la aplicación y pulsar INICIAR REGISTRO.
4. Confirmar que aparece la notificación permanente.
5. Bloquear la pantalla y realizar un trayecto normal.
6. Detener desde la aplicación.
7. Recuperar el CSV desde el directorio indicado.

No provocar caídas ni impactos peligrosos. Para pruebas de anomalías, usar movimientos controlados y soportes seguros.

## Notas técnicas
- El período de 10.000 microsegundos es una solicitud de muestreo de 100 Hz; el hardware puede entregar menos.
- La captura CSV se escribe en un hilo de E/S separado.
- Las anomalías actuales son marcadores de investigación, no decisiones de accidente.
- El servicio debe iniciarse desde una actividad visible, después de conceder permisos. Android restringe el inicio de servicios en primer plano desde segundo plano.
- La frecuencia de sensores, ubicación y comportamiento en Doze deben verificarse en cada modelo de teléfono.
