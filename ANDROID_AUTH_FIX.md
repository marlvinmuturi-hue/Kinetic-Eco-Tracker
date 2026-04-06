# Android Authentication Fix Guide

## Problem
Android app gets `FirebaseFunctionsException: UNAUTHENTICATED` when calling Cloud Functions, even though:
- User is authenticated locally
- Fresh ID token is obtained
- SHA-256 fingerprint is added to Firebase

## Complete Solution

### Step 1: Verify Firebase Project Setup

1. **Go to Firebase Console** → [https://console.firebase.google.com](https://console.firebase.google.com)
2. **Select project**: gen-lang-client-0114974661
3. **Settings (gear icon) → Project settings**
4. **Scroll to "Your apps"**
5. **Find Android app** (package: `Kinetic_Eco.Tracker`)
6. **Verify SHA-256 fingerprint is added**:
   ```
   9E:6F:13:4E:39:F8:F1:9F:8C:B7:7D:81:85:D8:95:8C:48:57:7A:4E:7E:D1:6E:7B:B0:1B:BA:37:EB:6D:83:F3
   ```

### Step 2: Download Updated google-services.json

**CRITICAL**: After adding SHA-256, you MUST download the new config file!

1. In Firebase Console → Project settings → Your apps
2. Click **Download google-services.json** (under your Android app)
3. **Replace** `android/app/google-services.json` with the new file
4. **Verify the file was updated** (check timestamp)

### Step 3: Verify Firebase Authentication Domain

1. Firebase Console → **Authentication**
2. Click **Settings** tab
3. Click **Authorized domains**
4. **Verify these domains are listed**:
   - `gen-lang-client-0114974661.firebaseapp.com`
   - `gen-lang-client-0114974661.web.app`
   - `us-central1-gen-lang-client-0114974661.cloudfunctions.net`

5. If missing, **Add** the Cloud Functions domain:
   - Click **Add domain**
   - Enter: `us-central1-gen-lang-client-0114974661.cloudfunctions.net`
   - Click **Add**

### Step 4: Make Cloud Functions Publicly Accessible (Temporary)

Since gcloud CLI isn't installed, do this manually:

1. Firebase Console → **Functions**
2. Click **analyzeActivity** function
3. Click **Permissions** tab
4. Click **Add Principal**
5. Enter: `allUsers`
6. Select Role: **Cloud Functions Invoker**
7. Click **Save**

### Step 5: Clean and Rebuild Android App

```bash
# In Android Studio:
1. Build → Clean Project
2. Build → Rebuild Project
3. Uninstall app from device/emulator
4. Run app again
```

Or use command line:
```bash
cd android
./gradlew clean
./gradlew assembleDebug
adb uninstall Kinetic_Eco.Tracker
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Step 6: Test Again

1. Open app
2. Sign in (if needed)
3. Go to Analytics → AI Analysis
4. Click "Analyze My Activity"
5. Check Logcat for logs

## Expected Logcat Output (Success)

```
AIAnalysisService: ===== AI ANALYSIS REQUEST =====
AIAnalysisService: User authenticated: 4pKkd40ki3dt8SMQVMjIKM7xK2O2
AIAnalysisService: Calling Firebase Function...
AIAnalysisService: ✅ Analysis successful
```

## If Still Failing

### Check Firebase Function Logs
1. Firebase Console → Functions
2. Click **analyzeActivity**
3. Click **Logs** tab
4. Look for entries - **if empty**, the request isn't reaching the function

### Verify Network Connectivity
Test if your device can reach Firebase:
```bash
# From your computer:
curl -X POST https://us-central1-gen-lang-client-0114974661.cloudfunctions.net/analyzeActivity
```

Should return: `{"error":{"message":"Bad Request","status":"INVALID_ARGUMENT"}}`
This means the endpoint is reachable.

## Alternative: Use HTTP Endpoint Instead

If callable functions continue to fail, we can switch to direct HTTP calls:

**Pros**:
- More control over authentication
- Better debugging
- Works with any HTTP client

**Cons**:
- Need to manually handle auth headers
- More code to write

Let me know if you want to try this approach!

## Your SHA-256 Fingerprint
```
9E:6F:13:4E:39:F8:F1:9F:8C:B7:7D:81:85:D8:95:8C:48:57:7A:4E:7E:D1:6E:7B:B0:1B:BA:37:EB:6D:83:F3
```

## Region Note
Your location (outside us-central1) should NOT affect function calls - Firebase Functions work globally!
