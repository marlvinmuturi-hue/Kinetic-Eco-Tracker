# 🔍 Authentication Debug Guide

## Understanding the Issue

There's **NO separate AI platform to sign into**. The AI Analysis uses your app's Firebase authentication automatically. The error means your auth token isn't reaching the Cloud Function.

---

## How Authentication Works

```
Android App
    ↓ (You sign in)
Firebase Auth
    ↓ (Gets auth token)
Firebase Functions
    ↓ (Validates token)
Gemini AI
```

**You only sign in ONCE - in the Android app!**

---

## Step-by-Step Debug Process

### Step 1: Check You're Signed In

1. **Open your app**
2. **Look at the bottom tabs** - do you see them?
   - If you see Login screen → You're NOT signed in
   - If you see Tracker/Analytics/Profile tabs → You're signed in

3. **Go to Profile tab**
   - Do you see your email/name?
   - ✅ YES = Signed in
   - ❌ NO = Not signed in (sign in first!)

---

### Step 2: Rebuild with Debug Logging

1. **Sync Gradle**:
   ```
   File → Sync Project with Gradle Files
   ```

2. **Clean & Rebuild**:
   ```
   Build → Clean Project
   Build → Rebuild Project
   ```

3. **Open Logcat** (bottom of Android Studio)
   - Click "Logcat" tab
   - Filter by: `AIAnalysisService`

4. **Run the app** (click ▶️)

5. **Sign in to the app** (if not already)

6. **Go to Analytics → AI Analysis**

7. **Click "Analyze My Activity"**

8. **Watch Logcat** for these messages:

---

### Step 3: Read the Debug Output

#### ✅ GOOD - Authentication Working:
```
D/AIAnalysisService: ===== AI ANALYSIS REQUEST =====
D/AIAnalysisService: User authenticated: abc123xyz
D/AIAnalysisService: User email: your@email.com
D/AIAnalysisService: Timeframe: 7days
D/AIAnalysisService: ID Token obtained: eyJhbGciOiJSUzI1NiI...
D/AIAnalysisService: Calling Firebase Function...
D/AIAnalysisService: Function call successful!
D/AIAnalysisService: AI analysis received: score=8.5
```

#### ❌ BAD - Not Signed In:
```
W/AIAnalysisService: User not authenticated locally
```
**Fix:** Sign in to the app!

#### ❌ BAD - Auth Token Not Reaching Function:
```
D/AIAnalysisService: ===== AI ANALYSIS REQUEST =====
D/AIAnalysisService: User authenticated: abc123xyz
D/AIAnalysisService: User email: your@email.com
D/AIAnalysisService: Timeframe: 7days
D/AIAnalysisService: ID Token obtained: eyJhbGciOiJSUzI1NiI...
D/AIAnalysisService: Calling Firebase Function...
E/AIAnalysisService: Error analyzing activity
    com.google.firebase.functions.FirebaseFunctionsException: UNAUTHENTICATED
```
**Fix:** See Step 4 below

---

### Step 4: Fix "UNAUTHENTICATED" Error

If Logcat shows you're signed in locally but the function says "UNAUTHENTICATED", try these:

#### Option A: Clear App Data
1. **Uninstall the app** completely from device/emulator
2. **In Android Studio**: Build → Clean Project
3. **Run the app** again (fresh install)
4. **Sign in** (fresh auth token)
5. **Test AI Analysis** again

#### Option B: Check Firebase Console
1. Go to [Firebase Console](https://console.firebase.google.com)
2. Select your project: `gen-lang-client-0114974661`
3. Go to **Authentication**
4. Check **Users** tab - is your email listed?
   - ✅ YES = Firebase knows about you
   - ❌ NO = Sign in again in the app

#### Option C: Check Functions Region
Make sure your functions are in `us-central1`:
1. Firebase Console → **Functions**
2. Look for `analyzeActivity`
3. Check region column - should say `us-central1`

If it's a different region, update `AIAnalysisService.kt`:
```kotlin
private val functions: FirebaseFunctions = Firebase.functions("YOUR_REGION_HERE")
```

---

### Step 5: Test with Fresh Account

If nothing works, try creating a new test account:

1. **Sign out** of the app
2. **Sign up** with a new email:
   - Email: `test@example.com`
   - Password: `Test123456!`
3. **Track one activity** (walk 1 minute)
4. **Go to Analytics → AI Analysis**
5. **Click "Analyze My Activity"**

---

## Common Errors & Fixes

### Error: "Please sign in to the app first"
**Logcat shows:** `User not authenticated locally`

**Cause:** You're not signed in to the app

**Fix:**
1. Look for **Login** button or screen
2. Sign in with email/password or Google
3. Try AI Analysis again

---

### Error: "User must be authenticated to analyze activity"
**Logcat shows:** Auth token obtained but function rejects it

**Cause:** Auth token not reaching Cloud Function

**Fix:**
1. **Uninstall app** completely
2. **Clean build**: Build → Clean Project
3. **Rebuild**: Build → Rebuild Project
4. **Run app** (fresh install)
5. **Sign in** again
6. **Test** AI Analysis

---

### Error: "No activity data found"
**This means AUTH IS WORKING!** ✅

**Fix:**
1. Go to **Tracker** tab
2. **Start tracking**
3. Walk for 1-2 minutes
4. **Stop and save**
5. Go to **Analytics → AI Analysis**
6. Try again - should work now!

---

## Quick Checklist

Before asking for help, verify:

- [ ] I can see bottom navigation tabs (Tracker, Analytics, Profile)
- [ ] Profile tab shows my email/name
- [ ] I've synced Gradle files
- [ ] I've cleaned and rebuilt the project
- [ ] I've uninstalled and reinstalled the app
- [ ] I've signed in fresh after reinstall
- [ ] Logcat shows "User authenticated: [my-user-id]"
- [ ] Logcat shows "ID Token obtained: ..."
- [ ] I have at least 1 tracked activity session

---

## Still Not Working?

### Share These Debug Logs:

1. **Open Logcat**
2. **Filter by**: `AIAnalysisService`
3. **Click "Analyze My Activity"**
4. **Copy the output** (all red/yellow lines)
5. **Share** the logs

### Example of helpful log output:
```
D/AIAnalysisService: ===== AI ANALYSIS REQUEST =====
D/AIAnalysisService: User authenticated: abc123xyz
D/AIAnalysisService: User email: test@example.com
D/AIAnalysisService: Timeframe: 7days
D/AIAnalysisService: ID Token obtained: eyJhbGciOiJSUzI1NiI...
D/AIAnalysisService: Calling Firebase Function...
E/AIAnalysisService: Error analyzing activity
    com.google.firebase.functions.FirebaseFunctionsException: UNAUTHENTICATED: User must be authenticated
```

This will show exactly where the auth is failing!

---

## Summary

1. **Sign in to the APP** (not a separate platform)
2. **Check Profile tab** - see your email?
3. **Rebuild the app** with debug logging
4. **Check Logcat** for auth messages
5. **If "UNAUTHENTICATED"** → Uninstall, clean, rebuild, sign in fresh
6. **If "No activity data"** → Auth works! Track activities first

**There is NO separate AI platform login!** Everything uses your app's Firebase Auth. 🔐
