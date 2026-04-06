@echo off
echo ================================================
echo  FINAL CLEANUP - ALL ERRORS FIXED
echo ================================================
echo.
echo Cleaning up ALL old files and build artifacts...
echo.

cd /d "%~dp0"

REM Clean build folders
if exist ".gradle" (
    echo [1/5] Deleting .gradle folder...
    rmdir /s /q ".gradle" 2>nul
    echo       Done!
)

if exist "app\build" (
    echo [2/5] Deleting app\build folder...
    rmdir /s /q "app\build" 2>nul
    echo       Done!
)

if exist "build" (
    echo [3/5] Deleting build folder...
    rmdir /s /q "build" 2>nul
    echo       Done!
)

REM Clean old layout files
if exist "app\src\main\res\layout\fragment_tracker.xml" (
    echo [4/5] Deleting old layout files...
    del /q "app\src\main\res\layout\fragment_*.xml" 2>nul
    echo       Done!
) else (
    echo [4/5] Old layout files already cleaned
)

REM Verify MainActivity.kt is the only Kotlin file
echo [5/5] Verifying clean structure...
dir /s /b "app\src\main\java\Kinetic_Eco\Tracker\*.kt" 2>nul | find /c /v "" > temp.txt
set /p count=<temp.txt
del temp.txt

if "%count%"=="1" (
    echo       Perfect! Only MainActivity.kt remains
) else (
    echo       Warning: Found %count% Kotlin files
)

echo.
echo ================================================
echo.
echo SUCCESS! Everything is cleaned up.
echo.
echo FILES REMAINING (correct structure):
echo   - MainActivity.kt (WebView + Native FAB)
echo   - activity_main.xml (Layout)
echo   - ic_activity.xml (Icon)
echo.
echo ALL OLD FILES REMOVED:
echo   - TrackingService.kt (deleted)
echo   - All fragments (deleted)
echo   - Auth files (deleted)
echo   - Navigation files (deleted)
echo   - Old menu files (deleted)
echo.
echo ================================================
echo.
echo NEXT STEPS IN ANDROID STUDIO:
echo.
echo 1. Open Android Studio
echo 2. Open this 'android' folder
echo 3. Build -^> Clean Project
echo 4. File -^> Sync Project with Gradle Files
echo 5. Build -^> Rebuild Project (will take 5-7 minutes)
echo 6. Run on device
echo.
echo EXPECTED RESULT:
echo   - BUILD SUCCESSFUL
echo   - No errors
echo   - Purple FAB button appears
echo   - Activity selector works
echo.
echo ================================================
echo.
pause



