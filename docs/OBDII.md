# Enlace OBDII del perfil COCHE

Implementado en la 1.72s con un adaptador **ELM327 por Bluetooth clasico (SPP)**.
La velocidad y las revoluciones salen del coche; el GPS queda como respaldo.

## De donde salen los datos de conexion

Del APK de referencia `docs/referencia/OBDII/Torque_Pro v1.8.16.apk`, por analisis
estatico del DEX (REA + extraccion de literales). Lo observado:

- Transporte Bluetooth clasico: `createRfcommSocketToServiceRecord` con UUID
  **`00001101-0000-1000-8000-00805F9B34FB`** y `processBluetoothStateChanged`.
- Transporte WiFi (no implementado aun): puerto **35000**.
- Comandos: `ATZ`, `ATE0`, `ATL0`, `ATS0`, `ATH0/ATH1`, `ATSP0`, `ATDP`, `ATDPN`,
  `ATRV`, `ATMA`, `ATST33`, `ATSH7E0`, `ATSH7B0`, `ATCRA7B0`, `ATCAF0`, `ATCFC0`.
- Protocolos nombrados: ISO 15765-4 CAN (11/250, 11/500, 29/250, 29/500), SAE
  J1850 (PWM/VPW), ISO 9141-2, ISO 14230-4 (KWP).
- PIDs: `0100`, `0101`, `0105`, `010C`, `010D`, `010F`, `0111`, `011C`, `012F`,
  `0140`.

No se copia codigo de Torque: solo se usan esos datos publicos de protocolo.

## Como esta montado

| Pieza | Fichero | Que hace |
|---|---|---|
| `ObdParser` | `Obd.kt` | Convierte la respuesta cruda del ELM327 en velocidad, rpm, acelerador, refrigerante, codigos y protocolo. Puro y con pruebas. |
| `ObdPids` | `Obd.kt` | Comandos y PIDs, con la secuencia de arranque. |
| `SppObdLink` | `ObdLinkController.kt` | Abre el socket RFCOMM y manda un comando cada vez (terminador `>`). |
| `ObdLinkController` | `ObdLinkController.kt` | Un hilo con la ronda de PIDs, reconexion y `StateFlow<ObdState>` que lee la tarjeta. |

Arranque: `ATZ` (con 400 ms de espera), `ATE0`, `ATL0`, `ATS0`, `ATH1`, `ATSP0` y
`ATDPN` para saber el protocolo negociado. Ronda: velocidad cada 200 ms; rpm,
acelerador, refrigerante, tension y codigos cada segundo. Silencio de 6 rondas o
una excepcion del socket cierran el enlace y lo reintentan (3 intentos).

## Estados que ve el usuario

`APAGADO`, `SIN PERMISO` (falta BLUETOOTH_CONNECT en Android 12+), `SIN ADAPTADOR`,
`CONECTANDO`, `SIN DATOS` (conectado pero el coche no responde: contacto apagado),
`EN MARCHA` y `ERROR` con el motivo.

## Reglas

- **Solo lectura.** Nunca se envia `04` (borrar codigos) ni se escribe en la ECU:
  solo configuracion del propio adaptador y PIDs de lectura.
- El enlace **no toca el camino del SOS**: si el adaptador falla, la deteccion por
  movil y el GPS siguen igual y el fallo se queda en la tarjeta.
- Con el monitor en marcha, en perfil COCHE la velocidad del OBDII (fresca, 2 s)
  manda sobre la del GPS para la pantalla y el aviso. En la 1.73s esa velocidad
  pasa a alimentar la regla de choque del coche.

## Limites conocidos

- **El airbag no es un PID estandar.** Se puede leer la velocidad, las rpm, la
  temperatura, la tension y los codigos de averia; el despliegue del airbag vive
  en la centralita SRS (codigos o PIDs de cada marca) y se aborda en la 1.74s con
  lectura de codigos y buscador de senal.
- Adaptadores clonados: si no negocian `ATSP0` o no contestan al PID de velocidad,
  la tarjeta lo dice y no se rompe nada.
- WiFi y BLE quedan para mas adelante; primero el SPP que ya funciona en cualquier
  ELM327 barato.
