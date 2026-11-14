# Respuesta a la revisión crítica de 1.80s

Contrapunto técnico al documento `Revision_critica_Segu-Luky_1.80s.docx`, comprobado en el
código real del repositorio (no solo en la descripción). Cada hallazgo se clasifica como
**confirmado y corregido**, **confirmado y pendiente de decisión** o **matizado**.

Estado: `main` con **242 pruebas en verde** y `lintDebug` sin errores. Corregidos en este round
el buffer, las ventanas de 5/15 s y los tres cambios acordados con el autor (ancho por
resolución, calidad del giro y periodicidad condicionada). **Los pesos y umbrales de puntuación
no se han cambiado**: lo que cambia es *cuándo* se aplican el ancho y la resta del patrón, no
las cifras.

---

## 1. Buffer de 2.000 muestras: defecto confirmado y corregido

**Confirmado.** `ImpactSampleRecorder` tenía `CAPACITY = 2000` y `WINDOW_MS = 20_000`. El
`snapshot()` filtraba por tiempo, pero la capacidad mandaba: a 148 Hz (frecuencia real medida en
un OPPO CPH2695 pidiendo 100 Hz) 2.000 muestras son **13,5 s**, asi que la ventana de
recuperación (15 s) no cabía y las muestras mas viejas se perdían aunque el filtro temporal
dijera "20 s".

**Corregido** (`ImpactSampleRecorder.kt`):

- Capacidad **4.000** huecos = 20 s hasta **200 Hz** (el doble del techo medido).
- La retención la manda el tiempo: al llenarse se descarta lo mas viejo y `snapshot()` corta en
  `lastAtMs - 20 s`.
- Nuevo `retainedMs()` y `count()` para poder registrarlo.
- **5 pruebas nuevas** (`ImpactSampleRecorderTest`): 50, 100 y 148 Hz conservan al menos 15 s;
  a 200 Hz durante 40 s la ventana no pasa de 20 s y no baja de 19 s; las muestras guardadas son
  las mas recientes; buffer vacío.

---

## 2. Ventanas de 5 y 15 s: defecto confirmado y corregido

**Confirmado.** `DECEL_WINDOW_MS` (5 s) y `RECOVERY_WINDOW_MS` (15 s) estaban declaradas y
**no se usaban**: la secuencia se evaluaba en el golpe y a los 2 s. Llamar "recuperación 0-15 s"
a una lectura de 2 s era incorrecto.

**Corregido** (`SafetyMonitorService.kt`):

- `ImpactSequence.coveredMs(muestras, hitAtMs, ventana)` (función pura, con prueba) dice cuanta
  ventana cubren de verdad las muestras.
- Nuevos hitos diferidos **a los 5 s y a los 15 s**, sobre el **mismo `hitAtMs`**, cancelables:
  si mientras tanto se cuenta otro golpe (`sequenceHitAtMs` cambia), el hito se descarta y no
  mezcla muestras de dos eventos.
- El registro dice la cobertura y si la ventana está completa:

```text
Secuencia a los 2 s: GOLPE DURO (57, giro)
Secuencia a los 5 s (ventana 5 s, completa): GOLPE DURO (47, desaceleracion)
Secuencia a los 15 s (ventana 15 s, completa): BACHE (28, impacto)
```

Sigue siendo **solo registro**: no toca el SOS, ni la cuenta atrás, ni el estado que ve el usuario.

---

## 3. Suma de señales correlacionadas (pico, RMS, cruces, saturación)

**Confirmado como riesgo de diseño, no como fallo.** Hoy se suman linealmente: pico 35/22/12,
RMS +5, cruces +10, saturación +8. Cuatro expresiones de la misma sacudida pueden sumar juntas.

**No se ha cambiado nada** (cambiar pesos sin datos etiquetados es precisamente lo que no hay que
hacer). Propuesta congelada para cuando haya registros etiquetados:

| Familia | Señales | Aportación |
|---|---|---|
| A · intensidad | pico, RMS, saturación | tope conjunto (p. ej. 35) |
| B · dinámica | cruces, ancho con calidad | tope conjunto (p. ej. 15) |
| C · respuesta del vehículo | caída de velocidad, parada, RPM, recuperación | peso alto y aditivo |
| D · rotación | giroscopio, cambio de orientación | auxiliar |

