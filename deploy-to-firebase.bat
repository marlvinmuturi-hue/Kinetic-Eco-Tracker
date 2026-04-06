@echo off
echo ================================================
echo  DEPLOY TO FIREBASE HOSTING
echo ================================================
echo.
echo This will deploy your web app to Firebase.
echo.
echo What this does:
echo   1. Builds your React app (production)
echo   2. Deploys to Firebase Hosting
echo   3. Makes it available at: https://0114974661.web.app
echo.
echo ================================================
echo.

cd /d "%~dp0"

echo [1/3] Installing dependencies (if needed)...
call npm install
if %errorlevel% neq 0 (
    echo ERROR: Failed to install dependencies
    pause
    exit /b 1
)
echo       Done!
echo.

echo [2/3] Building production app...
echo       This may take 2-3 minutes...
call npm run build
if %errorlevel% neq 0 (
    echo ERROR: Build failed!
    echo Please fix any errors and try again.
    pause
    exit /b 1
)
echo       Done! Production build created.
echo.

echo [3/3] Deploying to Firebase Hosting...
echo       This may take 2-3 minutes...
call firebase deploy --only hosting
if %errorlevel% neq 0 (
    echo ERROR: Deployment failed!
    echo.
    echo Possible issues:
    echo   - Not logged into Firebase (run: firebase login)
    echo   - No internet connection
    echo   - Firebase CLI not installed
    echo.
    pause
    exit /b 1
)

echo.
echo ================================================
echo.
echo SUCCESS! Your app is deployed! 🎉
echo.
echo Your app is now live at:
echo   https://0114974661.web.app
echo.
echo NEXT STEPS:
echo.
echo 1. Open browser and visit:
echo    https://0114974661.web.app
echo.
echo 2. Test that it works (should see your app)
echo.
echo 3. Update Android app in MainActivity.kt:
echo    webView.loadUrl("https://0114974661.web.app")
echo.
echo 4. In Android Studio:
echo    Build -^> Rebuild Project
echo    Run -^> Run 'app'
echo.
echo 5. Test on device:
echo    - App loads from Firebase
echo    - Google Sign-In works
echo    - No auth errors
echo    - Purple FAB button works
echo.
echo ================================================
echo.
pause



