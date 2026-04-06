# Background Activity: Current vs Health App Patterns

## Comparison: Your App vs Typical Health Apps

| Aspect | Your App (Current) | Typical Health Apps (e.g. Strava, Google Fit) |
|--------|--------------------|----------------------------------------------|
| **Foreground service type** | `location` for both services | `health` for activity monitoring, `health\|location` for tracking |
| **AutoStartMonitorService** | Uses `location` type but doesn't use location | Use `health` type (activity recognition is fitness-related) |
| **Battery optimization** | No user guidance | Often prompt user to disable battery optimization or add to "protected apps" |
| **Manufacturer-specific** | No handling | Guide users for Xiaomi "Autostart", Samsung "Never sleeping", Huawei "Protected apps" |
| **Service restart** | Only on boot (BootReceiver) | Boot + periodic WorkManager/JobScheduler to restart if killed |
| **startForeground() type** | Not explicitly passing type (relies on manifest) | Android 14+ requires passing `ServiceInfo.FOREGROUND_SERVICE_TYPE_*` to `startForeground()` |
| **Background location** | Declared in manifest | Required for TrackingService when started from background (e.g. auto-start) |

---

## Likely Issues & Recommended Changes

### 1. **Wrong foreground service type for AutoStartMonitorService** (High impact)

**Current:** `foregroundServiceType="location"` — but the service only uses Activity Recognition, not location.

**Problem:** On Android 14+, foreground services must match their actual use. Using `location` for activity-only monitoring can cause:
- Play Store policy issues
- System may kill the service if it doesn't actually use location

**Fix:** Switch to `foregroundServiceType="health"` and add `FOREGROUND_SERVICE_HEALTH` permission. You already have `ACTIVITY_RECOGNITION`, which satisfies the health type prerequisite.

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_HEALTH" />

<service android:name=".services.AutoStartMonitorService"
    android:foregroundServiceType="health" />
```

Also update `startForeground()` in the service to pass the type (required on Android 14+):

```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
} else {
    startForeground(NOTIFICATION_ID, notification)
}
```

---

### 2. **TrackingService started from background** (High impact)

**Current:** When `ActivityTransitionReceiver` detects walking, it starts `TrackingService` via `startForegroundService()`. At that moment the app may be in the background.

**Problem:** On Android 10+, starting a `location` foreground service from the background requires `ACCESS_BACKGROUND_LOCATION`. Without it, the system may block the start or kill the service.

**Fix:** Ensure you request and obtain `ACCESS_BACKGROUND_LOCATION` when the user enables auto-start. You already declare it in the manifest; verify it's requested at runtime (it's a "dangerous" permission that needs user grant).

---

### 3. **No battery optimization guidance** (Medium impact)

**Current:** No in-app guidance for battery optimization.

**Problem:** On Xiaomi, Huawei, Samsung, Oppo, etc., aggressive battery optimization can kill background services even when they're foreground services. Users must manually:
- Disable battery optimization for your app
- Add the app to "Autostart" / "Protected apps" / "Never sleeping"

**Fix:** Add a Settings card or first-run prompt that:
1. Checks if the app is battery-optimized
2. If yes, shows instructions or opens `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (use sparingly; Play policy restricts this for most apps)
3. For manufacturer-specific settings, show a help screen with links/instructions for common OEMs

---

### 4. **No periodic service restart** (Medium impact)

**Current:** `AutoStartMonitorService` is started on boot and when the user enables the toggle. If the system kills it (e.g. low memory, OEM battery saver), it stays dead until the next boot or app launch.

**Fix:** Use WorkManager to run a periodic job (e.g. every 15–30 minutes) that:
1. Checks if auto-start is enabled
2. Checks if `AutoStartMonitorService` is running
3. If not running, starts it

This mimics how health apps keep monitoring alive.

---

### 5. **startForeground() must pass service type on Android 14+** (Medium impact)

**Current:** `startForeground(NOTIFICATION_ID, notification)` without the service type.

**Problem:** Android 14 (API 34) requires passing the foreground service type to `startForeground()` when targeting API 34+.

**Fix:** In both `AutoStartMonitorService` and `TrackingService`:

```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
    startForeground(NOTIFICATION_ID, notification, 
        ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)  // or TYPE_LOCATION for TrackingService
} else {
    startForeground(NOTIFICATION_ID, notification)
}
```

---

### 6. **BootReceiver and Android 15** (Low impact for now)

**Current:** `BootReceiver` starts `AutoStartMonitorService` on `BOOT_COMPLETED`.

**Note:** Android 15 restricts some foreground service types from being started from `BOOT_COMPLETED`. The `health` type is not in the restricted list (dataSync and mediaPlayback are). So switching to `health` type should keep boot behavior working.

---

## Summary of Recommended Changes (Priority Order)

1. **Switch AutoStartMonitorService to `health` type** — Add `FOREGROUND_SERVICE_HEALTH`, update manifest and `startForeground()`.
2. **Pass service type in `startForeground()`** — For both services, on Android 14+.
3. **Verify ACCESS_BACKGROUND_LOCATION** — Request and handle it when auto-start is enabled.
4. **Add WorkManager periodic restart** — Restart `AutoStartMonitorService` if it's not running and auto-start is enabled.
5. **Add battery optimization guidance** — Settings screen or help text for OEM-specific "protected app" / "autostart" settings.

---

## Files to Modify

| File | Changes |
|------|---------|
| `AndroidManifest.xml` | Add `FOREGROUND_SERVICE_HEALTH`, change AutoStartMonitorService to `health` type |
| `AutoStartMonitorService.kt` | Pass `FOREGROUND_SERVICE_TYPE_HEALTH` to `startForeground()` on API 34+ |
| `TrackingService.kt` | Pass `FOREGROUND_SERVICE_TYPE_LOCATION` to `startForeground()` on API 34+ |
| `MainActivity.kt` | Request `ACCESS_BACKGROUND_LOCATION` when user enables auto-start (if not already) |
| New: `AutoStartWorkManager.kt` or similar | Periodic job to restart AutoStartMonitorService if needed |
| `SettingsScreen.kt` | Optional: add "Battery & background" help card with OEM instructions |
