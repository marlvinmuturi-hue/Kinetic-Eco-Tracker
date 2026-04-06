@echo off
echo Cleaning Android build cache...
cd /d "%~dp0"
if exist gradlew.bat (
    call gradlew.bat clean --no-daemon
    echo Build cache cleaned successfully!
) else (
    echo gradlew.bat not found. Please run this from the android directory.
)
pause



