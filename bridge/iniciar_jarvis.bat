@echo off
rem Inicia el puente de Jarvis. La primera vez crea el entorno e instala lo necesario.
cd /d "%~dp0"
chcp 65001 >nul

where py >nul 2>nul
if errorlevel 1 (
  echo No encontre Python. Instalalo desde https://www.python.org/downloads/ y marca "Add python.exe to PATH".
  pause
  exit /b 1
)

if not exist ".venv\Scripts\python.exe" (
  echo Preparando Jarvis por primera vez, esto tarda unos minutos...
  py -3 -m venv .venv || goto :error
  ".venv\Scripts\python.exe" -m pip install --upgrade pip || goto :error
  ".venv\Scripts\python.exe" -m pip install -r requirements.txt || goto :error
)

".venv\Scripts\python.exe" jarvis_bridge.py %*
pause
exit /b 0

:error
echo Algo fallo durante la instalacion. Copia el mensaje de arriba y envialo.
pause
exit /b 1