Sobre la saturación (15,9 g): de acuerdo en tratarla como **dato censurado** (el sensor se ha
recortado y la forma real se ha perdido), no como prueba independiente. Pendiente: marcarla en el
motivo y no sumarla cuando el pico ya está por encima del rango del sensor.

---

## 4. Ancho con histéresis a 20-50 Hz: análisis pedido

**Confirmado.** El ancho se mide con entrada por el umbral, salida al 60 % y tolerancia de 20 ms.
La resolución real depende de la frecuencia efectiva:

| Hz efectivos | Intervalo | Muestras en la ventana de 500 ms | Tolerancia 20 ms | Resolución del ancho |
|---|---|---|---|---|
| 20 Hz | 50 ms | hasta 10 | **menor que un intervalo** | 50 ms |
| 25 Hz | 40 ms | hasta 12 | menor que un intervalo | 40 ms |
| 50 Hz | 20 ms | hasta 25 | ≈ 1 intervalo | 20 ms |
| 100 Hz | 10 ms | hasta 50 | ≈ 2 intervalos | 10 ms |
| 148 Hz | 6,8 ms | hasta 74 | ≈ 3 intervalos | 6,8 ms |

Consecuencias reales, no teóricas:

- A 20 Hz, una sola muestra por debajo del umbral cierra el impacto: la tolerancia de 20 ms no
  protege de nada y el ancho queda cuantizado en pasos de 50 ms.
- La misma regla "ancho >= 60 ms" significa 1-2 muestras a 20 Hz y unas 9 a 148 Hz: **no es
  comparable entre moviles**.
- El pico de una muestra con ancho 0 ms no demuestra que el golpe durase cero: solo que no hay
  resolución para medirlo.

**IMPLANTADO (A).** Lo que se ha hecho:

1. Tolerancia proporcional a la resolución: `max(20 ms, 1,5 × intervalo mediano)` → 20 Hz: 75 ms;
   25 Hz: 60 ms; 50 Hz: 30 ms; 100 y 148 Hz: 20 ms.
2. Nuevo `ImpactSequence.widthQuality(...)`: devuelve ancho, muestras por encima del umbral,
   intervalo real, tolerancia aplicada y si el resultado es fiable. El registro lo dice:

```text
Ancho: 190 ms (20 muestras, 100 Hz, tolerancia 20 ms, fiable)
Ancho: 450 ms (10 muestras, 20 Hz, tolerancia 75 ms, NO fiable: no puntua)
```

3. El ancho **solo alimenta al clasificador si es fiable** (>= 50 Hz y al menos 3 muestras por
   encima del umbral). Si no, se registra como diagnóstico y las reglas "ancho >= 60 ms / +15" y
   "pico corto / -6" no se aplican. Antes de esto, el ancho ya se medía sin saber si era medible.

Pruebas nuevas: a 100 Hz el ancho es fiable y a 20 Hz no; la tolerancia crece con el intervalo y
no parte el golpe en un hueco de una muestra; menos de tres muestras por encima no es fiable.

**Cambio de fondo**: `ImpactWidthMeter` (la variante con estado de B3, que solo se usaba en sus
pruebas) **se ha eliminado**. Tenía la misma histéresis con tolerancia fija de 20 ms y dos copias
de la misma regla son una invitación a que se separen. Ahora hay una sola implementación, la pura.

---

## 5. Giro contra confianza de orientación: se separan (B)

**De acuerdo en el fondo:** la **magnitud** del giroscopio en grados/s no necesita conocer la
orientación absoluta del móvil. Antes el +10 del giro exigía `orientationConfidence >= 0,5`, que
sale de la calibración de gravedad: se usaba como puerta de algo que no lo necesitaba.

**IMPLANTADO (B).**

- `ImpactGyroHold.qualityAt(atMs)` mide la calidad del giro por su propia lectura: 1 mientras es
  fresca (< 100 ms), decae en línea hasta 0 al caducar (200 ms) y se queda a la mitad si la
  entrega tiene un hueco (> 150 ms). Esa es la puerta del giro (`gyroConfidence >= 0,5`).
