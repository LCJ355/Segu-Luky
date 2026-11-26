# Changelog

## 2.02s (versionCode 89)

### El rumbo gira en el sentido correcto

- **Al girar el sensor a la derecha el rumbo ahora sube**, y al girar a la
  izquierda baja, como una brujula normal. El WT9011 entrega el angulo Z en
  sentido antihorario, asi que se invierte el signo y se normaliza a 0-360
  (`headingFromYaw`).
- Nueva prueba `headingIncreasesClockwise` con los cuatro puntos cardinales y
  los limites 1/359. Total: 300 pruebas.
- Sin cambios en la inclinacion, la temperatura ni el detector: solo cambia la
  representacion del rumbo.

## 2.01s (versionCode 88)

### El rumbo ahora es el del sensor, no el del movil

- **A la derecha de `- M O D O -` va el rumbo magnetico del WT9011** (angulo Z
  en 9 ejes), no la aceleracion Z ni la brujula del telefono. En la prueba real
  marca `215°` y se mantiene estable.
- **El sensor vuelve a 9 ejes.** `Wt9011Config` activa el magnetometro
  (`FF AA 24 00 00`) para que el angulo Z sea un rumbo absoluto; en 6 ejes era
  relativo y derivaba. La inclinacion de vuelco sigue calculandose con la
  gravedad (X e Y), asi que el detector no cambia.
- **La tarjeta de Sensores muestra `RUMBO DEL SENSOR`** junto a la temperatura y
  a X/Y/Z.
- **Se mantiene el enlace sin `MONITOR ON`.** El sensor se conecta al abrir
  Moto y se suelta al salir de la app o cambiar a Coche/Bolsillo.
- **Aviso:** el magnetometro puede desviarse cerca del motor o de masas
  metalicas; es un dato informativo y no interviene en el SOS.
- 299 pruebas y `lintDebug` sin errores.

## 2.00s (versionCode 87)

### Ver la temperatura al conectar, sin MONITOR ON

- **El sensor se enlaza al abrir la pantalla de Moto.** Con el monitor parado,
  la app abre el BLE del WT9011 en cuanto la pantalla principal esta visible y
  ya muestra `TEMP`: sirve para comprobar que el sensor conecta y responde sin
  arrancar la vigilancia.
- **El enlace se suelta al salir de la app** o al cambiar a Coche/Bolsillo; si
  el monitor esta activo se mantiene como siempre. Al volver a Moto se
  reconecta solo.
- **A la derecha de `- M O D O -` ahora va el eje Z del sensor** (`+1.00 g`),
  con el mismo texto grande. Sustituye al rumbo magnetico del movil, que se
  retira junto con `PhoneHeading`.
- **La tarjeta de Sensores muestra X, Y y Z** ademas de la temperatura interna,
  para verificar el montaje y la conexion de un vistazo.
- La temperatura se sigue pidiendo por BLE al registro `0x40` cada 5 s y las
  tramas `0x61` continuan a 50 Hz.
- 299 pruebas y `lintDebug` sin errores.

## 1.99s (versionCode 86)

### Temperatura del sensor y rumbo junto a MODO

- **Temperatura en la pantalla principal.** En `MODO Moto`, a la izquierda del
  boton `- M O D O -`, aparece `TEMP` en grande con la temperatura interna del
  WT9011DCL-BT50 (`25.6 °C` en la prueba real).
- **Se lee por BLE, no por USB.** El sensor no incluye la temperatura en la
  trama `0x61`; la app envia `FF AA 27 40 00` (registro `0x40`) cada 5 s y
  decodifica la respuesta `55 71 40 00 TL TH…`:
  `T = ((TH << 8) | TL) / 100 °C`. No necesita desbloquear el sensor ni baja la
  tasa de 50 Hz de la inclinacion.
- **Rumbo magnetico a la derecha.** La tarjeta `RUMBO` usa el vector de rotacion
  del propio movil (o acelerometro + magnetometro de respaldo), con el giro de
  pantalla y suavizado. Es informativo: no interviene en el detector ni en el
  SOS. Si no hay brujula o no esta disponible, muestra `--`.
- **Solo en MOTO.** Las dos tarjetas laterales solo aparecen con
  `TIPO DE VEHICULO = MOTO`; en Coche y Bolsillo el selector queda como antes.
- **Aclaracion importante:** en el BT50 el USB Type-C es **solo carga**; los
  datos van por BLE. La temperatura es la interna del modulo, no la ambiente.
- 299 pruebas y `lintDebug` sin errores.

## 1.98s (versionCode 85)

### El sensor de vuelco solo existe en MODO MOTO

- **Tarjeta solo en MOTO.** `SENSOR DE VUELCO (WT9011)` desaparece de
  Configuracion > Sensores en MOVIL y COCHE; el sensor no se enlaza ni puede
  abrir una cuenta atras en esos perfiles.
- **Enlace controlado por perfil.** Al arrancar el monitor se conecta solo con
  `TIPO DE VEHICULO` en MOTO; en los demas se desconecta. Cambiar a MOVIL o
  COCHE con el monitor parado corta el BLE inmediatamente.
- **Android 14+:** el tipo `connectedDevice` del servicio solo se declara en
  MOTO, cuando el sensor esta activado y hay permiso de Bluetooth.
- **Instrucciones y textos** aclaran que el sensor es exclusivo de MOTO; el
  punto 4 de `Como usar` y la seccion 4 del TXT lo indican.
- **Limpieza del repositorio:** `APK´s/` conserva la 1.98s y las dos anteriores;
  las 1.82s-1.95s se retiran del arbol (hashes en
  `APK´s/HISTORICO-RETIRADO.txt`), siguen en tags/historial y sus releases
  antiguas ya no se listan. Tambien se retira el diagrama 1.11s.
- 298 pruebas y `lintDebug` sin errores.

## 1.97s (versionCode 84)

### Vuelco por sensor externo WT9011DCL-BT50

- **Sensor inalambrico en la moto.** La app se enlaza por BLE con el
  WT9011DCL-BT50 (se anuncia como `WT901BLE67`) y calcula la inclinacion con
  los ejes **X e Y**. El eje Z solo aporta rumbo y no interviene.
- **Condicion de SOS propia y no excluyente.** Si la inclinacion supera el
  umbral durante **5 s**, se abre la misma cuenta atras de 30 s y se ejecuta el
  protocolo completo con todos los canales configurados. No sustituye a
  CAIDA / ORIENTACION, ni a los golpes del movil, ni a la voz, ni al SOS manual;
  cualquiera de ellos sigue funcionando y nunca se duplica una cuenta atras.
- **Umbral ajustable 50-60 grados**, con **55 grados** por defecto. El aviso
  visual es verde hasta 15 grados antes del umbral, amarillo en esos 15 y rojo
  al alcanzarlo. Se aplica en caliente, sin reiniciar el monitor.
- **Flechas X e Y junto al SOS.** Las dos columnas que ya marcaban los 5 s de
  pulsacion muestran ademas una flecha con la inclinacion de cada eje. El
  relleno verde sigue siendo el progreso de la pulsacion; la flecha se dibuja
  encima y sigue visible durante los 5 s.
- **Calibracion con la moto vertical.** Se aprende la referencia al conectar y
  con el boton `CALIBRAR`; si la moto se mueve durante la ventana, la
  calibracion se reinicia. La calibracion automatica espera si la moto esta a
  mas de 10 grados (por ejemplo, en la pata de cabra); `CALIBRAR` permite
  confirmar un montaje con algo de pendiente. Un pasa-bajos de la gravedad
  filtra la vibracion del motor y los baches.
- **Un vuelco no se suma a un SOS en curso.** Si ya hay una cuenta atras o un
  envio de avisos pendiente, la condicion externa se ignora y queda en el
  registro, para no duplicar llamadas ni mensajes.
