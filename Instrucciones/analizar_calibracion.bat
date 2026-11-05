@echo off
setlocal
set HERE=%~dp0
set FOLDER=%~1
if "%FOLDER%"=="" set FOLDER=%HERE%Calibracion
echo Analizando calibraciones de: %FOLDER%
echo.
python "%HERE%analizar_calibracion.py" "%FOLDER%"
echo.
pause
