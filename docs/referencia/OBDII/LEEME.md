# Referencia OBDII y bus de la moto

Material de apoyo para los perfiles COCHE y MOTO. **No forma parte del APK**: son
documentos y aplicaciones de terceros que se usan para analizar como hablar con el
adaptador y con el bus del vehiculo.

## Que hay aqui

| Fichero | Tamano | SHA-256 | Para que se usa |
|---|---|---|---|
| `Torque_Pro v1.10.120.apk` | 16.959.999 B | `cc3058d97e1fc87ea3387d3b083b04a774bb0a3dfd929c03f1a674d48f95b440` | Referencia de como se habla con un adaptador ELM327 (transporte Bluetooth/WiFi, inicializacion y PIDs). Analisis en [ANALISIS-TORQUE.md](ANALISIS-TORQUE.md). |
| `Torque_Pro v1.8.16.apk` | 5.657.338 B | `7f13b45beff8605bd629b0f700dc68146fdca247c25e4c491a4dedd433f6b58f` | Version antigua y mas pequena, para comparar el mismo transporte en un APK menos ofuscado. |
| `Softail-2018-Service-Manual-Harley-Davidson-94000529-OPT.pdf` | 23.688.241 B | `82616f67b818796bdee58284fe568d01a8e167dac6eadf826a2d944d40984bad` | Manual de servicio de la Harley-Davidson Softail 2018 (1988 paginas). De aqui sale el pinout del conector de diagnostico y el uso del bus CAN. Resumen en [../MOTO-CAN.md](../MOTO-CAN.md). |

Los dos APK estan excluidos del repositorio por `.gitignore` (`*.apk`): se quedan
en disco para el analisis y su digest queda anotado aqui para poder identificarlos
si hay que recuperarlos. El PDF si se versiona porque es la base tecnica del
trabajo de MOTO.

## Reglas de uso

- Ninguno de estos ficheros se compila ni se empaqueta con Segu-Luky.
- Torque Pro se usa **solo** para leer como implementa el enlace con el ELM327
  (clases, comandos y tiempos). No se copia codigo suyo: la app es propietaria y
  su licencia no lo permite.
- Del manual de Harley se usan **datos tecnicos** (pinout, resistencia del bus,
  que modulo publica la velocidad, codigos de averia), no texto literal largo.
- El analisis de la moto es **solo lectura**: nunca se escribira en el bus CAN.

## Como se regeneran los digests

```text
powershell -Command "Get-FileHash 'docs/referencia/OBDII/<fichero>' -Algorithm SHA256"
```
