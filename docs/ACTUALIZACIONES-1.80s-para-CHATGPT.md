# Segu-Luky: actualizaciones 1.79s y 1.80s (documento para revisión externa)

Documento autocontenido: no hace falta el repositorio para leerlo ni para criticarlo.
Describe **qué se ha cambiado**, **por qué** y **qué queda abierto**, en la app Android
Segu-Luky (Kotlin + Jetpack Compose, minSdk 29, paquete `com.intercom.segluky`).

La pregunta al revisor está al final, en el apartado 10.

---

## 1. Contexto de la app

App de seguridad para moto y coche. Detecta golpes con el acelerometro del movil (y, en coche,
con la velocidad real leida por OBDII). Si el golpe se confirma, arranca una cuenta atras
cancelable y despues avisa a los contactos de emergencia (WhatsApp, Telegram, SMS y llamadas en
orden).

El objetivo central es **distinguir un bache grande de un accidente real**, con las menos llamas
falsas posibles y sin perderse un accidente.

En la version 1.78s el clasificador era **puntual**: con el pico del golpe se calculaba una
puntuacion 0-100 y un veredicto (`RUIDO`, `BACHE`, `GOLPE DURO`, `CHOQUE`). Pesos de aquella
version (`ImpactClassifier.kt`, sin cambios en su formula):

| Senal | Puntos |
|---|---|
| Pico >= 1,5x el nivel | +35 |
| Pico >= 1,2x el nivel | +22 |
| Pico >= 1,0x | +12 |
| Ancho del impacto >= 60 ms | +15 |
| Ancho > 0 y < 25 ms | -6 |
| Velocidad -30 km/h y queda <= 10 km/h | +40 |
| Velocidad -30 km/h | +25 |
| Velocidad -10 km/h | +10 |
| Velocidad intacta | -6 |
| Jerk >= 60 g/s **y sin dato de velocidad** | +10 |
| Motor calado (RPM 0 con velocidad previa >= 20 km/h) | +20 |
| Postura cambiada >= 15 grados | +15 |

Niveles: `CHOQUE` con 45 o mas si hay caida severa terminando parado o motor calado;
`GOLPE DURO` con 40; `BACHE` con 18; si no, `RUIDO`.

Una revision externa (la propuesta que originó este trabajo) critico, entre otras cosas, que el
ancho no se medía con criterio, que el giroscopio no se usaba, que sumar todas las senales infla
la puntuacion y que faltaba mirar la secuencia completa y la periodicidad de los badenes.

---

## 2. Que se ha hecho, en una tabla

| Lote | Version | Contenido | Estado |
|---|---|---|---|
| **B1** | 1.79s (versionCode 66) | El jerk deja de estar muerto y no se suma dos veces | publicado |
| **B2** | 1.80s (versionCode 67) | Secuencia del golpe con ventanas + buffer circular de 20 s | publicado |
| **B3** | 1.80s | Ancho con histeresis, giroscopio real, confianza de orientacion, Hz efectivos | publicado |
| **B4** | 1.80s | Ritmo de badenes: memoria de impactos y coeficiente de variacion | publicado |

**Importante:** el disparo del SOS **no ha cambiado**. Todo esto mide y registra; el veredicto
se guarda en el estado de la app y en el registro (`Registro`), pero quien decide avisar sigue
siendo la deteccion de siempre (pico sobre el nivel + validacion). El motivo es no romper una
funcion de seguridad con una heuristica que aun no esta validada en carretera.

---

## 3. B1 · el jerk

Problema: `jerkGPerSecond` se calculaba y no puntuaba nada. La critica decia tambien que sumar
pico + RMS + jerk + duracion infla la puntuacion.

Solucion: el jerk aporta **+10 solo cuando no hay dato de velocidad** (es decir, cuando el
acelerometro es la unica evidencia). Con velocidad conocida no suma nada, para no contar dos
veces la misma sacudida.

```kotlin
if (signals.speedDropKmh == null && signals.jerkGPerSecond >= 60f) {
    score += 10
    reasons.add("jerk alto sin dato de velocidad")
}
```

---

## 4. B2 · la secuencia en vez del pico

Nuevo modulo puro (sin Android) `ImpactSequence.kt` y un buffer `ImpactSampleRecorder.kt`.

