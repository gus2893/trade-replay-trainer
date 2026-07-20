@echo off
rem One-click Tape Dojo: starts the backend (which also serves the UI) and opens the app.
cd /d "%~dp0backend"
tasklist /fi "windowtitle eq TapeDojo Backend" 2>nul | find "cmd.exe" >nul
if errorlevel 1 (
  start "TapeDojo Backend" cmd /c mvnw.cmd -q spring-boot:run
  echo Starting Tape Dojo backend...
  timeout /t 12 /nobreak >nul
)
start http://localhost:8080/index.html
