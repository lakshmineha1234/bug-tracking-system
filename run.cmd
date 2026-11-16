@echo off
rem Starts BugTrack on http://localhost:8080.
rem Uses the portable JDK in %USERPROFILE%\.jdks if one is there; otherwise JAVA_HOME must point to JDK 17+.
setlocal
for /d %%J in ("%USERPROFILE%\.jdks\jdk-21*") do set "JAVA_HOME=%%J"
if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d "%~dp0"
call "%~dp0mvnw.cmd" spring-boot:run
