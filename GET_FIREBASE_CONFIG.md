# How to Get Your Firebase Configuration Values

## Quick Steps

1. **Go to Firebase Console:**
   - Open: https://console.firebase.google.com/project/gen-lang-client-0114974661/settings/general

2. **Find Your Web App Config:**
   - Scroll down to the **"Your apps"** section
   - Look for a web app (icon: `</>`)
   - If no web app exists, click **"Add app"** → Select **Web** (`</>`)
   - Click on the web app to see its configuration

3. **Copy the Configuration:**
   You'll see something like this:
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

4. **Update Your .env File:**
   Replace the placeholder values in `.env` with the actual values:
   - `apiKey` → `VITE_FIREBASE_API_KEY`
   - `messagingSenderId` → `VITE_FIREBASE_MESSAGING_SENDER_ID`
   - `appId` → `VITE_FIREBASE_APP_ID`

## Alternative: Use Firebase CLI

If you have Firebase CLI installed, you can also get the config:

```bash
firebase apps:sdkconfig web
```

This will output the configuration in JSON format.

## After Updating .env

1. **Restart your dev server:**
   - Stop the current server (Ctrl+C)
   - Run `npm run dev` again

2. **Test the login:**
   - Try signing in with email/password
   - Try Google sign-in
   - The error should be resolved















