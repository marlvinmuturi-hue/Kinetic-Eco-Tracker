@echo off
echo Fixing Kotlin build cache lock...
echo.

REM Stop Gradle daemons (releases file locks)
if exist gradlew.bat (
    echo Stopping Gradle daemons...
    call gradlew.bat --stop 2>nul
    timeout /t 2 /nobreak >nul
)

REM Delete Kotlin compilation cache (the locked folder)
echo Removing Kotlin compile cache...
if exist "app\build\kotlin\compileDebugKotlin" (
    rmdir /s /q "app\build\kotlin\compileDebugKotlin" 2>nul
    if exist "app\build\kotlin\compileDebugKotlin" (
        echo WARNING: Could not delete - close Android Studio and try again
    ) else (
        echo Kotlin cache removed.
    )
)

REM Optional: full clean
echo Running clean...
if exist gradlew.bat (
    call gradlew.bat clean --no-daemon 2>nul
    echo.
    echo Done. Try building again: gradlew.bat assembleDebug
) else (
    echo gradlew.bat not found. Delete app\build\kotlin manually, then rebuild.
)
echo.
pause
