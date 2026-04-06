# 🔧 Troubleshooting "Failed to Save Session" Error

## 🎯 Quick Fixes (Try These First!)

### **Fix 1: Update Firestore Security Rules** (Most Common!)

1. Go to [Firebase Console](https://console.firebase.google.com/)
2. Select project: **gen-lang-client-0114974661**
3. Click **Firestore Database** (left menu)
4. Click **Rules** tab
5. Replace with this:

```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    // Allow authenticated users to read/write their own data
    match /users/{userId} {
      allow read, write: if request.auth != null && request.auth.uid == userId;
      
      // Allow users to read/write their own sessions
      match /sessions/{sessionId} {
        allow read, write: if request.auth != null && request.auth.uid == userId;
      }
    }
  }
}
```

6. Click **Publish**
7. **Test your app again!**

---

### **Fix 2: Check Internet Connection**

- Make sure device has WiFi or mobile data
- Try toggling airplane mode off/on
- Check if other apps can access internet

---

### **Fix 3: Verify User is Logged In**

1. Open your app
2. Go to Profile tab
3. Check if you see your email at the top
4. If not logged in → Sign out and sign back in

---

## 🔍 **Detailed Diagnosis**

### **Step 1: Check Logcat for Exact Error**

In Android Studio:
```
1. View → Tool Windows → Logcat
2. Filter by "SessionManager" or "TrackerFragment"
3. Look for red error messages
```

### **Common Error Messages:**

#### **Error: "PERMISSION_DENIED"**
**Cause:** Firestore security rules blocking access  
**Fix:** Update Firestore rules (see Fix 1 above)

#### **Error: "User not logged in"**
**Cause:** Firebase Auth session expired  
**Fix:** 
```
1. Go to Profile tab
2. Sign out
3. Sign back in
4. Try tracking again
```

#### **Error: "Network error" or "UNAVAILABLE"**
**Cause:** No internet connection or Firestore offline  
**Fix:**
```
1. Check internet connection
2. Session will be queued offline
3. Will sync when connection restored
```

#### **Error: "Field validation failed"**
**Cause:** Data type mismatch  
**Fix:** Already handled in code, shouldn't happen

---

## 🧪 **Test the Fixes**

### **Step 1: Rebuild App**
```
1. Build → Clean Project
2. Build → Rebuild Project
3. Run app
```

### **Step 2: Test Tracking**
```
1. Start tracking
2. Walk a few meters
3. Stop tracking
4. Click "Save Session"
5. Check Logcat for:
   "✅ Session saved successfully: [ID]"
```

### **Step 3: Verify in Firebase Console**
```
1. Firebase Console → Firestore Database
2. Navigate to: users → [your-user-id] → sessions
3. Should see your saved sessions
```

---

## 📊 **Check Firestore Database**

### **Verify Database Exists:**

1. Firebase Console → Firestore Database
2. Should see "users" collection
3. If empty, that's okay - will populate on first save

### **Check User ID:**

Run this in your app to see user ID:
```kotlin
// In any fragment
Log.d("DEBUG", "Current user ID: ${auth.currentUser?.uid}")
Log.d("DEBUG", "User email: ${auth.currentUser?.email}")
```

---

## 🔄 **Updated Error Handling**

I've added better error messages. Now when save fails, you'll see:

**In Toast:**
- "Error: Not logged in" (if no user)
- "Failed to save: [specific error]" (with actual error message)

**In Logcat:**
- "❌ Error saving session: [detailed message]"
- "Error type: [exception type]"
- Session data for debugging

---

## 🎯 **Most Likely Causes (in order):**

1. **Firestore Security Rules** (90% of cases)
   - Fix: Update rules as shown above
   
2. **User Not Logged In** (5% of cases)
   - Fix: Sign out and sign back in
   
3. **No Internet Connection** (4% of cases)
   - Fix: Connect to internet, session will sync
   
4. **Firebase Not Initialized** (1% of cases)
   - Fix: Restart app

---

## 💡 **Quick Test:**

Try this to verify everything works:

```kotlin
// Add to TrackerFragment temporarily
private fun testSave() {
    lifecycleScope.launch {
        Log.d(TAG, "Testing session save...")
        
        // Check auth
        val user = auth.currentUser
        if (user == null) {
            Log.e(TAG, "❌ TEST FAILED: User not logged in")
            Toast.makeText(requireContext(), "Not logged in!", Toast.LENGTH_LONG).show()
            return@launch
        }
        Log.d(TAG, "✅ User logged in: ${user.email}")
        
        // Create test session
        val testSession = Session(
            distance = 1.0,
            duration = 60,
            avgSpeed = 5.0,
            carbonFootprint = 0.21,
            activityType = "Test",
            steps = 100,
            startTime = System.currentTimeMillis() - 60000,
            endTime = System.currentTimeMillis(),
            status = SessionStatus.COMPLETED
        )
        
        // Try to save
        val result = sessionManager.saveSession(testSession)
        if (result.isSuccess) {
            Log.d(TAG, "✅ TEST PASSED: Session saved!")
            Toast.makeText(requireContext(), "Test session saved!", Toast.LENGTH_LONG).show()
        } else {
            Log.e(TAG, "❌ TEST FAILED: ${result.exceptionOrNull()?.message}")
            Toast.makeText(requireContext(), "Test failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
        }
    }
}

// Call testSave() from somewhere to test
```

---

## 📱 **Next Steps:**

1. **Update Firestore Rules** (most important!)
2. **Rebuild and run** app
3. **Check Logcat** for detailed error
4. **Tell me the exact error** from Logcat

---

## 🆘 **Still Not Working?**

**Send me:**
1. Exact error from Logcat (copy/paste)
2. Current Firestore security rules
3. Are you logged in? (check Profile tab)
4. Do you have internet connection?

**I'll fix it immediately!** 🚀









