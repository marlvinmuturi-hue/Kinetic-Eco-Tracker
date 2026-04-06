# Fix Firebase "auth/unauthorized-domain" Error

## The Problem
Firebase Authentication is blocking requests from `localhost` because it's not in the list of authorized domains.

## Solution: Add Authorized Domain in Firebase Console

### Step-by-Step Instructions:

1. **Open Firebase Console:**
   - Go to: https://console.firebase.google.com/project/gen-lang-client-0114974661/authentication/settings
   - Sign in with your Google account if prompted

2. **Navigate to Authorized Domains:**
   - Make sure you're on the **"Settings"** tab (it should be selected by default)
   - Scroll down to find the **"Authorized domains"** section
   - Look for a grid/table showing currently authorized domains

3. **Add Localhost:**
   - Look for an **"Add domain"** button (usually near the authorized domains list)
   - Click **"Add domain"**
   - In the input field, type: `localhost`
   - Click **"Add"** or press Enter

4. **Verify the Domain was Added:**
   - You should now see `localhost` in the list of authorized domains
   - Common default domains that should already be there:
     - `gen-lang-client-0114974661.firebaseapp.com` (your Firebase hosting domain)
     - `gen-lang-client-0114974661.web.app` (your Firebase hosting domain)

5. **Optional - Add Other Domains (if needed):**
   If you're accessing your app from:
   - `127.0.0.1` (IP address) → Add `127.0.0.1`
   - A network IP (e.g., `192.168.x.x:3000`) → Add your machine's IP address (without the port)

6. **Save Changes:**
   - The domain will be added automatically (no separate save button needed)
   - Changes take effect immediately

## Important Notes:

- ✅ `localhost` is usually already there by default, but sometimes it gets removed or needs to be re-added
- ✅ You only need to add the domain name (e.g., `localhost`), **NOT** the port (Firebase handles ports automatically)
- ✅ If you're deploying to production, make sure your production domain is also in the list

## After Adding the Domain:

1. **Refresh your browser** where the app is running
2. **Try authentication again** - the error should be resolved

## Common Authorized Domains:

For local development, you typically need:
- `localhost`
- `127.0.0.1` (if accessing via IP)

For production, you'll need:
- Your Firebase hosting domain (e.g., `your-project.web.app`)
- Your custom domain (if you have one)

## Quick Link:
Direct link to Authorized Domains settings:
https://console.firebase.google.com/project/gen-lang-client-0114974661/authentication/settings

