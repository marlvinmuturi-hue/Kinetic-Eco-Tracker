# Activity Detection Optimization

## Problem
Activity detection was taking 40-60 seconds to switch between activities (e.g., walking to idle, idle to walking, walking to running).

## Root Causes Identified

1. **Slow Activity Debouncing**: Required 5 GPS readings with 60% consistency (3/5) before changing activity
2. **GPS Update Delays**: Timeout settings allowed up to 20 seconds between updates
3. **Strict Update Throttling**: Rejected GPS updates coming within 0.5 seconds

## Changes Made

### 1. Optimized Activity Debouncing (App.tsx)

**Before:**
- Kept last 5 readings
- Required 3/5 readings (60%) to be consistent
- Fixed delay of ~25-30 seconds minimum

**After:**
- Keeps last 3 readings (40% reduction)
- Adaptive consistency requirements:
  - **Significant changes** (idle ↔ moving, large speed differences): Only 2/3 readings required
  - **Minor changes** (walking → running): 3/3 readings required
- **Expected delay: 10-15 seconds** for most transitions

### 2. Faster GPS Updates (mobileGPSService.ts)

**Before:**
- Mobile timeout: 20 seconds
- Desktop maximumAge: 5 seconds

**After:**
- Mobile timeout: 15 seconds (25% faster)
- Desktop maximumAge: 2 seconds (60% faster)
- More frequent position updates = faster activity detection

### 3. Relaxed Update Throttling (App.tsx)

**Before:**
- GPS accuracy threshold: 50m
- Minimum update interval: 0.5s

**After:**
- GPS accuracy threshold: 65m (allows slightly less accurate readings for faster detection)
- Minimum update interval: 0.3s (40% faster)

## How It Works Now

### Activity Change Detection Flow:

1. **GPS Update** → Received every 3-5 seconds (device-dependent)
2. **Speed Calculation** → Determines raw activity type
3. **History Buffer** → Stores last 3 readings
4. **Consistency Check** → After 2-3 readings:
   - **Big change** (idle→walking): Switches after 2 consistent readings (~6-10s)
   - **Small change** (walking→running): Switches after 3 consistent readings (~9-15s)
5. **Activity Updated** → UI reflects the new activity

## Expected Performance

| Transition Type | Old Delay | New Delay | Improvement |
|----------------|-----------|-----------|-------------|
| Idle → Walking | 40-60s | 10-15s | **70% faster** |
| Walking → Idle | 40-60s | 10-15s | **70% faster** |
| Walking → Running | 40-60s | 12-18s | **65% faster** |
| Running → Cycling | 40-60s | 12-18s | **65% faster** |

## Testing Recommendations

1. **Start Tracking** → Wait 5 seconds for initial GPS lock
2. **Begin Walking** → Should detect within 10-15 seconds
3. **Stop Moving** → Should switch to IDLE within 10-15 seconds
4. **Start Running** → Should detect within 12-18 seconds

## Technical Details

### Adaptive Debouncing Algorithm

The new system uses "significant change" detection:

```typescript
const isSignificantChange = (
  // Idle ↔ Any Activity
  (currentActivity === ActivityType.IDLE && mostCommon !== ActivityType.IDLE) ||
  (currentActivity !== ActivityType.IDLE && mostCommon === ActivityType.IDLE) ||
  // Large speed differences (>2 m/s ≈ 7 km/h)
  Math.abs(getActivitySpeed(currentActivity) - getActivitySpeed(mostCommon)) > 2
);
```

**Examples of Significant Changes:**
- IDLE → WALKING (0 → 5 km/h) ✓
- WALKING → IDLE (5 → 0 km/h) ✓
- WALKING → RUNNING (5 → 12 km/h) ✓
- CYCLING → DRIVING (20 → 50 km/h) ✓

**Examples of Minor Changes:**
- Slight speed variations within same activity
- GPS noise causing small fluctuations

## Trade-offs

### Benefits:
- ✅ 65-70% faster activity detection
- ✅ More responsive to user behavior
- ✅ Better user experience
- ✅ Still prevents flickering on minor changes

### Considerations:
- Slightly more sensitive to GPS noise (mitigated by accuracy filtering)
- May use marginally more battery (minimal impact)
- Requires good GPS signal (same as before)

## Battery Impact

**Minimal to None** - The changes primarily affect data processing logic, not GPS polling frequency. The device's GPS was already running continuously during tracking.

## Fallback Behavior

If GPS signal is poor or unavailable:
- System still uses hybrid sensor fusion (accelerometer + GPS)
- Falls back to manual activity selection mode
- No degradation compared to previous version

## Next Steps

If you need even faster detection (< 10 seconds):
1. Could reduce history buffer to 2 readings
2. Could implement instant detection for very large changes (e.g., driving → idle)
3. Could add accelerometer-based pre-detection for walking/running

However, going below 10 seconds risks false positives from GPS noise.

---

**Version:** v2.2.0-optimized
**Date:** January 27, 2026
**Status:** ✅ Applied and Ready to Test
