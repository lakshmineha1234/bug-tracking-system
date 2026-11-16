@echo off
rem Starts the local MySQL server for BugTrack (data in %USERPROFILE%\.bugtrack-mysql, localhost only).
rem Keep this window open while using the app; press Ctrl+C to stop MySQL.
"C:\Program Files\MySQL\MySQL Server 8.4\bin\mysqld.exe" --defaults-file="%USERPROFILE%\.bugtrack-mysql\my.ini" --console
