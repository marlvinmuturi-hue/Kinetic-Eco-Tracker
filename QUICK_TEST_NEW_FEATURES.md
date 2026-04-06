# Quick Test Guide for New Analytics Features

## 🐛 Testing the Session Bug Fix

### Before (Bug):
- Complete Session 1: 5 km, 300 seconds
- Save session
- Complete Session 2: 3 km, 200 seconds
- ❌ BUG: Session 2 would show 8 km, 500 seconds (accumulated)

### After (Fixed):
1. Start a new session
2. Let it track for ~30 seconds (or walk around a bit)
3. Stop and SAVE the session
4. Go to Profile → History tab
5. Note the session values (e.g., 0.05 km, 30 sec)
6. Start ANOTHER session
7. Track for ~30 seconds again
8. Stop and SAVE
9. ✅ Check Profile → History: You should see TWO separate sessions with DIFFERENT values!

**Expected Result**: Each session shows its own individual stats, not cumulative.

---

## 📊 Testing Trend Charts

1. **Complete Multiple Sessions** (at least 3-4 over a few days)
   - You can manually change dates in localStorage if testing quickly
   
2. **View Trends**:
   - Go to Analytics view (after completing a session)
   - Click "Trends" tab
   - You should see:
     - Distance line chart
     - CO2 impact dual-line chart (saved vs emitted)
     - Calories bar chart

3. **What to Look For**:
   - Charts display smoothly
   - X-axis shows days of week
   - Y-axis shows appropriate values
   - Tooltips appear on hover
   - Mobile responsive (try resizing window)

---

## 🏆 Testing Achievement System

1. **View Achievements**:
   - Go to Profile view
   - Click "Achievements" tab

2. **Check Unlocking**:
   - New users should have "Getting Started" unlocked after first session
   - Complete ~1km to unlock "First Steps"
   - Check progress bar at top

3. **Test Categories**:
   - Scroll through all 5 categories:
     - Distance
     - Environmental
     - Health & Fitness
     - Time
     - Consistency
   
4. **Visual States**:
   - Unlocked achievements: Full color, "UNLOCKED" badge
   - Locked achievements: Grayscale, lock icon

**Quick Unlock Test**: 
- To test multiple unlocks, you can temporarily edit `achievementService.ts` to lower thresholds
- OR accumulate stats naturally over time

---

## 🌍 Testing CO2 Equivalencies

1. **Complete a Session** with some movement (drive, cycle, or walk)

2. **View Impact**:
   - Go to Analytics view
   - Click "Impact" tab

3. **Check Equivalencies**:
   - **CO2 Conserved section** (if you walked/cycled):
     - Should show green card
     - Shows equivalencies like trees, car km avoided, etc.
   
   - **CO2 Emitted section** (if you drove/flew):
     - Should show red card
     - Shows equivalencies like beef meals, flights, etc.

4. **Net Impact**:
   - Scroll to bottom
   - See net calculation (conserved - emitted)
   - Check if positive (green) or negative (red)

**What to Look For**:
- Numbers make sense (e.g., 5km walk ≈ 0.96 kg CO2 saved ≈ 16 trees/day)
- Icons display correctly
- Descriptions are clear

---

## 🎯 Testing Goal System

### Creating a Goal:

1. **Go to Profile → Goals Tab**

2. **Click "Add Goal"**

3. **Test Suggested Goals**:
   - Click "Walk 5km Today"
   - Should appear in Active Goals
   - Progress bar should show current progress

4. **Test Custom Goal**:
   - Click "Add Goal" again
   - Switch to "Custom Goal" tab
   - Fill in:
     - Name: "Test Goal"
     - Type: Distance
     - Target: 1 km
     - Timeframe: Daily
   - Click "Create Goal"

### Testing Progress:

1. With goals active, complete a session
2. Return to Goals tab
3. Progress bars should update
4. When target reached, goal moves to "Completed" section

### Testing Timeframes:

1. Use the Daily/Weekly/Monthly selector
2. Stats should update to match timeframe
3. Goals progress should reflect selected period

**What to Look For**:
- Progress bars animate smoothly
- Percentages calculate correctly
- Completed goals have green checkmark
- Can remove goals (X button)

---

## 🔍 Edge Cases to Test

### Session Bug Fix:
- [ ] Save session → Start new → First session stats unchanged
- [ ] Discard session → Start new → Clean slate
- [ ] Multiple sessions same day → Each shows different values

### Trends:
- [ ] Only 1 session → Chart displays correctly
- [ ] No sessions yet → Shows "No trend data" message
- [ ] 7+ sessions → Chart shows last 7 days only

### Achievements:
- [ ] Brand new user → Only "Getting Started" available
- [ ] After 1 session → "Getting Started" unlocked
- [ ] Progress percentage updates

### Equivalencies:
- [ ] Zero emissions session → No red card
- [ ] Zero conservation → No green card
- [ ] Mixed session → Both cards show

### Goals:
- [ ] No goals → Empty state shows
- [ ] Goal completed → Moves to completed section
- [ ] Remove goal → Disappears from list
- [ ] Invalid input → Form validation works

---

## 🧪 Quick Test Scenario (5 Minutes)

1. **Login/Create Account**

2. **Session 1**:
   - Start tracking
   - Wait 30 seconds
   - Stop and SAVE
   - Note: Distance, Duration, Calories

3. **Check Profile**:
   - Go to Profile → History
   - Verify session appears correctly
   - Go to Achievements
   - "Getting Started" should be unlocked

4. **Session 2**:
   - Start NEW tracking
   - Wait 30 seconds
   - Stop and SAVE

5. **Verify Bug Fix**:
   - Go to Profile → History
   - ✅ Session 1 and Session 2 should have DIFFERENT values!
   - Session 2 should NOT be double Session 1's values

6. **Check Analytics**:
   - Go to Analytics
   - Click "Trends" → See charts
   - Click "Impact" → See CO2 equivalencies

7. **Set a Goal**:
   - Go to Profile → Goals
   - Add "Walk 5km Today"
   - See progress bar

**Expected Time**: 5-7 minutes
**Result**: All features working correctly!

---

## 📱 Mobile Testing

1. Open Chrome DevTools (F12)
2. Toggle device toolbar (Ctrl+Shift+M)
3. Select iPhone or Android device
4. Test all features:
   - Tabs should be touch-friendly
   - Charts should resize
   - Buttons should be >44px tall
   - Scrolling should work smoothly

---

## ⚠️ Known Limitations

1. **Trends require multiple sessions**: Need at least 1 session to show charts
2. **Achievements unlock automatically**: Can't manually unlock for testing
3. **Goals track timeframe stats**: Make sure correct timeframe selected
4. **CO2 equivalencies**: Only show if CO2 > 0

---

## 🐛 If You Find Issues

1. **Check Browser Console** (F12 → Console tab)
2. **Check localStorage**: `Application` tab → `Local Storage`
3. **Verify profile structure**: Look for `kinetic_profiles` key
4. **Clear cache if needed**: Ctrl+Shift+Delete

---

## ✅ Success Indicators

After testing, you should see:
- ✅ Each session has unique values (bug fixed)
- ✅ Trend charts display for last 7 days
- ✅ At least 1 achievement unlocked
- ✅ CO2 equivalencies showing relatable comparisons
- ✅ Goals can be created and tracked
- ✅ All features work on mobile viewport

Happy testing! 🎉
