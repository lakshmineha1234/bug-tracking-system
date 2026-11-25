@echo off
rem Starts BugTrack: MySQL, the test mail server (if your local settings use it) and the app,
rem then opens http://localhost:8080. Add -NoBrowser to skip opening the browser.
echo Starting BugTrack...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start.ps1" %*
if errorlevel 1 (
  echo.
  echo BugTrack did not start. See the message above.
  pause
  exit /b 1
)
ping -n 4 127.0.0.1 >nul
