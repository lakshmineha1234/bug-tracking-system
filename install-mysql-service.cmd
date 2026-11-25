@echo off
rem Registers BugTrack's MySQL as a Windows service that starts with Windows.
rem Right-click and choose "Run as administrator". Add -Remove to unregister it again.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\install-mysql-service.ps1" %*
echo.
pause
