# 🔧 Stability Fixes - GPS Status, Time Counter, and Session Saving

**Date:** January 4, 2026  
**Issues Fixed:** GPS indicator flickering, time counter malfunction, session save failures

---

## Problems Identified

### 1. **GPS Status Indicator Flickering**
- **Issue:** The GPS status rapidly switches between "Motion Detected", "GPS Ready", and "Motion Stopped"
- **Root Cause:** Activity classification happens on every GPS update (every second) without debouncing
- **Effect:** Confusing UI, appears unstable even during steady walking

### 2. **Time Counter Malfunction**
- **Issue:** Time counter doesn't increment properly or shows incorrect values
- **Root Cause:** Timer relies on GPS updates which may be inconsistent; missing dedicated timer interval
- **Effect:** Inaccurate session duration tracking

### 3. **Session Not Saving**
- **Issue:** Sessions don't persist to storage after stopping tracking
- **Root Cause:** saveSessionForUser only works with localStorage, not integrated with Firebase when using Firebase auth
- **Effect:** User loses all tracking data after stopping

---

## Solutions Implemented

### Fix 1: Activity Classification Debouncing

Added a stable activity state that only changes when activity is consistent for 3+ seconds:

```typescript
const [stableActivity, setStableActivity] = useState<ActivityType>(ActivityType.IDLE);
const activityHistory = useRef<ActivityType[]>([]);

// In handleGeoSuccess:
const activity = classifyActivity(speed);

// Add to history
activityHistory.current.push(activity);
if (activityHistory.current.length > 5) {
  activityHistory.current.shift(); // Keep last 5 readings
}

// Only update stable activity if consistent
const mostCommon = getMostCommonActivity(activityHistory.current);
if (mostCommon && activityHistory.current.filter(a => a === mostCommon).length >= 3) {
  setStableActivity(mostCommon);
}
```

### Fix 2: Independent Timer System

Created a dedicated timer that runs independently of GPS updates:

```typescript
const timerIntervalRef = useRef<number | null>(null);

// Start timer when tracking begins
useEffect(() => {
  if (!isTracking) {
    if (timerIntervalRef.current) {
      clearInterval(timerIntervalRef.current);
      timerIntervalRef.current = null;
    }
    return;
  }

  // Update duration every second
  timerIntervalRef.current = window.setInterval(() => {
    if (isTrackingRef.current) {
      setSessionDuration(prev => prev + 1);
      statsRef.current.totalDuration += 1;
    }
  }, 1000);

  return () => {
    if (timerIntervalRef.current) {
      clearInterval(timerIntervalRef.current);
    }
  };
}, [isTracking]);
```

### Fix 3: GPS Accuracy Filtering

Added better filtering to prevent GPS drift from causing false activity changes:

```typescript
// Only accept GPS updates with good accuracy
const MIN_ACCURACY = 50; // meters

if (accuracy > MIN_ACCURACY) {
  console.log(`Poor GPS accuracy (${accuracy}m), skipping update`);
  return;
}

// Only accumulate distance if movement is significant
const MIN_DISTANCE = 2; // meters
if (distanceDelta < MIN_DISTANCE && distanceDelta > 0) {
  // Skip small movements (GPS drift)
  distanceDelta = 0;
}
```

### Fix 4: Improved Session Saving

Enhanced session saving to work with both localStorage and Firebase:

```typescript
const stopTracking = async () => {
  setIsTracking(false);
  isTrackingRef.current = false;
  
  // ... existing code ...
  
  // Switch to Analytics view when stopped
  setView('ANALYTICS');
  
  if (activeUser) {
    try {
      // Save to localStorage (profileService)
      const snapshot: SessionStats = JSON.parse(JSON.stringify(statsRef.current));
      const updatedProfile = saveSessionForUser(activeUser.email, snapshot);
      
      if (updatedProfile) {
        setActiveUser({ ...updatedProfile });
      }
      
      // Also save to Firebase if using Firebase auth
      const currentUser = getCurrentUser();
      if (currentUser && db) {
        await saveSessionToFirebase(currentUser.uid, snapshot);
      }
      
      console.log('Session saved successfully');
    } catch (error) {
      console.error('Error saving session:', error);
      setErrorMsg('Session saved locally but failed to sync to cloud');
    }
  } else {
    console.warn('No active user - session not saved');
    setErrorMsg('Please log in to save your sessions');
  }
};
```

---

## Files Modified

1. `App.tsx` - Main fixes for timer and activity debouncing
2. `services/profileService.ts` - Enhanced session saving
3. `components/Tracker.tsx` - Improved UI stability

---

## Testing Checklist

- [ ] Start tracking while walking steadily
- [ ] Verify GPS status shows consistent activity (not flickering)
- [ ] Verify time counter increments by 1 second every second
- [ ] Stop tracking after 30 seconds
- [ ] Verify session appears in Analytics with correct duration
- [ ] Verify session is saved (check Profile > History)
- [ ] Test with poor GPS signal (indoor/urban canyon)
- [ ] Verify no crashes or errors during 5+ minute session



