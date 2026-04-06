@echo off
echo ========================================
echo Kinetic AI Analysis Setup
echo ========================================
echo.

echo Step 1: Installing Node.js dependencies...
cd functions
call npm install
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: Failed to install dependencies
    pause
    exit /b 1
)
echo ✓ Dependencies installed
echo.

echo Step 2: Checking Firebase login...
call firebase login:list
if %ERRORLEVEL% NEQ 0 (
    echo Please login to Firebase...
    call firebase login
)
echo ✓ Firebase authenticated
echo.

echo Step 3: Setting project...
call firebase use --add
echo ✓ Project selected
echo.

echo ========================================
echo Setup Complete!
echo ========================================
echo.
echo Next steps:
echo 1. Get Gemini API key from: https://aistudio.google.com/app/apikey
echo 2. Run: firebase functions:config:set gemini.key="YOUR_API_KEY"
echo 3. Run: firebase deploy --only functions
echo.
pause
