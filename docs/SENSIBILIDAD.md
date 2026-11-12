# Sensibilidad adaptativa

Documento de la deteccion por golpe a partir de la **1.54s**: como se decide el
umbral, por que se adapta a cada movil y como comprobarlo sin grabar nada.

## 1. El problema

El ajuste de sensibilidad es un numero en `g` de aceleracion dinamica (sin
gravedad), de 0,5 a 7 y por defecto 3,5. Ese numero no significa lo mismo en dos
telefonos distintos:

| Diferencia entre moviles | Efecto si se usa el mismo numero |
|---|---|
| Sensor ruidoso (0,2-0,5 g en reposo) | Dispara solo con el movil quieto |
| Sensor que suaviza la aceleracion lineal | Un golpe real llega a 1,5 g y no se detecta |
| Sensor que entrega 50 Hz en vez de 100 Hz | Los picos se miden mas bajos y el ajuste queda sordo |
| Soporte rigido o con goma, postura del telefono | Cambia el traqueteo "normal" del viaje |

Por eso la app mide su propio movil y calcula un **umbral efectivo**:

```
umbral efectivo = max(tu ajuste, piso del movil) x compensacion por frecuencia
```

El ajuste sigue siendo tuyo; el piso y la compensacion los aprende la app.

## 2. La cadena de deteccion

```
sensor de movimiento
   |  (25 / 50 / 100 Hz segun el contexto)
   v
AccidentDetector ---- umbral efectivo ----+
   |                                      |
   | golpe                              |
   v                                     |
candidato de caida (15 s)                |
   |  inclinacion >= 55 grados  4 s      |
   |  o golpe a >= 20 km/h y movil quieto|
   v                                     |
cuenta atras de 30 s                     |
   |                                     |
   v                                     |
SOS                                      |
                                         |
AdaptiveSensitivity ---------------------+
   aprende: ruido, nivel de marcha y frecuencia real
```

## 3. Los dos perfiles

| Perfil | Que mide | Cuando manda |
|---|---|---|
| **Ventana** (corto) | lo que esta pasando ahora: p50, p99 y ruido de los ultimos 1-3 minutos | en cuanto hay 10 s y 100 muestras |
| **Aprendido** (largo) | lo mismo pero fundido entre viajes, con el uso acumulado | a partir de 5 minutos acumulados |

El perfil aprendido se fusiona con `alpha = min(0,35, ventana / (uso + ventana))`:
las primeras horas pesan mas y luego se estabiliza, pero sigue corrigiendo si
cambias de soporte o de moto.

**Un golpe no entra en el promedio.** Solo se promedian las muestras por debajo
del umbral vigente; si entrara el propio golpe, subiria el umbral que lo detecta.

## 4. Formulas y constantes

`SensitivityMath` (en `AdaptiveSensitivity.kt`):

| Constante | Valor | Motivo |
|---|---|---|
| `NOISE_MARGIN` | 5 | suelo = 5 x ruido en reposo |
| `NOISE_MIN_G` | 0,30 g | por debajo de esto cualquier sensor es silencioso |
| `AMBIENT_FACTOR` | 1,30 | margen sobre el p99 de marcha |
| `REFERENCE_HZ` | 100 | frecuencia de referencia de los picos |
| `RATE_FACTOR_MIN/MAX` | 0,70 / 1,00 | compensacion acotada al 30 % |
| `PROFILE_MAX_RATIO` | 1,60 | tope: el perfil no puede pedir mas de 1,6 x tu ajuste |
| `MAX_LIFT_G` | +2,0 g | tope absoluto de subida |
| `PROFILE_MIN_USAGE_MS` | 5 min | hasta aqui manda solo la ventana corta |
| `MIN_DATA_MS` / `MIN_DATA_SAMPLES` | 10 s / 100 | sin datos no se toca el umbral |
| `DWELL_MIN_MS` | 20 s | permanencia minima antes de cambiar de frecuencia |

Ejemplos:

