# Activity Persistence Enhancement

## Overview
Enhanced the auto-detection logic to add "activity persistence" - making it significantly harder to switch activities when one has been ongoing for an extended period. This prevents unrealistic activity switches like going from driving → running → driving when speed temporarily reduces.

## Problem Solved
**Before:** The system could switch from DRIVING to RUNNING just because speed dropped temporarily (e.g., at a traffic light), and then switch back to DRIVING when speed increased again.

**After:** Once you're doing an activity for an extended period (2+ minutes), the system requires much stronger evidence to switch to a different activity, and it completely blocks incompatible transitions (like driving → running).

## Changes Made

### 1. Activity Duration Tracking
Added tracking for how long the current activity has been ongoing:

```typescript
// New refs to track activity persistence
const activityStartTimeRef = useRef<number>(Date.now());
const activityDurationSeconds = useRef<number>(0);
```

- Tracks when the current stable activity started
- Calculates how many seconds the activity has been ongoing
- Resets when activity changes or session resets

### 2. Activity Compatibility Check
Added logic to detect incompatible activity transitions:

```typescript
areActivitiesCompatible(from: ActivityType, to: ActivityType): boolean
```

**Incompatible Transitions (require ALL 3 readings to be consistent):**
- Driving → Walking/Running
- Electric Vehicle → Walking/Running
- Flying → Walking/Running/Cycling
- Cycling → Driving/Electric Vehicle
- Running → Driving/Electric Vehicle/Cycling

**Compatible Transitions:**
- Any activity → IDLE (stopping)
- IDLE → Any activity (starting)
- Similar-speed activities (e.g., Walking ↔ Running)

### 3. Persistence-Based Thresholds
The longer an activity continues, the more evidence is required to switch:

| Duration | Required Consistency | Description |
|----------|---------------------|-------------|
| **0-30 seconds** | 2/3 readings (significant) or 3/3 (minor) | Fresh activity - standard adaptive logic |
| **30-60 seconds** | 2/3 (significant) or 3/3 (minor) | Settling in - moderate requirements |
| **1-2 minutes** | 2/3 (significant) or 3/3 (minor) | Established - higher consistency |
| **2+ minutes** | **3/3 ALL readings** | Extended - maximum persistence |
| **Incompatible** | **3/3 ALL readings** | Regardless of duration |

## How It Works

### Example Scenario: Extended Driving Session

1. **Start Driving** (0-30s)
   - Speed: 60 km/h
   - Activity detected: DRIVING
   - Persistence level: Low

2. **Traffic Light** (2 minutes into driving)
   - Speed drops to 5 km/h temporarily
   - System detects: WALKING (2/3 readings)
   - **Result:** ❌ **Rejected** - Requires 3/3 readings due to 2+ minute persistence
   - Activity stays: DRIVING

3. **Slow Traffic** (3 minutes into driving)
   - Speed: 10-15 km/h for 30 seconds
   - System detects: RUNNING (3/3 readings)
   - **Result:** ❌ **Rejected** - Incompatible transition (DRIVING → RUNNING)
   - Activity stays: DRIVING

4. **Resume Speed** 
   - Speed: 60 km/h
   - System continues: DRIVING
   - **Result:** ✅ Smooth continuation

5. **Actually Stop and Park**
   - Speed: 0 km/h (consistently for 3 readings)
   - System detects: IDLE (3/3 readings)
   - **Result:** ✅ **Accepted** - Compatible transition (DRIVING → IDLE)
   - Activity changes to: IDLE

### Example Scenario: Short Activity
1. **Start Walking** (0-30s)
   - Speed: 5 km/h
   - Activity: WALKING
   - Quick transitions still work normally

2. **Start Running** (45 seconds after walking)
   - Speed: 12 km/h (2/3 readings)
   - **Result:** ✅ **Accepted** - Compatible activities, short duration
   - Activity changes to: RUNNING

## Console Logging

Enhanced logging shows persistence decisions:

```
Activity persistence: DRIVING ongoing for 145s - requiring 3/3 consistency
Activity change rejected: DRIVING -> RUNNING (2/3 required, duration: 145s, compatible: false)
```

```
Incompatible transition: DRIVING -> WALKING - requiring 3/3 consistency
Activity change rejected: DRIVING -> WALKING (2/3 required, duration: 67s, compatible: false)
```

```
Activity changed: DRIVING -> IDLE (3/3 consistent, duration: 0s, compatible: true)
```

## Benefits

✅ **Prevents unrealistic activity switches** during extended sessions
✅ **Blocks incompatible transitions** (e.g., driving → running)
✅ **Still responsive** for fresh activities (< 30 seconds)
✅ **Compatible with existing features** (manual mode, session persistence)
✅ **Better user experience** - activities stay stable during long sessions

## Edge Cases Handled

1. **Manual Activity Selection**: Resets persistence timer when user manually selects an activity
2. **Session Reset**: Clears persistence tracking when session ends
3. **App Restart**: Persistence tracking starts fresh (doesn't persist across sessions)
4. **IDLE Transitions**: Always allowed (you can stop any activity)

## Testing Recommendations

1. **Test Extended Driving**
   - Start driving for 5+ minutes
   - Slow down at traffic lights
   - Verify it stays as DRIVING
   - Stop completely → should switch to IDLE

2. **Test Quick Transitions**
   - Walk for 20 seconds
   - Start running
   - Verify it switches quickly to RUNNING

3. **Test Incompatible Transitions**
   - Drive for 3 minutes
   - Temporarily slow to walking speed (5 km/h)
   - Verify it stays as DRIVING
   - Don't get false WALKING detections

4. **Test Manual Override**
   - If auto-detection is wrong, use manual activity selector
   - Verify it immediately switches

## Configuration

The thresholds are configurable in the code:

```typescript
// Persistence thresholds (in seconds)
if (activityDurationSeconds.current > 120) { // 2+ minutes - maximum persistence
if (activityDurationSeconds.current > 60) { // 1-2 minutes - high persistence  
if (activityDurationSeconds.current > 30) { // 30-60 seconds - moderate persistence
```

You can adjust these values if you want:
- More aggressive persistence: Increase the 120s threshold
- Less aggressive: Decrease it to 90s or 60s
- Disable for certain activities by modifying the compatibility logic

## Compatibility

✅ Works with existing auto-detection optimization
✅ Compatible with manual activity mode
✅ Works with session persistence
✅ No changes to GPS or sensor logic
✅ Backward compatible with all existing features

---

**Version:** v2.3.0-persistence  
**Date:** January 30, 2026  
**Status:** ✅ Applied and Ready to Test
