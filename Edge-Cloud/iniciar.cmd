@echo off
rem Instala (la primera vez) y arranca el Node-RED de Juegito.
rem Doble clic, o desde una terminal: iniciar.cmd
cd /d "%~dp0"

where node >nul 2>nul
if errorlevel 1 (
  echo Falta Node.js. Instalarlo desde https://nodejs.org y volver a ejecutar.
  pause
  exit /b 1
)

if not exist node_modules (
  echo Instalando dependencias, la primera vez tarda unos minutos...
  call npm install
  if errorlevel 1 (
    echo Fallo npm install. Ver la seccion "Problemas comunes" del README.
    pause
    exit /b 1
  )
)

echo.
echo Editor:     http://localhost:1880
echo Historial:  http://localhost:1880/juegito/historial
echo Para cerrar Node-RED: Ctrl+C en esta ventana.
echo.
call npm start
pause
