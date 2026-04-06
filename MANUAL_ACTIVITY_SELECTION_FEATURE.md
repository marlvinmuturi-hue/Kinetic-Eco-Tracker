# 🎯 Manual Activity Selection Feature

## Overview

Added a floating activity selector button that allows users to manually select specific activities for tracking, including new activity types: **Running**, **Cycling**, and **Electric Vehicle (EV)**.

---

## 🎨 New Features

### 1. **Floating Activity Button**
- **Location**: Fixed position, bottom-right corner (above the floating menu)
- **Design**: Circular purple/indigo gradient button with pulsating ring animation
- **Icon**: Activity icon with a green badge indicator when tracking is active
- **Function**: Opens the Activity Selector modal

### 2. **Activity Selector Modal**
- **Full-screen modal** with backdrop blur
- **Activity options**:
  - 🔵 **Auto Detect** - Automatically detect activity based on GPS speed
  - 🟢 **Walking** - Track walking (saves CO2 vs driving)
  - 🟢 **Running** - Track running activity (saves CO2 vs driving)
  - 🔵 **Cycling** - Track cycling (saves CO2 vs driving)
  - 🟠 **Driving** - Track gas/diesel vehicle (emits CO2)
  - 🟣 **Electric Vehicle** - Track EV (70% less CO2 than gas)
  - 🔵 **Flying** - Track air travel (high CO2)

### 3. **Manual Activity Mode**
- When selected, locks the activity to the chosen type
- Disables automatic speed-based activity detection
- Shows **"MANUAL"** badge on tracker UI
- Displays info banner explaining manual mode
- Can be changed anytime via the floating button

---

## 📊 New Activity Types & CO2 Factors

### Activity Types Added:
1. **RUNNING**
   - Speed threshold: > 7.2 km/h (2.0 m/s)
   - CO2 factor: -0.192 kg/km (conservation)
   - Calories: 600 kcal/hour
   - Color: Emerald (green-600)

2. **CYCLING**
   - Speed threshold: 14.4-36 km/h (4.0-10.0 m/s)
   - CO2 factor: -0.192 kg/km (conservation)
   - Calories: 400 kcal/hour
   - Color: Cyan (cyan-500)

3. **ELECTRIC_VEHICLE**
   - No speed threshold (manual selection)
   - CO2 factor: 0.053 kg/km (70% less than gas)
   - Calories: 100 kcal/hour
   - Color: Violet (violet-500)

### Updated Speed Thresholds:
```typescript
WALKING_MIN: 0.5 m/s   (> 1.8 km/h)
RUNNING_MIN: 2.0 m/s   (> 7.2 km/h)
CYCLING_MIN: 4.0 m/s   (> 14.4 km/h)
DRIVING_MIN: 10.0 m/s  (> 36 km/h)
FLYING_MIN: 55.0 m/s   (> 200 km/h)
```

---

## 🎨 UI Updates

### Tracker View:
- **Activity badge** shows "MANUAL" tag when manual mode is active
- **Info banner** displays current manual activity and instructions
- **Floating button** appears only on TRACKER view

### Analytics View:
- Automatically displays all activity types in charts
- Shows breakdown for Running, Cycling, and EV activities
- Color-coded pie chart with all activities

---

## 🔧 Technical Implementation

### Files Modified:

1. **`types.ts`**
   - Added `RUNNING`, `CYCLING`, `ELECTRIC_VEHICLE` to `ActivityType` enum

2. **`constants.ts`**
   - Added speed thresholds for new activities
   - Added CO2 factors for new activities
   - Added calorie factors for new activities
   - Added colors for new activities

3. **`App.tsx`**
   - Added `activitySelectorOpen` state
   - Added `manualActivityMode` state (ActivityType | 'AUTO')
   - Added `handleActivityModeSelect()` function
   - Modified `handleGeoSuccess()` to respect manual mode
   - Updated `statsRef` initialization with new activity types
   - Updated `resetSession()` with new activity types
   - Added FloatingActivityButton and ActivitySelector components

4. **`components/ActivitySelector.tsx`** ✨ NEW
   - Full-screen modal with activity grid
   - Shows all activity options with icons and descriptions
   - Highlights currently selected mode
   - Responsive design with smooth animations

