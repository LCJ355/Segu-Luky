# Moto: bus CAN de la Harley-Davidson Softail 2018

Resumen del analisis del manual de servicio (`docs/referencia/OBDII/Softail-2018-
Service-Manual-Harley-Davidson-94000529-OPT.pdf`, 1988 paginas) y plan para leer
la moto **cuando haya cable**. En las versiones 1.71s a 1.74s la tarjeta MOTO
muestra este analisis como *pendiente*: la app usa el sensor del movil y el GPS.

## Lo que dice el manual

- La moto usa **bus CAN** en par trenzado, con dos circuitos: **CAN alta** y
  **CAN baja** (pag. 1330).
- Estan conectados al bus el **ECM**, el **BCM**, los **instrumentos (IM)**, los
  dos modulos de mandos (**RHCM/LHCM**) y la **centralita del ABS** (pag. 1330).
- **La velocidad del vehiculo se envia al modulo de instrumentos por el bus CAN**
  (pag. 1328): la velocidad existe en el bus, no solo en el cuadro.
- **Conector de diagnostico (DLC) [91A]**: en la prueba de resistencia se mide
  entre los **terminales 1 y 3**, y debe dar **50-70 ohmios** (pag. 1332); con el
  terminador desconectado, **110-130 ohmios** (pag. 1334). Correspondencia de
  cables: **terminal 1 = CAN alta (W/R)** y **terminal 3 = CAN baja (W/BK)**
  (pag. 1334).
- Cada extremo del bus lleva una resistencia de terminacion de **120 ohmios**,
  por eso en paralelo se miden unos **60** (pag. 1330). Una de ellas esta en el
  mazo principal y otra en el modulo de mando derecho.
- Averias tipicas del bus: `U0001` (fallo del CAN), `U0011` (CAN alta y baja en
  cortocircuito), `B2274` (fusible/cable de bateria). El cuadro muestra
  **"BUS Err"** cuando el IM detecta un problema en el bus (pag. 1329).

## Que significa para la app

La velocidad esta en el bus y se puede **escuchar sin pedir permiso a nadie**:
con un adaptador CAN en modo escucha se ven las tramas de todos los modulos. El
trabajo pendiente es identificar en que trama y en que bytes viaja la velocidad,
que es distinto en cada modelo.

## Plan cuando llegue el cable

1. **Cable**: DLC de 6 pines de Harley a OBD2 (o a un adaptador CAN). Solo se usan
   los pines 1 y 3 (CAN alta/baja) y masa; **no** se puentea nada mas.
2. **Modo escucha pasivo**: el adaptador se configura a 500 kbit/s y se pone a
   escuchar (`ATSP6` + `ATMA`). Nunca se envian peticiones a los modulos ni se
   borran codigos: el bus de una moto en marcha no se toca.
3. **Captura en ruta**: se graban tramas con marca de tiempo junto con la
   velocidad del GPS. Lo que se busca es un campo de 16 bits que crezca y mengue
   igual que la velocidad.
4. **Correlacion**: la app ya guarda la velocidad del GPS en el CSV de calibracion;
   el analisis compara cada byte de cada ID con esa curva y propone candidatos.
5. **Verificacion**: se muestra la velocidad leida del CAN junto a la del GPS en la
   tarjeta MOTO; si coinciden en varias rutas, se guarda el mapeo (ID, byte,
   factor) para ese modelo de moto.
6. **Uso**: solo entonces la velocidad del CAN puede usarse como fuente
   preferente, igual que el OBDII en el coche.

## Riesgos y reglas

- **Solo lectura.** Nada de escribir en el bus, ni de sesiones de diagnostico, ni
  de actuadores. Un fallo ahi puede dejar la moto sin arrancar.
- Con el contacto quitado el bus esta apagado: hay que leer con la moto en marcha
  o en contacto, y eso se avisa en la tarjeta.
- Si el cable o el adaptador no dan senal, la app sigue igual con el movil y el
  GPS: la tarjeta lo dice y no se rompe nada.
- El mapeo de tramas es **por modelo y ano**: el de una Softail 2018 no vale para
  otra generacion.
