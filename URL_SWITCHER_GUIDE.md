# 🔄 Quick URL Switcher Guide

## Current URL Configuration

Open: `android/app/src/main/java/Kinetic_Eco/Tracker/MainActivity.kt`

Go to **lines 110-115**

---

## 📍 Development Mode (Testing with localhost)

**Use this when:** Testing new features, seeing real-time updates

```kotlin
// PRODUCTION - Comment out this line:
// webView.loadUrl("https://0114974661.web.app")

// DEVELOPMENT - Uncomment and use this:
webView.loadUrl("http://192.168.100.14:3000")
```

**Steps:**
1. Run `npm run dev` on your PC
2. Note the "Network" URL (e.g., `http://192.168.100.14:3000`)
3. Update the IP address in MainActivity.kt
4. Build and run on Android Studio
5. Make sure phone and PC are on **same WiFi**

**Benefits:**
- ✅ Instant updates (hot reload)
- ✅ See changes immediately
- ✅ Console logs visible
- ✅ Easy debugging

---

## 🌐 Production Mode (Using deployed app)

**Use this when:** Final testing, distributing APK, no dev server needed

```kotlin
// PRODUCTION - Use this line:
webView.loadUrl("https://0114974661.web.app")

// DEVELOPMENT - Comment out this:
// webView.loadUrl("http://192.168.100.14:3000")
```

**Steps:**
1. Make sure code is in production mode
2. Build and run on Android Studio
3. Works anywhere, no PC needed

**Benefits:**
- ✅ No dev server required
- ✅ Fast loading (CDN)
- ✅ Works offline (if cached)
- ✅ Production-ready

---

## 🎯 Quick Reference

| Scenario | URL to Use | Dev Server Needed? | WiFi Needed? |
|----------|------------|-------------------|--------------|
| **Local Testing** | `http://192.168.100.14:3000` | ✅ Yes | ✅ Same network |
| **APK Distribution** | `https://0114974661.web.app` | ❌ No | ❌ Any internet |
| **Final Testing** | `https://0114974661.web.app` | ❌ No | ❌ Any internet |
| **Development** | `http://192.168.100.14:3000` | ✅ Yes | ✅ Same network |

---

## 💡 Pro Tips

### Get Your PC's IP Address:

**Windows:**
```cmd
ipconfig
```
Look for "IPv4 Address" under your WiFi adapter (e.g., 192.168.100.14)

**Mac/Linux:**
```bash
ifconfig
```
Look for "inet" address (e.g., 192.168.100.14)

### From Dev Server:
When you run `npm run dev`, Vite shows:
```
➜  Local:   http://localhost:3000/
➜  Network: http://192.168.100.14:3000/
```
Use the **Network** URL!

---

## 🚀 Current Status

**Your app is configured for:** 
✅ **PRODUCTION** (`https://0114974661.web.app`)

To switch to development:
1. Open `MainActivity.kt`
2. Comment line 110: `// webView.loadUrl("https://0114974661.web.app")`
3. Uncomment line 115: `webView.loadUrl("http://YOUR_IP:3000")`
4. Replace `YOUR_IP` with your actual IP
5. Rebuild app

---

## ✅ Verification

**How to know which mode you're in:**

### Development Mode:
- Dev server must be running
- App won't load without PC running
- See console logs in Chrome DevTools
- Changes reflect immediately

### Production Mode:
- Works without dev server
- Loads from Firebase
- Independent of PC
- Ready for distribution

---

## 🎨 Result

**Both modes show:**
- ✅ Identical beautiful UI
- ✅ Pulsating button animation
- ✅ Modern indigo/red colors
- ✅ All features working
- ✅ Smooth animations

The only difference is **where** the app is loaded from!



