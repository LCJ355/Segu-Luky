#requires -Version 5.1
<#
Comprueba un APK de Segu-Luky antes de publicarlo.

Falla (exit 1) si el paquete lleva algo que no deberia salir de este movil:
  - entradas que no son codigo, recursos o firmas (CSV, JSON, bases de datos,
    preferencias, cualquier assets suelto),
  - telefonos espanoles, tokens de bot de Telegram o pares de coordenadas,
  - nombres de la lista -Nombres.

Ademas verifica la firma contra la huella publicada en docs/SIGNING.md y, si el
APK esta en APK's, el SHA-256 contra APK's/SHA256SUMS.txt.

Uso:
  powershell -File tools/comprobar-apk.ps1                 # el APK mas reciente de APK's
  powershell -File tools/comprobar-apk.ps1 -Apk "APK's/Segu-Luky-1.64s.apk"
  powershell -File tools/comprobar-apk.ps1 -Nombres 'Pilar','Antonio'
#>
param(
    [string]$Apk,
    [string[]]$Nombres = @('Pilar', 'Antonio'),
    [string]$HuellaEsperada,
    [string]$AndroidSdk = 'C:\Android\sdk'
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent (Split-Path -Parent $PSCommandPath)
Add-Type -AssemblyName System.IO.Compression.FileSystem

$problemas = New-Object System.Collections.Generic.List[string]
function Fallo([string]$texto) { $script:problemas.Add($texto) }

# --- 1. Elegir el APK -------------------------------------------------------
if (-not $Apk) {
    $carpeta = Join-Path $repo "APK's"
    if (-not (Test-Path -LiteralPath $carpeta)) { $carpeta = Join-Path $repo "APK´s" }
    $ultimo = Get-ChildItem -LiteralPath $carpeta -Filter 'Segu-Luky-*.apk' |
        Sort-Object LastWriteTime | Select-Object -Last 1
    if (-not $ultimo) { throw "No hay APK en $carpeta" }
    $Apk = $ultimo.FullName
}
$Apk = [System.IO.Path]::GetFullPath($Apk)
if (-not (Test-Path -LiteralPath $Apk)) { throw "No existe el APK: $Apk" }
$sha = (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash.ToLower()
$bytes = (Get-Item -LiteralPath $Apk).Length
"APK:     $Apk"
"Tamano:  $bytes bytes"
"SHA-256: $sha"

# --- 2. Contenido: solo codigo, recursos y firmas ---------------------------
$permitidas = '^(classes[0-9]*\.dex|resources\.arsc|AndroidManifest\.xml|DebugProbesKt\.bin|kotlin-tooling-metadata\.json)$' +
              '|^(res|META-INF|kotlin|assets/dexopt)/'
$sospechosas = @()
$zip = [System.IO.Compression.ZipFile]::OpenRead($Apk)
try {
    foreach ($e in $zip.Entries) {
        if ($e.FullName -notmatch $permitidas) { $sospechosas += $e.FullName }
    }
    if ($sospechosas.Count -gt 0) {
        Fallo ("Entradas que no deberian viajar en el APK: " + ($sospechosas -join ', '))
    }

    # --- 3. Contenido: datos personales ------------------------------------
    $patrones = [ordered]@{
        'telefono con prefijo' = '\+34[0-9]{6,12}'
        'telefono espanol'     = '(?<![0-9])34[67][0-9]{8}(?![0-9])'
        'token de bot'         = '[0-9]{8,10}:[A-Za-z0-9_-]{35}'
        'coordenadas'          = '-?[0-9]{1,2}\.[0-9]{5,}[,;]-?[0-9]{1,2}\.[0-9]{5,}'
    }
    foreach ($n in $Nombres) { if ($n) { $patrones["nombre '$n'"] = [regex]::Escape($n) } }

    $latin1 = [System.Text.Encoding]::GetEncoding(28591)
    foreach ($e in $zip.Entries) {
        if ($e.Length -eq 0 -or $e.Length -gt 32MB) { continue }
        $ms = New-Object System.IO.MemoryStream
        $stream = $e.Open()
        $stream.CopyTo($ms)
        $stream.Close()
        $texto = $latin1.GetString($ms.ToArray())
        $ms.Dispose()
        foreach ($k in $patrones.Keys) {
            $m = [regex]::Match($texto, $patrones[$k])
            if ($m.Success) { Fallo ("Dato personal en $($e.FullName): $k -> $($m.Value)") }
        }
    }
} finally {
    $zip.Dispose()
}

# --- 4. Firma ---------------------------------------------------------------
$apksigner = Get-ChildItem -Path (Join-Path $AndroidSdk 'build-tools') -Directory -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending | ForEach-Object { Join-Path $_.FullName 'apksigner.bat' } |
    Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if ($apksigner) {
    $salida = & $apksigner verify --print-certs $Apk 2>&1
    $m = [regex]::Match(($salida -join "`n"), 'certificate SHA-256 digest: ([0-9a-f]{64})')
    if (-not $m.Success) {
        Fallo "No se pudo leer el certificado del APK."
    } else {
        $digest = $m.Groups[1].Value
        $esperado = $HuellaEsperada
        if (-not $esperado) {
            $signing = Join-Path $repo 'docs/SIGNING.md'
            if (Test-Path -LiteralPath $signing) {
                $h = [regex]::Match((Get-Content -LiteralPath $signing -Raw -Encoding UTF8),
                    '([0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2}')
                if ($h.Success) { $esperado = ($h.Value -replace ':', '').ToLower() }
            }
        }
        "Firma:   $digest"
        if ($esperado -and $esperado -ne $digest) {
            Fallo "La firma no coincide con la publicada. Esperada $esperado."
        }
    }
} else {
    "Firma:   omitida (no se encontro apksigner en $AndroidSdk)"
}

# --- 5. SHA-256 frente a SHA256SUMS ----------------------------------------
$nombre = Split-Path -Leaf $Apk
$sums = Get-ChildItem -LiteralPath $repo -Recurse -Filter 'SHA256SUMS.txt' -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($sums) {
    $linea = Select-String -LiteralPath $sums.FullName -Pattern $nombre -SimpleMatch | Select-Object -First 1
    if ($linea) {
        if ($linea.Line -notmatch $sha) { Fallo "El SHA-256 de $nombre no coincide con SHA256SUMS.txt." }
        else { "Sums:    coincide con SHA256SUMS.txt" }
    } else {
        "Sums:    $nombre aun no esta en SHA256SUMS.txt"
    }
}

# --- Resultado --------------------------------------------------------------
if ($problemas.Count -gt 0) {
    ""
    "NO PUBLICAR: $($problemas.Count) problema(s)"
    $problemas | ForEach-Object { "  - $_" }
    exit 1
}
""
"OK: el APK no contiene datos personales y la firma es la publicada."
exit 0
