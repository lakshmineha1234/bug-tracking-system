@echo off
rem Stops BugTrack: the app, the test mail server and MySQL (shut down cleanly).
rem A MySQL Windows service is left running; add -IncludeService to stop it too.
echo Stopping BugTrack...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\stop.ps1" %*
if errorlevel 1 (
  echo.
  echo Something could not be stopped. See the message above.
  pause
  exit /b 1
)
ping -n 4 127.0.0.1 >nul