- `orientationConfidence` **ya no bloquea la puntuación**: solo añade el motivo
  "giro sin orientacion calibrada" cuando el giro puntúa sin actitud fiable, y queda para el
  cambio de orientación/postura cuando se implemente.
- Motivos nuevos en el registro: "giro 140 grados/s" (puntúa), "giro 140 grados/s sin calidad"
  (no puntúa, lectura poco fiable), "giro sin orientacion calibrada" (puntúa, actitud sin calibrar).

Pruebas nuevas: la calidad empieza en 1 con lectura fresca, decae a la mitad a medio camino,
es 0 sin lectura o caducada, un hueco de entrega la baja a la mitad y `reset` la deja a 0; y en
la secuencia, giro con confianza puntúa, sin calidad no puntúa y sin orientación calibrada
puntúa igual pero lo deja escrito.

Queda pendiente probar la caducidad de 200 ms con huecos reales de cada móvil.

---

## 6. Periodicidad: contexto benigno condicionado (C)

**De acuerdo.** Antes `ImpactPeriodicity.adjust()` restaba 8 puntos **siempre** que detectaba
patrón (4 golpes, CV <= 0,25, intervalos 1-90 s) y solo protegía el `CHOQUE`. Un accidente con
varios impactos podía parecer "badenes" y bajar de `GOLPE DURO` a `BACHE`.

**IMPLANTADO (C).**

- La resta exige **contexto benigno**: velocidad conocida antes y después, caída menor de
  30 km/h, velocidad final por encima de 10 km/h y sin motor calado. Sin dato de velocidad **no
  se resta nada**.
- Un `CHOQUE` nunca se desmonta: se mantiene y solo se anota el patrón.
- Estadística robusta: **mediana** de los intervalos y **desviación absoluta mediana** (MAD) en
  lugar de media y desviación típica, con umbral MAD/mediana <= 0,25.
- Hacen falta **6 golpes (5 intervalos)**, no 4: con dos o tres golpes el ritmo no es concluyente.
- El registro distingue los dos casos:

```text
Patron repetitivo (6 impactos, mediana 20.0 s, MAD 0.00): sin contexto benigno, no resta (GOLPE DURO)
Patron repetitivo (6 impactos, mediana 20.0 s, MAD 0.00): GOLPE DURO -> BACHE (37)
```

Pruebas nuevas: seis impactos regulares forman patrón y cinco no; sin contexto benigno no resta;
con contexto baja de golpe duro a bache; un choque sigue siendo choque; un intervalo raro suelto
no rompe el patrón (para eso está la MAD) y una cadencia irregular sí lo rompe.

---

## 7. "Sin velocidad no hay CHOQUE": confirmado, y es peor de lo que parece

**Confirmado.** En la secuencia, `CHOQUE` exige motor calado o caída >= 30 km/h terminando parado,
y las dos cosas necesitan lectura de velocidad. **En moto o en coche sin OBD, la secuencia nunca
podrá decir CHOQUE**: como mucho `GOLPE DURO`. Lo mismo pasa en el clasificador puntual.

**No se ha cambiado la etiqueta** (inventar una vía de CHOQUE sin velocidad sería precisamente
"pesos por intuición"). Lo que sí se registra ya es el dato de calidad: "sin dato de velocidad del
coche" cuando hay enlace OBD sin lectura, y `null` cuando no hay OBD.

Propuesta para decidir con datos:

- Tratar la ausencia de velocidad como **desconocida**: el veredicto se queda en `GOLPE DURO` pero
  se marca `datos insuficientes` y se cuenta aparte en las estadísticas (no como "no accidente").
- Vía de confirmación para moto sin OBD, con evidencia física en vez de velocidad: pico sostenido,
  saturación, varios cruces, giro alto **y** inmovilidad posterior medida con el acelerómetro
  (el móvil deja de vibrar y no se mueve). Eso sí es medible sin OBD y es lo que hay que validar
  en carretera antes de tocar nada.

---

## 8. Migración a un único motor de decisión: plan

