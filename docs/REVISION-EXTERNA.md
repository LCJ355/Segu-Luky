# Revision externa de Segu-Luky (para otra IA)

Documento autocontenido para pedir una critica. No hace falta el repositorio.

## La pregunta

> **¿Que mejorarias tu?**

Contexto para responder: app Android (Kotlin + Jetpack Compose, minSdk 29) de seguridad
para moto y coche. Detecta golpes con el acelerometro del movil y, en coche, con la
velocidad real leida por OBDII. Si el golpe se confirma, arranca una cuenta atras
cancelable y despues llama y manda mensajes a los contactos de emergencia.

El objetivo central es **distinguir un bache grande de un accidente real**, con las
menos llamas falsas posibles y sin perderse un accidente.

## 1. Como se clasifica hoy un golpe

Con cada golpe que supera el nivel configurado se calcula una puntuacion 0-100 y un
veredicto: `RUIDO`, `BACHE`, `GOLPE DURO` o `CHOQUE`.

| Senal | Puntos | Razon |
|---|---|---|
| Pico >= 1,5x el nivel | +35 | golpe claramente por encima |
| Pico >= 1,2x el nivel | +22 | por encima |
| Pico >= 1,0x | +12 | justo en el nivel |
| Ancho del impacto >= 60 ms | +15 | un accidente suele ser mas ancho |
| Ancho < 25 ms | -6 | un bache es un pico muy corto |
| Velocidad -30 km/h y queda <= 10 km/h | +40 | firma de colision |
| Velocidad -30 km/h | +25 | caida fuerte |
| Velocidad -10 km/h | +10 | caida leve |
| Velocidad intacta | -6 | sigues rodando |
| Motor calado (RPM 0 con velocidad previa >= 20 km/h) | +20 | corrobora el choque |
| Postura cambiada >= 15 grados | +15 | el movil no vuelve a su sitio |

Veredicto:

- `CHOQUE` si (caida >= 30 km/h terminando <= 10 km/h) o (motor calado) **y** la
  puntuacion llega a 45.
- `GOLPE DURO` con 40 o mas.
- `BACHE` con 18 o mas.
- `RUIDO` por debajo de 18.

Detalles de implementacion:

- Todo es opcional menos el pico y el nivel: si falta la velocidad, el veredicto como
  mucho llega a `GOLPE DURO` (no se inventa un choque).
- A los **2 segundos** se vuelve a evaluar con la velocidad de ese momento; ahi es
  donde de verdad aparece la caida de velocidad.
- **De momento solo mide**: el veredicto va al registro y a la pantalla, pero el SOS
  sigue disparandose con la regla anterior. La idea es ajustar umbrales con datos
  reales antes de conectar el disparo.
- Pesos y umbrales salen de fisica basica, **sin validar con datos de campo todavia**.

Codigo (Kotlin, sin dependencias de Android):

```kotlin
enum class ImpactVerdict { RUIDO, BACHE, GOLPE_DURO, CHOQUE }

data class ImpactSignals(
    val peakG: Float, val thresholdG: Float,
    val jerkGPerSecond: Float = 0f, val widthMs: Long = 0L,
    val speedBeforeKmh: Float? = null, val speedAfterKmh: Float? = null,
    val rpmAfter: Int? = null, val attitudeChangeDegrees: Float? = null,
    val hasVehicleLink: Boolean = false
)

object ImpactClassifier {
    const val WIDE_IMPACT_MS = 60L
    const val SHORT_SPIKE_MS = 25L
    const val SEVERE_SPEED_DROP_KMH = 30f
    const val STOPPED_KMH = 10f
    const val STALL_SPEED_KMH = 20f
    const val ATTITUDE_CHANGE_DEGREES = 15f

    fun assess(s: ImpactSignals): ImpactAssessment {
        val reason = ArrayList<String>()
        var score = 0
        val ratio = s.peakG / s.thresholdG.coerceAtLeast(0.1f)
        when {
            ratio >= 1.5f -> { score += 35; reason.add("pico grande") }
            ratio >= 1.2f -> { score += 22; reason.add("pico alto") }
            else -> { score += 12; reason.add("pico justo") }
        }
        if (s.widthMs >= WIDE_IMPACT_MS) { score += 15; reason.add("impacto ancho") }
        else if (s.widthMs in 1 until SHORT_SPIKE_MS) { score -= 6; reason.add("pico corto") }

        val drop = s.speedDropKmh
        if (drop != null) {
            when {
                drop >= SEVERE_SPEED_DROP_KMH && (s.speedAfterKmh ?: Float.MAX_VALUE) <= STOPPED_KMH ->
                    { score += 40; reason.add("velocidad abajo, casi parado") }
                drop >= SEVERE_SPEED_DROP_KMH -> { score += 25; reason.add("velocidad abajo") }
                drop >= 10f -> { score += 10; reason.add("velocidad baja") }
                else -> { score -= 6; reason.add("velocidad intacta") }
            }
        }
        val stalled = s.rpmAfter == 0 && (s.speedBeforeKmh ?: 0f) >= STALL_SPEED_KMH
        if (stalled) { score += 20; reason.add("motor calado") }
        s.attitudeChangeDegrees?.let { if (it >= ATTITUDE_CHANGE_DEGREES) { score += 15; reason.add("postura") } }

        val total = score.coerceIn(0, 100)
        val verdict = when {
            drop != null && drop >= SEVERE_SPEED_DROP_KMH &&
                (s.speedAfterKmh ?: Float.MAX_VALUE) <= STOPPED_KMH && total >= 45 -> ImpactVerdict.CHOQUE
            stalled && total >= 45 -> ImpactVerdict.CHOQUE
            total >= 40 -> ImpactVerdict.GOLPE_DURO
            total >= 18 -> ImpactVerdict.BACHE
            else -> ImpactVerdict.RUIDO
        }
        return ImpactAssessment(verdict, total, reason)
    }
}
```

