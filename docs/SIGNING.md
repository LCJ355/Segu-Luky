# Firma de la release

La app se distribuye fuera de Play Store, así que la firma es la que decide si una
actualización se puede instalar **encima** de la versión existente. Cambiar de clave
obliga a desinstalar (y perder contactos y ajustes).

## Qué se necesita

Localmente, en la raíz del proyecto (ambos ignorados por `.gitignore`):

| Archivo | Contenido |
|---|---|
| `segu-luky-release.jks` | almacén de claves, clave RSA 2048 |
| `keystore.properties` | ruta, contraseñas y alias (plantilla: `keystore.properties.example`) |

El build lee primero variables de entorno y, si no existen, el archivo:

- `SEGU_STORE_FILE`, `SEGU_STORE_PASSWORD`, `SEGU_KEY_ALIAS`, `SEGU_KEY_PASSWORD`

## Identidad de la clave en uso (verificada)

| Dato | Valor |
|---|---|
| Alias | `segu` |
| Distinguished Name | `CN=Segu-Luky, OU=Personal, O=Segu-Luky, C=ES` |
| Clave | RSA 2048, firma `SHA256withRSA` |
| Número de serie | `e53fb04d41b7b9ae` |
| Validez | 06/09/2026 → **22/01/2054** |
| SHA-256 del certificado | `E0:E5:A8:FD:9B:42:EC:DA:38:22:B3:B1:BC:05:AD:D7:CA:02:DC:B3:DE:B8:77:1B:AF:8F:64:08:CF:E2:10:9E` |

Ese `SHA-256` es el valor con el que se comprueba que una APK publicada es de este
proyecto y no una recompilación con otra clave.

## Comprobar una APK

```text
C:\Android\sdk\build-tools\35.0.0\apksigner.bat verify --print-certs app-release.apk
```

El resultado debe mostrar el `SHA-256` de arriba. Si no coincide, no se instalará
sobre las versiones anteriores.

## Restaurar en una copia nueva del repositorio

El repositorio **no** contiene el material de firma (correcto por seguridad), así que
una copia recién clonada no puede generar una release firmada:

1. Copiar `segu-luky-release.jks` y `keystore.properties` a la raíz del proyecto.
2. Comprobar la identidad con `keytool -list -v -keystore segu-luky-release.jks -alias segu`.
3. `gradlew assembleRelease` debe terminar sin `Keystore file ... not found`.

En CI, en lugar de archivos, configurar los cuatro secretos `SEGU_*`.

## Copia de seguridad

El `.jks` es irrecuperable: si se pierde, las versiones futuras tendrán otra firma y
los usuarios deberán desinstalar. Guardar una copia fuera del repositorio (almacén de
secretos, unidad cifrada) junto con sus contraseñas, y **no** subirlo nunca al
repositorio ni compartirlo.

## Rotación

Si las contraseñas se exponen: cambiarlas en el almacén y en los secretos, manteniendo
la misma clave para no romper las actualizaciones. Si se cambia la clave, avisar en las
notas de la release de que hay que desinstalar la versión anterior.
