# GPS Speed & Distance Accuracy Fix

## 🐛 Problem Identified

### Symptoms:
- Speed spikes to 100-600 km/h when walking
- Incorrect speeds even in manual mode
- Triggers wrong activity detection (flying while walking)

### Root Causes:
1. **No speed validation** - GPS can report wildly inaccurate speeds
2. **Poor filtering** - Web app has NO smoothing, Android has basic EMA
3. **Trust GPS speed directly** - GPS speed calculation is unreliable at low speeds
4. **No outlier rejection** - Spikes aren't filtered out
5. **No accuracy-based weighting** - Poor GPS readings treated same as good ones

---

## ✅ Solution Implemented

### 1. **Realistic Speed Caps**
```typescript
const MAX_REALISTIC_SPEEDS = {
  WALKING: 7,      // 7 km/h (fast walk)
  RUNNING: 25,     // 25 km/h (world-class sprint)
  CYCLING: 60,     // 60 km/h (pro cyclist)
  DRIVING: 180,    // 180 km/h (reasonable max)
  FLYING: 900      // 900 km/h (commercial flight)
};
```

### 2. **Calculate Speed from Distance (Primary Method)**
Instead of trusting GPS speed, calculate from actual position changes:
```typescript
speed = distance / time
```
Much more reliable than GPS-reported speed!

### 3. **Outlier Rejection**
Reject speeds that change too dramatically:
```typescript
if (abs(newSpeed - oldSpeed) > MAX_SPEED_CHANGE) {
  // Reject as outlier, use smoothed value
}
```

### 4. **Improved EMA Smoothing**
- Web App: Added EMA filter (was missing!)
- Android: Enhanced existing filter with outlier detection
- Alpha = 0.3 (good balance between responsiveness and smoothness)

### 5. **Accuracy-Based Filtering**
- Reject readings with accuracy > 50m (was 100m)
- Weight GPS readings by accuracy
- Require minimum accuracy for speed calculations

---

## 📋 Changes Made

### Web App (`App.tsx`)
✅ Added speed validation and capping
✅ Implemented EMA smoothing filter
✅ Added outlier rejection
✅ Calculate speed from distance primarily
✅ Stricter accuracy filtering (50m)
✅ Added speed history for better filtering

### Android App (`TrackingService.kt`)
✅ Added speed validation and capping
✅ Enhanced EMA smoothing with outlier detection
✅ Calculate speed from distance primarily
✅ Stricter accuracy filtering
✅ Added maximum speed change limits

### Android App (`LocationService.kt`)
✅ Added accuracy validation
✅ Improved speed filtering

---

## 🎯 Expected Results

### Before Fix:
- Walking: 0-600 km/h (chaotic)
- Triggers flying while walking
- Distance calculations wildly inaccurate

### After Fix:
- Walking: 0-7 km/h (realistic)
- Correct activity detection
- Accurate distance tracking

---

## 🔧 Technical Details

### Speed Calculation Priority:
1. **Calculate from distance** (most reliable)
2. **Apply realistic caps** (reject impossible speeds)
3. **Filter outliers** (reject sudden spikes)
4. **EMA smoothing** (smooth out noise)
5. **Use GPS speed as fallback** (only if calculation fails)

### Filtering Parameters:
- **Accuracy threshold**: 50m (was 100m)
- **EMA alpha**: 0.3 (70% old, 30% new)
- **Max speed change**: 10 m/s per update (36 km/h per second)
- **Distance threshold**: 1m minimum movement

---

## 📊 Validation Formulas

### Speed from Distance:
```
speed (m/s) = distance (m) / time (s)
speed (km/h) = speed (m/s) × 3.6
```

### EMA Smoothing:
```
smoothedSpeed = (alpha × newSpeed) + ((1 - alpha) × oldSpeed)
```

### Outlier Detection:
```
if |newSpeed - smoothedSpeed| > MAX_CHANGE:
    reject newSpeed, keep smoothedSpeed
```

### Activity-Specific Caps:
```
speed = min(speed, MAX_SPEED_FOR_ACTIVITY)
```

---

## 🧪 Testing Recommendations

1. **Walk test**: Should show 0-7 km/h, stay in WALKING
2. **Run test**: Should show 7-25 km/h, stay in RUNNING  
3. **Cycle test**: Should show 10-60 km/h, stay in CYCLING
4. **Drive test**: Should show 0-180 km/h, stay in DRIVING
5. **Stationary test**: Should show 0 km/h consistently

---

**Status:** ✅ Fixed and Ready to Test
**Priority:** Critical
**Impact:** High - Fixes core tracking functionality
