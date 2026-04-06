@echo off
echo ================================================
echo  ANDROID STUDIO REFRESH SCRIPT
echo ================================================
echo.
echo This script will help refresh Android Studio to see the new changes.
echo.

echo Step 1: Checking if files exist...
echo.

if exist "app\src\main\res\layout\activity_main.xml" (
    echo [OK] activity_main.xml found
) else (
    echo [ERROR] activity_main.xml NOT found!
)

if exist "app\src\main\res\drawable\ic_activity.xml" (
    echo [OK] ic_activity.xml found
) else (
    echo [ERROR] ic_activity.xml NOT found!
)

if exist "app\src\main\java\Kinetic_Eco\Tracker\MainActivity.kt" (
    echo [OK] MainActivity.kt found
) else (
    echo [ERROR] MainActivity.kt NOT found!
)

if exist "app\build.gradle.kts" (
    echo [OK] build.gradle.kts found
) else (
    echo [ERROR] build.gradle.kts NOT found!
)

echo.
echo ================================================
echo.

echo Step 2: Cleaning Gradle caches...
echo.

if exist ".gradle" (
    echo Deleting .gradle folder...
    rmdir /s /q ".gradle"
    echo [OK] Deleted project .gradle folder
) else (
    echo [SKIP] No .gradle folder found
)

if exist "app\build" (
    echo Deleting app\build folder...
    rmdir /s /q "app\build"
    echo [OK] Deleted app\build folder
) else (
    echo [SKIP] No app\build folder found
)

if exist "build" (
    echo Deleting build folder...
    rmdir /s /q "build"
    echo [OK] Deleted build folder
) else (
    echo [SKIP] No build folder found
)

echo.
echo ================================================
echo.
echo SUCCESS! Gradle caches cleared.
echo.
echo NEXT STEPS:
echo.
echo 1. Open Android Studio
echo 2. File -^> Open -^> Select the 'android' folder
echo 3. Wait for Gradle sync to complete (3-5 minutes)
echo 4. File -^> Invalidate Caches -^> Invalidate and Restart
echo 5. After restart, Build -^> Clean Project
echo 6. Build -^> Rebuild Project
echo 7. Run the app on your device
echo.
echo You should now see:
echo   - Purple FAB button in bottom-right corner
echo   - Tap it to open activity selector
echo.
echo ================================================
echo.
pause



