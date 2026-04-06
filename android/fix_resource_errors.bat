@echo off
echo ================================================
echo  RESOURCE LINKING ERROR - CLEANUP
echo ================================================
echo.
echo Cleaning up old build artifacts...
echo.

cd /d "%~dp0"

if exist ".gradle" (
    echo Deleting .gradle folder...
    rmdir /s /q ".gradle"
    echo [OK] Deleted
) else (
    echo [SKIP] No .gradle folder
)

if exist "app\build" (
    echo Deleting app\build folder...
    rmdir /s /q "app\build"
    echo [OK] Deleted
) else (
    echo [SKIP] No app\build folder
)

if exist "build" (
    echo Deleting build folder...
    rmdir /s /q "build"
    echo [OK] Deleted
) else (
    echo [SKIP] No build folder
)

if exist "app\build\generated" (
    echo Deleting generated folder...
    rmdir /s /q "app\build\generated"
    echo [OK] Deleted
) else (
    echo [SKIP] No generated folder
)

echo.
echo ================================================
echo.
echo SUCCESS! Old build artifacts cleaned.
echo.
echo NEXT STEPS:
echo.
echo 1. Open Android Studio
echo 2. Open the 'android' folder
echo 3. Build -^> Clean Project
echo 4. File -^> Sync Project with Gradle Files
echo 5. Build -^> Rebuild Project
echo 6. Run on device
echo.
echo The resource linking errors should be GONE!
echo.
echo ================================================
echo.
pause