- **Etiquetas X/Y.** Debajo de cada columna, la etiqueta indica el eje y los
  grados con el mismo color del semaforo. El texto es mayor y queda centrado
  con su barra, sin tapar el relleno de los 5 s.
- **Tarjeta de diagnostico** en Sensores: `BUSCAR`, `CONECTAR`, `CORTAR`,
  `CALIBRAR`, valores `X` e `Y` en g, inclinacion, umbral, estado del enlace,
  frecuencia real y aviso de que el magnetometro no se usa. Se puede conectar
  con el monitor parado para comprobar los ejes con el sensor en la mano.
- **Configuracion del sensor:** 6 ejes (`FF AA 24 01 00`), 50 Hz y contenido
  aceleracion + giro + angulo (`0x07`), con 130 ms entre ordenes. El enlace se
  mantiene en el servicio en primer plano y se reconecta solo.
- **Android 14+:** se declara `FOREGROUND_SERVICE_CONNECTED_DEVICE` y el tipo
  `connectedDevice` cuando el sensor esta activado y hay permiso de Bluetooth.
- 298 pruebas y `lintDebug` sin errores.

Prueba realizada: sensor real en el OPPO CPH2695 (`WT901BLE67`,
`E8:B6:BE:8C:76:19`): conexion, calibracion con la moto vertical, `X/Y` en vivo,
flechas y etiquetas junto al SOS y disparo de la cuenta atras al inclinar.
Pendiente validar en la moto en marcha: curvas, pata de cabra, baches y consumo
del BLE.

## 1.96s (versionCode 83)

### La ubicacion del SOS llega aunque el GPS despierte tarde

- **El problema.** El aviso solo lleva enlace de mapa si en ese instante hay una
  posicion de menos de cinco minutos. En un tunel, en un parking subterraneo o
  con el GPS recien encendido, los contactos reciben "ubicacion GPS no
  disponible" y no vuelven a saber nada: aunque el movil consiga la posicion
  veinte segundos despues, nadie se la manda.
- **Seguimiento de ubicacion** (`SosLocationFollowUp`). Al enviar el SOS se arma
  un seguimiento de 15 minutos, guardado en disco, y si el movil recupera la
  posicion el enlace se reenvia solo:
  - si el SOS salio **sin** mapa, el primer aviso sale con la primera posicion;
  - si el SOS ya llevaba el mapa, solo se reenvia cuando el motorista se ha
    movido de verdad (50 m) y ha pasado al menos un minuto, para no inundar de
    mensajes a quien ya va hacia el accidente;
  - maximo cinco reenvios por aviso, y se cancela al cancelar el SOS o al
    detener el monitor.
- **Canales.** SMS y bot de Telegram salen solos (funcionan con la pantalla
  bloqueada y sin abrir nada). WhatsApp y el enlace t.me necesitan abrir la app y
  desbloquear la pantalla, asi que no se relanzan solos: el aviso con la
  ubicacion nueva queda en la notificacion para enviarlo con un toque, una sola
  vez por aviso.
- **Persistencia.** El estado se guarda en su propio fichero
  (`segu_sos_follow_up`) para que matar el proceso entre el SOS y el fix no
  pierda el reenvio. Cada reenvio usa un id de cola propio (`<evento>#ubiN`) para
  que las colas durables de Telegram y WhatsApp no lo descarten como repetido.
- **Refactor sin cambio de comportamiento.** `sendSosSms` y los avisos manuales
  se dividen en una parte reutilizable (envio de SMS y notificacion con titulo
  propio) para que el reenvio no duplique codigo.
- **Historial dentro de la app** con la entrada 1.96s.

Pruebas: 275 pruebas unitarias, 0 fallos (`SosLocationFollowUpTest`, 13
casos nuevos). `lintDebug`: 0 errores, 9 avisos (los mismos de la version anterior).

## 1.95s (versionCode 82)

### El detector ya no se queda dormido

- **Cuenta atras huerfana.** Si el proceso moria durante la cuenta atras y la
  hora de fin guardada llegaba ya caducada, el detector se restauraba en fase
  COUNTDOWN sin ningun servicio que cerrase esa cuenta: seguia diciendo
  "monitor activo", ignoraba todos los golpes y no habria avisado de un
  accidente real durante el resto de la sesion. Ahora `activateMonitoring`
  detecta ese caso y llama a `abandonOrphanedCountdown()`, que devuelve el
  detector a MONITORING.
- **Veredicto de impacto viejo.** El diagnostico diferido de 2 s (`assessImpact`)
  podia sobrescribir el veredicto de un impacto nuevo con el del anterior; ahora
  comprueba que el instante evaluado sigue siendo el ultimo registrado y, si no,
  descarta el resultado.
- **Restauracion sin `!!`.** `restoreRuntime` calculaba `immobileSince` con una
  asercion no nula sobre un valor que puede ser nulo; ahora el intervalo
  restaurado se valida contra el inicio de la validacion y, si no encaja, arranca
  en el instante actual.
- **Historial dentro de la app** con la entrada 1.95s.

Pruebas: 262 pruebas unitarias, 0 fallos. `lintDebug`: 0 errores, 9 avisos.
## 1.94s (versionCode 81)

### Configurar Telegram sin copiar numeros de chat

- **Boton `BUSCAR CHATS` en cada contacto.** Dentro del editor del contacto,
  bajo `Numero de chat del bot`, la app pregunta al bot quien le ha escrito
  (`getUpdates`) y muestra esos chats como botones. Al tocar uno, el numero se
  rellena solo y solo queda pulsar `GUARDAR`.
- **Se listan todos los tipos de chat:** privado, grupo y canal, con el nombre y
  el numero a la vista (`-100...` en grupos). El mas reciente aparece primero y
  no se repite ninguno.
- **Avisos claros cuando no hay nada que elegir:** lista vacia (nadie ha escrito
  al bot todavia), clave no valida, o bot con `webhook` configurado, que impide
  leer los mensajes.
- **`ENVIAR PRUEBA` prueba todos los contactos con bot**, no solo el primero, e
  informa de cuantos recibieron el mensaje y cual fallo con su motivo.
- Se mantiene el envio por enlace de Telegram cuando el contacto solo tiene
  usuario `@nombre` (campo de numero vacio).
- Instrucciones dentro de la app (`Como usar`, pasos 7 y 8) reescritas para el
  flujo nuevo, sin mandar al navegador.
- Historial dentro de la app al dia: se anaden las entradas de 1.83s a 1.93s,
  que faltaban.
- 257 pruebas y `lintDebug` sin errores.

## 1.93s (versionCode 80)

### Sensores en oscuro y barra en horizontal

- Las filas de `Sensores` usan fondo y texto adaptados al modo oscuro; ya no
  quedan textos claros sobre fondo claro.
- La fila `SENSOR` separa etiqueta y valor y usa `0.00 g / 2.5 g` para no
  partir la unidad en otra linea.
- La barra superior respeta los laterales seguros del sistema: en horizontal,
  Informacion deja de quedar debajo de los botones del telefono.
- La barra de Sensores/Configuracion tambien respeta esos laterales.
- Verificado en OPPO CPH2695 en vertical y horizontal.
- 253 pruebas y `lintDebug` sin errores.

## 1.92s (versionCode 79)

### Distribucion de la barra superior

- Los botones de configuracion y sensores dejan de estar pegados: ahora tienen
  separacion entre si y quedan mas cerca del titulo.
- El titulo y la version siguen centrados; Informacion permanece a la derecha.
- Los iconos recuperan un area tactil de 48 dp (circulo visible de 38 dp).
- Verificado en el OPPO CPH2695.
- 253 pruebas y `lintDebug` sin errores.

