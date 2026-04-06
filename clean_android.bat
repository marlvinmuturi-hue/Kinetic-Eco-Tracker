@echo off
echo ========================================
echo  Cleaning Android Project for WebView
echo ========================================
echo.

cd /d "%~dp0android"

echo [1/4] Stopping Gradle daemon...
call gradlew --stop

echo [2/4] Cleaning build artifacts...
call gradlew clean

echo [3/4] Deleting .gradle folders...
if exist ".gradle" rmdir /s /q ".gradle"
if exist "app\build" rmdir /s /q "app\build"
if exist "build" rmdir /s /q "build"

echo [4/4] Done!
echo.
echo ========================================
echo  Cleanup Complete!
echo ========================================
echo.
echo Next steps:
echo 1. Open Android Studio
echo 2. File -^> Sync Project with Gradle Files
echo 3. Build -^> Rebuild Project
echo 4. Uninstall old app from phone
echo 5. Click Run
echo.
pause



