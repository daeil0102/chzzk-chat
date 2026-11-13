@echo off
cd /d "%~dp0"
"C:\Program Files\Java\jdk-21\bin\java.exe" -jar server.jar config.yaml
if errorlevel 1 pause
