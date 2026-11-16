@echo off
rem Maven wrapper for Windows: downloads Maven on first use (see .mvn\wrapper), then runs it.
setlocal
set "MAVEN_HOME="
for /f "delims=" %%M in ('powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0.mvn\wrapper\download-maven.ps1"') do set "MAVEN_HOME=%%M"
if not defined MAVEN_HOME (
  echo Could not download Maven. Check your internet connection. 1>&2
  exit /b 1
)
call "%MAVEN_HOME%\bin\mvn.cmd" %*
exit /b %ERRORLEVEL%
