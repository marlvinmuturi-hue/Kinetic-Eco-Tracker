# 🔧 Pedometer Troubleshooting Guide

## ✅ Recent Fixes Applied

### Issue 1: Steps Not Showing
**Problem**: Steps counter was hidden unless already > 0 (chicken-and-egg problem)

**Fix Applied**:
- Changed condition from `isWalkingOrRunning && sessionSteps > 0` 
- To: `isWalkingOrRunning` (shows even at 0)
- Now step counter appears immediately when in WALKING or RUNNING mode

### Issue 2: Missing Color Definition
**Problem**: `Green300` color didn't exist in theme

**Fix Applied**:
- Changed step count text color from `Green300` to `Slate50` (bright white)
- Better contrast and readability

---

## 📱 How to Test Step Counter

### Step 1: Check Logcat
After starting the app and beginning tracking, check Android Studio Logcat for these messages:

```
SensorService: Step counter available: true/false
SensorService: Has step counter feature: true/false
SensorService: Step counter registered
SensorService: Initial step count: [number]
SensorService: Step counter event - Raw: [number], Current: [number]
TrackingService: Has step counter: true/false
TrackingService: Steps: +[delta] (Total: [number]) - Activity: WALKING/RUNNING
```

### Step 2: Start Tracking
1. Open the app
2. Tap "Start Tracking" button
3. Activity should auto-detect as WALKING or manually select it

### Step 3: Look for Step Display
You should now see **TWO** step displays:

#### Location 1: Stats Grid (Top)
```
┌───────────┬───────────┬─────────┐
│ Duration  │ Distance  │ Steps   │
│  00:00:30 │  0.05 km  │  0      │  ← Shows "0" immediately
└───────────┴───────────┴─────────┘
```

#### Location 2: Below Altitude (Bottom)
```
┌─────────────────────────────────┐
│ 🏔️ Altitude: 345 m              │
└─────────────────────────────────┘
┌─────────────────────────────────┐
│ 🚶 Steps: 0                      │  ← Green badge
└─────────────────────────────────┘
```

### Step 4: Walk and Count
- **Walk exactly 20 steps** (count manually)
- Check if app shows approximately 20 steps (±2 is acceptable)
- Steps should increment in real-time

---

## 🐛 Common Issues

### Issue 1: "Step counter not available!"
**Symptoms**: Logcat shows `Step counter not available!`

