# Torque Pro: que se ha sacado en limpio para el enlace OBDII

Analisis estatico de `Torque_Pro v1.10.120.apk` con REA (`inspect_android_package`).
La app **no se ejecuta**: todo lo que sigue son hechos del paquete publicado.

## Identidad del artilugio

- Paquete: `org.prowl.torque`, versionName `1.10.120`, versionCode `110120`.
- `minSdk 16`, `targetSdk 28`.
- 1473 clases y 1008 recursos.

## Permisos que delatan los transportes

```
android.permission.BLUETOOTH
android.permission.BLUETOOTH_ADMIN
android.permission.ACCESS_WIFI_STATE
android.permission.ACCESS_NETWORK_STATE
android.permission.ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION
android.permission.FOREGROUND_SERVICE
android.permission.INTERNET
```

Es decir: Torque soporta **adaptadores por Bluetooth clasico y por WiFi**, y pide
ubicacion porque en Android antiguo el descubrimiento Bluetooth la exige. Eso
confirma el plan de Segu-Luky: empezar por **Bluetooth SPP (ELM327 clasico)** y
dejar WiFi como segundo transporte.

## Lo que ya se sabe del ELM327 (y se comprobara con el adaptador real)

Torque no aporta nada nuevo aqui: el protocolo del ELM327 es publico y estable.
Lo que interesa copiar como idea es su forma de trabajar, no su codigo:

| Paso | Comando | Para que |
|---|---|---|
| Reinicio | `ATZ` | Deja el adaptador en estado conocido; espera ~1 s. |
| Eco | `ATE0` | Sin eco, para no confundir respuestas con comandos. |
| Saltos | `ATL0` | Respuestas en una sola linea. |
| Espacios | `ATS0` | Respuesta compacta (`410Dxx` en vez de `41 0D xx`). |
| Cabeceras | `ATH1` | Necesario para leer varias centralitas y capturar tramas. |
| Protocolo | `ATSP0` | Automatico; `ATSP6` fuerza ISO 15765-4 CAN 11/500. |
| Protocolo real | `ATDPN` | Que ha elegido el adaptador. |
| Bateria | `ATRV` | Tension de la bateria. |
| Velocidad | `010D` | Km/h, un byte (sin factor). |
| RPM | `010C` | Dos bytes, dividir entre 4. |
| Acelerador | `0111` | Un byte, % = x*100/255. |
| Refrigerante | `0105` | Un byte menos 40, en grados. |

Cada respuesta CRUDA acaba en `>`; las utilices llevan prefijo `41 <pid>` y los
errores son `NO DATA`, `?`, `SEARCHING...`, `BUS INIT: ...`, `STOPPED`,
`UNABLE TO CONNECT`. Todo eso es lo que implementa el parser de Segu-Luky
(`ObdParser`), con pruebas unitarias, y lo que se valida contra el coche real.

## Limites de este analisis

- El DEX de Torque esta ofuscado: los nombres de sus clases de transporte no
  aparecen como texto (`elm327`, `iso15765`, `j1850` dan cero coincidencias en el
  DEX), asi que **no se ha reconstruido su secuencia de inicializacion concreta**
  ni sus timeouts. No hace falta: se implementa la secuencia estandar y se ajusta
  con el adaptador del coche.
- No se ha analizado el empaquetado de recursos ni las firmas del APK: da igual
  para nuestro objetivo.
- Nada de Torque se ejecuta ni se copia; solo se usa como referencia de
  comportamiento y de que transportes existen en el mercado.

## Que falta por comprobar en el coche (fase 1.72s)

1. Protocolo que negocia el adaptador concreto (`ATDPN`).
2. Tiempo real por comando y cuantas consultas por segundo aguanta.
3. Si responde a `010C` (RPM) y `0105` (refrigerante) en ese coche.
4. Diferencia entre la velocidad del OBDII y la del cuadro (suele ir bien, pero
   hay coches que la publican redondeada).
