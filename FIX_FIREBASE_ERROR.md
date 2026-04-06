# Fix Firebase API Key Error - Quick Guide

## The Problem
Your `.env` file has placeholder values instead of real Firebase credentials, causing the error:
```
Firebase: Error (auth/api-key-not-valid.-please-pass-a-valid-api-key.)
```

## Solution (Choose One)

### Option 1: Create Web App in Firebase Console (Recommended)

1. **Open Firebase Console:**
   - Go to: https://console.firebase.google.com/project/gen-lang-client-0114974661/settings/general
   - Sign in with your Google account if prompted

2. **Scroll down to "Your apps" section**

3. **Add a Web App:**
   - Click the **"Add app"** button (or the `</>` Web icon)
   - Register your app with nickname: "Kinetic Eco Tracker Web"
   - Click **"Register app"**

4. **Copy the Configuration:**
   - You'll see a code snippet like this:
   ```javascript
   const firebaseConfig = {
     apiKey: "AIzaSyXXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
     authDomain: "gen-lang-client-0114974661.firebaseapp.com",
     projectId: "gen-lang-client-0114974661",
     storageBucket: "gen-lang-client-0114974661.appspot.com",
     messagingSenderId: "123456789012",
     appId: "1:123456789012:web:abcdef123456"
   };
   ```

5. **Update your `.env` file:**
   - Copy the values from the config above
   - Replace in `.env`:
     - `apiKey` → `VITE_FIREBASE_API_KEY=AIzaSy...` (the actual value)
     - `messagingSenderId` → `VITE_FIREBASE_MESSAGING_SENDER_ID=123456789012`
     - `appId` → `VITE_FIREBASE_APP_ID=1:123456789012:web:abcdef123456`

6. **Restart your dev server:**
   ```bash
   # Stop current server (Ctrl+C), then:
   npm run dev
   ```

### Option 2: Use Firebase CLI (If web app already exists)

If you've already created a web app, run:
```bash
firebase apps:sdkconfig web --project gen-lang-client-0114974661
```

Copy the values and update your `.env` file accordingly.

## Current `.env` Status

Your `.env` currently has:
- ✅ `VITE_FIREBASE_AUTH_DOMAIN` - Correct
- ✅ `VITE_FIREBASE_PROJECT_ID` - Correct  
- ✅ `VITE_FIREBASE_STORAGE_BUCKET` - Correct
- ❌ `VITE_FIREBASE_API_KEY` - **NEEDS REAL VALUE**
- ❌ `VITE_FIREBASE_MESSAGING_SENDER_ID` - **NEEDS REAL VALUE**
- ❌ `VITE_FIREBASE_APP_ID` - **NEEDS REAL VALUE**

## After Fixing

Once you've updated `.env` with real values:
1. Save the file
2. Restart your dev server (`npm run dev`)
3. Try logging in again - the error should be gone!

## Quick Reference: What to Replace

In your `.env` file, replace these lines:

**BEFORE:**
```env
VITE_FIREBASE_API_KEY=your-api-key
VITE_FIREBASE_MESSAGING_SENDER_ID=your-sender-id
VITE_FIREBASE_APP_ID=your-app-id
```

**AFTER (example):**
```env
VITE_FIREBASE_API_KEY=AIzaSyBcUXGNlijGHTbGezD7U-2pgwzsQapk8wc
VITE_FIREBASE_MESSAGING_SENDER_ID=123456789012
VITE_FIREBASE_APP_ID=1:123456789012:web:abcdef123456
```

*(Use YOUR actual values from Firebase Console, not these examples)*















