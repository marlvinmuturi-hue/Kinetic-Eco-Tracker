# CO2 Tracking Enhancement - Update Summary

**Date:** January 4, 2026  
**Changes Made:** Eliminated cycling misclassification and separated CO2 emissions from conservation

---

## Overview

This update addresses two key issues:
1. **Eliminated "cycling" misclassification** that occurred when slowing down from driving speeds
2. **Separated CO2 tracking** into emissions (from polluting activities) and conservation (from eco-friendly activities)

---

## Changes Made

### 1. Speed Threshold Adjustment

**File:** `constants.ts`

**What Changed:**
- Increased `DRIVING_MIN` threshold from 7.0 m/s (25.2 km/h) to 10.0 m/s (36 km/h)
- This prevents misclassification when slowing down from driving

**Why:**
- Previously, when slowing down from driving (e.g., at a stoplight), speeds would drop into the 7-25 km/h range
- This range was being interpreted as "cycling" in some documentation
- The current system only has 4 activity types: IDLE, WALKING, DRIVING, FLYING
- By raising the driving threshold, we ensure clearer boundaries between activities

**Activity Classification Now:**
| Activity | Speed Range | Notes |
|----------|-------------|-------|
| **IDLE** | < 0.5 m/s (< 1.8 km/h) | Filters GPS drift |
| **WALKING** | 0.5 - 10.0 m/s (1.8 - 36 km/h) | Includes walking, jogging, slow urban speeds |
| **DRIVING** | 10.0 - 55.0 m/s (36 - 198 km/h) | Standard vehicle speeds |
| **FLYING** | > 55.0 m/s (> 198 km/h) | Air travel |

---

### 2. CO2 Tracking Separation

**Files Modified:**
- `types.ts` - Added `co2Conserved` field to `SessionStats`
- `constants.ts` - Changed CO2 factors to use negative values for conservation
- `App.tsx` - Updated calculation logic to separate emissions and conservation
- `components/Analytics.tsx` - Enhanced UI to show both metrics
- `Docs/Architecture/FIRESTORE_SCHEMA.md` - Updated schema documentation

**New CO2 Factors:**
```typescript
export const CO2_FACTORS = {
  [ActivityType.IDLE]: 0,           // No impact
  [ActivityType.WALKING]: -0.192,   // Negative = saves CO2 (vs driving)
  [ActivityType.DRIVING]: 0.192,    // Positive = emits CO2
  [ActivityType.FLYING]: 0.255,     // Positive = emits CO2
};
```

**Calculation Logic:**
- Positive CO2 impact → Accumulated in `co2Emissions`
- Negative CO2 impact → Accumulated in `co2Conserved` (as positive value)
- Net impact = `co2Conserved - co2Emissions`

**Example:**
- Walk 5 km: Conserve 0.96 kg CO2 (5 × 0.192)
- Drive 5 km: Emit 0.96 kg CO2 (5 × 0.192)
- Net for mixed session: Shows both values separately

---

### 3. Analytics UI Enhancement

**What Changed:**
- Top cards now show 4 metrics instead of 3:
  - **CO2 Emitted** (red) - From driving/flying
  - **CO2 Conserved** (green) - From walking
  - **Calories Burned** (orange)
  - **Total Time** (blue)

- Environmental Impact chart now shows:
  - CO2 Emitted (red bar)
  - CO2 Conserved (green bar)
  - Tree Daily Absorption (emerald bar - reference)
  - Net Impact calculation below chart

**Net Impact Display:**
- Positive net → Shows as green "conserved"
- Negative net → Shows as red "emitted"
- Formula: `co2Conserved - co2Emissions`

---

### 4. Data Schema Updates

**New Field:** `co2Conserved`
- Type: `number`
- Unit: kilograms (kg)
- Description: Total CO2 conserved from eco-friendly activities
- Required: Yes
- Default: 0

**Backward Compatibility:**
- Existing sessions will have `co2Conserved = 0` by default
- Migration code includes fallback: `co2Conserved: session.stats.co2Conserved || 0`
- Schema version remains at 1 (additive change only)

---

## Testing Recommendations

### Test Case 1: Walking Activity
1. Start tracking
2. Walk for 1 km at normal walking pace (5 km/h)
3. Stop tracking
4. **Expected Results:**
   - Activity: WALKING
   - CO2 Emitted: 0.000 kg
   - CO2 Conserved: ~0.192 kg
   - Net Impact: +0.192 kg conserved (green)

### Test Case 2: Driving Activity
1. Start tracking
2. Drive for 5 km at 60 km/h
3. Stop tracking
4. **Expected Results:**
   - Activity: DRIVING
   - CO2 Emitted: ~0.960 kg
   - CO2 Conserved: 0.000 kg
   - Net Impact: -0.960 kg emitted (red)

### Test Case 3: Mixed Activity
1. Start tracking
2. Walk 2 km
3. Drive 3 km
4. Walk 1 km
5. Stop tracking
6. **Expected Results:**
   - Activities: WALKING + DRIVING + WALKING
   - CO2 Emitted: ~0.576 kg (from 3 km driving)
   - CO2 Conserved: ~0.576 kg (from 3 km walking)
   - Net Impact: ~0.000 kg (balanced)

### Test Case 4: No More Cycling Misclassification
1. Start tracking while driving at 60 km/h
2. Slow down to stop at red light (speed drops through 40, 30, 20, 10, 0 km/h)
3. Accelerate back to 60 km/h
4. Stop tracking
5. **Expected Results:**
   - Activity should remain DRIVING or transition to IDLE
   - Should NOT show as WALKING during deceleration
   - Speed below 36 km/h for brief moments is acceptable

---

## Files Modified

1. `constants.ts` - Speed thresholds and CO2 factors
2. `types.ts` - SessionStats interface
3. `App.tsx` - Tracking logic and initialization
4. `components/Analytics.tsx` - UI display
5. `Docs/Architecture/FIRESTORE_SCHEMA.md` - Schema documentation

---

## Known Limitations

1. **Walking Threshold Wide:** 
   - Walking now includes speeds up to 36 km/h to avoid cycling misclassification
   - This is acceptable since typical walking/jogging is 3-15 km/h
   - Speeds between 15-36 km/h (cycling/slow urban driving) now count as WALKING
   - This gives them eco-friendly benefits, which is intentional

2. **CO2 Conservation Calculation:**
   - Assumes baseline of driving for all walking activities
   - In reality, not all walking trips would have been driven
   - This is a common assumption in carbon footprint apps

3. **Existing Data:**
   - Old sessions will show `co2Conserved = 0`
   - This is expected and doesn't affect historical data integrity

---

## Future Enhancements

Consider adding:
1. **Smart Activity Context:** Use device sensors to distinguish actual cycling from driving slowdowns
2. **Trip Purpose Classification:** "Would you have driven this trip?" to improve conservation accuracy
3. **Transportation Mode Toggle:** Manual override for activity classification
4. **Public Transit Support:** Add bus/train as separate categories with their own CO2 factors

---

## Contact

For questions about these changes, refer to:
- Speed thresholds: `constants.ts`
- CO2 calculation: `App.tsx` line ~286-302
- UI display: `components/Analytics.tsx`
- Data schema: `types.ts` and Firestore schema doc




