# Quick Start: AI Analysis Feature (Steps 1-4)

## 🚀 Fast Track (20 minutes)

### Prerequisites
1. ✅ Firebase project set up
2. ✅ Android app connected to Firebase
3. ⬜ Node.js installed ([Download](https://nodejs.org/))
4. ⬜ Firebase CLI installed

---

## Step 1: Get Gemini API Key (2 minutes)

### Open in browser:
```
https://aistudio.google.com/app/apikey
```

1. Click **"Get API key"** or **"Create API key"**
2. Select your Firebase project
3. **Copy the key** (starts with `AIza...`)
4. Save it somewhere safe (you'll need it in Step 2)

---

## Step 2: Set Up Cloud Functions (5 minutes)

### Open PowerShell in project root:

```powershell
# 1. Navigate to functions folder
cd functions

# 2. Install dependencies
npm install

# 3. Login to Firebase (if not already)
firebase login

# 4. Select your project
firebase use --add
# Choose your project from the list

# 5. Set the Gemini API key (replace YOUR_API_KEY)
firebase functions:config:set gemini.key="YOUR_API_KEY_HERE"

# 6. Verify it's set
firebase functions:config:get
```

**Expected output:**
```json
{
  "gemini": {
    "key": "AIza..."
  }
}
```

---

## Step 3: Deploy Functions (5 minutes)

### Still in functions folder:

```powershell
# Deploy to Firebase
firebase deploy --only functions
```

**Wait for deployment...** (2-3 minutes)

**Expected output:**
```
✔  functions[analyzeActivity(us-central1)] Successful create operation.
✔  functions[healthCheck(us-central1)] Successful create operation.
```

### Test health check:

1. Copy the healthCheck URL from deployment output
2. Open in browser
3. Should see: `{"status":"ok","timestamp":...}`

---

## Step 4: Update Android App (8 minutes)

### 4.1 Update build.gradle

Open: `android/app/build.gradle`

Add to dependencies section:
```gradle
dependencies {
    // ... existing dependencies ...
    
    // Firebase Functions
    implementation 'com.google.firebase:firebase-functions-ktx:20.4.0'
    
    // Gson (if not already added)
    implementation 'com.google.gson:gson:2.10.1'
    
    // Coroutines (if not already added)
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3'
}
```

Click **"Sync Now"**

### 4.2 Files Already Created ✓

I've already created these files for you:
- ✅ `models/ActivityAnalysis.kt` - Data model
- ✅ `services/AnalysisService.kt` - API client

### 4.3 Test Integration

Add this to any Activity/Fragment to test:

```kotlin
import Kinetic_Eco.Tracker.services.AnalysisService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.util.Log

// In your activity or fragment
private fun testAnalysis() {
    lifecycleScope.launch {
        val service = AnalysisService.getInstance()
        val result = service.analyzeActivity()
        
        result.onSuccess { analysis ->
            Log.d("TEST", "Score: ${analysis.score}")
            Log.d("TEST", "Insights: ${analysis.insights}")
            // Success!
        }.onFailure { error ->
            Log.e("TEST", "Error: ${service.getErrorMessage(error)}")
        }
    }
}
```

---

## Testing Checklist

### ✓ Functions Deployed
```powershell
# Check if functions are live
firebase functions:list
```

Should show:
- analyzeActivity
- healthCheck

### ✓ Android Build Works
1. Sync Gradle
2. Build project
3. Should compile without errors

### ✓ Can Call Function
1. Make sure you're logged in to Firebase Auth
2. Have at least one tracked session
3. Call the test function
4. Check Logcat for results

---

## Troubleshooting

### "API key not set"
```powershell
firebase functions:config:set gemini.key="YOUR_ACTUAL_KEY"
firebase deploy --only functions
```

### "No activity data found"
1. Track an activity first
2. Make sure it's saved to Firestore
3. Check Firestore console: `users/{userId}/sessions`

### "Build fails in Android Studio"
1. File → Invalidate Caches → Restart
2. Clean Project
3. Rebuild Project

### "Function not found"
```powershell
# Re-deploy
firebase deploy --only functions --force
```

### "Timeout error"
- Gemini can take 5-10 seconds
- This is normal for first request
- Subsequent requests use cache (instant)

---

## What's Next?

Once Steps 1-4 work:

### Phase 2: Create UI
- Beautiful card to display insights
- Loading animations
- Error handling UI
- Timeframe selector (7/30/90 days)

### Phase 3: Polish
- Pull-to-refresh
- Share insights
- Achievement animations
- Notification when analysis ready

---

## Command Reference

### Firebase Commands
```powershell
# Deploy functions
firebase deploy --only functions

# View function logs
firebase functions:log

# Test locally (emulator)
firebase emulators:start --only functions

# Check config
firebase functions:config:get

# Remove config
firebase functions:config:unset gemini.key
```

### Android Gradle
```powershell
# Clean build
.\gradlew clean

# Build debug
.\gradlew assembleDebug

# Install on device
.\gradlew installDebug
```

---

## Cost Estimate

For testing/demo (100 analysis requests):
- **Gemini API**: FREE (within limits)
- **Cloud Functions**: FREE (within limits)
- **Firestore**: FREE (within limits)

**Total: $0/month** 🎉

---

## Support

### Firebase Console URLs
- **Functions**: https://console.firebase.google.com/project/YOUR_PROJECT/functions
- **Firestore**: https://console.firebase.google.com/project/YOUR_PROJECT/firestore
- **Logs**: https://console.firebase.google.com/project/YOUR_PROJECT/functions/logs

### Gemini API
- **Dashboard**: https://aistudio.google.com/app/apikey
- **Docs**: https://ai.google.dev/docs

---

**Ready to start? Run the setup script:**

```powershell
.\setup-ai-analysis.bat
```

Or follow steps manually above! 🚀
