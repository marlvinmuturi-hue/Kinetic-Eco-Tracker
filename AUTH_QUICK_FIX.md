# ⚡ QUICK FIX - Firebase Auth Error

## 🎯 The Error

```
Firebase: error (auth/unauthorized-domain)
The requested action is invalid
```

## ✅ Quick Solution (2 Minutes)

### Step 1: Open Firebase Console
```
https://console.firebase.google.com
```

### Step 2: Go to Authentication
1. Select project: `0114974661`
2. Click **"Authentication"** (left sidebar)
3. Click **"Settings"** tab
4. Scroll to **"Authorized domains"**

### Step 3: Add Your Localhost IP
1. Click **"Add domain"**
2. Type: `192.168.100.14`
3. Click **"Add"**

### Step 4: Wait & Test
1. Wait 1 minute (Firebase updates)
2. Close Android app completely
3. Reopen app
4. Try Google Sign-In again
5. **Should work!** ✅

---

## 🎯 Alternative: Use Firebase URL Instead

Don't want to add domain? Deploy and use production:

```bash
# 1. Deploy to Firebase
npm run build
firebase deploy --only hosting

# 2. Update MainActivity.kt line 145:
webView.loadUrl("https://0114974661.web.app")

# 3. Rebuild Android app
```

Firebase URL is already authorized, so auth will work immediately!

---

## ✅ What to Expect After Fix

- ✅ Click "Sign in with Google"
- ✅ See Google account picker
- ✅ Select your account
- ✅ Sign in successfully
- ✅ Access tracker features!

---

**Go add the domain in Firebase Console now!** 🚀

See `FIREBASE_AUTH_FIX.md` for detailed guide.



