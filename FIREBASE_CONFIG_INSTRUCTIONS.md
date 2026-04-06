# Fix Firebase API Key Error - Step by Step

## The Problem
Your `.env` file has placeholder values (`your-api-key`, `your-sender-id`, `your-app-id`) instead of real Firebase credentials.

## Solution: Get Your Firebase Config

### Option 1: From Firebase Console (Recommended)

1. **Open Firebase Console:**
   - Go to: https://console.firebase.google.com/project/gen-lang-client-0114974661/settings/general

2. **Check if Web App Exists:**
   - Scroll down to **"Your apps"** section
   - If you see a web app (icon: `</>`), click on it
   - If NO web app exists, continue to step 3

3. **Create Web App (if needed):**
   - Click **"Add app"** button
   - Select **Web** icon (`</>`)
   - Register app with nickname: "Kinetic Eco Tracker Web"
   - Click **"Register app"**

4. **Copy Configuration:**
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

5. **Update .env File:**
   Copy these values to your `.env` file:
   - `apiKey` → Replace `your-api-key`
   - `messagingSenderId` → Replace `your-sender-id`
   - `appId` → Replace `your-app-id`

### Option 2: Quick Fix - Use Firebase Hosting Config

Since you're already using Firebase Hosting, the config might be in your Firebase project settings. The easiest way is to:

1. Go to: https://console.firebase.google.com/project/gen-lang-client-0114974661/settings/general
2. Scroll to "Your apps"
3. If no web app exists, add one
4. Copy the config values

## After Updating .env

1. **Save the .env file**

2. **Restart your dev server:**
   ```bash
   # Stop current server (Ctrl+C)
   npm run dev
   ```

3. **Test login again** - the error should be gone!

## Current .env Status

Your `.env` currently has:
- ✅ `VITE_FIREBASE_AUTH_DOMAIN` - Correct
- ✅ `VITE_FIREBASE_PROJECT_ID` - Correct  
- ✅ `VITE_FIREBASE_STORAGE_BUCKET` - Correct
- ❌ `VITE_FIREBASE_API_KEY` - Needs real value
- ❌ `VITE_FIREBASE_MESSAGING_SENDER_ID` - Needs real value
- ❌ `VITE_FIREBASE_APP_ID` - Needs real value

## Need Help?

If you can't find the config in Firebase Console, you can also:
1. Check your Firebase project's hosting settings
2. Look at any existing Firebase config files
3. Contact Firebase support