De acuerdo en el destino y en los plazos. Hoy conviven:

- `ImpactClassifier` (puntual) → es lo que se guarda en el estado y se ve en pantalla.
- `ImpactSequence` + `ImpactPeriodicity` → solo registro.

Plan por fases, sin romper compatibilidad:

1. **Fase 0 (actual):** sombra. Se registran las dos cosas con el mismo `hitAtMs` y los mismos
   datos de calidad.
2. **Fase 1:** la secuencia puede **elevar** (adelantar la cuenta atrás) pero **nunca rebajar** lo
   que el detector validado considera peligroso.
3. **Fase 2:** motor único que consuma características (pico, ancho con calidad, giro, velocidad,
   recuperación, periodicidad) y devuelva veredicto + motivos + confianza + estado de datos, con
   un `eventId`. `ImpactClassifier` queda como referencia de comparación.

---

## 9. Pruebas: qué se puede verificar sin carretera y qué no

Verificable con unitarios (y ya cubierto o preparado):

- Retención del buffer por tiempo a varias frecuencias (hecho, 5 pruebas).
- Cobertura real de cada ventana y que un hito tardío no se mezcle con otro golpe (prueba de
  `coveredMs`; el descarte por `sequenceHitAtMs` es de servicio).
- Puntuación con y sin velocidad, con y sin giro, con y sin confianza.
- Periodicidad: patrón regular, irregular, espaciado, reinicio y que el CHOQUE no se desmonte.
- Ancho: pico corto, sostenido, valle que no fragmenta, saturación, reinicio.

**No verificable sin carretera:** sensibilidad real, falsas alarmas por hora, latencia hasta la
cuenta atrás, comportamiento por posición del móvil y por modelo. Requiere el conjunto etiquetado
del apartado 4 de la revisión (baches, badenes, juntas, frenadas, móvil caído, con y sin OBD).

---

## 10. Sobre "no cambiar pesos por intuición"

Cumplido: **0 pesos y 0 umbrales** modificados (pico, RMS, cruces, saturación, velocidad, calado,
giro siguen con las mismas cifras). Lo que cambia es el **régimen de aplicación**, y todo con
prueba asociada:

- el buffer guarda mas historia (mas datos para el mismo análisis);
- dos evaluaciones diferidas (5 s y 15 s) que **solo escriben en el registro**;
- (A) el ancho solo puntúa si la frecuencia permite medirlo;
- (B) el giro se abre por la calidad de su lectura, no por la calibración de orientación;
- (C) la resta por badenes exige contexto benigno y 6 golpes (antes 4).

Sigue pendiente, por necesitar datos etiquetados: los topes por familia de señales (apartado 3),
la saturación como dato censurado y la vía de confirmación sin OBD (apartado 7).

---

## Resumen para el revisor

| Hallazgo de la revisión | Estado |
|---|---|
| Buffer 2.000 muestras no son 20 s a 148 Hz | **corregido** (4.000 + purga por tiempo, 5 pruebas) |
| Ventanas de 5 s y 15 s sin evaluar | **corregido** (hitos a 5 s y 15 s con cobertura, solo registro) |
| Ancho no resoluble a 20-50 Hz | **corregido (A)**: tolerancia por resolución y ancho que solo puntúa si es fiable; `ImpactWidthMeter` eliminado |
| Confianza de orientación usada como puerta del giro | **corregido (B)**: calidad propia del giro, orientación solo informativa |
| Periodicidad puede rebajar un evento grave | **corregido (C)**: contexto benigno + 6 golpes + mediana/MAD |
| Suma de señales correlacionadas | confirmado como riesgo; topes por familia pendientes de datos etiquetados |
| "Sin velocidad no hay CHOQUE" bloquea a las motos | confirmado; vía de confirmación pendiente de datos de carretera |
| TICK_MS sin uso | aceptado: no se añade temporizador (escrito en `docs/ACTUALIZACIONES-1.80s-para-CHATGPT.md`) |
| Semántica de ejes | aclarado en el código: el eje X lleva la magnitud agregada, no un vector 3D |
| Duplicidad clasificador/secuencia | plan por fases en el apartado 8 |
