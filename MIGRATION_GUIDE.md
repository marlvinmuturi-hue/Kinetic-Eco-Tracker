# Migration Guide - Security Updates

## For Existing Users (Web App)

If you've been using the web app before January 15, 2026, your password was stored using insecure base64 encoding in localStorage. Here's how to migrate to secure Firebase Authentication:

### What Changed:
- **Old:** Passwords stored in browser localStorage (insecure)
- **New:** Passwords managed by Firebase Auth (secure)

### Migration Steps:

#### Option 1: Automatic Migration (Recommended)
1. Simply log in with your existing email and password
2. The app will authenticate via Firebase Auth
3. Your session data will be preserved
4. Old password hash will be ignored

#### Option 2: Fresh Start
1. Clear browser data for the site
2. Create a new account with the same email
3. Your old session data will be available once logged in

### What Happens to Your Data:
- ✅ All your session history is preserved
- ✅ Tracking data remains intact
- ✅ Profile settings stay the same
- ❌ Old password hash is no longer used (safe to ignore)

### For Developers:

The `authenticateOrCreateProfile` function now:
- Checks for legacy profiles with stored password hashes
- Allows authentication for backward compatibility
- Creates new profiles with `passwordHash: 'FIREBASE_AUTH'`
- Does NOT store new passwords in localStorage

Legacy profiles will continue to work but should be migrated to Firebase Auth by:
1. User logs in normally
2. System verifies via Firebase Auth
3. Local profile updated to use Firebase marker

---

## For Android Users

No action required! Android app already uses Firebase Auth exclusively.

### What Changed:
- OAuth client ID moved from code to resources
- Backup disabled for security
- Network security enforced (HTTPS only)

---

## For Administrators

### Deploy Firestore Rules:
```bash
firebase deploy --only firestore:rules
```

### Update Android strings.xml:
Replace `YOUR_WEB_CLIENT_ID_HERE` in `android/app/src/main/res/values/strings.xml` with your actual OAuth 2.0 Web Client ID from Firebase Console.

### Remove Secrets from Git:
```bash
# If google-services.json was committed
git filter-branch --force --index-filter \
  "git rm --cached --ignore-unmatch google-services.json android/app/google-services.json" \
  --prune-empty --tag-name-filter cat -- --all

# Force push (WARNING: coordinate with team)
git push origin --force --all
git push origin --force --tags
```

---

## Testing Checklist

After migration, verify:
- [ ] Web app login works with Firebase Auth
- [ ] Android app login works with Google Sign-In
- [ ] Session data loads correctly
- [ ] New sessions save properly
- [ ] Feedback submission works
- [ ] No errors in browser console
- [ ] No errors in Android logcat

---

## Troubleshooting

### "Invalid email or password" error (Web)
- Ensure Firebase Auth is properly configured
- Check Firebase Console → Authentication → Sign-in method
- Verify email/password provider is enabled

### "Popup blocked" error (Web)
- The app now falls back to redirect method automatically
- No action needed

### "OAuth client ID not found" (Android)
- Update `strings.xml` with your Web Client ID
- Rebuild the app

### Data not loading after login
- Check browser console for errors
- Verify Firestore rules are deployed
- Ensure user is authenticated (check Firebase Auth)

---

**Need Help?** Check the [SECURITY.md](./SECURITY.md) file for more details.