## 1.91s (versionCode 78)

### Barra superior centrada y sin recortes

- El titulo `Segu-Luky` queda centrado en la barra; la version pasa debajo.
- `Informacion` se mueve al extremo derecho; engranaje y sensores quedan a la
  izquierda.
- En pantallas estrechas el titulo y la version reducen su tamano para no
  solaparse con los iconos ni recortar texto.
- Los iconos de la barra usan un area tactil de 40 dp, manteniendo el circulo
  visible de 38 dp.
- Verificado en el OPPO CPH2695 (720x1604, 320 dpi).
- 253 pruebas y `lintDebug` sin errores.

## 1.90s (versionCode 77)

### Velocidades GPS y OBD2 en modo Coche

- En **modo Coche**, la fila del selector `MODO` muestra dos cuadros de
  velocidad: `GPS` a la izquierda y `OBD2` a la derecha.
- Los tres elementos comparten linea y ancho de pantalla; el selector queda en
  el centro y los indicadores a sus lados.
- `GPS` usa la velocidad del telefono; `OBD2` usa la velocidad fresca del
  adaptador (maximo 2 s de antiguedad). Sin dato muestran `-- km/h`.
- Los indicadores solo aparecen en modo Coche; Bolsillo y Moto conservan el
  selector centrado.
- 253 pruebas y `lintDebug` sin errores.

## 1.89s (versionCode 76)

### El tema se cambia desde el titulo

- **Tocar `Segu-Luky` en la barra superior** alterna entre modo claro y oscuro;
  ya no hay una entrada `TEMA VISUAL` dentro de Configuracion.
- El titulo mantiene la version a su lado y ahora tiene area tactil y etiqueta
  accesible para lectores de pantalla.
- La preferencia sigue guardandose en `segu_theme` y se mantiene al reiniciar.
- 253 pruebas y `lintDebug` sin errores.

## 1.88s (versionCode 75)

### Modo claro y modo oscuro

- **Nuevo ajuste `TEMA VISUAL`** en Configuracion: alterna entre el modo claro
  actual y un modo oscuro pensado para reducir consumo en pantallas AMOLED.
- **La eleccion se guarda** en preferencias y se mantiene al cerrar o reiniciar
  la app; el modo claro sigue siendo el valor inicial.
- El modo oscuro ajusta fondo, tarjetas, textos, bordes y barras de estado y
  navegacion, manteniendo visibles los avisos rojos, verdes y naranjas.
- La tarjeta de estado, las barras de nivel y de pulsacion del SOS adaptan su
  contraste al tema activo.
- 253 pruebas y `lintDebug` sin errores.

## 1.87s (versionCode 74)

### Ahorro de bateria sin apagar la deteccion

- **La velocidad no apaga los sensores.** El acelerometro y la deteccion de
  impactos siguen activos siempre; la velocidad solo limita la frecuencia maxima
  de muestreo.
- **En marcha (>15 km/h)** se permite el nivel que pida el aprendizaje, incluido
  100 Hz.
- **Parado, lento o sin velocidad** se limita como maximo a 50 Hz. Sin dato de
  velocidad se usa ese modo intermedio seguro, nunca se desactiva la deteccion.
- **Histeresis:** entra en marcha a >=15 km/h y sale a <=10 km/h, con 5 s de
  permanencia, para que el GPS no cambie de nivel constantemente.
- El giroscopio sigue activandose solo durante candidato/validacion.
- 253 pruebas y `lintDebug` sin errores.

## 1.86s (versionCode 73)

### Enlace de compartir y modo persistente

- **Compartir apunta a la ultima version.** El texto de Informacion > Compartir
  usa `releases/latest`, que GitHub redirige siempre a la release mas reciente.
- **MODO ya no vuelve a Bolsillo al parar.** El boton lee el perfil guardado en
  preferencias en vez del estado temporal del monitor, que se reseteaba al pasar
  a `MONITOR OFF`.
- 248 pruebas y `lintDebug` sin errores.

## 1.85s (versionCode 72)

### Acceso rapido a modo y audio

- **Boton `- M O D O -`.** Debajo del SOS, con el mismo tamano que `MONITOR` y
  texto verde. Rota entre `Bolsillo`, `Moto` y `Coche`, ajustando vehiculo y
  soporte.
- **Cambio de modo fiable.** La recarga de configuracion ya no arranca el
  servicio cuando el monitor esta parado; antes su `onDestroy()` reseteaba el
  estado y el modo volvia al anterior.
- **Nivel de audio seleccionable.** Circulo hueco naranja cuando esta disponible
  y punto verde cuando esta activo. Al iniciar `MONITOR` se activa solo, pidiendo
  microfono si hace falta, y sigue pudiendo desactivarse.
- **Interfaz mas limpia.** Eliminadas la linea redundante `VEHICULO: ...` y la
  linea de `MOVIMIENTO DEL MOVIL`.
- 248 pruebas y `lintDebug` sin errores.

## 1.84s (versionCode 71)

### Ajustes de interfaz y vuelco en coche

- **Barra superior centrada.** Los tres iconos, `Segu-Luky` y la version forman un
  bloque centrado; el nombre baja a 24 sp y el espaciado queda ajustado.
- **Sensores mas limpio.** El encabezado `SENSORES` va centrado y se elimina la
  seccion `GRABACION DE SENSORES (OPCIONAL)` con sus controles de grabacion,
  archivos y exportacion.
- **Vuelco en coche.** `COCHE` vuelve a mostrar `CAIDA / ORIENTACION` y la activa
  por defecto; el texto y el estado (`VOLCADO`) hablan de vuelco, no solo de moto.
- **Bolsillo.** Elegir el soporte `Bolsillo` activa automaticamente
  `CAIDA / ORIENTACION`; los demas soportes no la cambian y el interruptor sigue
  siendo modificable.
- 248 pruebas, `lintDebug` sin errores y `assembleRelease` correcto.

## 1.83s (versionCode 70)

### Revision de fiabilidad del despacho y GPS

- **Telegram ya no se descarta al cerrar el protocolo.** Cuando el bot tenia avisos
  pendientes o estaba reintentando, `finishProtocolState()` cerraba su cola. Ahora
  el despacho pasa por `DispatchStep.TELEGRAM`, espera al outbox con watchdog y
  conserva el resultado de la ronda de llamadas. Tambien se limpia la escalada y
  sus alarmas al cambiar de fase.
- **GPS desactivado sin perder la red.** Al apagarse el proveedor GPS se dejaban de
  pedir tambien las actualizaciones del proveedor de red. Ahora se vuelven a
  solicitar, manteniendo velocidad y ultima posicion cuando sea posible.
- **Limpieza interna:** se eliminan variables/parametros sin uso y avisos de
  compilacion; sin cambios de UI, umbrales ni decision del SOS.
- 247 pruebas, `lintDebug` sin errores, `assembleRelease` y comprobacion de firma
  y datos personales correctas.

## 1.82s (versionCode 69)

