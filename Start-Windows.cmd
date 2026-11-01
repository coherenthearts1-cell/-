@echo off
setlocal
cd /d "%~dp0"
py -3 -c "import sys;sys.exit(sys.version_info < (3,10))" >nul 2>nul
if not errorlevel 1 goto usepy
python -c "import sys;sys.exit(sys.version_info < (3,10))" >nul 2>nul
if not errorlevel 1 goto usepython
echo Python 3.10 or newer is required. Download from https://www.python.org/downloads/windows/
echo During setup, enable the Python launcher or Add Python to PATH.
pause
exit /b 1
:usepy
py -3 app.py
goto end
:usepython
python app.py
:end
pause