- Cada muestra es `ImpactSample(atMs, ax, ay, az, gyroDegPerSec)`; el acelerometro entrega la
  aceleracion dinamica agregada en el eje X y el giro real en su campo.
- Buffer circular **fijo**: 2.000 muestras / 20 s (unos pocos KB, nunca crece).
- Ventanas: impacto `0-500 ms`, giro `0-2 s`, desaceleracion `0-5 s`, recuperacion `0-15 s`.
  La constante `TICK_MS = 150 ms` (ritmo de reevaluacion previsto) **todavia no se usa**: hoy la
  secuencia se evalua en el golpe y a los 2 s (ver B3). Es el mismo tipo de deuda que tenia el
  jerk en 1.78s, reconocida a proposito para no añadir un temporizador mas sin datos reales.
- Senales nuevas frente al clasificador puntual: **numero de cruces** del umbral (rebotes),
  **RMS** de la ventana de impacto, **saturacion** del sensor (>= 15,9 g) y **giro** en grados/s.

Puntuacion de la secuencia (`ImpactSequence.evaluate`, hoy solo para registro):

| Senal | Puntos |
|---|---|
| Pico >= 1,5x el nivel | +35 |
| Pico >= 1,2x | +22 |
| Pico justo | +12 |
| 2 o mas cruces del umbral | +10 |
| RMS > nivel | +5 |
| Sensor saturado | +8 |
| Giro >= 90 grados/s **con confianza >= 0,5** | +10 |
| Velocidad -30 km/h y parado | +40 |
| Velocidad -30 km/h | +25 |
| Velocidad -10 km/h | +10 |
| Velocidad intacta | -6 |
| Motor calado | +20 |

Niveles: `CHOQUE` con 45 o mas si hay motor calado o caida severa terminando parado;
`GOLPE DURO` con 40; `BACHE` con 18; si no, `RUIDO`. **Sin dato de velocidad no hay CHOQUE
posible**: como mucho `GOLPE DURO`.

---

## 5. B3 · ancho real, giroscopio y confianza

### 5.1 Ancho con histeresis

Dos piezas: `ImpactWidthMeter` (clase con estado) y `ImpactSequence.widthMs` (funcion pura sobre
una lista de muestras). Las dos aplican la misma idea: entrar con el umbral del golpe, mantenerse
con el **60 %** de ese umbral y no cerrar hasta que pasen **20 ms** por debajo. Asi un valle de
pocos milisegundos no parte un mismo golpe en dos.

Devuelve ancho en ms, numero de picos, muestras y saturacion. Un pico de una sola muestra mide
**0 ms**: no se inventa duracion. El ancho medido ya llega al clasificador (`widthMs` en
`ImpactSignals`), asi que la regla "ancho >= 60 ms -> +15" y "pico corto -> -6" empiezan a
actuar de verdad (antes el ancho era siempre 0).

### 5.2 Giroscopio real

Antes el giro llegaba siempre 0 al buffer, por eso la rotacion nunca podia puntuar.

- `ImpactGyroHold` (nuevo, puro): guarda la ultima lectura del giroscopio y **caduca a los
  200 ms**. Cada muestra de movimiento se queda con el giro vigente; si esta caducado, giro 0.
  Razon: atribuir a un golpe una rotacion vieja convertiria un bache en un accidente, que es peor
  que no puntuar el giro.
- Se resetea cuando el giroscopio se deja de escuchar (solo se escucha durante la validacion) y
  al desregistrar sensores, para que no se cuele la lectura del golpe anterior.
- La magnitud es `sqrt(x^2 + y^2 + z^2)` en grados/s.

### 5.3 Confianza de orientacion

```kotlin
fun orientationConfidence(calibrated: Boolean, accuracyReliable: Boolean = true): Float =
    when {
        !calibrated -> 0.2f            // actitud aun no calibrada: no puntua
        accuracyReliable -> 1f         // calibrada y precisa
        else -> 0.4f                   // calibrada pero gravedad no fiable: no puntua
    }
```

El umbral para puntuar rotacion es 0,5, asi que solo con actitud calibrada y fiable se suman los
+10 del giro. La calibracion necesita unos 2 s de gravedad, de ahi el punto siguiente.

### 5.4 Reevaluacion a los 2 s

