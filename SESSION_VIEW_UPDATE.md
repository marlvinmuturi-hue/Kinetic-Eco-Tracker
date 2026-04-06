# Session View Update - Individual Session Display

## Overview
Updated both the **Web App** and **Android App** to display individual session data when a session is tapped, while keeping accumulated data on the profile overview.

## Changes Made

### 🌐 Web App (React/TypeScript)

**File Modified**: `components/Profile.tsx`

#### Key Changes:
1. **Added Session Selection State**
   - Added `selectedSessionId` state to track which session is selected
   - Added logic to retrieve selected session from the sessions list

2. **Dynamic Stats Display**
   - Created `displayStats` variable that shows:
     - **Individual session data** when a session is selected
     - **Accumulated data** when viewing the profile overview

3. **UI Updates**
   - Made session cards clickable with hover effects
   - Added "Tap to view details" hint on session cards
   - Updated header to show "Session Details" when viewing individual session
   - Added "← Back to All Sessions" button in session detail view
   - Hide navigation tabs when viewing individual session
   - Display session date prominently in detail view

4. **Session Detail View**
   - Shows individual session's stats in the top cards
   - Displays activity breakdown for that specific session only
   - Shows CO₂ balance and session statistics for that session

### 📱 Android App (Kotlin/Jetpack Compose)

**Files Modified**:
1. `android/app/src/main/java/Kinetic_Eco/Tracker/ui/screens/SessionDetailScreen.kt`
2. `android/app/src/main/java/Kinetic_Eco/Tracker/navigation/NavGraph.kt`
3. `android/app/src/main/java/Kinetic_Eco/Tracker/viewmodel/AnalyticsViewModel.kt`

#### Key Changes:

**SessionDetailScreen.kt**:
- Added `session: SessionStats?` parameter to accept individual session
- Added `displayStats` logic to show either individual or aggregated data
- Added `isIndividualSession` flag for conditional UI rendering
- Updated header title dynamically ("Session Details" vs "All Sessions Overview")
- Added session date display using `formatSessionDate()` function
- Updated all stat cards to use `displayStats` instead of hardcoded aggregated stats
- Added **Activity Breakdown Section** with new `ActivityBreakdownCard` composable
- Activity cards show:
  - Activity type with color-coded badge
  - Duration and distance for each activity
  - Percentage of total distance

**AnalyticsViewModel.kt**:
- Added `selectedSession` property to hold the currently selected session
- Added `setSelectedSession()` method to store selected session
- Added `clearSelectedSession()` method to reset when navigating back

**NavGraph.kt**:
- Updated `SessionsList` navigation to store selected session in ViewModel
- Updated `SessionDetail` navigation to retrieve and pass selected session
- Added `clearSelectedSession()` call when navigating back

## How It Works Now

### Profile Overview (Default View)
- **Web & Android**: Shows accumulated data from all sessions
- Stats cards display: Total Trips, Total Distance, Total CO₂ Saved, Total Time, Total Calories
- These stats represent the sum of all tracked sessions

### Individual Session View (When Tapped)
- **Web & Android**: Shows data for that specific session only
- Stats cards display: Single Trip, Session Distance, Session CO₂ Saved, Session Time, Session Calories
- Activity breakdown shows only activities performed in that session
- CO₂ balance and duration are specific to that session

### Navigation Flow
1. **Profile Screen** → Shows accumulated lifetime statistics
2. **Tap "Total Sessions" card** → Navigate to Sessions List
3. **Tap any session card** → Navigate to Session Detail (shows individual session data)
4. **Tap Back button** → Return to Sessions List
5. **Tap Back button** → Return to Profile

## User Experience Improvements

✅ **Clear Data Separation**: Profile shows overall progress, individual sessions show specific trip details

✅ **Consistent Behavior**: Both web and Android apps now work the same way

✅ **Visual Feedback**: 
- Session cards have hover effects (web) and ripple effects (Android)
- "Tap to view details" hint guides users
- Back button clearly indicates how to return

✅ **Activity Breakdown**: New feature on Android showing detailed breakdown of activities within each session

## Testing the Changes

### Web App:
1. Run `npm run dev`
2. Go to Profile tab
3. View accumulated stats at the top
4. Click on any session in the "All Sessions" list
5. Verify individual session data is displayed
6. Click "← Back to All Sessions" to return

### Android App:
1. Build and run the APK: `cd android && ./gradlew assembleDebug`
2. Install on device: `adb install app/build/outputs/apk/debug/app-debug.apk`
3. Navigate to Profile screen
4. Tap "Total Sessions" card
5. Tap any session from the list
6. Verify individual session details with activity breakdown
7. Tap back arrow to return

## Technical Notes

- **Web**: Uses React state (`selectedSessionId`) to manage session selection
- **Android**: Uses ViewModel (`selectedSession`) to persist selection across navigation
- **Data Consistency**: Both apps calculate stats the same way (distance, duration, calories, CO₂)
- **Performance**: No additional API calls; uses existing session data
- **Backward Compatible**: Existing functionality remains unchanged

## Files Changed Summary

### Web App:
- ✏️ `components/Profile.tsx` - Added session selection and individual display logic

### Android App:
- ✏️ `android/app/src/main/java/Kinetic_Eco/Tracker/ui/screens/SessionDetailScreen.kt` - Enhanced to show individual sessions
- ✏️ `android/app/src/main/java/Kinetic_Eco/Tracker/navigation/NavGraph.kt` - Updated navigation to pass sessions
- ✏️ `android/app/src/main/java/Kinetic_Eco/Tracker/viewmodel/AnalyticsViewModel.kt` - Added session selection state

---

## Next Steps (Optional Enhancements)

Consider these future improvements:
1. Add session deletion functionality
2. Add session editing (rename, add notes)
3. Add session comparison (compare two sessions side-by-side)
4. Add session export (share session details as PDF/image)
5. Add session filtering (by date range, activity type, distance)
6. Add session search functionality
7. Add route map visualization for each session (if GPS data available)

---

**Date Updated**: January 28, 2026
**Status**: ✅ Complete - Both web and Android apps optimized
