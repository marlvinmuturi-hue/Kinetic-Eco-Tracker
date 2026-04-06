# Kinetic Eco Tracker - Android Native App

This is the Android native app for Kinetic Eco Tracker, built with Kotlin and Firebase.

## Quick Start

### 1. Open in Android Studio
- Open Android Studio
- File → Open → Select this `android` folder
- Wait for Gradle sync to complete

### 2. Configure Firebase
- `google-services.json` is already in place
- Add Web Client ID in `app/src/main/res/values/strings.xml`
  - Replace `YOUR_WEB_CLIENT_ID_HERE` with your Firebase Web Client ID

### 3. Build and Run
- Connect an Android device or start an emulator
- Click Run (green play button)
- App should launch

## Project Structure

- **Package Name**: `Kinetic_Eco.Tracker`
- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 34 (Android 14)

## Features

- Firebase Authentication (Email/Password, Google Sign-In)
- Cloud Firestore integration
- GPS location tracking
- Background service for continuous tracking
- Material Design 3 UI
- Dark theme matching web app

## Dependencies

All dependencies are managed via Gradle. See `app/build.gradle.kts` for details.

## More Information

See `ANDROID_PROJECT_READY.md` for detailed setup instructions.














