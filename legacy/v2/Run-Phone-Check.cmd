@echo off
setlocal
cd /d "%~dp0"
:menu
echo.
echo ANDROID TOOLS
echo 1. Create report or set up Google ADB
echo 2. Open phone management menu
echo 0. Exit
choice /c 120 /n /m "Press 1, 2 or 0: "
if errorlevel 3 exit /b
if errorlevel 2 goto manage
goto report
:report
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0Check-Phone.ps1"
goto menu
:manage
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0Manage-Phone.ps1"
goto menu
