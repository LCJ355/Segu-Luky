@echo off
setlocal enabledelayedexpansion
title Segu-Luky - activar gesto global 2 dedos

rem Activa el servicio de accesibilidad "Gesto global 2 dedos" sin tocar menus.
rem Solo hay que ejecutarlo una vez, con el movil conectado por USB y la
rem depuracion USB autorizada. El ajuste se mantiene tras reiniciar.

set "ADB=adb"
where adb >nul 2>nul
if errorlevel 1 (
  if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
)
if exist "C:\platform-tools\platform-tools\adb.exe" set "ADB=C:\platform-tools\platform-tools\adb.exe"

set "PKG=com.intercom.segluky"
set "SVC=%PKG%/%PKG%.GlobalTouchAccessibilityService"

echo Buscando el movil...
"%ADB%" wait-for-device
for /f "delims=" %%d in ('"%ADB%" shell getprop ro.product.model') do set "MODEL=%%d"
echo Movil detectado: !MODEL!

for /f "delims=" %%v in ('"%ADB%" shell settings get secure enabled_accessibility_services') do set "CUR=%%v"
if "!CUR!"=="null" set "CUR="

echo Servicios de accesibilidad actuales: !CUR!

echo !CUR! | find /i "%SVC%" >nul
if not errorlevel 1 (
  echo El gesto global ya estaba activado. No hay nada que hacer.
  goto :done
)

if "!CUR!"=="" (
  set "NEW=%SVC%"
) else (
  set "NEW=!CUR!:%SVC%"
)

"%ADB%" shell settings put secure enabled_accessibility_services "!NEW!"
"%ADB%" shell settings put secure accessibility_enabled 1

for /f "delims=" %%v in ('"%ADB%" shell settings get secure enabled_accessibility_services') do set "AFTER=%%v"
echo Servicios de accesibilidad ahora: !AFTER!
echo !AFTER! | find /i "%SVC%" >nul
if errorlevel 1 (
  echo.
  echo ERROR: no se pudo activar. Activalo a mano en Ajustes ^> Accesibilidad.
) else (
  echo.
  echo Listo: manten dos dedos 5 segundos en cualquier pantalla para lanzar el SOS.
)

:done
echo.
pause