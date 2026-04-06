# Android App Installation Troubleshooting Guide

## Error: "Can't find service: package"

This error typically indicates an ADB (Android Debug Bridge) connection issue. Follow these steps to resolve it:

## Solution Steps

### 1. Restart ADB Server (Recommended First Step)

**In Android Studio:**
1. Open **Terminal** tab at the bottom of Android Studio
2. Run these commands:
   ```bash
   adb kill-server
   adb start-server
   adb devices
   ```

**Or manually:**
- Close Android Studio completely
- Open Command Prompt or PowerShell as Administrator
- Navigate to Android SDK platform-tools directory (usually `C:\Users\YOUR_USERNAME\AppData\Local\Android\Sdk\platform-tools`)
- Run:
  ```cmd
  adb kill-server
  adb start-server
  adb devices
  ```

### 2. Check Device Connection

1. **Enable USB Debugging:**
   - On your Android device: Settings → About Phone
   - Tap "Build Number" 7 times to enable Developer Options
   - Go back to Settings → Developer Options
   - Enable "USB Debugging"

2. **Authorize Computer:**
   - Connect device via USB
   - When prompted on device: "Allow USB debugging?" → Check "Always allow from this computer" → OK

3. **Verify Connection:**
   ```bash
   adb devices
   ```
   Should show your device with "device" status (not "unauthorized" or "offline")

### 3. Try Different Connection Methods

**Option A: USB Connection**
- Use a different USB cable
- Try a different USB port
- Ensure USB is set to "File Transfer" or "MTP" mode (not "Charging only")

**Option B: Wireless ADB (Android 11+)**
1. Connect via USB first
2. Enable "Wireless debugging" in Developer Options
3. Run: `adb tcpip 5555`
4. Disconnect USB
5. Connect wirelessly: `adb connect DEVICE_IP:5555`

### 4. Restart Android Studio

1. Close Android Studio completely
2. Restart your computer (if needed)
3. Reopen Android Studio
4. Try installing again

### 5. Clean and Rebuild

In Android Studio:
1. **Build → Clean Project**
2. **Build → Rebuild Project**
3. **Run → Run 'app'** (or click the green play button)

### 6. Check Device Compatibility

- Ensure your device meets minimum SDK requirements (minSdk = 24, Android 7.0+)
- Check if device has enough storage space

### 7. Alternative: Install APK Manually

1. Build APK: **Build → Build Bundle(s) / APK(s) → Build APK(s)**
2. Locate APK: `android/app/build/outputs/apk/debug/app-debug.apk`
3. Transfer APK to device (via USB, email, or cloud)
4. On device: Open file manager → Tap APK → Install
5. Enable "Install from Unknown Sources" if prompted

### 8. Check Android Studio Settings

1. **File → Settings → Build, Execution, Deployment → Deployment**
2. Ensure "Deploy APK" is selected (not "Deploy APK from app bundle")
3. Check "Use same device for future launches"

### 9. Update ADB and Android Studio

1. **Tools → SDK Manager**
2. Update "Android SDK Platform-Tools"
3. Update Android Studio to latest version

### 10. Check Device Logs

In Android Studio:
1. **View → Tool Windows → Logcat**
2. Filter by "package" or "install"
3. Look for specific error messages

## Quick Fix Script

Create a file `fix_adb.bat` in the `android` folder:

```batch
@echo off
echo Restarting ADB server...
cd /d "%~dp0"
if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" (
    "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" kill-server
    timeout /t 2 /nobreak >nul
    "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" start-server
    timeout /t 2 /nobreak >nul
    "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" devices
    echo.
    echo ADB server restarted. Try installing the app again.
) else (
    echo ADB not found. Please check Android SDK installation.
)
pause
```

## Common Causes

1. **ADB server not running** - Most common cause
2. **Device not authorized** - Need to accept USB debugging prompt
3. **USB connection issues** - Bad cable or port
4. **Outdated ADB** - Need to update platform-tools
5. **Device in wrong mode** - Should be in File Transfer/MTP mode
6. **Multiple ADB instances** - Kill all and restart

## Still Having Issues?

1. Check Android Studio's **Run** tab for more detailed error messages
2. Check device's **Developer Options → USB Debugging** is enabled
3. Try installing on a different device or emulator
4. Check if antivirus/firewall is blocking ADB
5. Restart both computer and Android device

## Success Indicators

When working correctly:
- `adb devices` shows your device with "device" status
- Android Studio shows device in device selector dropdown
- Installation proceeds without errors
- App appears on device after installation



