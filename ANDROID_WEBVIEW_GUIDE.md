# WebView Android Wrapper - Implementation Guide

## 📱 Convert Your Android App to Display the Web App

This guide shows how to make your Android app display the web version, ensuring they look identical.

---

## Step 1: Update MainActivity.kt

Replace the entire `MainActivity.kt` content:

```kotlin
package Kinetic_Eco.Tracker

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import androidx.appcompat.app.AppCompatActivity
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    
    private lateinit var webView: WebView
    
    companion object {
        private const val LOCATION_PERMISSION_CODE = 100
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Request location permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_CODE
            )
        }
        
        setupWebView()
    }
    
    private fun setupWebView() {
        webView = WebView(this)
        
        webView.settings.apply {
            // Enable JavaScript
            javaScriptEnabled = true
            
            // Enable DOM storage (for localStorage)
            domStorageEnabled = true
            
            // Enable geolocation
            setGeolocationEnabled(true)
            
            // Allow file access
            allowFileAccess = true
            allowContentAccess = true
            
            // Enable zoom controls
            builtInZoomControls = false
            displayZoomControls = false
            
            // Enable viewport
            useWideViewPort = true
            loadWithOverviewMode = true
            
            // Cache settings
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            
            // Mixed content (if using http during dev)
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        
        // WebViewClient - handle page navigation
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                view.loadUrl(url)
                return true
            }
            
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Page loaded
            }
        }
        
        // WebChromeClient - handle permissions and dialogs
        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, true, false)
            }
            
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request.resources)
            }
        }
        
        // Load your web app URL
        // For production:
        webView.loadUrl("https://0114974661.web.app")
        
        // For development (make sure your PC and phone are on same WiFi):
        // webView.loadUrl("http://192.168.100.14:3000")
        
        setContentView(webView)
    }
    
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted, reload WebView
                setupWebView()
            }
        }
    }
}
```

---

## Step 2: Update AndroidManifest.xml

Add necessary permissions:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <!-- Permissions -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />

    <application
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.KineticEcoTracker"
        android:usesCleartextTraffic="true"
        tools:targetApi="31">
        
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize|keyboardHidden"
            android:hardwareAccelerated="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>

</manifest>
```

---

## Step 3: Update build.gradle (Module: app)

Ensure you have the right dependencies:

```gradle
android {
    compileSdk 34
    
    defaultConfig {
        applicationId "Kinetic_Eco.Tracker"
        minSdk 24
        targetSdk 34
        versionCode 1
        versionName "1.0"
    }
    
    buildTypes {
        release {
            minifyEnabled false
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }
    
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
    
    kotlinOptions {
        jvmTarget = '1.8'
    }
}

dependencies {
    implementation 'androidx.core:core-ktx:1.12.0'
    implementation 'androidx.appcompat:appcompat:1.6.1'
    implementation 'com.google.android.material:material:1.11.0'
}
```

---

## Step 4: Delete Unnecessary Files

You can now delete:
- All Fragment files (TrackerFragment, AnalyticsFragment, etc.)
- All layout XMLs except activity_main (if needed)
- All resource files related to the old native UI
- AllViewModel/Repository classes

Keep only:
- MainActivity.kt
- AndroidManifest.xml
- build.gradle files
- res/mipmap (app icon)

---

## Step 5: Build and Run

### For Development (Local Testing):
1. Make sure dev server is running: `npm run dev`
2. Get your PC's IP address (e.g., 192.168.100.14)
3. In MainActivity.kt, use: `webView.loadUrl("http://192.168.100.14:3000")`
4. Ensure phone and PC are on same WiFi
5. Build and run in Android Studio

### For Production:
1. Deploy your web app to Firebase Hosting
2. In MainActivity.kt, use: `webView.loadUrl("https://0114974661.web.app")`
3. Build APK or AAB for Play Store

---

## Benefits:

✅ **Identical UI** - Web and Android look exactly the same
✅ **Single Codebase** - Update once, works everywhere  
✅ **All Features** - GPS, animations, everything works
✅ **Easy Updates** - No app store approval for web changes
✅ **Modern UI** - Includes pulsating button animation
✅ **Smaller APK** - WebView is built into Android

---

## Testing Checklist:

- [ ] GPS permission granted
- [ ] Location tracking works
- [ ] Pulsating button animation visible
- [ ] Timer counts correctly
- [ ] Sessions save properly
- [ ] Back button works (goes back in web history)
- [ ] Internet connection required message (if offline)

---

## Optional Enhancements:

### Add Splash Screen:
Create a splash screen while web app loads

### Add Offline Detection:
```kotlin
if (!isNetworkAvailable()) {
    // Show offline message
    webView.loadData("<html><body><h1>No Internet</h1></body></html>", "text/html", "UTF-8")
}
```

### Add Loading Progress:
```kotlin
webChromeClient = object : WebChromeClient() {
    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        // Show progress bar
        progressBar.progress = newProgress
        if (newProgress == 100) {
            progressBar.visibility = View.GONE
        }
    }
}
```

---

## Troubleshooting:

**Issue:** Blank white screen  
**Fix:** Check `usesCleartextTraffic="true"` in manifest for HTTP

**Issue:** GPS not working  
**Fix:** Grant location permission in app settings

**Issue:** Can't load localhost  
**Fix:** Ensure phone and PC on same WiFi, use PC's IP address

**Issue:** Slow loading  
**Fix:** Deploy to Firebase Hosting for faster loading

---

## Result:

Your Android app will now look **exactly** like the web app with:
- ✅ Pulsating button animation
- ✅ Modern indigo/red color scheme
- ✅ Smooth animations
- ✅ All features working
- ✅ Easy to maintain