**Causes**:
- Device doesn't have hardware step counter sensor
- Virtual device/emulator (most don't support step counter)
- Old device (requires Android 4.4+)

**Solution**:
- Test on a real physical device
- Check if device has step counter: Settings → Apps → Google Fit (if it works there, sensor exists)

---

### Issue 2: Steps Show "0" and Never Increment
**Symptoms**: Step display shows but stays at 0

**Debugging Steps**:

1. **Check Logcat for Sensor Events**:
   ```
   Look for: "Step counter event - Raw: X, Current: Y"
   ```
   - If missing: Sensor not sending events
   - If present: Check activity type

2. **Verify Activity Type**:
   ```
   Look for: "Steps: +X (Total: Y) - Activity: WALKING"
   ```
   - Steps only count during WALKING or RUNNING
   - Not during CYCLING, DRIVING, FLYING, IDLE
   - Try manually selecting WALKING mode

3. **Check Permissions**:
   - Go to: Settings → Apps → Kinetic → Permissions
   - Verify: "Physical activity" permission is granted
   - Grant if missing, restart app

4. **Initial Step Count Issue**:
   ```
   Look for: "Initial step count: [large number]"
   ```
   - This is normal (device's total steps since boot)
   - App uses delta: Current - Initial = Session Steps

---

### Issue 3: Steps Increment Too Fast/Slow
**Symptoms**: Steps don't match manual count

**Acceptable Range**: ±5% (95-105 steps for 100 actual steps)

**If Out of Range**:
- Sensor calibration issue (hardware level)
- Phone placement matters (pocket vs hand vs backpack)
- Walking surface affects accuracy

**Best Results**:
- Phone in front pocket
- Walking on flat ground
- Normal walking pace

---

### Issue 4: Steps Don't Show in Analytics
**Symptoms**: Real-time shows steps, but Analytics doesn't

**Check**:
1. Did you SAVE the session? (tap Stop, then Save)
2. Check Logcat: `Session saved to Room database: [id]`
3. Check Logcat: `💾 Breakdown: X activities, total steps: Y`

**If Steps = 0 in Save Log**:
- Step counter wasn't active during session
- Activity wasn't WALKING or RUNNING
- Sensor issue (see Issue 2)

---

## 🔍 Manual Debug Checks

### Check 1: Sensor Availability
Add this temporary code to see sensor details:

```kotlin
// In MainActivity onCreate, add:
val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
Log.d("DEBUG", "Step Sensor: $stepSensor")
Log.d("DEBUG", "Has Feature: ${packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER)}")
```

Expected Output:
```
Step Sensor: {Sensor name="...", type=19, ...}
Has Feature: true
```

### Check 2: Activity Type During Tracking
```
Logcat filter: "Activity changed"
Look for: "Activity changed: IDLE -> WALKING"
```

### Check 3: Step Events
```
Logcat filter: "Step counter event"
Should see frequent updates (every few steps)
```

---

## 📊 Test Plan

### Basic Test (5 minutes)
1. ✅ Start app → Check Logcat for sensor registration
2. ✅ Start tracking → Verify WALKING mode
3. ✅ Check step display shows "0"
4. ✅ Walk 20 steps → Verify count increases
5. ✅ Stop & Save → Check Analytics shows steps
6. ✅ Check Profile → Verify lifetime steps

### Advanced Test (15 minutes)
1. ✅ Test WALKING → RUNNING transition (steps continue)
2. ✅ Test WALKING → CYCLING transition (steps stop)
3. ✅ Test CYCLING → WALKING transition (steps resume)
4. ✅ Test accuracy: Walk 100 steps, expect 95-105
5. ✅ Test persistence: Close app, reopen, check Analytics

---

## 🎯 Expected Behavior

### When Tracking STARTS (WALKING/RUNNING):
- ✅ Step counter badge appears (green)
- ✅ Shows "0 steps" initially
- ✅ Stats grid shows third "Steps" column

### While WALKING:
- ✅ Steps increment every few steps
- ✅ Real-time update (1-2 second delay is normal)
- ✅ Logcat shows step events

### When Switch to CYCLING/DRIVING:
- ✅ Step counter badge disappears
- ✅ Stats grid shows only Duration/Distance
- ✅ Steps preserved (not lost)

### When Switch Back to WALKING:
- ✅ Step counter badge reappears
- ✅ Shows previous step count
- ✅ Continues counting from previous total

### When STOP & SAVE:
- ✅ Session shows total steps in Analytics
- ✅ Breakdown shows steps per activity (WALKING: X, RUNNING: Y)
- ✅ Profile shows updated lifetime steps

---

## 🚨 Known Limitations

1. **Emulators**: Most don't support step counter
2. **First Few Steps**: May be delayed (sensor warmup)
3. **Device Reboot**: Step counter resets (app handles this)
4. **Background**: May be throttled on some devices
5. **Battery Saver**: May affect sensor update rate

---

## ✅ Success Criteria

Your pedometer is working correctly if:

1. ✅ Logcat shows: "Step counter registered"
2. ✅ Step badge appears when WALKING/RUNNING
3. ✅ Steps increment while walking (within ±5% accuracy)
4. ✅ Steps don't increment during CYCLING/DRIVING
5. ✅ Steps persist in Analytics after saving
6. ✅ Lifetime steps aggregate in Profile

---

## 📞 Still Not Working?

If steps still don't show after all fixes:

### Check These:
1. **Device**: Real device (not emulator)?
2. **Android Version**: 4.4+ (API 19+)?
3. **Permission**: Physical activity granted?
4. **Activity**: In WALKING or RUNNING mode?
5. **Logcat**: Sensor registration successful?

### Provide Debug Info:
When reporting issues, include:
- Device model and Android version
- Relevant Logcat output (filter: "SensorService", "TrackingService")
- Screenshot of Tracker screen during WALKING
- Whether sensor registration succeeded

---

**Last Updated**: 2026-02-05  
**Status**: Troubleshooting guide complete
