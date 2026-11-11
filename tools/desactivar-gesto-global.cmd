@echo off
setlocal enabledelayedexpansion
title Segu-Luky - desactivar gesto global 2 dedos

rem Desactiva el servicio de accesibilidad "Gesto global 2 dedos" sin tocar menus.
rem Usalo si la pantalla deja de responder al tacto.

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
if errorlevel 1 (
  echo El gesto global no estaba activado. No hay nada que hacer.
  goto :fin
)

set "NEW=!CUR:%SVC%=!"
call :limpiar NEW

if "!NEW!"=="" (
  "%ADB%" shell settings delete secure enabled_accessibility_services >nul
) else (
  "%ADB%" shell settings put secure enabled_accessibility_services "!NEW!"
)
"%ADB%" shell settings put secure accessibility_enabled 1

for /f "delims=" %%v in ('"%ADB%" shell settings get secure enabled_accessibility_services') do set "AFTER=%%v"
echo Servicios de accesibilidad ahora: !AFTER!
echo !AFTER! | find /i "%SVC%" >nul
if errorlevel 1 (
  echo.
  echo Listo: el gesto global esta desactivado y el tacto vuelve a funcionar.
) else (
  echo.
  echo ERROR: sigue activado. Desactivalo a mano en Ajustes ^> Accesibilidad.
)

:fin
echo.
pause
exit /b 0

:limpiar
set "TMP=!%~1!"
:limpiar_bucle
if not defined TMP goto :limpiar_fin
if "!TMP:~0,1!"==":" set "TMP=!TMP:~1!" & goto :limpiar_bucle
if not defined TMP goto :limpiar_fin
if "!TMP:~-1!"==":" set "TMP=!TMP:~0,-1!" & goto :limpiar_bucle
if not defined TMP goto :limpiar_fin
set "TMP2=!TMP:::=:!"
if "!TMP2!"=="!TMP!" goto :limpiar_fin
set "TMP=!TMP2!"
goto :limpiar_bucle
:limpiar_fin
set "%~1=!TMP!"
exit /b 0