### Los cuatro puntos de la revision de 1.81s
Cambia en un caso concreto cuando se abre la validacion: golpe con el movil en el bolsillo y
velocidad de los ultimos 5 segundos. Ahi la frase de la 1.81s ("sigue sin cambiar cuando salta
el SOS") deja de ser exacta. La decision de avisar, los pesos y el numero de golpes contados
siguen igual.

- **Velocidad fresca en el rescate de bolsillo.** La velocidad que decide si el golpe fue en
  marcha usaba `RECENT_LOCATION_WINDOW_MS` (60 s), asi que un dato del semaforo anterior podia
  promover a validacion un golpe con el movil quieto. Ahora exige `POCKET_SPEED_MAX_AGE_MS` = 5 s.
  El mismo criterio (`recentSpeedWithin`) lo usa el jerk, que mantiene sus 10 s.
- **Margen y umbral de movimiento del rescate.** `POCKET_SETTLE_MS` pasa de 2 s a 6 s y el
  movimiento que cancela el rescate sube a `POCKET_MOTION_THRESHOLD_G` = 0,45 g. Con 0,15 g la
  vibracion del motor cancelaba el rescate justo en el caso para el que existe. Girar el aparato
  despues del margen tambien lo cancela (`onGyroscope`).
- **Promocion sin golpe.** `onValidationStarted(startedAt, fromPromotion)` dice si la validacion
  viene de un golpe real o de promover un candidato, y `SafetyMonitorService` solo llama a
  `assessImpact` en el primer caso: ya no se clasifica un golpe que no ha ocurrido.
- **`assessImpact(trigger)`.** El instante del golpe lo pone el detector en vez de la ultima
  muestra del buffer. Al promover, esa muestra podia ser de 15 s antes: la ventana de impacto
  salia vacia e `ImpactPeriodicity` recibia una entrada falsa (patron repetitivo inventado y
  -8 puntos).
- **Cruces reales y fase de giro.** `ImpactSequence` cuenta subidas por encima del umbral y no
  muestras por encima (a 100 Hz cualquier golpe regalaba los +10 de rebote), y la fase "giro" ya
  no depende de una comparacion tautologica: pide rotacion y calidad de giroscopio.
- **`speedAfterKmh = null` en el instante del golpe.** Pasar la misma velocidad como antes y
  despues daba una caida de 0 km/h, restaba siempre 6 puntos por "velocidad intacta" y dejaba
  muerta la rama de jerk del clasificador.
- 247 pruebas (5 nuevas) y `lintDebug` sin errores.
- `Instrucciones/Estado.txt` reparado: rutas de APK partidas por el caracter de `APK´s`, la
  entrada de la 1.53s sin cuerpo, bloques fuera de orden y los huecos 1.65s-1.70s y 1.72s.

## 1.81s (versionCode 68)

### Respuesta a la revision critica de 1.80s (sin tocar los pesos)
Sigue sin cambiar cuando salta el SOS: esto mide con mas criterio, no avisa distinto.

- **Buffer por tiempo.** El buffer de muestras tenia 2.000 huecos: con los 148 Hz reales medidos
  en un OPPO solo cubria 13,5 s, asi que la ventana de recuperacion (15 s) no cabia. Ahora son
  4.000 huecos (20 s a 200 Hz) y la retencion la manda el tiempo, no la capacidad.
- **Ventanas de 5 y 15 s.** `DECEL_WINDOW_MS` y `RECOVERY_WINDOW_MS` estaban declaradas y sin
  usar: la secuencia solo se evaluaba en el golpe y a los 2 s. Ahora hay hitos a los 5 s y a los
  15 s sobre el mismo golpe (se descartan si ha habido otro), y el registro dice la cobertura
  real: "ventana 15 s, completa" o "INCOMPLETA".
- **Ancho por resolucion (A).** La tolerancia de histeresis crece con el intervalo real de
  muestreo (`max(20 ms; 1,5 x intervalo)`), asi que a 20 Hz no basta un hueco de una muestra para
  cerrar el impacto. El ancho **solo puntua si es fiable** (>= 50 Hz y 3 muestras por encima del
  umbral); si no, queda como diagnostico y las reglas de ancho no se aplican. El registro dice
  muestras, Hz, tolerancia y fiabilidad. Se elimina `ImpactWidthMeter`, que duplicaba la misma
  histeresis con tolerancia fija.
- **Calidad del giro (B).** La rotacion ya no depende de que la orientacion este calibrada: la
  magnitud en grados/s se valida con su propia lectura (fresca < 100 ms, caduca a 200 ms, hueco
  de entrega > 150 ms la baja a la mitad). `orientationConfidence` queda para postura y solo
  anade el motivo "giro sin orientacion calibrada".
- **Badenes condicionados (C).** La resta por ritmo repetido exige **contexto benigno** (velocidad
  conocida y mantenida, sin parada ni motor calado) y **6 golpes** en vez de 4, con mediana y
  desviacion absoluta mediana en lugar de media y tipica. Sin dato de velocidad no resta; un
  CHOQUE nunca se desmonta.
- 242 pruebas (6 nuevas) y `lintDebug` sin errores.

## 1.80s (versionCode 67)

### B2, B3 y B4 del plan de mejoras: la secuencia del golpe, el ancho real y los badenes
Sigue sin cambiar cuando salta el SOS: esto mide mejor, no dispara distinto.

- **B2 · secuencia (`ImpactSequence` + `ImpactSampleRecorder`).** Analiza la ventana del golpe
  (forma del impacto, giro, desaceleracion, recuperacion) con picos, RMS, cruces, saturacion del
  sensor y motor calado, en vez de un unico pico. Guarda los ultimos 20 s de muestras en un buffer
  circular de tamano fijo. El veredicto se registra con su fase (`Secuencia: ...`).
- **B3 · ancho con histeresis y giro de verdad.** El ancho del impacto se mide entrando con el
  umbral del golpe y cerrando solo tras 20 ms por debajo del 60 % (un valle corto ya no parte el
  golpe en dos), y llega al clasificador en lugar de quedarse a 0. El **giroscopio real** entra en
  cada muestra (lectura de menos de 200 ms; si esta caducada se usa giro 0) y la rotacion solo
  puntua con `orientationConfidence` alta: 0,2 sin actitud calibrada, 1,0 calibrada y precisa.
  Como el giroscopio solo se escucha durante la validacion, la secuencia se reevalua a los 2 s,
  que es cuando la ventana de giro tiene datos.
- **B4 · ritmo de badenes.** Memoria de los ultimos 10 golpes con sus intervalos y coeficiente de
  variacion: cuatro o mas golpes regulares (CV <= 0,25 y entre 1 s y 90 s) restan 8 puntos a un
  golpe duro, pero **un choque nunca se desmonta** por mucho que el ritmo parezca de badenes.
- El registro de la secuencia anade tambien la **frecuencia efectiva frente a la pedida**
  (`Secuencia: ... 148 Hz reales (pedidos 100 Hz)`), para saber si el movil entrega lo que se pide.
- 230 pruebas (19 nuevas desde 1.79s), `lintDebug` sin errores.

## 1.79s (versionCode 66)

### B1 del plan de mejoras: el jerk deja de estar muerto y no infla el score
- `jerkGPerSecond` estaba declarado y no puntuaba. Ahora aporta **+10 solo cuando no hay dato de
  velocidad**, es decir cuando el acelerometro es la unica evidencia; con velocidad conocida no suma
  nada, para no contar dos veces la misma sacudida (crítica de la revision externa).
- Nueva prueba: el jerk sube el score sin velocidad y lo deja igual con velocidad.

## 1.78s (versionCode 65)

### Soportes del coche: guantera
- En COCHE el soporte es ahora **Sin definir / Bolsillo / Guantera / Personalizado**. El soporte
  generico "Coche" pasa a llamarse **Guantera**, que es donde de verdad se deja el telefono en un
  coche. Al entrar en COCHE desde otro soporte se aplica Guantera.
- 200 pruebas siguen en verde (las de soportes se adaptan a Guantera).

## 1.77s (versionCode 64)

### El soporte entra en el vehiculo corregido
- Al cambiar a COCHE/MOTO, la tarjeta SOPORTE DEL MOVIL estaba filtrando bien las opciones pero segia
  mostrando el soporte anterior (por ejemplo "Bolsillo" en coche) hasta volver al indice. Ahora se
  relee al cambiar de vehiculo y se ve el que se ha aplicado.

## 1.76s (versionCode 63)

### El soporte del movil depende del vehiculo
- Cada perfil ofrece solo los soportes que tienen sentido, para no elegir combinaciones imposibles:
  - MOVIL: sin definir, bolsillo, mochila, personalizado.
  - COCHE: sin definir, **coche** (nuevo soporte), personalizado. Ya no aparece deposito ni manillar.
  - MOTO: manillar, deposito, bolsillo, mochila, personalizado.
- Nuevo soporte **Coche** en `MountProfile`, con su propio aprendizaje de ruido y traqueteo.
- Al cambiar de vehiculo, si el soporte elegido no existe en ese vehiculo (deposito en coche) se pasa
  automaticamente al que corresponde (coche, manillar o bolsillo), sin dejar estados raros.
- 3 pruebas nuevas (200 en total): soportes permitidos por vehiculo, soporte por defecto permitido y
  lectura del soporte `coche`.

## 1.75s (versionCode 62)

### Bloque de calibracion a la vista (y se puede borrar)
- DIAGNOSTICO > ARCHIVOS DE CALIBRACION dice cual es el bloque de 15 minutos guardado: puntuacion y
  lo que ocupa en MB. Si no hay ninguno, explica la regla (mejor de 15 min, tope de 4 h).
- Boton **BORRAR GRABACION**: usa `CalibrationRepository.clearBestBlock()`, borra el fichero y deja el
  registro limpio.
- `CalibrationRepository.bestBlockBytes()` da el tamano para mostrarlo.

## 1.74s (versionCode 61)

### Grabaciones de calibracion: solo el mejor bloque de 15 minutos
- La grabacion se parte en bloques de **15 minutos**. Al cerrarse cada bloque se puntua (bandas de
  velocidad cubiertas, filas con velocidad valida, Hz reales frente a pedidos y actividad) y **solo se
  queda el de mayor puntuacion**; el resto se borra al momento. En disco hay como mucho un bloque
  bueno y el que se esta grabando.
- **Tope de seguridad de 4 horas** por sesion: al llegar, la grabacion se detiene sola y lo anota en el
  registro. Tambien sigue estando el limite natural de almacenamiento del movil.
- El bloque guardado y su puntuacion se recuerdan entre arranques, y se puede borrar cuando se quiera.
- 6 pruebas nuevas (`CalibrationRetentionTest`): puntuacion de un bloque completo, bloque sin
  velocidad, bloque vacio, sustitucion solo si mejora, bandas y los dos topes.

## 1.73s (versionCode 60)

### Clasificador bache / golpe (solo mide)
- Cada golpe contado se clasifica con las senales disponibles en **RUIDO, BACHE, GOLPE DURO o CHOQUE** y
  el veredicto queda en el registro y en el estado que ve la pantalla. **El SOS sigue disparandose
  igual**: esta version mide para poder ajustar con datos reales.
- Puntuacion: pico frente al nivel, ancho del impacto (un bache es corto), caida de velocidad (-30 km/h
  y casi parado), motor calado (RPM 0 con velocidad previa), postura cambiada. A los 2 s se vuelve a
  evaluar con la velocidad de ese momento, que es lo que de verdad separa un bache de una colision.
- Sin dato de velocidad no se inventa un choque: como mucho queda en GOLPE DURO.
- 8 pruebas nuevas (ImpactClassifierTest) con el bache grande, el pico flojo, el golpe ancho, la
  caida de velocidad, el motor calado y el caso sin datos.

## 1.72s (versionCode 59)

### Enlace OBDII del perfil COCHE (ELM327 por Bluetooth)
- Nueva tarjeta **ENLACE CON EL COCHE**, solo en el perfil COCHE: elige el adaptador ya emparejado,
  `CONECTAR` / `CORTAR` / `BUSCAR`, y datos en vivo de **velocidad, RPM, acelerador y refrigerante**,
  con protocolo negociado, lecturas por segundo, tension de bateria y numero de codigos de averia.
- Transporte **Bluetooth clasico (SPP, UUID `00001101-...`)** e inicializacion `ATZ`, `ATE0`, `ATL0`,
  `ATS0`, `ATH1`, `ATSP0`; ronda de velocidad cada 200 ms y el resto cada segundo, con reconexion y
  estados claros (`SIN PERMISO`, `SIN ADAPTADOR`, `CONECTANDO`, `SIN DATOS`, `EN MARCHA`, `ERROR`).
- Android 12+: pide `BLUETOOTH_CONNECT` al pulsar `BUSCAR` y explica que hacer si se deniega. Los
  permisos Bluetooth son opcionales (`uses-feature` no obligatorio).
- En perfil COCHE, con el monitor en marcha, la velocidad del adaptador (menos de 2 s) manda sobre la
  del GPS; sin adaptador todo sigue como antes. El enlace **nunca** toca el camino del SOS.
- Solo lectura: no se borran codigos ni se escribe en la centralita.
- Los datos de conexion salen del APK de referencia (`docs/referencia/OBDII/`): UUID del puerto serie,
  familia de comandos `AT`, protocolos (ISO 15765-4 CAN 11/500, J1850, ISO 9141, KWP) y PIDs
  (`0100`, `0101`, `0105`, `010C`, `010D`, `0111`...). Documentado en `docs/OBDII.md`.
- 10 pruebas nuevas (`ObdParserTest`) con respuestas reales del ELM327: con espacios, sin ellos, con
  eco del comando, `NO DATA`, `SEARCHING...`, `?` y `UNABLE TO CONNECT`.

## 1.71s (versionCode 58)

### Perfiles MOVIL, COCHE y MOTO en Sensores
- Nueva tarjeta **TIPO DE VEHICULO** al principio de Configuracion → Sensores, con tres
  botones: `MOVIL`, `COCHE` y `MOTO`. Se cambia con el monitor parado (o con la grabacion de
  sensores parada) y cada perfil recuerda los suyos.
- **Controles por perfil**:
  - MOVIL: soporte del movil, sensibilidad, caida/orientacion, voz y diagnostico (lo de siempre).
  - COCHE: soporte, sensibilidad con **nivel propio de 4,5 g**, voz, tarjeta **ENLACE CON EL
    COCHE** (el OBDII llega en la 1.72s) y diagnostico. Sin caida/orientacion: un coche no vuelca.
  - MOTO: soporte, sensibilidad, caida/orientacion (MODO MOTO), voz, tarjeta **BUS CAN DE LA MOTO**
    (pendiente del cable) y diagnostico.
- **Ajustes independientes por perfil**: nivel de golpe, ventana ADAPT., voz y caida/orientacion se
  guardan con clave propia. MOVIL sigue usando las claves de siempre, asi que actualizar no pierde
  ningun ajuste; el primer uso de COCHE/MOTO arranca con lo que pide ese vehiculo.
- La pantalla principal muestra `VEHICULO: <perfil>` y el indice de Configuracion marca el perfil
  activo en la fila SENSORES.
- El perfil viaja en `ajustes.txt` (`vehiculo=`, `vehiculo_nombre=`) y en el CSV de calibracion (nueva
  columna `vehicle` y cabecera `# vehiculo=...`).

### Referencia OBDII y moto
- `docs/referencia/OBDII/`: que es cada fichero, tamanos y SHA-256, con el analisis estatico de
  Torque Pro hecho con REA (`ANALISIS-TORQUE.md`).
- `docs/MOTO-CAN.md`: del manual de la Softail 2018 salen el bus CAN, los modulos que hay en el, que
  **la velocidad viaja al cuadro por CAN** y el pinout del conector `[91A]` (pin 1 CAN alta, pin 3 CAN
  baja, 50-70 ohmios entre ellos). Plan de escucha pasiva para cuando haya cable.

## 1.70s (versionCode 57)

### Perfil por soporte
- Nuevo ajuste **SOPORTE DEL MOVIL**: sin definir, bolsillo, manillar, deposito,
  mochila y personalizado.
- El perfil aprendido (ruido, traqueteo, muestreo y uso acumulado) se guarda por
  soporte. Mientras no se elige, se mantiene el perfil global anterior.
- Al elegir el primer soporte se hereda el aprendizaje existente; los demas
  empiezan de cero.
- El soporte aparece en la ficha de aprendizaje, en `ajustes.txt` y en el CSV.

### Calibracion por velocidad
- La grabacion de sensores usa 20 Hz de 0 a 12,5 km/h y sube en escalones de
  20 Hz hasta 100 Hz a partir de 50 km/h.
- Mediana de 3 fixes, 10 s de estabilidad y 15 s minimos entre cambios para
  evitar rebindings y consumo.
- Sin velocidad valida se mantiene 20 Hz; se usa la ultima posicion GPS conocida
  si tiene menos de 30 s.
- Solo el sensor de movimiento cambia de tasa; giroscopio a 25 Hz y gravedad a
  tasa normal.
- El CSV anade `wall_ms`, `magnitude`, `mount`, `event`, `speed_kmh` y
  `target_hz`, y registra cada cambio de tasa.
- El analizador agrupa por dispositivo + soporte + actividad y muestra bandas de
  velocidad y muestreo real.

### Jerk incremental
- Nueva senal de jerk (cambio de aceleracion en g/s) como refuerzo, nunca como
  disparador independiente.
- Solo se considera con velocidad reciente (<= 10 s) y a partir de **20 km/h**;
  por debajo vale cero.
- Aporta como maximo **+0,35 g** al valor dinamico: 20 % del maximo a 20 km/h y
  100 % a 50 km/h o mas. Un jerk de 120 g/s satura el aporte.
- El pico real y el umbral siguen siendo la referencia; el jerk solo puede hacer
  que un golpe justo por debajo del umbral cuente.
- El CSV de calibracion guarda `jerk_g_s` en las muestras de movimiento; el
  analizador muestra p99/max global y por banda de velocidad.
- Tests: `JerkAssistTest` y `AccidentDetectorJerkTest`.

## 1.64s (versionCode 56)

### Textos de la app en lenguaje llano
Se reescriben todos los párrafos visibles para que no hagan falta conocimientos técnicos:

| Antes | Ahora |
|---|---|
| enlace `t.me` | enlace de Telegram |
| `chat id` | número de chat |
| `token` del bot | clave del bot |
| umbral / umbral efectivo | nivel de golpe / nivel que se aplica |
| `{MAPA}` insertará el enlace | `{MAPA}` se cambia por el enlace de Google Maps |
| la API pública de GitHub | GitHub |
| analítica | estadísticas de uso |
| muestreo (25/50/100 Hz) | cuantas veces por segundo mira el sensor |
| “arma un candidato” | “deja el aviso en espera” |
| aviso heurístico | alerta automática de Android |

- Configuración → Canales de salida SOS: el texto de ENVIO AUTO MENSAJES ya no habla de `t.me`
  (“la app abre el chat de WhatsApp o el de Telegram y pulsa Enviar sin preguntar”), el resumen del
  bot dice “contactos listos” y “enlace de Telegram”, y la tarjeta TELEGRAM SOS usa **Clave del bot**,
  **GUARDAR CLAVE** y **BORRAR CLAVE**.
- Sensores: la línea del valor pasa a `NIVEL DE GOLPE =` y la ayuda explica con palabras normales qué
  es PICO, EFECTIVO y SUGERIDO. El diagnóstico ya no dice `p99` ni `Hz`: dice `traqueteo` y
  `medidas/s`.
- Caída / orientación y el aviso de la cuenta atrás se explican sin “candidato”: “un golpe fuerte deja
  el aviso en espera de 15 s”.
- Privacidad: “la clave del bot”, “estadísticas de uso” y “GitHub” en lugar de token, analítica y API.
- Las tarjetas de Instrucciones (1 a 8) se reescriben en el mismo tono, incluidas las de Telegram.

## 1.63s (versionCode 55)

### Texto de ENVIO AUTO MENSAJES
- La descripción de `Configuración → Canales de salida SOS → ENVIO AUTO MENSAJES` estaba mal escrita:
  `Al terminar el SOS, WhatsApp y los enlaces t.me se abren y se pulsa Enviar sin confirmacion`. Tenía
  dos sujetos (los chats "se abren", alguien "pulsa Enviar") y dejaba la frase a medias.
- Ahora dice: **"Al terminar el SOS, la app abre el chat de WhatsApp o el enlace t.me en Telegram y
  pulsa Enviar sin confirmacion."** Un solo sujeto (la app), un solo hilo y los dos canales nombrados.
- El texto con el interruptor en OFF no cambia: `WhatsApp y Telegram se abren con el mensaje listo; el
  envio es manual.`

## 1.62s (versionCode 54)

### Repaso visual de todos los menús
- La tarjeta `INTERRUPTOR S O S` (Configuración → Canales de salida SOS) ya no parte el título en
  dos líneas: la etiqueta de estado (`DESACTIVADO` / `ACTIVADO`) le robaba el ancho y el texto
  quedaba como `INTERRUPTOR S` / `O S`. Ahora **el título ocupa toda la línea y la etiqueta va
  debajo**.
- `S O S` se escribe con espacios duros (U+00A0), así que nunca se separa entre letras: ni en
  títulos, ni en descripciones, ni en los avisos de la pantalla principal.
- Nueva cabecera compartida `CardHeader` para tarjetas, filas del índice de Configuración y fichas
  de contacto: mismo orden (título, etiqueta, descripción) y ningún título partido a mitad de frase.
- Filas de permisos en dos líneas legibles: `Accesibilidad` ya no se corta por la mitad
  (`Accesibilid` / `ad`) y el botón `ABRIR` no empuja al texto.
- Tarjeta `INTERCOM-LUKY`: el estado se lee en cuerpo pequeño, el punto de color queda junto al
  título y los botones se reparten en dos filas, así `ACTUALIZAR` deja de salir recortado
  (`ACTUALIZ…`) y `PROBAR AVISO` pasa a línea completa.
- Sensores: el valor interior se llama `UMBRAL DE GOLPE` (antes repetía el título de la tarjeta) y
  la ventana automática se anota en su propia línea (`AUTO aplica 2 min ahora`) en lugar de
  partirse dentro de la fila de chips.
- `REINICIAR APRENDIZAJE` queda alineado con el texto de la tarjeta y los chips de actividad
  comparten altura.
- Textos corregidos: la ayuda de `COMO SE ENVIA LA UBICACION` había perdido el final de la frase;
  se acortan las de SMS, diagnóstico y contactos. La cabecera de Información ya no deja la versión
  sola en una línea.

## 1.61s (versionCode 53)

### T. PROMEDIO más compacto y en negrilla
- La fila `T. PROMEDIO` va **más pegada** a la línea de sensibilidad: la etiqueta y la ventana forman
  un solo grupo (2 dp en vez de los 8 dp del resto de bloques de la tarjeta).
- `AUTO` `1` `2` `3` se muestran siempre en **negrilla**; el activo, además, en verde (y con el
  destello cuando AUTO cambia de ventana).

## 1.60s (versionCode 52)

### El número de T. PROMEDIO destella cuando AUTO cambia de ventana
- Si la ventana automática cambia (por ejemplo de 3 a 2 minutos porque el camino es más estable),
  el número activo de `T. PROMEDIO` **destella tres veces** (320 ms encendido, 220 ms apagado)
  subiendo el verde del fondo, para que se note sin mirar fijo.
- Solo destella con `AUTO`, y solo en cambios reales: la primera composición no parpadea, así que
  abrir la app o volver desde Configuración no produce destellos.
- El resaltado es un tercer estado del chip (`selected` + `emphasised`), sin tocar el selector de
  Configuración → Sensores.

## 1.59s (versionCode 51)

### T. PROMEDIO más claro en la pantalla principal
- Se elimina el texto de minutos que había a la derecha (`2 min`): la fila queda `T. PROMEDIO` con
  `AUTO` `1` `2` `3` y nada más.
- Con **AUTO** activo se marcan en verde **AUTO y el número de minutos que se está aplicando**
  (por ejemplo `AUTO` y `2`), porque la ventana cambia sola según el camino.
- Con una ventana fija (`1`, `2` o `3`) solo se marca ese número.
- Sigue siendo solo información: el selector real está en Configuración → Sensores.

## 1.58s (versionCode 50)

### La ventana de promedio se elige en Sensores
- La selección `ADAPT. AUTO / 1 / 2 / 3` se mueve a **Configuración → Sensores**, dentro de la misma
  tarjeta que el deslizador de sensibilidad (junto con el valor, la escala 0..7, `PICO / EFECTIVO /
  SUGERIDO` y `PROBAR GOLPE`). Sigue bloqueada con el monitor en marcha.
- En la **pantalla principal** queda solo información: `T. PROMEDIO` con `AUTO` `1` `2` `3`, la
  opción activa en verde y, a la derecha, `auto: N min` o `N min`. No es pulsable: no hay forma de
  cambiar la ventana sin querer desde el manillar.
- El chip de información es de solo lectura (`AdaptiveWindowInfoChip`); el selector interactivo
  sigue siendo `AdaptationWindowChip`.
- `onWindowChange` se centraliza en la actividad, igual que la sensibilidad y la prueba de golpe.
- Instrucciones (3b, 3c y uso diario), protocolo e historial con la nueva ubicación.

## 1.57s (versionCode 49)

### La pantalla principal se queda solo con lo esencial
- En la tarjeta del monitor quedan **`SENSIBILIDAD A LOS GOLPES = x,x g`** y la línea **`ADAPT.`**
  (`AUTO` o `1`/`2`/`3` MIN, con lo elegido resaltado en verde). Desaparecen de ahí el deslizador,
  la escala 0..7, la línea de pico y el botón `PROBAR GOLPE`.
- Todo eso pasa a **Configuración → Sensores**, en una tarjeta nueva `SENSIBILIDAD A LOS GOLPES`
  con el valor, el deslizador, la escala 0..7, `PICO / EFECTIVO / SUGERIDO`, `USAR` y
  `PROBAR GOLPE` (con su veredicto). La tarjeta indica `AJUSTABLE` o `MONITOR EN MARCHA`, y con el
  monitor en marcha los controles quedan bloqueados igual que antes.
- La etiqueta con el valor se mantiene en las dos pantallas para no perder la referencia.
- El control se ha dividido en piezas (`AdaptationWindowSelector`, `SensitivityPeakRow`) y un solo
  `SensitivityControl` decide qué enseña con `showSlider` / `showWindow` / `showPeak`.
- `onSensitivityChange` y `onHitTest` se centralizan en la actividad, así que la pantalla principal
  y la de Sensores guardan y prueban exactamente igual.
- Ayuda de la app, instrucciones (3b y 3c) y protocolo con las nuevas ubicaciones.

## 1.56s (versionCode 48)

### Dos botones de estado en la pantalla principal
- El botón **INICIAR / DETENER MONITOR** se parte en dos botones que muestran **solo** el valor
  actual, nunca los dos:
  - **MONITOR ON/OFF**: `ON` en verde cuando el monitor vigila y `OFF` en naranja cuando está
    parado; un toque alterna.
  - **S O S ON/OFF**: `ON` en verde cuando los avisos están habilitados y `OFF` en rojo cuando no;
    un toque alterna y, al activarlo con contactos de llamada, pide el permiso `CALL_PHONE` como
    hacía el interruptor anterior.
- **FUNCIONALIDAD SOS** sale de Configuración. En *Canales de salida SOS* queda una tarjeta
  informativa que recuerda dónde está el interruptor y mantiene el aviso de "llamadas sin permiso".
- El aviso de la pantalla principal ("avisos SOS desactivados") ahora señala el botón
  `S O S OFF` en vez de mandar a Configuración.
- La ayuda de la app (Como usar) y el protocolo usan la nueva nomenclatura.
- El pico del viaje menor de 0,5 g ya no se guarda ni se muestra como `ULTIMO 0.0 g`.
- Sin cambios en el comportamiento del SOS ni en el despacho: solo en dónde y cómo se encienden
  el monitor y los avisos.

## 1.55s (versionCode 47)

### Configuración dividida en cuatro submenús
- **Índice**: el engranaje abre Configuración con cuatro bloques y el estado resumido de cada uno
  (modo de golpe, número de contactos, canales activos y permisos pendientes). Al entrar en uno,
  la lista muestra solo sus ajustes y una cabecera `‹ CONFIGURACION` para volver; el botón atrás
  del sistema también sube un nivel en vez de cerrar la configuración entera.
- **Sensores**: CAIDA / ORIENTACION, VOZ AYUDA / SOCORRO y DIAGNÓSTICO (ficha del móvil,
  REINICIAR APRENDIZAJE y grabación CSV/ZIP opcional). Un aviso recuerda que la sensibilidad, la
  ventana `ADAPT.` y `PRUEBA DE GOLPE` viven en la tarjeta del monitor.
- **Contactos SOS**: lista y editor con el orden de llamada, y el bloque de cómo se envía la
  ubicación.
- **Canales de salida SOS**: FUNCIONALIDAD SOS, ENVIO AUTO MENSAJES (con sus dos permisos),
  TELEGRAM SOS, SMS SOS AUTOMATICO e INTERCOM-LUKY.
- **Permisos sistema**: alarmas exactas, accesibilidad y overlay, y el estado de notificaciones,
  ubicación, micrófono, llamadas y SMS, con un botón a los ajustes de la app.
- La lista vuelve al principio al cambiar de bloque. Sin cambios en el comportamiento del SOS:
  solo cambia dónde está cada ajuste.
- Instrucciones, protocolo e historial actualizados; sin pruebas nuevas (la UI no tiene lógica
  propia más allá de los estados que ya se calculaban).

## 1.54s (versionCode 46)

### La sensibilidad se afina sola con cada móvil y con el uso
- **Umbral efectivo por dispositivo**: el ajuste en g deja de ser el número que se aplica. La app
  aprende el **ruido en reposo** (5×, mínimo 0,30 g), el **nivel normal de marcha** (p99 × 1,30) y
  la **frecuencia real** que entrega el sensor (compensación `sqrt(hz/100)`, acotada al 30 %) para
  calcular un umbral propio de cada teléfono. Motivo: el mismo 3,5 g dispara solo en un móvil con
  el sensor ruidoso y no detecta nada en otro que suaviza los picos.
- **Aprendizaje acumulado**: dos perfiles, uno corto (ventana de 1-3 min) y otro largo que se funde
  entre viajes con `α = min(0,35, ventana/(uso+ventana))`. A partir de 5 min de uso manda el
  aprendido, con tope de **1,6× el ajuste** y **+2 g** para que un perfil malo no pueda dejar el
  móvil sordo. Los golpes **nunca** entran en el promedio: si entraran, subirían el umbral que los
  detecta.
- **Ventana AUTO** (por defecto) o fija a 1/2/3 min: compara el p99 de las dos últimas mitades,
  acorta a 1 min si el camino cambia ≥30 % y alarga a 3 min si lleva dos ventanas cambiando ≤10 %.
  Un escalón por ventana y permanencia mínima.
- **Muestreo adaptativo**: 25 Hz con el móvil quieto, 50 Hz normal, 100 Hz en demanda o tras un
  golpe/candidato. Se **mide** la frecuencia real (no la pedida) y es la que se muestra y se
  compensa. Cambiar de nivel re-registra solo el sensor de movimiento: la actitud de inclinación
  aprendida no se pierde.
- **PRUEBA DE GOLPE** (15 s, en la tarjeta del monitor): dice si el móvil habría disparado
  (`pico 4,2 g / umbral 3,9 g`) o si no llegaría, con el ajuste recomendado. No graba CSV, no
  inicia la cuenta atrás y no avisa a nadie. Se bloquea con el monitor en marcha.
- **Histéresis y separación de picos** en `AccidentDetector`: un pico cuenta solo si el detector
  está rearmado (haber bajado a 0,6× el umbral) y entre dos picos contados deben pasar ≥250 ms.
  Así el **rebote de un solo golpe** ya no se lee como una segunda sacudida (antes disparaba el SOS
  sin confirmar inclinación) y un bache con doble impacto tampoco.
- **Rescate de caída sin inclinación**: si el golpe fue a ≥20 km/h, el móvil no se movió en toda la
  ventana de candidato y no reanuda la marcha, el candidato ya no se descarta: pasa a vigilar
  inmovilidad (el caso del teléfono en el bolsillo, donde la actitud nunca confirma nada).
- **Ajuste guiado**: bajo el deslizador aparecen `PICO`, `EFECTIVO` y `SUGERIDO` (pico × 1,20 en
  pasos de 0,5 g con la misma fórmula que `analizar_calibracion.py`) y `USAR` aplica el valor.
- **La calibración deja de ser un paso**: la grabación CSV/ZIP se mueve a **Configuración →
  DIAGNÓSTICO** (adjuntar datos a una incidencia o comparar móviles) y añade `REINICIAR
  APRENDIZAJE`. El ZIP incluye ahora umbral efectivo, ventana, ruido, p99, Hz y pico del viaje.
- Nuevos módulos puros `AdaptiveSensitivity.kt` y `SensitivityAdvisor.kt`, `docs/SENSIBILIDAD.md`,
  y 32 pruebas nuevas (145 en total, 0 fallos).

## 1.53s (versionCode 45)

### El historial vuelve a estar completo y la cola de Telegram deja de perder avisos
- **Historial**: la lista de `Información → Historial` se quedó parada en 1.41s y no mencionaba
  ninguna de las versiones 1.42s–1.52s. Se añaden las doce entradas que faltaban, con la 1.53s
  arriba del todo.
- **Carrera en `TelegramBotOutbox`**: cada mutación de la cola era un ciclo leer-modificar-escribir
  sin protección sobre un JSON de `SharedPreferences`. El servicio crea una instancia en el hilo
  del manejador y el trabajador de red apunta ahí el resultado de cada envío; si un `enqueue` leía
  la lista justo antes de que el trabajador apuntara una entrega, la escritura del `enqueue` borraba
  ese apunte. Consecuencia: ese contacto se quedaba **sin aviso y sin reintento** —el canal que
  precisamente existe porque el móvil va en el bolsillo—. Ahora todas las mutaciones
  (`enqueue`, `record`, `clearEvent`, `clear`) pasan por un candado compartido por instancias y la
  escritura se persiste con `commit()` antes de soltarlo.
- `closeEvent(eventId)`: el resumen final y el borrado de la cola del evento van bajo el mismo
  candado. Antes se leía el resumen y luego se borraba, así que un aviso entregado entre las dos
  llamadas quedaba en el registro como "sin entregar".
- La cola ya no depende de `Context` para poder probarse: `TelegramOutboxStore` esconde el almacén
  y las pruebas usan una versión en memoria. `testImplementation("org.json:json:20240303")` pone la
  implementación real de `org.json` en el classpath de test (la de `android.jar` son stubs).
- 10 pruebas nuevas en `TelegramBotOutboxTest`, incluida la de dos hilos escribiendo a la vez:
  comprobado que **falla** sin el candado y pasa con él.

## 1.52s (versionCode 44)

Publicada con APK, mapping, protocolo e instrucciones: `APK´s/Segu-Luky-1.52s.apk`,
`APK´s/mapping/Segu-Luky-1.52s-mapping.txt.gz`, release `V-1.52s` con el APK adjunto.

### Verificar la instalación y explicar el aviso de Play Protect
- Nueva pantalla **Acerca de → Verificar instalación**: muestra la huella SHA-256 del
  certificado con el que está firmado el APK instalado, para compararla con la publicada sin
  necesidad de un PC. Si alguien reempaqueta la app con otra clave, esa huella cambia.
- `docs/SEGURIDAD.md`: inventario completo de permisos (con su motivo real y cuándo se piden),
  alcance del servicio de accesibilidad, destinos de red, dependencias, datos guardados y por qué
  Play Protect marca el APK como sospechoso —con lo que eso significa y lo que no.
- `APK´s/SHA256SUMS.txt`: hashes de todas las APK publicadas y la huella del certificado.
- Instrucciones con un apartado **"Si Play Protect dice que puede ser dañina"**: qué hacer,
  cómo verificar el archivo y por qué el aviso reaparece en cada versión.

## 1.51s (versionCode 43)

Publicada con APK, mapping, protocolo e instrucciones: `APK´s/Segu-Luky-1.51s.apk`,
`APK´s/mapping/Segu-Luky-1.51s-mapping.txt.gz`, release `V-1.51s` con el APK adjunto.

### Cancelar el desbloqueo ya no abre WhatsApp
- Con el móvil bloqueado, el SOS pedía quitar el bloqueo y **en los tres** resultados (incluido
  "cancelado") abría el chat: el mensaje de emergencia quedaba encima de la pantalla de bloqueo
  aunque el usuario acabara de rechazarlo. Ahora `WhatsAppUnlockPolicy` decide por resultado
  (`DISMISSED`/`FAILED` abren, `CANCELLED` no) y el `requestId` viaja en el intent del relay.
- Al cancelar: no se abre nada, se cierra **ese** aviso de la cola con
  `WhatsAppAutoSend.markFailed(..., "desbloqueo cancelado por el usuario")` para que la ronda siga
  con el resto de contactos, y queda la notificación "mensaje SOS pendiente" que abre el chat con
  un toque cuando el usuario desbloquee.
- 4 tests nuevos (103 en total).

## 1.50s (versionCode 42)

Publicada con APK, mapping, protocolo e instrucciones: `APK´s/Segu-Luky-1.50s.apk`,
`APK´s/mapping/Segu-Luky-1.50s-mapping.txt.gz`, release `V-1.50s` con el APK adjunto.

### Cola durable del bot de Telegram
- `dispatchTelegramBot()` hacía **un único** POST por contacto: sin datos en el punto del accidente
  (el caso normal), un 502 o un timeout y ese contacto no recibía nada, sin reintento ni aviso.
  Ahora cada aviso es un trabajo en `TelegramBotOutbox` (persistido, sobrevive a reinicios) que se
  reintenta con espera creciente —20 s, 60 s, 120 s, 300 s, máximo 5 intentos— dentro de la
  ventana de 10 min que ya vigilaba el despacho, tanto en cada vuelta del vigilante como al
  reanudar un SOS interrumpido. Los fallos definitivos (token revocado, chat inexistente, bot
  bloqueado) no se reintentan: gastarían la ventana sin poder mejorar.
- Al cerrar la ronda el registro resume lo entregado y lo que quedó sin entregar, con el motivo.
- 5 tests nuevos (99 en total) sobre la escala de espera, la ventana y los fallos definitivos.

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
