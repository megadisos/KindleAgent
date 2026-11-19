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

rem cloudflared abre el link para usar Jarvis fuera de casa (gratis, sin cuenta).
where cloudflared >nul 2>nul && goto :run
if exist "%LOCALAPPDATA%\Microsoft\WinGet\Links\cloudflared.exe" goto :run
if exist "%ProgramFiles(x86)%\cloudflared\cloudflared.exe" goto :run
if exist "%ProgramFiles%\cloudflared\cloudflared.exe" goto :run
echo Instalando cloudflared para usar Jarvis fuera de casa (solo esta vez)...
winget install --id Cloudflare.cloudflared -e --accept-source-agreements --accept-package-agreements
if errorlevel 1 echo No pude instalar cloudflared. Jarvis funcionara solo en el wifi de casa.

:run
".venv\Scripts\python.exe" jarvis_bridge.py %*
pause
exit /b 0

:error
echo Algo fallo durante la instalacion. Copia el mensaje de arriba y envialo.
pause
exit /b 1
