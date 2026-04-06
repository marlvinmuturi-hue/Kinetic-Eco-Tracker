# Crash Fix Applied - ViewModel Initialization

## Problem
The app was crashing on launch with a black screen because the ViewModels (`AuthViewModel`, `TrackerViewModel`, `AnalyticsViewModel`) require an `Application` context, but `by viewModels()` was using the default factory which doesn't provide it.

## Solution Applied
Updated `MainActivity.kt` to use `ViewModelProvider.AndroidViewModelFactory.getInstance(application)` for all ViewModels:

```kotlin
private val authViewModel: AuthViewModel by viewModels {
    ViewModelProvider.AndroidViewModelFactory.getInstance(application)
}
private val trackerViewModel: TrackerViewModel by viewModels {
    ViewModelProvider.AndroidViewModelFactory.getInstance(application)
}
private val analyticsViewModel: AnalyticsViewModel by viewModels {
    ViewModelProvider.AndroidViewModelFactory.getInstance(application)
}
```

## Next Steps

1. **Clean and Rebuild:**
   - In Android Studio: **Build → Clean Project**
   - Then: **Build → Rebuild Project**

2. **Uninstall Old App:**
   - On your device, uninstall the old version of the app
   - Or use: `adb uninstall Kinetic_Eco.Tracker`

3. **Install Fresh Build:**
   - In Android Studio: **Run → Run 'app'**
   - Or build APK and install manually

4. **Check Logcat:**
   - If app still doesn't launch, check Logcat for `FATAL EXCEPTION`
   - Filter by: `package:Kinetic_Eco.Tracker`

## Expected Behavior After Fix

- App should launch without crashing
- Login screen should appear (if not logged in)
- No black screen
- ViewModels should initialize properly

## If Issues Persist

1. Check Logcat for actual crash stack trace
2. Verify `google-services.json` exists in `android/app/`
3. Ensure all dependencies are synced (File → Sync Project with Gradle Files)
4. Try invalidating caches: **File → Invalidate Caches / Restart**



