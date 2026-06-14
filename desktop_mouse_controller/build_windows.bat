@echo off
setlocal
cd /d "%~dp0"

title MouseSlideController Builder
set "LOG_FILE=%~dp0build_windows.log"
set "ICON_FILE=%~dp0app.ico"
set "ICON_PNG=%~dp0app.png"
set "ICON_OPTION="
set "ADD_DATA_OPTION="

echo ========================================
echo MouseSlideController Windows Builder
echo ========================================
echo Current directory: %cd%
echo Log file: %LOG_FILE%
echo.

if exist "%ICON_FILE%" (
    set "ICON_OPTION=--icon=%ICON_FILE%"
    set "ADD_DATA_OPTION=--add-data=%ICON_FILE%;."
    echo Icon file: %ICON_FILE%
) else if exist "%ICON_PNG%" (
    set "ICON_OPTION=--icon=%ICON_FILE%"
    set "ADD_DATA_OPTION=--add-data=%ICON_PNG%;. --add-data=%ICON_FILE%;."
    echo PNG icon file: %ICON_PNG%
    echo It will be converted to app.ico before build.
) else (
    echo Icon file: not found, default exe icon will be used.
    echo Put your icon at app.ico or app.png to customize the exe icon.
)
echo.

where python >nul 2>nul
if %errorlevel%==0 (
    set "PYTHON_CMD=python"
) else (
    where py >nul 2>nul
    if %errorlevel%==0 (
        set "PYTHON_CMD=py"
    ) else (
        echo Python command was not found.
        echo Reinstall Python and enable: Add python.exe to PATH
        pause
        exit /b 1
    )
)

echo Python command: %PYTHON_CMD%
%PYTHON_CMD% --version
echo.

echo [1/3] Upgrading pip...
%PYTHON_CMD% -m pip install --upgrade pip > "%LOG_FILE%" 2>&1
if errorlevel 1 goto :error

echo [2/3] Installing dependencies...
%PYTHON_CMD% -m pip install -r requirements.txt >> "%LOG_FILE%" 2>&1
if errorlevel 1 goto :error

echo [3/3] Installing PyInstaller and building exe...
%PYTHON_CMD% -m pip install pyinstaller >> "%LOG_FILE%" 2>&1
if errorlevel 1 goto :error

if exist "%ICON_FILE%" (
    echo Installing Pillow for icon conversion...
    %PYTHON_CMD% -m pip install pillow >> "%LOG_FILE%" 2>&1
    if errorlevel 1 goto :error
)

if not exist "%ICON_FILE%" if exist "%ICON_PNG%" (
    echo Installing Pillow and converting app.png to app.ico...
    %PYTHON_CMD% -m pip install pillow >> "%LOG_FILE%" 2>&1
    if errorlevel 1 goto :error
    %PYTHON_CMD% -c "from PIL import Image; img=Image.open(r'%ICON_PNG%').convert('RGBA'); img.save(r'%ICON_FILE%', sizes=[(256,256),(128,128),(64,64),(32,32),(16,16)])" >> "%LOG_FILE%" 2>&1
    if errorlevel 1 goto :error
)

if exist "%ICON_FILE%" if exist "%ICON_PNG%" (
    set "ADD_DATA_OPTION=--add-data=%ICON_PNG%;. --add-data=%ICON_FILE%;."
)
if exist "%ICON_FILE%" if not exist "%ICON_PNG%" (
    set "ADD_DATA_OPTION=--add-data=%ICON_FILE%;."
)

%PYTHON_CMD% -m PyInstaller --onefile --windowed %ICON_OPTION% %ADD_DATA_OPTION% --name MouseSlideController mouse_controller.py >> "%LOG_FILE%" 2>&1
if errorlevel 1 (
    if exist "%ICON_FILE%" (
        echo Icon build failed. Retrying without custom icon...
        echo. >> "%LOG_FILE%"
        echo Icon build failed. Retrying without custom icon... >> "%LOG_FILE%"
        %PYTHON_CMD% -m PyInstaller --onefile --windowed --name MouseSlideController mouse_controller.py >> "%LOG_FILE%" 2>&1
        if errorlevel 1 goto :error
    ) else (
        goto :error
    )
)

echo.
echo Build completed.
echo Exe path: %~dp0dist\MouseSlideController.exe
if exist "%ICON_FILE%" echo Custom icon was requested. If Explorer still shows old icon, refresh Windows icon cache or rename the exe.
echo.
pause
exit /b 0

:error
echo.
echo Build failed. Please check log: %LOG_FILE%
echo.
type "%LOG_FILE%"
echo.
pause
exit /b 1
