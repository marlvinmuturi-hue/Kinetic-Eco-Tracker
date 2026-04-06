# Enable Google Sign-In on Deployed Site

## Problem
The Google sign-in button is not visible or not working on the deployed site at https://gen-lang-client-0114974661.web.app/

## Solution Steps

### Step 1: Enable Google Authentication in Firebase Console

1. **Open Firebase Console:**
   - Go to: https://console.firebase.google.com/project/gen-lang-client-0114974661/authentication/providers

2. **Enable Google Sign-In:**
   - Look for **"Google"** in the list of sign-in providers
   - Click on **"Google"**
   - Toggle the **"Enable"** switch to ON
   - Enter your project's support email (usually your Google account email)
   - Click **"Save"**

### Step 2: Add Authorized Domain for Production

1. **Open Authentication Settings:**
   - Go to: https://console.firebase.google.com/project/gen-lang-client-0114974661/authentication/settings

2. **Add Production Domain:**
   - Scroll to **"Authorized domains"** section
   - Click **"Add domain"**
   - Add: `gen-lang-client-0114974661.web.app`
   - Also add: `gen-lang-client-0114974661.firebaseapp.com` (if not already there)

### Step 3: Rebuild and Redeploy

After enabling Google auth and adding domains, rebuild and redeploy your site:

```bash
# Build the project
npm run build

# Deploy to Firebase
npm run deploy:hosting
```

Or deploy everything:
```bash
npm run deploy
```

### Step 4: Verify

1. Visit: https://gen-lang-client-0114974661.web.app/
2. You should now see:
   - A "Continue with Google" button at the top of the login form
   - A divider saying "Or continue with email"
   - The Google sign-in button should work when clicked

## Troubleshooting

### Google Button Still Not Showing?

1. **Check Browser Console:**
   - Open browser DevTools (F12)
   - Check for any JavaScript errors
   - Look for Firebase configuration errors

2. **Clear Browser Cache:**
   - Hard refresh: Ctrl+Shift+R (Windows) or Cmd+Shift+R (Mac)
   - Or clear browser cache completely

3. **Verify Firebase Config:**
   - Check that your `.env` file has correct Firebase values
   - The build process should include these values

4. **Check Network Tab:**
   - Verify Firebase API calls are working
   - Check if there are any CORS or network errors

### Google Sign-In Popup Blocked?

- Make sure popups are allowed for your site
- Check browser popup blocker settings
- Try a different browser or incognito mode

### Still Having Issues?

1. Verify the deployed build has the latest code:
   ```bash
   # Check if dist folder exists and has latest files
   ls -la dist/
   ```

2. Check Firebase logs:
   - Go to Firebase Console > Authentication > Users
   - Check for any error messages

3. Test locally first:
   ```bash
   npm run dev
   # Visit http://localhost:3000
   # Verify Google sign-in works locally
   ```















