# Security Guidelines for Kinetic Eco Tracker

## 🔒 Security Improvements Implemented

### ✅ Completed (January 15, 2026)

1. **Secrets Protection**
   - Added `google-services.json` to `.gitignore` to prevent Firebase config exposure
   - Moved OAuth client ID from hardcoded string to Android resources (`strings.xml`)

2. **Data Privacy**
   - Updated Firestore rules: feedback is now write-only (users can't read others' feedback)
   - Disabled Android backup (`allowBackup="false"`) to prevent data extraction

3. **Network Security**
   - Added `network_security_config.xml` to enforce HTTPS for all connections
   - Cleartext traffic only allowed for localhost (development/debugging)

4. **Password Security**
   - Removed insecure base64 "hashing" from web app
   - Marked localStorage password storage as deprecated
   - New profiles use Firebase Auth exclusively (no local password storage)

---

## ⚠️ Important Configuration Steps

### Before Deploying:

1. **Update Firebase Web Client ID** (Android App)
   - Open `android/app/src/main/res/values/strings.xml`
   - Replace `YOUR_WEB_CLIENT_ID_HERE` with your actual OAuth 2.0 Web Client ID
   - Get it from: Firebase Console → Project Settings → General → Web App

2. **Deploy Firestore Security Rules**
   ```bash
   firebase deploy --only firestore:rules
   ```

3. **Remove google-services.json from Git History** (if already committed)
   ```bash
   git rm --cached google-services.json
   git rm --cached android/app/google-services.json
   git commit -m "Remove sensitive Firebase config files"
   git push
   ```

4. **Set Environment Variables** (Web App)
   Create a `.env` file in the root directory:
   ```env
   VITE_FIREBASE_API_KEY=your-api-key
   VITE_FIREBASE_AUTH_DOMAIN=your-project.firebaseapp.com
   VITE_FIREBASE_PROJECT_ID=your-project-id
   VITE_FIREBASE_STORAGE_BUCKET=your-project.appspot.com
   VITE_FIREBASE_MESSAGING_SENDER_ID=your-sender-id
   VITE_FIREBASE_APP_ID=your-app-id
   ```

---

## 🔐 Recommended Future Enhancements

### High Priority:

1. **Encrypted Local Database (Android)**
   - Implement SQLCipher for Room database encryption
   - Protects data if device is rooted/compromised

2. **Certificate Pinning**
   - Pin Firebase and Google API certificates
   - Prevents man-in-the-middle attacks

3. **ProGuard/R8 Obfuscation**
   - Enable code obfuscation for release builds
   - Makes reverse engineering more difficult

### Medium Priority:

4. **Biometric Authentication**
   - Add fingerprint/face unlock option
   - Improves user experience and security

5. **Firebase App Check**
   - Prevent unauthorized API access
   - Block requests from non-genuine apps

6. **Root/Jailbreak Detection**
   - Warn users if device is compromised
   - Optionally disable sensitive features

### Low Priority:

7. **Session Timeout**
   - Auto-logout after inactivity
   - Reduces risk on shared devices

8. **Two-Factor Authentication (2FA)**
   - Optional SMS/authenticator app verification
   - Extra layer of security for user accounts

---

## 📋 Security Checklist Before Production

- [ ] All secrets moved to environment variables or secure storage
- [ ] google-services.json removed from version control
- [ ] Firestore security rules tested and deployed
- [ ] HTTPS enforced for all network connections
- [ ] Android backup disabled or using encrypted backup
- [ ] ProGuard/R8 enabled for release builds
- [ ] OAuth client ID configured in strings.xml
- [ ] Firebase App Check enabled
- [ ] Error messages don't leak sensitive information
- [ ] Logging disabled or sanitized in production

---

## 🐛 Reporting Security Issues

If you discover a security vulnerability, please:
1. **DO NOT** open a public issue
2. Email: security@kineticeco.app (or your contact email)
3. Include: detailed description, steps to reproduce, potential impact

---

## 📚 Resources

- [Firebase Security Best Practices](https://firebase.google.com/docs/rules/best-practices)
- [Android Security Best Practices](https://developer.android.com/privacy-and-security/security-best-practices)
- [OWASP Mobile Security](https://owasp.org/www-project-mobile-security/)
- [Web Security Checklist](https://owasp.org/www-project-web-security-testing-guide/)

---

**Last Updated:** January 15, 2026  
**Security Review By:** AI Security Audit