El giroscopio solo se escucha durante la validacion, asi que la lectura tomada justo en el golpe
casi nunca tiene giro. Por eso, ademas del veredicto inmediato (que se registra igual), **la
secuencia se reevalua a los 2 s** con el mismo `hitAtMs`: ahi la ventana de giro (0-2 s) ya tiene
datos reales. Se registran las dos lineas:

```text
Secuencia: GOLPE DURO (47, impacto) - giro conf 0.2 - 148 Hz reales (pedidos 100 Hz)
Secuencia a los 2 s: GOLPE DURO (57, giro)
```

### 5.5 Frecuencia efectiva frente a pedida

El aprendizaje de muestreo pide 25, 50 o 100 Hz segun la demanda. El registro anade los Hz que el
movil entrega de verdad, para saber si lo pedido se cumple:

```text
Secuencia: ... - 148 Hz reales (pedidos 100 Hz)
```

---

## 6. B4 · ritmo de badenes

`ImpactPeriodicity` (nuevo, puro): memoria de los **ultimos 10 golpes** con su instante.

- Intervalos entre golpes consecutivos y **coeficiente de variacion** (desviacion tipica / media).
- Hay "patron repetitivo" con **4 golpes o mas**, todos los intervalos entre **1 s y 90 s** y
  **CV <= 0,25**.
- Regla asimetrica: un patron repetido **resta 8 puntos** a un golpe duro (puede bajarlo de
  `GOLPE DURO` a `BACHE`), pero **un CHOQUE nunca se desmonta**; solo se anota el patron en los
  motivos.
- Dos golpes en el mismo instante o fuera de orden cuentan como uno.

Filtro aplicado (para el revisor): ningun filtro de suavizado nuevo; el unico "filtro" es la
histeresis del ancho (60 % del umbral y 20 ms de tolerancia) y la caducidad de 200 ms del giro.

---

## 7. Que se guarda y que se muestra

- El veredicto del **clasificador puntual** sigue siendo el que se guarda en el estado de la app
  (`impactAssessment`) y el que se ve en pantalla.
- La **secuencia** (B2/B3) y el **ritmo de badenes** (B4) se escriben solo en el **registro**.
- Nada de esto escribe por muestra de sensor: solo al contar un golpe (y a los 2 s), asi que no
  hay impacto en bateria por escrituras.

---

## 8. Archivos nuevos y tocados en la 1.80s

| Archivo | Que es |
|---|---|
| `ImpactSequence.kt` (nuevo) | Ventanas, puntuacion de la secuencia, `widthMs` con histeresis, `orientationConfidence` |
| `ImpactSampleRecorder.kt` (nuevo) | Buffer circular de 20 s (2.000 muestras) |
| `ImpactWidthMeter.kt` (nuevo) | Medidor con estado: ancho, picos, muestras, saturacion |
| `ImpactGyroHold.kt` (nuevo) | Ultimo giro vigente (caduca a los 200 ms) |
| `ImpactPeriodicity.kt` (nuevo) | Memoria de 10 impactos, intervalos, CV y ajuste del veredicto |
| `SafetyMonitorService.kt` | Alimenta el buffer, mide el ancho, aplica confianza, reevalua a los 2 s, registra Hz y ritmo |
| `ImpactClassifier.kt` | Sin cambios en la formula (solo recibe ya el ancho real) |

---

## 9. Verificacion hecha

- **230 pruebas unitarias, 0 fallos** (19 nuevas desde 1.79s: 6 de giro y confianza, 3 de
  secuencia, 10 de periodicidad). `lintDebug` sin errores.
- Casos cubiertos por las pruebas nuevas: bache corto sin caida de velocidad se queda en BACHE;
  choque con caida + motor calado sube a CHOQUE; sin velocidad no se inventa CHOQUE; giro
  fuerte sin caida no llega a CHOQUE; **el giro real sube la puntuacion solo con confianza**;
  giro sin confianza no puntua; **sin giroscopio el veredicto no cambia**; giro caducado no se
  atribuye al golpe siguiente; cinco impactos regulares forman patron de badenes; **un choque
  dentro del patron sigue siendo CHOQUE**; intervalos irregulares o muy espaciados no son patron.
