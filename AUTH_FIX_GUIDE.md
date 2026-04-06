# 🔧 Authentication Error Fix Guide

## Error
```
"Analysis Error. Please sign in to use activity analysis"
```

## What I Fixed

### 1. Added Region Specification
Firebase Functions needs to know the region where your functions are deployed.

**Updated `AIAnalysisService.kt`:**
```kotlin
private val functions: FirebaseFunctions = Firebase.functions("us-central1")
```

### 2. Added Authentication Check
Added explicit check to verify user is signed in before calling the function.

```kotlin
val currentUser = auth.currentUser
if (currentUser == null) {
    return Result.failure(Exception("Please sign in to use activity analysis"))
}
```

---

## Troubleshooting Steps

### Step 1: Verify You're Signed In

**Check in your app:**
1. Go to **Profile** or **Settings** tab
2. Verify you see your email/name displayed
3. Make sure you're signed in with **Firebase Auth** (not local auth)

**If you're NOT signed in:**
- Go to Login screen
- Sign in with email/password OR Google Sign-In
- **Important:** You must use the same account you used when testing the web app

---

### Step 2: Check Logcat for Auth Info

After syncing and rebuilding:

1. Open **Logcat** in Android Studio
2. Filter by: `AIAnalysisService`
3. Click "Analyze My Activity"
4. Look for these log messages:

**✅ Good (User authenticated):**
```
D/AIAnalysisService: User authenticated: [user-id]
D/AIAnalysisService: Requesting AI analysis for timeframe: 7days
```

**❌ Bad (User not authenticated):**
```
W/AIAnalysisService: User not authenticated
```

---

### Step 3: Rebuild and Test

1. **Sync Gradle**:
   ```
   File → Sync Project with Gradle Files
   ```

2. **Clean Build**:
   ```
   Build → Clean Project
   Build → Rebuild Project
   ```

3. **Uninstall the old app** from your device/emulator
   - This ensures fresh authentication state

4. **Run the app** again

5. **Sign in** (email or Google)

6. **Go to Analytics → AI Analysis**

7. **Click "Analyze My Activity"**

---

### Step 4: Test With Sample Data

**If you see "No activity data found":**

That's actually GOOD! It means authentication worked, but you need to track activities first.

1. Go to **Tracker** tab
2. Click **Start Tracking**
3. Walk for 1-2 minutes (or just wait)
4. Click **Stop Tracking**
5. **Save** the session
6. Go back to **Analytics → AI Analysis**
7. Try again!

---

## Common Issues & Solutions

### Issue 1: "User not authenticated" in Logcat

**Cause:** Not signed in to Firebase Auth

**Solution:**
- Make sure you **sign in** after installing the app
- Try signing out and signing back in
- Check that Firebase Auth is properly configured in `google-services.json`

---

### Issue 2: "UNAUTHENTICATED" error from Firebase

**Cause:** Auth token not being passed to Cloud Function

**Solution:**
- Uninstall and reinstall the app
- Sign in again
- The region fix (`us-central1`) should resolve this

---

### Issue 3: "No activity data found"

**Cause:** No tracked sessions in Firestore

**Solution:** ✅ This means auth is working!
- Track at least one activity session
- The Cloud Function needs data to analyze
- Try analyzing again after tracking

---

### Issue 4: Function times out

**Cause:** Cold start (first call takes longer)

**Solution:**
- Wait 15-20 seconds on first call
- Subsequent calls will be much faster (2-5 seconds)
- Cached results are instant

---

## Quick Test

Run this quick test to verify everything:

1. ✅ **Sync and rebuild** the app
2. ✅ **Uninstall** old version from device
3. ✅ **Install** new version (Run button)
4. ✅ **Sign in** with email or Google
5. ✅ **Track** an activity (1-2 minutes)
6. ✅ **Go to Analytics → AI Analysis**
7. ✅ **Click "Analyze My Activity"**

**Expected Result:**
- If no data: "No activity data found" (auth worked!)
- If has data: Beautiful AI insights appear!

---

## Debug Commands

### Check Logcat for Authentication
```
Filter: AIAnalysisService
```

### Check Logcat for Function Call
```
Filter: functions
```

### Check Firebase Auth State
In your `AIAnalysisScreen`, you can temporarily add this to verify:
```kotlin
val auth = FirebaseAuth.getInstance()
Log.d("DEBUG", "Current user: ${auth.currentUser?.email ?: "Not signed in"}")
```

---

## Still Not Working?

### Check these files:

1. **`google-services.json`** - Must be in `android/app/` folder
2. **Firebase Console** - Check that Authentication is enabled
3. **Firebase Console** - Check Functions are deployed (`analyzeActivity`)
4. **Firebase Console** - Check Functions region is `us-central1`

### Check Firebase Console Logs:

1. Go to **Firebase Console**
2. Click **Functions** → **Logs**
3. Click "Analyze My Activity" in your app
4. Check for errors in Firebase logs

Common errors in logs:
- `"User not authenticated"` - Sign in to the app
- `"No activity data found"` - Track activities first
- `"Rate limit exceeded"` - Wait 1 hour (or use cached result)

---

## Summary of Changes

**File:** `android/app/src/main/java/Kinetic_Eco/Tracker/services/AIAnalysisService.kt`

**Changes:**
1. Added region: `Firebase.functions("us-central1")`
2. Added auth check before calling function
3. Added logging to track authentication state

**Next Steps:**
1. Sync & rebuild
2. Uninstall old app
3. Install fresh
4. Sign in
5. Test AI Analysis

---

**The auth error should now be resolved!** 🔐

If you still see the error after following these steps, check Logcat for the specific auth state.
