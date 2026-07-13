import { doc, setDoc, Timestamp } from 'firebase/firestore';
import { getAuth } from 'firebase/auth';
import { SessionStats } from '../types';
import { db } from './firebaseConfig';

/** Session start time from param, or the earliest segment start. Falls back to now ONLY for a genuinely
 *  live save (no start time, no segments). Callers re-syncing historical data must pass a real start time
 *  (or a parseable date) so old sessions aren't misdated to today. */
function getSessionTimestampMs(stats: SessionStats, sessionStartTimeMs?: number): number {
  if (sessionStartTimeMs && sessionStartTimeMs > 0) return sessionStartTimeMs;
  const starts = (stats.segments || []).map((s) => s.startTime).filter((t) => t > 0);
  if (starts.length > 0) return Math.min(...starts);
  return Date.now();
}

/** yyyy-MM-dd (local) for a ms timestamp — mirrors the Android `sessionDateKey` field. */
function dateKeyFromMs(ms: number): string {
  const d = new Date(ms);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

/** A session with no distance and no duration is spurious — never write it to the cloud. */
function isEmptySession(stats: SessionStats): boolean {
  return (stats.totalDistance || 0) <= 0 && (stats.totalDuration || 0) <= 0;
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

    if (isEmptySession(stats)) {
      console.warn('Skipping empty session (0 distance & 0 duration) — not saving to Firestore');
      return;
    }

    const timestampMs = getSessionTimestampMs(stats, sessionStartTimeMs);
    const sessionId = `session-${timestampMs}-${Math.random().toString(16).slice(2)}`;
    const sessionRef = doc(db, 'users', user.uid, 'sessions', sessionId);

    // `createdAt` mirrors the real session time (NOT Timestamp.now()) and `sessionDateKey` gives the doc a
    // stable, correct date. The Android app + cleanup tooling key off these; stamping "now" here previously
    // misdated re-synced sessions to today and inflated the dashboard's weekly totals.
    const firestoreSession = {
      timestamp: timestampMs,
      sessionDateKey: dateKeyFromMs(timestampMs),
      totalDistance: stats.totalDistance || 0,
      totalDuration: stats.totalDuration || 0,
      caloriesBurned: stats.caloriesBurned || 0,
      co2Emissions: stats.co2Emissions || 0,
      co2Conserved: stats.co2Conserved || 0,
      breakdown: stats.breakdown || {},
      createdAt: Timestamp.fromMillis(timestampMs),
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

    let skipped = 0;
    for (const session of sessions) {
      if (isEmptySession(session.stats)) { skipped++; continue; }

      // Determine the real session time WITHOUT ever falling back to "now": use the parseable date, else the
      // earliest segment start. If neither is known we skip — dating historical data to today is exactly the
      // corruption this sync caused before.
      const parsedDateMs = new Date(session.date).getTime();
      const segStarts = (session.stats.segments || []).map((s) => s.startTime).filter((t) => t > 0);
      const timestampMs = Number.isFinite(parsedDateMs)
        ? parsedDateMs
        : (segStarts.length ? Math.min(...segStarts) : NaN);
      if (!Number.isFinite(timestampMs)) { skipped++; continue; }

      // Use session id when available for idempotent sync (re-sync overwrites instead of duplicating)
      const sessionId = session.id
        ? `synced-${session.id}`
        : `synced-${timestampMs}-${Math.random().toString(16).slice(2)}`;
      const sessionRef = doc(db, 'users', user.uid, 'sessions', sessionId);

      const firestoreSession = {
        timestamp: timestampMs,
        sessionDateKey: dateKeyFromMs(timestampMs),
        totalDistance: session.stats.totalDistance || 0,
        totalDuration: session.stats.totalDuration || 0,
        caloriesBurned: session.stats.caloriesBurned || 0,
        co2Emissions: session.stats.co2Emissions || 0,
        co2Conserved: session.stats.co2Conserved || 0,
        breakdown: session.stats.breakdown || {},
        createdAt: Timestamp.fromMillis(timestampMs),
        userId: user.uid,
        userEmail: user.email || 'unknown',
        syncedFromLocalStorage: true
      };

      await setDoc(sessionRef, firestoreSession);
    }
    if (skipped > 0) console.log(`   (skipped ${skipped} empty/undatable sessions)`);

    console.log(`✅ Successfully synced ${sessions.length} sessions to Firestore`);
  } catch (error) {
    console.error('❌ Error batch saving sessions to Firestore:', error);
    throw error;
  }
};
