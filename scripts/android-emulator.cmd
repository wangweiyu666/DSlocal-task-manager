@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0android-emulator.ps1" %*
exit /b %ERRORLEVEL%