5. **`components/FloatingActivityButton.tsx`** ✨ NEW
   - Circular floating button with gradient
   - Pulsating animation
   - Green badge indicator when tracking
   - Fixed positioning

6. **`components/Tracker.tsx`**
   - Added `manualActivityMode` prop
   - Shows "MANUAL" badge when manual mode is active
   - Displays manual mode info banner
   - Added manual mode instructions

7. **`components/ActivityIcon.tsx`**
   - Added icons for RUNNING (Activity)
   - Added icons for CYCLING (custom SVG bicycle)
   - Added icons for ELECTRIC_VEHICLE (Zap/lightning)

---

## 🎯 User Workflow

### How to Use Manual Activity Selection:

1. **On Tracker View:**
   - Click the **purple floating button** (bottom-right)
   - Activity Selector modal opens

2. **Select Activity:**
   - Choose from 7 activity options
   - Selected activity is highlighted
   - Modal closes automatically

3. **Start Tracking:**
   - Activity is locked to your selection
   - "MANUAL" badge appears on tracker
   - Info banner shows manual mode status

4. **Change Activity:**
   - Click floating button anytime
   - Select a different activity
   - Changes apply immediately

5. **Return to Auto Mode:**
   - Select "Auto Detect" option
   - App resumes speed-based detection

---

## 💡 Benefits

### For Users:
- ✅ **Accurate tracking** for specific activities
- ✅ **No misclassification** when slowing down/speeding up
- ✅ **Dedicated EV tracking** with lower emissions
- ✅ **Running & cycling** properly categorized
- ✅ **Manual override** when GPS is inaccurate

### Environmental Impact:
- 🌱 **Better CO2 calculations** for eco-friendly transport
- 🌱 **Separate EV category** encourages electric vehicle adoption
- 🌱 **Accurate conservation** tracking for walking/running/cycling
- 🌱 **Clear feedback** on emissions vs conservation

---

## 🎨 Design Highlights

### Activity Selector Modal:
- Modern, clean interface with gradient cards
- Color-coded activities matching tracker theme
- Descriptive text for each activity type
- Smooth slide-up animation
- Backdrop blur for focus

### Floating Button:
- Eye-catching purple/indigo gradient
- Consistent with app's color scheme
- Pulsating animation draws attention
- Badge shows tracking status
- Always accessible, never intrusive

### Manual Mode Indicator:
- Clear visual feedback on tracker
- Info banner with instructions
- "MANUAL" badge on activity display
- Indigo theme matches floating button

---

## 🧪 Testing Recommendations

### Manual Mode Testing:
1. ✅ Select Walking manually while stationary
2. ✅ Start tracking - should stay WALKING
3. ✅ Move at driving speed - should still show WALKING
4. ✅ Switch to Auto - should detect DRIVING
5. ✅ Test all 7 activity types
6. ✅ Verify CO2 calculations for each type

### UI Testing:
1. ✅ Floating button visible on TRACKER view only
2. ✅ Modal opens/closes smoothly
3. ✅ Selected mode persists during session
4. ✅ "MANUAL" badge appears correctly
5. ✅ Analytics shows all activity types
6. ✅ Icons display correctly for new activities

---

## 📱 Mobile Considerations

- Floating button positioned to avoid navigation bar
- Touch-friendly button sizes (56px minimum)
- Modal scrollable on small screens
- Activity cards use `touch-manipulation` CSS
- Backdrop prevents accidental clicks

---

## 🚀 Future Enhancements

### Potential Additions:
- 🎯 **Save favorite activities** for quick access
- 🎯 **Activity presets** (e.g., "Morning Commute")
- 🎯 **Auto-switch** based on time/location patterns
- 🎯 **Activity history** showing most used modes
- 🎯 **Custom activities** with user-defined CO2 factors

---

## 📝 Summary

The manual activity selection feature provides users with:
- **Full control** over activity tracking
- **Accurate CO2 calculations** for specific transport modes
- **New categories** (Running, Cycling, EV) for better granularity
- **Beautiful UI** with floating button and modal selector
- **Flexibility** to switch between manual and auto modes

This feature addresses the original issue of activity misclassification while adding valuable new tracking capabilities for eco-conscious users.

---

**Created**: January 4, 2026  
**Status**: ✅ Complete  
**All TODOs**: Completed



