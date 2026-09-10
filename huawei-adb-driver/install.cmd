@echo off
REM Thin launcher for install.ps1, which does the actual work: batch cannot
REM enumerate PnP devices, so the driver binding lives on the PowerShell side.
REM -ExecutionPolicy Bypass is scoped to this one process.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1"
pause