## 2. De donde sale la velocidad del coche

- Adaptador **ELM327 por Bluetooth clasico (SPP)**, UUID `00001101-0000-1000-8000-00805F9B34FB`.
- Inicializacion `ATZ`, `ATE0`, `ATL0`, `ATS0`, `ATH1`, `ATSP0`; se pregunta el protocolo con
  `ATDPN` (da ISO 15765-4 CAN 11/500, J1850, ISO 9141, KWP...).
- Ronda: `010D` (velocidad) cada 200 ms; `010C` (RPM), `0111` (acelerador), `0105`
  (refrigerante), `0101` (codigos) y `ATRV` (tension) cada segundo.
- **Solo lectura**: nunca se envia `04` (borrar codigos) ni se escribe en la centralita.
- Con el monitor en marcha y perfil COCHE, la velocidad del adaptador (menos de 2 s de
  antiguedad) manda sobre la del GPS; sin adaptador se usa el GPS.
- Si el adaptador falla, la deteccion por movil sigue igual: el enlace nunca esta en el
  camino del SOS.
- El estado del **airbag no es un PID estandar**: se puede leer velocidad, RPM, temperatura,
  tension y codigos de averia; el despliegue del airbag vive en la centralita SRS y varia por
  marca (codigos o PIDs de fabricante).

## 3. Perfiles de vehiculo

`MOVIL` / `COCHE` / `MOTO`, cada uno con sus ajustes (nivel de golpe, ventana de aprendizaje,
voz, caida/orientacion) y sus soportes:

- MOVIL: bolsillo, mochila, personalizado.
- COCHE: bolsillo, guantera, personalizado. Sin caida/orientacion (un coche no vuelca),
  nivel recomendado 4,5 g.
- MOTO: manillar, deposito, bolsillo, mochila, personalizado. Modo moto y voz por defecto.

## 4. Como aprende y que guarda

- El aprendizaje de ruido y traqueteo se guarda por soporte en preferencias: 4 numeros
  (ruido, p99, Hz, uso). No crece con el uso.
- Las grabaciones de calibracion se parten en bloques de 15 minutos: se conserva solo el de
  mayor puntuacion (bandas de velocidad, filas con velocidad valida, Hz reales, actividad).
  Tope de seguridad de 4 horas por sesion.

## 5. Lo que me gustaria que criticaras

1. Los **umbrales y pesos** del clasificador: que cambiarias y por que.
2. Que senales **faltan** con lo que ya hay en un movil (acelerometro, giroscopio, gravedad,
   GPS) y con velocidad por OBDII: por ejemplo periodicidad de baches, eje lateral, rebote
   tras el impacto, perfil temporal del pico.
3. Como medir el **ancho del impacto** con muestras de 20 a 100 Hz sin que dependa del azar.
4. Si tiene sentido reevaluar a los 2 s o seria mejor una ventana deslizante.
5. Cuando conectar el veredicto al **disparo** del SOS sin poner en riesgo a un accidentado
   (que regla propondrias para pasar de medir a decidir).
6. Cualquier fallo de diseno que veas en el enlace OBDII o en los perfiles de vehiculo.

## 6. Datos de contexto

- 200 pruebas unitarias en verde, lint sin errores, APK firmado y publicado (version 1.78s).
- Proyecto: `github.com/LCJ355/Segu-Luky` (publico, sin datos personales en el codigo).
- Falta la validacion en carretera: no hay todavia un choque real ni una bateria de baches
  medidos con la app.
