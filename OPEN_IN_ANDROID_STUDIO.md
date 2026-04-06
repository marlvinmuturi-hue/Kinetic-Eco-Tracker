# How to Open This Project in Android Studio

## Step-by-Step Instructions

### Step 1: Launch Android Studio
1. Open **Android Studio** (download from https://developer.android.com/studio if needed)
2. If you see the welcome screen, click **"Open"**
3. If Android Studio is already open, go to **File → Open**

### Step 2: Select the Project Folder
1. Navigate to: `c:\Users\ADMIN\Downloads\kinetic-eco-tracker\android`
2. **Important**: Select the **`android`** folder (not the parent `kinetic-eco-tracker` folder)
3. Click **"OK"** or **"Open"**

### Step 3: Trust the Project (if prompted)
- If Android Studio asks to trust the Gradle project, click **"Trust Project"**

### Step 4: Wait for Gradle Sync
- Android Studio will automatically detect this is a Gradle project
- It will start **syncing Gradle files** (bottom status bar will show progress)
- **First time may take 5-10 minutes** (downloading dependencies)
- Wait for sync to complete (you'll see "Gradle sync finished" message)

### Step 5: Configure SDK (if needed)
If you see errors about missing SDK:

1. Go to **File → Project Structure** (or press `Ctrl+Alt+Shift+S`)
2. Click **"SDK Location"** on the left
3. Set **Android SDK location** to:
   - Windows: `C:\Users\YOUR_USERNAME\AppData\Local\Android\Sdk`
   - Or click "..." to browse to your SDK location
4. Click **"OK"**

### Step 6: Add Web Client ID
**IMPORTANT**: For Google Sign-In to work:

1. Go to Firebase Console: https://console.firebase.google.com/project/gen-lang-client-0114974661/settings/general
2. Scroll to **"Your apps"** → Click on your **Web app**
3. Find the **OAuth client ID** (looks like: `943799650262-xxxxxxxxxxxxx.apps.googleusercontent.com`)
4. In Android Studio, open: `app/src/main/res/values/strings.xml`
5. Find: `<string name="default_web_client_id">YOUR_WEB_CLIENT_ID_HERE</string>`
6. Replace `YOUR_WEB_CLIENT_ID_HERE` with your actual OAuth client ID
7. Save the file

### Step 7: Build the Project
1. Click **Build → Make Project** (or press `Ctrl+F9` / `Cmd+F9` on Mac)
2. Wait for build to complete
3. Check for any errors in the **Build** output window

### Step 8: Create App Icons (if missing)
If you see errors about missing launcher icons:

1. Right-click on `app/src/main/res` folder
2. Select **New → Image Asset**
3. Choose **Launcher Icons (Adaptive and Legacy)**
4. Select an image or use default
5. Click **Next** → **Finish**

### Step 9: Run the App
1. Connect an Android device via USB (with USB debugging enabled)
   - OR create an Android emulator: **Tools → Device Manager → Create Device**
2. Click the **green Run button** (play icon) in the toolbar
3. Select your device/emulator
4. Click **OK**
5. App should install and launch!

## Troubleshooting

### "SDK not found"
- Install Android SDK via Android Studio: **Tools → SDK Manager**
- Set SDK location in **File → Project Structure → SDK Location**

### "Gradle sync failed"
- Check internet connection
- **File → Invalidate Caches → Invalidate and Restart**
- Try: **File → Sync Project with Gradle Files**

### "google-services.json not found"
- Ensure file exists at: `android/app/google-services.json`
- **File → Sync Project with Gradle Files**

### "Package name mismatch"
- All should be: `Kinetic_Eco.Tracker`
- Check: `build.gradle.kts` (applicationId)
- Check: Package declaration in Kotlin files
- Check: `google-services.json` (package_name)

### Build errors
- Check **Build** output window for specific errors
- Most errors will show what's missing
- Common: Missing SDK, wrong package name, missing dependencies

## Project is Ready! ✅

Once opened and synced, you're ready to:
- ✅ Build and run the app
- ✅ Start implementing features
- ✅ Debug and test
- ✅ Deploy to devices

See `ANDROID_PROJECT_READY.md` for more details about the project structure.














