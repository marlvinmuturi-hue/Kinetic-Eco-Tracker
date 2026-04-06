# App Launch Issues Troubleshooting Guide

## Common Causes and Solutions

### 1. Check Logcat for Error Messages

**In Android Studio:**
1. Open **View → Tool Windows → Logcat**
2. Filter by your app package: `Kinetic_Eco.Tracker`
3. Look for red error messages (especially `FATAL EXCEPTION`)
4. Check for specific error types:
   - `RuntimeException`
   - `IllegalStateException`
   - `NullPointerException`
   - `ClassNotFoundException`

### 2. Firebase Initialization Issues

**Symptoms:**
- App crashes immediately on launch
- Error: "Default FirebaseApp is not initialized"

**Solution:**
- Firebase should auto-initialize with `google-services.json`
- Ensure `google-services.json` is in `android/app/` directory
- Verify package name matches: `Kinetic_Eco.Tracker`
- Clean and rebuild: **Build → Clean Project → Rebuild Project**

### 3. Missing Permissions

**Symptoms:**
- App crashes when requesting location
- Permission-related errors in Logcat

**Solution:**
- Check AndroidManifest.xml has all required permissions
- For Android 6.0+, runtime permissions are requested automatically
- Ensure device has location services enabled

### 4. ViewModel Initialization Issues

**Symptoms:**
- App crashes in onCreate
- Error: "Cannot create instance of ViewModel"

**Solution:**
- ViewModels require Application context
- Ensure all ViewModel dependencies are properly initialized
- Check if Room database is properly set up

### 5. Room Database Issues

**Symptoms:**
- App crashes on database access
- Error: "Cannot find implementation for Database"

**Solution:**
- Ensure KSP is properly configured
- Rebuild project to generate Room implementation
- Check `AppDatabase.kt` is properly configured

### 6. Compose Runtime Issues

**Symptoms:**
- Blank screen on launch
- Compose-related errors

**Solution:**
- Ensure Compose dependencies are up to date
- Check for Compose version compatibility
- Verify theme is properly applied

### 7. Navigation Issues

**Symptoms:**
- App launches but shows blank screen
- Navigation errors in Logcat

**Solution:**
- Check if start destination is valid
- Verify all screen routes are properly defined
- Ensure navigation graph is correctly set up

## Quick Diagnostic Steps

### Step 1: Check Logcat
```bash
# In Android Studio Terminal or ADB
adb logcat | grep -i "Kinetic_Eco"
```

### Step 2: Verify Installation
```bash
adb shell pm list packages | grep "Kinetic_Eco"
```

### Step 3: Check App Info
```bash
adb shell dumpsys package Kinetic_Eco.Tracker
```

### Step 4: Clear App Data and Reinstall
1. **Settings → Apps → Kinetic Eco Tracker → Storage → Clear Data**
2. Uninstall the app
3. Rebuild and reinstall from Android Studio

## Common Fixes

### Fix 1: Clean Build
1. **Build → Clean Project**
2. **File → Invalidate Caches → Invalidate and Restart**
3. **Build → Rebuild Project**

### Fix 2: Check Minimum SDK
- Your app requires Android 7.0 (API 24) or higher
- Ensure your device is running Android 7.0+

### Fix 3: Verify Dependencies
- All dependencies should be properly synced
- **File → Sync Project with Gradle Files**

### Fix 4: Check ProGuard (if enabled)
- If `isMinifyEnabled = true`, check ProGuard rules
- Currently disabled, so this shouldn't be an issue

## Debug Mode

To get more detailed error information:

1. **Enable Developer Options** on device
2. **Enable "Stay Awake"** and **"USB Debugging"**
3. **Enable "Show Layout Bounds"** to see UI structure
4. Check **Logcat** for detailed stack traces

## Testing on Emulator

If physical device has issues, try Android Emulator:

1. **Tools → Device Manager**
2. Create/Start an emulator (API 24+)
3. Run app on emulator
4. Check if same issue occurs

## Getting Detailed Error Information

**In Android Studio:**
1. **Run → Edit Configurations**
2. Under "Logcat" tab, check "Show logcat automatically"
3. Run the app and check Logcat window immediately

**Common Error Patterns:**
- `ClassNotFoundException` → Missing dependency or class not found
- `IllegalStateException` → State management issue
- `NullPointerException` → Null value not handled
- `SecurityException` → Permission issue
- `SQLiteException` → Database issue

## Still Not Working?

1. **Share the Logcat error message** - This will help identify the exact issue
2. **Check device compatibility** - Ensure Android 7.0+
3. **Try on different device** - Rule out device-specific issues
4. **Check Android Studio version** - Ensure latest stable version



