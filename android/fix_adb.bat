@echo off
echo ========================================
echo ADB Connection Troubleshooting Script
echo ========================================
echo.

REM Try to find ADB in common locations
set ADB_PATH=
if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" (
    set ADB_PATH=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
) else if exist "%USERPROFILE%\AppData\Local\Android\Sdk\platform-tools\adb.exe" (
    set ADB_PATH=%USERPROFILE%\AppData\Local\Android\Sdk\platform-tools\adb.exe
) else if exist "C:\Android\Sdk\platform-tools\adb.exe" (
    set ADB_PATH=C:\Android\Sdk\platform-tools\adb.exe
) else (
    echo ERROR: ADB not found in common locations.
    echo Please locate your Android SDK platform-tools folder and update this script.
    echo.
    pause
    exit /b 1
)

echo Found ADB at: %ADB_PATH%
echo.

echo Step 1: Killing existing ADB server...
"%ADB_PATH%" kill-server
timeout /t 2 /nobreak >nul

echo Step 2: Starting ADB server...
"%ADB_PATH%" start-server
timeout /t 2 /nobreak >nul

echo Step 3: Checking connected devices...
echo.
"%ADB_PATH%" devices
echo.

echo ========================================
echo Troubleshooting Steps:
echo ========================================
echo 1. Ensure your device is connected via USB
echo 2. Enable USB Debugging in Developer Options
echo 3. Accept the USB debugging authorization prompt on your device
echo 4. If device shows as "unauthorized", revoke USB debugging and reconnect
echo 5. Try installing the app again from Android Studio
echo.
echo ========================================
pause



