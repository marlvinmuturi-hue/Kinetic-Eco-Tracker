# Authentication Setup Guide

This guide will help you set up Google Sign-In and Email/Password authentication with password reset functionality.

## Firebase Console Setup

### 1. Enable Authentication Providers

1. Go to [Firebase Console](https://console.firebase.google.com/)
2. Select your project: `gen-lang-client-0114974661`
3. Navigate to **Authentication** > **Sign-in method**
4. Enable the following providers:

#### Email/Password Authentication
- Click on **Email/Password**
- Toggle **Enable** to ON
- Click **Save**

#### Google Sign-In
- Click on **Google**
- Toggle **Enable** to ON
- Enter your **Project support email** (your email address)
- Click **Save**

### 2. Configure Authorized Domains

1. In **Authentication** > **Settings** > **Authorized domains**
2. Ensure these domains are listed:
   - `gen-lang-client-0114974661.web.app`
   - `gen-lang-client-0114974661.firebaseapp.com`
   - `localhost` (for development)

### 3. Configure Email Templates (Password Reset)

1. Go to **Authentication** > **Templates**
2. Click on **Password reset**
3. Customize the email template if desired
4. The default template will work fine

## Environment Variables

Make sure your `.env` file includes Firebase configuration:

```env
VITE_FIREBASE_API_KEY=your-api-key
VITE_FIREBASE_AUTH_DOMAIN=gen-lang-client-0114974661.firebaseapp.com
VITE_FIREBASE_PROJECT_ID=gen-lang-client-0114974661
VITE_FIREBASE_STORAGE_BUCKET=gen-lang-client-0114974661.appspot.com
VITE_FIREBASE_MESSAGING_SENDER_ID=your-sender-id
VITE_FIREBASE_APP_ID=your-app-id
```

To find these values:
1. Go to Firebase Console > Project Settings > General
2. Scroll down to "Your apps"
3. Click on the web app icon (`</>`)
4. Copy the configuration values

## Features Implemented

### ✅ Email/Password Sign-In
- Users can sign in with email and password
- New users are automatically created if they don't exist
- Passwords are securely stored by Firebase (not in localStorage)

### ✅ Google Sign-In
- One-click sign-in with Google account
- Automatically creates user profile in Firestore
- No password required

### ✅ Password Reset
- "Forgot password?" link on login page
- Sends password reset email to user's registered email
- User receives email with reset link
- Clicking the link allows user to set a new password

### ✅ Backward Compatibility
- Still supports localStorage-based authentication for legacy users
- Seamless migration from old system to Firebase Auth

## How It Works

### Sign-In Flow

1. **Email/Password Sign-In:**
   - User enters email and password
   - App tries Firebase Auth first
   - If user doesn't exist, creates new account
   - Falls back to localStorage for legacy users

2. **Google Sign-In:**
   - User clicks "Continue with Google"
   - Google popup appears
   - User selects Google account
   - App creates/updates user profile in Firestore

3. **Password Reset:**
   - User clicks "Forgot password?"
   - Enters email address
   - Firebase sends password reset email
   - User clicks link in email
   - User sets new password
   - User can now sign in with new password

### User Profile Storage

- **Firebase Auth:** Handles authentication (email, password, Google)
- **Firestore:** Stores user profile data (sessions, stats, preferences)
- **localStorage:** Fallback for legacy users (backward compatibility)

## Testing

### Test Email/Password Sign-In
1. Enter a new email and password
2. Click "Sign in"
3. Account should be created automatically
4. You should be logged in

### Test Google Sign-In
1. Click "Continue with Google"
2. Select your Google account
3. Grant permissions
4. You should be logged in

### Test Password Reset
1. Click "Forgot password?"
2. Enter your registered email
3. Check your email inbox
4. Click the reset link
5. Set a new password
6. Sign in with new password

## Troubleshooting

### Google Sign-In Not Working
- Ensure Google provider is enabled in Firebase Console
- Check that authorized domains include your domain
- Verify Firebase config in `.env` file

### Password Reset Email Not Received
- Check spam/junk folder
- Verify email address is correct
- Ensure Email/Password provider is enabled
- Check Firebase Console > Authentication > Users to see if user exists

### "User not found" Error
- User may not exist in Firebase Auth
- Try creating a new account first
- Check Firebase Console > Authentication > Users

## Security Notes

- Passwords are never stored in localStorage (only Firebase Auth)
- Google OAuth tokens are managed by Firebase
- Password reset links expire after 1 hour (Firebase default)
- All authentication is handled securely by Firebase















