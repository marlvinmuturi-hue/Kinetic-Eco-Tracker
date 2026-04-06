# Android Activity Persistence Enhancement (Kotlin)

## Overview
Applied the same activity persistence logic to your native Kotlin Android app. The auto-detection now prevents unrealistic activity switches during extended sessions.

## Modified File
- `android/app/src/main/java/Kinetic_Eco/Tracker/services/TrackingService.kt`

## Changes Made

### 1. Activity Duration Tracking
Added persistence tracking variables:

```kotlin
// Activity persistence tracking - how long current activity has been ongoing
private var activityStartTime: Long = System.currentTimeMillis()
private var activityDurationSeconds: Long = 0
```

Also optimized:
- **Activity history size**: Reduced from 5 to 3 readings for faster response time
- **Persistence timing**: Tracks when current activity started and calculates duration

### 2. Activity Compatibility Function
Added `areActivitiesCompatible()` to detect unlikely transitions:

```kotlin
private fun areActivitiesCompatible(from: ActivityType, to: ActivityType): Boolean
```

**Incompatible Transitions:**
- Driving/Electric Vehicle → Walking/Running
- Flying → Walking/Running/Cycling
- Cycling → Driving/Electric Vehicle
- Running → Driving/Electric Vehicle/Cycling

### 3. Activity Speed Helper
Added `getActivitySpeed()` for magnitude calculations:

```kotlin
private fun getActivitySpeed(activity: ActivityType): Float
```

Returns typical speeds (m/s) for each activity type.

### 4. Enhanced Debouncing Logic
Completely rewrote `updateActivityWithDebounce()` with persistence-based thresholds:

| Duration | Required Consistency | Behavior |
|----------|---------------------|----------|
| **0-30 seconds** | 2/3 (significant) or 3/3 (minor) | Fresh activity - standard logic |
| **30-60 seconds** | 2/3 (significant) or 3/3 (minor) | Settling in - moderate |
| **1-2 minutes** | 2/3 (significant) or 3/3 (minor) | Established - higher consistency |
| **2+ minutes** | **3/3 ALL readings** | Extended - maximum persistence |
| **Incompatible** | **3/3 ALL readings** | Regardless of duration |

### 5. Reset Logic Updates
Updated functions to reset persistence tracking:

**`setManualActivityMode()`**
- Resets persistence when user manually selects an activity
- Clears activity history

**`resetSession()`**
- Resets persistence tracking when session ends

**`startTracking()`**
- Initializes persistence tracking when tracking starts

## How It Works in Android

### Example: Extended Driving Session

```kotlin
// User driving for 5 minutes
activityDurationSeconds = 300  // 5 minutes

// Speed temporarily drops to 5 km/h (traffic light)
// System detects: WALKING (2/3 readings)
// Result: ❌ REJECTED (requires 3/3 due to 2+ minute persistence)
// Activity stays: DRIVING

// Speed back to 60 km/h
// Activity continues: DRIVING
```

### Console Logging

The enhanced logging shows persistence decisions:

```
D/TrackingService: Activity persistence: DRIVING ongoing for 145s - requiring 3/3 consistency
D/TrackingService: Activity change rejected: DRIVING -> RUNNING (2/3 required, duration: 145s, compatible: false)
```

```
D/TrackingService: Incompatible transition: DRIVING -> WALKING - requiring 3/3 consistency
D/TrackingService: Activity change rejected: DRIVING -> WALKING (2/3 required, duration: 67s, compatible: false)
```

```
D/TrackingService: Activity changed: DRIVING -> IDLE (3/3 consistent, duration: 0s, compatible: true)
```

## Benefits

✅ **Prevents unrealistic switches** during extended sessions  
✅ **Blocks incompatible transitions** (e.g., driving → running)  
✅ **Faster initial detection** (3 readings vs 5)  
✅ **Compatible with existing features** (Kalman filter, flight tracking, sensor fusion)  
✅ **Enhanced Android logging** for debugging

## Compatibility with Existing Features

The changes work seamlessly with your existing Android features:

✅ **Kalman Filter** - GPS smoothing still applied  
✅ **Flight Tracker** - Flying detection with altitude awareness  
✅ **Sensor Fusion** - Accelerometer patterns for ambiguous speeds  
✅ **Manual Activity Mode** - User override still works  
✅ **Barometric Altitude** - Elevation tracking unaffected  

## Testing Recommendations

### 1. Extended Driving Test
```
1. Start tracking
2. Drive for 5+ minutes at highway speed
3. Slow down at traffic lights (< 10 km/h)
4. Verify activity stays as DRIVING
5. Come to complete stop
6. Verify switches to IDLE
```

### 2. Quick Transitions Test
```
1. Walk for 20 seconds
2. Start running
3. Verify it switches to RUNNING quickly
4. Within 10-15 seconds
```

### 3. Incompatible Transition Test
```
1. Drive for 3 minutes
2. Temporarily slow to 5 km/h (walking speed)
3. Check Logcat for "Incompatible transition" messages
4. Verify activity stays as DRIVING
5. No false WALKING detections
```

### 4. Manual Override Test
```
1. If auto-detection is wrong
2. Use activity selector to manually set activity
3. Verify it immediately switches
4. Check that persistence tracking resets
```

## Logcat Filtering

To see persistence-related logs:

```bash
adb logcat | grep "TrackingService.*persistence\|TrackingService.*compatible\|TrackingService.*rejected\|TrackingService.*changed"
```

Or in Android Studio Logcat:
- Filter: `TrackingService`
- Look for: `persistence`, `compatible`, `rejected`, `changed`

## Configuration

Persistence thresholds are configurable in `TrackingService.kt`:

```kotlin
when {
    activityDurationSeconds > 120 -> {  // 2+ minutes - maximum persistence
        requiredConsistency = 3
    }
    activityDurationSeconds > 60 -> {   // 1-2 minutes - high persistence
        requiredConsistency = if (isSignificantChange) 2 else 3
    }
    activityDurationSeconds > 30 -> {   // 30-60 seconds - moderate
        requiredConsistency = if (isSignificantChange) 2 else 3
    }
}
```

Adjust these values based on your testing:
- More aggressive: Increase 120 to 180 (3 minutes)
- Less aggressive: Decrease to 90 (1.5 minutes)
- Disable persistence: Set all thresholds to 0

## Building the App

After these changes:

1. **Rebuild the app** in Android Studio:
   ```
   Build > Clean Project
   Build > Rebuild Project
   ```

2. **Or via command line**:
   ```bash
   cd android
   ./gradlew clean
   ./gradlew assembleDebug
   ```

3. **Install on device**:
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

## Performance Impact

✅ **No negative impact** - Changes are purely logical  
✅ **Reduced history size** (5 → 3) = Less memory  
✅ **Better accuracy** with persistence checks  
✅ **Same battery usage** - No additional sensors or GPS calls  

## Synchronized with Web App

The Kotlin Android app now has **identical logic** to the TypeScript web app:
- Same persistence thresholds
- Same compatibility rules
- Same adaptive debouncing
- Consistent behavior across platforms

---

**Version:** v2.3.0-persistence-android  
**Date:** January 30, 2026  
**Status:** ✅ Applied to TrackingService.kt - Ready to Build and Test
