/**
 * Authentication Service
 * Handles Firebase Authentication for email/password and Google sign-in
 */

import { 
  signInWithEmailAndPassword, 
  createUserWithEmailAndPassword,
  signInWithPopup,
  signInWithRedirect,
  getRedirectResult,
  GoogleAuthProvider,
  sendPasswordResetEmail,
  signOut,
  onAuthStateChanged,
  User,
  AuthError
} from 'firebase/auth';
import { auth } from './firebaseConfig';
import { doc, setDoc, getDoc } from 'firebase/firestore';
import { db } from './firebaseConfig';
import { UserProfile } from '../types';

const googleProvider = new GoogleAuthProvider();
googleProvider.setCustomParameters({ prompt: 'select_account' });

/**
 * Sign in with email and password
 */
export const signInWithEmail = async (email: string, password: string): Promise<{ user: User | null; error?: string }> => {
  try {
    const normalizedEmail = email.trim().toLowerCase();
    const userCredential = await signInWithEmailAndPassword(auth, normalizedEmail, password);
    return { user: userCredential.user };
  } catch (error: any) {
    const authError = error as AuthError;
    let errorMessage = 'Failed to sign in. Please try again.';
    
    switch (authError.code) {
      case 'auth/user-not-found':
        errorMessage = 'No account found with this email.';
        break;
      case 'auth/wrong-password':
        errorMessage = 'Incorrect password.';
        break;
      case 'auth/invalid-credential':
        // Firebase returns this for both wrong password and non-existent user (for security)
        errorMessage = 'Invalid email or password.';
        break;
      case 'auth/invalid-email':
        errorMessage = 'Invalid email address.';
        break;
      case 'auth/user-disabled':
        errorMessage = 'This account has been disabled.';
        break;
      case 'auth/too-many-requests':
        errorMessage = 'Too many failed attempts. Please try again later.';
        break;
      default:
        errorMessage = authError.message || errorMessage;
    }
    
    return { user: null, error: errorMessage };
  }
};

/**
 * Create new account with email and password
 */
export const signUpWithEmail = async (email: string, password: string): Promise<{ user: User | null; error?: string }> => {
  try {
    const normalizedEmail = email.trim().toLowerCase();
    const userCredential = await createUserWithEmailAndPassword(auth, normalizedEmail, password);
    
    // Create user profile in Firestore
    await createUserProfile(userCredential.user);
    
    return { user: userCredential.user };
  } catch (error: any) {
    const authError = error as AuthError;
    let errorMessage = 'Failed to create account. Please try again.';
    
    switch (authError.code) {
      case 'auth/email-already-in-use':
        errorMessage = 'An account with this email already exists. Please check your password or use "Forgot password" to reset it.';
        break;
      case 'auth/invalid-email':
        errorMessage = 'Invalid email address.';
        break;
      case 'auth/weak-password':
        errorMessage = 'Password should be at least 6 characters.';
        break;
      default:
        errorMessage = authError.message || errorMessage;
    }
    
    return { user: null, error: errorMessage };
  }
};

/**
 * Sign in with Google
 */
export const signInWithGoogle = async (): Promise<{ user: User | null; error?: string }> => {
  try {
    // Attempt popup first
    const result = await signInWithPopup(auth, googleProvider);
    const user = result.user;
    
    // Check if user profile exists, create if not
    const profileExists = await checkUserProfileExists(user.uid);
    if (!profileExists) {
      await createUserProfile(user);
    }
    
    return { user };
  } catch (error: any) {
    const authError = error as AuthError;
    console.error("Google sign-in error:", authError.code, authError.message);

    if (authError.code === 'auth/popup-blocked') {
      // Fallback to redirect if popup is blocked
      try {
        await signInWithRedirect(auth, googleProvider);
        // This will redirect the page, so we won't return anything here
        return { user: null }; 
      } catch (redirectError: any) {
        return { user: null, error: 'Popup blocked and redirect failed. Please allow popups for this site.' };
      }
    }
    
    let errorMessage = 'Failed to sign in with Google. Please try again.';
    
    if (authError.code === 'auth/popup-closed-by-user') {
      errorMessage = 'Sign-in popup was closed. Please try again.';
    } else if (authError.code === 'auth/cancelled-popup-request') {
      errorMessage = 'Sign-in was cancelled. Please try again.';
    } else if (authError.code === 'auth/operation-not-allowed') {
      errorMessage = 'Google sign-in is not enabled in Firebase Console.';
    } else {
      errorMessage = authError.message || errorMessage;
    }
    
    return { user: null, error: errorMessage };
  }
};