- Movil limpio (0,02 g de ruido, 100 Hz) con ajuste 3,5 g → efectivo **3,5 g**.
- Movil ruidoso (0,45 g de ruido) con ajuste 1,5 g → suelo 2,25 g → efectivo **2,25 g**.
- Movil que entrega 50 Hz con ajuste 3,5 g → factor 0,71 → efectivo **2,5 g**
  (mas sensible, para compensar que mide picos mas bajos).

## 5. Ventana ADAPT. (automatica o fija)

- **AUTO** (por defecto): empieza en 2 min y compara el p99 de las dos ultimas
  mitades. Si cambia un 30 % o mas, acorta a 1 min; si lleva dos ventanas
  cambiando un 10 % o menos, alarga a 3 min. Un escalon por ventana como maximo.
- **1 / 2 / 3 min**: se fija a mano y la logica automatica se desactiva.

Solo afecta al perfil corto: el aprendido es independiente, asi que como mucho
cambia la velocidad de reaccion, no el ajuste de fondo.

## 6. Muestreo

| Nivel | Frecuencia | Cuando |
|---|---|---|
| `QUIET` | 25 Hz | p50 < 0,08 g y p99 < 0,60 g (moto parada, movil apoyado) |
| `CRUISE` | 50 Hz | el resto del tiempo |
| `DEMANDING` | 100 Hz | p99 >= 1,60 g, o tras golpe / candidato / cuenta atras / validacion |

La frecuencia se mide de verdad (muestras por segundo) y es lo que se aplica a la
compensacion. Cambiar de nivel re-registra **solo** el sensor de movimiento: la
actitud de inclinacion aprendida no se pierde.

## 7. PRUEBA DE GOLPE

En la tarjeta del monitor, bajo el deslizador: 15 s para sacudir el movil como
pedirias ayuda. Al terminar dice:

- `habria disparado (pico 4,2 g / umbral 3,9 g)`, o
- `no llegaria (pico 2,1 g / umbral 3,9 g) - baja el ajuste a 2,5 g`.

No graba CSV, no dispara la cuenta atras, no avisa a nadie. Se bloquea con el
monitor en marcha (el monitor es dueño de los sensores).

## 8. La grabacion ahora es diagnostico

La grabacion de sensores (CSV + ZIP) sigue existiendo en **Configuracion ->
DIAGNOSTICO**, pero ya no hace falta para ajustar: sirve para adjuntar datos a
una incidencia, comparar dos moviles con el mismo criterio
(`Instrucciones/analizar_calibracion.py`) y conservar evidencia para elegir los
valores por defecto de futuras versiones.

El resumen del ZIP incluye ahora: sensibilidad, umbral efectivo, ventana,
ruido, p99 de marcha, frecuencia medida, uso acumulado y pico del ultimo viaje.

## 9. Como comprobarlo

1. Con el monitor detenido, pulsa **PROBAR GOLPE** y sacude el movil: debe decir
   "habria disparado". Si no, baja el ajuste y repite.
2. Arranca el monitor y deja el movil quieto: en el log debe aparecer
   `Muestreo del sensor: 25 Hz (quieto)` y el efectivo igual al ajuste.
3. Circula por un camino roto: `100 Hz (demanda)` y el efectivo sube (por
   ejemplo 3,5 -> 4,1 g). Al volver al llano baja.
4. Tras un viaje de mas de un minuto, la linea bajo el deslizador muestra
   `PICO ... SUG ...` y `USAR` aplica el valor recomendado.
5. En Configuracion -> DIAGNOSTICO se ve el aprendizaje acumulado; el boton
   **REINICIAR APRENDIZAJE** lo borra (util al cambiar de soporte o de moto).

## 10. Limites

- La normalizacion corrige **escala** (ruido, nivel de marcha y densidad de
  muestreo), no la fisica del soporte ni la calidad del sensor.
- La compensacion por frecuencia es una estimacion (`sqrt(hz/100)` acotada), no
  una igualdad exacta entre moviles.
- Con la moto parada el sensor va a 25 Hz; un alcance por detras se sigue viendo
  porque el vehiculo acelera durante decenas de milisegundos.
- Una caida real es una senal de 6-8 g: muy por encima de los topes de subida
  (+2 g), asi que el aprendizaje no puede taparla.
