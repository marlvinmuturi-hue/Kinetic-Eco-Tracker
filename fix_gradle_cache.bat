@echo off
echo ========================================
echo  Fixing Gradle Cache Error
echo ========================================
echo.
echo This will delete corrupted Gradle caches
echo and force a fresh sync.
echo.
pause

echo.
echo [1/4] Deleting corrupted Gradle caches...
if exist "%USERPROFILE%\.gradle\caches" (
    echo Deleting %USERPROFILE%\.gradle\caches...
    rmdir /s /q "%USERPROFILE%\.gradle\caches"
    echo Done!
) else (
    echo Cache folder not found, skipping...
)

echo.
echo [2/4] Deleting daemon files...
if exist "%USERPROFILE%\.gradle\daemon" (
    echo Deleting %USERPROFILE%\.gradle\daemon...
    rmdir /s /q "%USERPROFILE%\.gradle\daemon"
    echo Done!
) else (
    echo Daemon folder not found, skipping...
)

echo.
echo [3/4] Deleting project-specific Gradle files...
cd /d "%~dp0android"
if exist ".gradle" (
    echo Deleting project .gradle folder...
    rmdir /s /q ".gradle"
    echo Done!
) else (
    echo Project .gradle not found, skipping...
)

echo.
echo [4/4] Stopping Gradle daemon...
call gradlew --stop
echo Done!

echo.
echo ========================================
echo  Cleanup Complete!
echo ========================================
echo.
echo Now do this in Android Studio:
echo 1. File -^> Sync Project with Gradle Files
echo 2. Wait 5-10 minutes for fresh download
echo 3. Build -^> Rebuild Project
echo.
pause



