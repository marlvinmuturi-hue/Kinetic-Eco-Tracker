import { doc, setDoc, Timestamp } from 'firebase/firestore';
import { getAuth } from 'firebase/auth';
import { SessionStats } from '../types';
import { db } from './firebaseConfig';

/** Use session start time from param, segments, or fallback to now. */
function getSessionTimestampMs(stats: SessionStats, sessionStartTimeMs?: number): number {
  if (sessionStartTimeMs && sessionStartTimeMs > 0) return sessionStartTimeMs;
  const segments = stats.segments;
  if (segments && segments.length > 0) {
    return Math.min(...segments.map((s) => s.startTime));
  }
  return Date.now();
}

/**
 * Save session to Firestore for cloud analysis
 * Structure: /users/{userId}/sessions/{sessionId}
 */
export const saveSessionToFirestore = async (stats: SessionStats, sessionStartTimeMs?: number): Promise<void> => {
  try {
    const auth = getAuth();
    const user = auth.currentUser;
    
    if (!user) {
      console.warn('No authenticated user, skipping Firestore session save');
      return;
    }

    const timestampMs = getSessionTimestampMs(stats, sessionStartTimeMs);
    const sessionId = `session-${timestampMs}-${Math.random().toString(16).slice(2)}`;
    const sessionRef = doc(db, 'users', user.uid, 'sessions', sessionId);

    // Convert session data to Firestore format (timestamp = when session started)
    const firestoreSession = {
      timestamp: timestampMs,
      totalDistance: stats.totalDistance || 0,
      totalDuration: stats.totalDuration || 0,
      caloriesBurned: stats.caloriesBurned || 0,
      co2Emissions: stats.co2Emissions || 0,
      co2Conserved: stats.co2Conserved || 0,
      breakdown: stats.breakdown || {},
      createdAt: Timestamp.now(),
      userId: user.uid,
      userEmail: user.email || 'unknown'
    };

    await setDoc(sessionRef, firestoreSession);
    console.log('✅ Session saved to Firestore:', sessionId);
  } catch (error) {
    console.error('❌ Error saving session to Firestore:', error);
    // Don't throw - we don't want to break the app if Firestore fails
  }
};

/**
 * Batch save multiple sessions to Firestore (for syncing localStorage sessions)
 * @throws if not signed in or if Firestore write fails (so UI can show real success/failure)
 */
export const batchSaveSessionsToFirestore = async (sessions: Array<{ id?: string; date: string; stats: SessionStats }>): Promise<void> => {
  const auth = getAuth();
  const user = auth.currentUser;

  if (!user) {
    console.warn('No authenticated user, skipping batch Firestore save');
    throw new Error('Sign in required to sync sessions to the cloud.');
  }

  try {
    console.log(`📤 Syncing ${sessions.length} sessions to Firestore...`);

    for (const session of sessions) {
      // Use session id when available for idempotent sync (re-sync overwrites instead of duplicating)
      const sessionId = session.id
        ? `synced-${session.id}`
        : `synced-${new Date(session.date).getTime()}-${Math.random().toString(16).slice(2)}`;
      const sessionRef = doc(db, 'users', user.uid, 'sessions', sessionId);

      const firestoreSession = {
        timestamp: new Date(session.date).getTime(),
        totalDistance: session.stats.totalDistance || 0,
        totalDuration: session.stats.totalDuration || 0,
        caloriesBurned: session.stats.caloriesBurned || 0,
        co2Emissions: session.stats.co2Emissions || 0,
        co2Conserved: session.stats.co2Conserved || 0,
        breakdown: session.stats.breakdown || {},
        createdAt: Timestamp.now(),
        userId: user.uid,
        userEmail: user.email || 'unknown',
        syncedFromLocalStorage: true
      };

      await setDoc(sessionRef, firestoreSession);
    }

    console.log(`✅ Successfully synced ${sessions.length} sessions to Firestore`);
  } catch (error) {
    console.error('❌ Error batch saving sessions to Firestore:', error);
    throw error;
  }
};