/**
 * Handle redirect result (call this on app mount)
 */
export const handleRedirectResult = async (): Promise<User | null> => {
  try {
    const result = await getRedirectResult(auth);
    if (result) {
      const user = result.user;
      console.log("Found redirect user:", user.email);
      const profileExists = await checkUserProfileExists(user.uid);
      if (!profileExists) {
        console.log("Creating new profile for redirect user...");
        await createUserProfile(user);
      }
      return user;
    }
    return null;
  } catch (error: any) {
    console.error("Error handling redirect result:", error.code, error.message);
    // If there's an error with the redirect (e.g. cross-origin issues), 
    // it might be why the user is getting kicked back.
    return null;
  }
};

/**
 * Send password reset email
 */
export const resetPassword = async (email: string): Promise<{ success: boolean; error?: string }> => {
  try {
    const normalizedEmail = email.trim().toLowerCase();
    
    // For security, we don't check if the user exists before sending.
    // Firebase sendPasswordResetEmail will succeed even if the user doesn't exist
    // to prevent email enumeration attacks, as long as the email format is valid.
    await sendPasswordResetEmail(auth, normalizedEmail);
    
    return { success: true };
  } catch (error: any) {
    console.error('Firebase sendPasswordResetEmail error:', error);
    const authError = error as AuthError;
    
    // Still handle obviously invalid emails
    if (authError.code === 'auth/invalid-email') {
      return { success: false, error: 'Invalid email address.' };
    }
    
    // For any other error (including network errors), return a generic success message
    // to prevent enumeration, or a generic failure if it's clearly a system failure.
    if (authError.code === 'auth/too-many-requests') {
      return { success: false, error: 'Too many requests. Please try again later.' };
    }
    
    // Fallback to success to prevent enumeration, but log the error
    return { success: true };
  }
};

/**
 * Sign out current user
 */
export const signOutUser = async (): Promise<void> => {
  await signOut(auth);
};

/**
 * Get current authenticated user
 */
export const getCurrentUser = (): User | null => {
  return auth.currentUser;
};

/**
 * Listen to authentication state changes
 */
export const onAuthStateChange = (callback: (user: User | null) => void) => {
  return onAuthStateChanged(auth, callback);
};

/**
 * Create user profile in Firestore
 */
const createUserProfile = async (user: User): Promise<void> => {
  try {
    const userRef = doc(db, 'users', user.uid);
    const userDoc = await getDoc(userRef);
    
    if (!userDoc.exists()) {
      // Create new profile
      const profile: Partial<UserProfile> = {
        email: user.email || '',
        createdAt: new Date().toISOString(),
        lastLogin: new Date().toISOString(),
        schemaVersion: 1,
        isLegacy: false,
        sessions: [],
      };
      
      await setDoc(userRef, {
        ...profile,
        totalSessions: 0,
        totalDistance: 0,
        totalDuration: 0,
        totalCO2Saved: 0,
        updatedAt: new Date().toISOString(),
      });
    } else {
      // Update last login
      await setDoc(userRef, {
        lastLogin: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      }, { merge: true });
    }
  } catch (error) {
    console.error('Error creating/updating user profile:', error);
    // Don't throw - allow user to continue even if profile creation fails
  }
};

/**
 * Check if user profile exists in Firestore
 */
const checkUserProfileExists = async (uid: string): Promise<boolean> => {
  try {
    const userRef = doc(db, 'users', uid);
    const userDoc = await getDoc(userRef);
    return userDoc.exists();
  } catch (error) {
    console.error('Error checking user profile:', error);
    return false;
  }
};

/**
 * Get user profile from Firestore
 */
export const getUserProfile = async (uid: string): Promise<UserProfile | null> => {
  try {
    const userRef = doc(db, 'users', uid);
    const userDoc = await getDoc(userRef);
    
    if (userDoc.exists()) {
      const data = userDoc.data();
      return {
        email: data.email || '',
        passwordHash: '', // Not stored in Firestore for security
        createdAt: data.createdAt || new Date().toISOString(),
        lastLogin: data.lastLogin || new Date().toISOString(),
        sessions: data.sessions || [],
        schemaVersion: data.schemaVersion || 1,
        isLegacy: data.isLegacy || false,
      };
    }
    return null;
  } catch (error) {
    console.error('Error getting user profile:', error);
    return null;
  }
};