- APK firmada y publicada: `Segu-Luky-1.80s.apk`, 5.635.099 bytes,
  SHA-256 `a6df8e1c752181a4f64a2af76e42aafb31b3c8b885adba824e6e9fe3cd3d8383`,
  certificado `E0:E5:A8:FD:...:CF:E2:10:9E`. Instalada y arrancada en un OPPO CPH2695
  (Android 13) sin fallos.

---

## 10. Lo que me gustaria que criticaras

1. **Pesos y umbrales nuevos**: pico 35/22/12, cruces +10, RMS +5, saturacion +8, giro +10 solo
   con >= 90 grados/s y confianza >= 0,5, calado +20. Que quitaria o cambiaria, y por que.
2. **El ancho medido asi** (entrada con el umbral, salida con el 60 %, 20 ms de tolerancia)
   discrimina de verdad un bache de un choque con sensores de 20-50 Hz? Que alternativa probaria.
3. **La confianza de orientacion** (0,2 / 0,4 / 1,0) es demasiado gruesa? Se te ocurre una forma
   mejor de saber si el giro es creible sin pedir calibracion al usuario.
4. **El ritmo de badenes**: CV <= 0,25 con 4 golpes y ventana de 1-90 s. Falla con un tramo de
   badenes de cadencia irregular? Y al contrario, puede un accidente multiple (varios golpes
   seguidos) parecer "badenes" y bajar la sospecha? Como lo blindaria.
5. **Duplicidad**: ahora conviven el clasificador puntual (que decide lo que se guarda) y la
   secuencia (que solo registra), con pesos parecidos. Deberia quedar uno solo? Cual, y con que
   regla de decision.
6. **El paso de medir a decidir**: que condicion propondrias para que la secuencia influya en el
   disparo del SOS sin poner en riesgo a un accidentado.
7. **La deuda declarada**: `TICK_MS` sin usar (la secuencia solo se evalua en el golpe y a los
   2 s). Merece la pena un tick de 150 ms con el coste de bateria que supone, o es mejor quedarse
   con las dos evaluaciones?
8. Cualquier fallo de diseno que veas en los archivos del apartado 8 o en los limites del
   apartado 7.

## 11. Datos de contexto

- Proyecto publico: `github.com/LCJ355/Segu-Luky` (sin datos personales en el codigo).
- Version instalada y publicada: **1.80s (versionCode 67)**, release unica `V-1.80s`.
- **Falta la validacion en carretera**: no hay todavia ni un choque real ni una bateria de
  baches medidos con la app. Todo lo de arriba esta probado en unitarios, no en asfalto.

## 12. Addendum: cambios posteriores a la revision externa (sin publicar aun)

Tras una revision critica de esta misma version (documento `Revision_critica_Segu-Luky_1.80s.docx`,
respuesta en `docs/RESPUESTA-REVISION-1.80s.md`) se corrigieron **cinco cosas**, sin cambiar
ningun peso de puntuacion. Lo que sigue ya no coincide con lo descrito arriba:

1. **Buffer**: 4.000 huecos (20 s a 200 Hz) y retencion por tiempo. Con 2.000 muestras a 148 Hz
   solo se cubrian 13,5 s y la ventana de recuperacion no cabia.
2. **Ventanas**: hitos diferidos a los 5 s y 15 s, sobre el mismo golpe, con la cobertura real
   registrada ("ventana 15 s, completa" / "INCOMPLETA"). Antes solo se evaluaban golpe y 2 s.
3. **Ancho (A)**: la tolerancia crece con el intervalo real (`max(20 ms, 1,5 × intervalo)`) y el
   ancho **solo puntua si es fiable** (>= 50 Hz y 3 muestras por encima del umbral). Se elimino
   `ImpactWidthMeter`, que duplicaba la histeresis con tolerancia fija.
4. **Giro (B)**: la rotacion puntua por la **calidad de su propia lectura** (frescura y huecos),
   no por la calibracion de orientacion. `orientationConfidence` solo anade un motivo.
5. **Periodicidad (C)**: la resta exige **contexto benigno** (velocidad conocida y mantenida)
   y **6 golpes**, y usa mediana y desviacion absoluta mediana en vez de media y tipica. Sin
   velocidad no resta; un CHOQUE nunca se desmonta.

Estado: 242 pruebas en verde, `lintDebug` limpio. Pendiente de publicar como version nueva
(la 1.80s instalada sigue siendo la de antes de este addendum).
