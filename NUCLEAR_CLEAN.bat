@echo off
echo =====================================================
echo   NUCLEAR CLEAN - Kinetic Eco Tracker Android
echo =====================================================
echo.
echo This will DELETE all build caches and force a fresh build.
echo.
pause

echo.
echo [1/6] Stopping Gradle daemon...
cd /d "%~dp0android"
call gradlew --stop

echo.
echo [2/6] Cleaning with Gradle...
call gradlew clean

echo.
echo [3/6] Deleting Android project build folders...
if exist ".gradle" (
    echo Deleting .gradle folder...
    rmdir /s /q ".gradle"
)
if exist "app\build" (
    echo Deleting app\build folder...
    rmdir /s /q "app\build"
)
if exist "build" (
    echo Deleting build folder...
    rmdir /s /q "build"
)

echo.
echo [4/6] Deleting Gradle caches from user folder...
if exist "%USERPROFILE%\.gradle\caches" (
    echo Deleting %USERPROFILE%\.gradle\caches...
    rmdir /s /q "%USERPROFILE%\.gradle\caches"
)

echo.
echo [5/6] Deleting Android build cache...
if exist "%USERPROFILE%\.android\build-cache" (
    echo Deleting %USERPROFILE%\.android\build-cache...
    rmdir /s /q "%USERPROFILE%\.android\build-cache"
)

echo.
echo [6/6] Done!
echo.
echo =====================================================
echo   CLEANUP COMPLETE!
echo =====================================================
echo.
echo IMPORTANT: Now do these steps IN ORDER:
echo.
echo 1. CLOSE Android Studio completely
echo 2. REOPEN Android Studio and open the 'android' folder
echo 3. Wait for Gradle sync to complete (5-10 min)
echo 4. File -^> Invalidate Caches -^> Check ALL -^> Restart
echo 5. Build -^> Clean Project
echo 6. Build -^> Rebuild Project
echo 7. UNINSTALL the app from your phone completely
echo 8. Click Run in Android Studio
echo.
echo After install, you should see:
echo - Pulsating BLUE rings (not green buttons!)
echo - Modern speedometer UI
echo - Dark gradient background
echo.
pause



