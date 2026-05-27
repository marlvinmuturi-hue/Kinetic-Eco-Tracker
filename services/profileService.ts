import { SessionStats, StoredSession, UserProfile, UserPhysicalProfile } from '../types';

/** Get session date (YYYY-MM-DD) from stats: use sessionStartTimeMs, segments, or fallback to now. */
function getSessionDateFromStats(stats: SessionStats, sessionStartTimeMs?: number): string {
  if (sessionStartTimeMs && sessionStartTimeMs > 0) {
    return new Date(sessionStartTimeMs).toISOString().split('T')[0];
  }
  const segments = stats.segments;
  if (segments && segments.length > 0) {
    const startMs = Math.min(...segments.map((s) => s.startTime));
    return new Date(startMs).toISOString().split('T')[0];
  }
  return new Date().toISOString().split('T')[0];
}
import { saveSessionToFirestore } from './firestoreSessionService';

const CURRENT_SCHEMA_VERSION = 1;

const STORAGE_KEY = 'kinetic_profiles';
const ACTIVE_USER_KEY = 'kinetic_active_user';

type ProfileRegistry = Record<string, UserProfile>;

const generateId = () => {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID();
  }
  return `session-${Date.now()}-${Math.random().toString(16).slice(2)}`;
};

const migrateStoredSession = (session: StoredSession): StoredSession => {
  // Ensure every stored session has a schemaVersion for future migrations
  if (!session.schemaVersion) {
    return { ...session, schemaVersion: CURRENT_SCHEMA_VERSION };
  }
  return session;
};

const migrateUserProfile = (profile: UserProfile): UserProfile => {
  // Treat profiles without a schemaVersion as legacy users
  if (!profile.schemaVersion) {
    return {
      ...profile,
      schemaVersion: CURRENT_SCHEMA_VERSION,
      isLegacy: true,
      sessions: (profile.sessions || []).map(migrateStoredSession),
    };
  }
  return {
    ...profile,
    sessions: (profile.sessions || []).map(migrateStoredSession),
  };
};

const readProfiles = (): ProfileRegistry => {
  if (typeof window === 'undefined') return {};
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    const parsed: ProfileRegistry = raw ? JSON.parse(raw) : {};
    // Migrate any legacy records on read so we can evolve safely
    const migrated: ProfileRegistry = {};
    for (const email of Object.keys(parsed)) {
      migrated[email] = migrateUserProfile(parsed[email]);
    }
    return migrated;
  } catch {
    return {};
  }
};

const persistProfiles = (profiles: ProfileRegistry) => {
  if (typeof window === 'undefined') return;
  localStorage.setItem(STORAGE_KEY, JSON.stringify(profiles));
};


export const getActiveUserEmail = (): string | null => {
  if (typeof window === 'undefined') return null;
  return localStorage.getItem(ACTIVE_USER_KEY);
};

export const setActiveUserEmail = (email: string | null) => {
  if (typeof window === 'undefined') return;
  if (email) {
    localStorage.setItem(ACTIVE_USER_KEY, email);
  } else {
    localStorage.removeItem(ACTIVE_USER_KEY);
  }
};

/**
 * DEPRECATED: This function is kept for backward compatibility with legacy profiles only.
 * New authentication should use Firebase Auth directly (see authService.ts).
 * Passwords are NO LONGER stored locally for security reasons.
 */
export const authenticateOrCreateProfile = (email: string, password: string): { profile: UserProfile | null; error?: string; created?: boolean } => {
  const normalizedEmail = email.trim().toLowerCase();
  if (!normalizedEmail || !password) {
    return { profile: null, error: 'Email and password are required.' };
  }

  const profiles = readProfiles();
  const existing = profiles[normalizedEmail];

  // Migrate legacy profiles: clear any stored btoa hash and force Firebase Auth.
  // btoa is reversible Base64, not a real hash — accepting it as proof of identity is insecure.
  if (existing) {
    if (existing.passwordHash && existing.passwordHash !== 'FIREBASE_AUTH' && existing.passwordHash !== 'SOCIAL_LOGIN_ONLY') {
      existing.passwordHash = 'FIREBASE_AUTH';
      profiles[normalizedEmail] = existing;
      persistProfiles(profiles);
    }
    return { profile: null, error: 'Please sign in using Firebase Authentication. If you have forgotten your password, use the reset password option.' };
  }

  // Create new profile without password - Firebase Auth handles authentication
  const newProfile: UserProfile = {
    email: normalizedEmail,
    passwordHash: 'FIREBASE_AUTH', // Marker indicating Firebase-managed auth
    createdAt: new Date().toISOString(),
    lastLogin: new Date().toISOString(),
    sessions: [],
    schemaVersion: CURRENT_SCHEMA_VERSION,
    isLegacy: false,
  };

  profiles[normalizedEmail] = newProfile;
  persistProfiles(profiles);
  setActiveUserEmail(normalizedEmail);
  return { profile: newProfile, created: true };
};

export const loadProfile = (email: string): UserProfile | null => {
  const profiles = readProfiles();
  return profiles[email] ?? null;
};

export const saveSessionForUser = (
  email: string,
  stats: SessionStats,
  sessionStartTimeMs?: number
): UserProfile | null => {
  const profiles = readProfiles();
  const profile = profiles[email];
  if (!profile) return null;

  const session: StoredSession = {
    id: generateId(),
    date: getSessionDateFromStats(stats, sessionStartTimeMs),
    stats,
    schemaVersion: CURRENT_SCHEMA_VERSION,
  };

  profile.sessions = [session, ...profile.sessions];
  profiles[email] = profile;
  persistProfiles(profiles);
  
  // ALSO save to Firestore for cloud analysis (use same date/timestamp)
  saveSessionToFirestore(stats, sessionStartTimeMs).catch(err => {
    console.error('Failed to sync session to Firestore:', err);
  });
  
  return profile;
};

export const logoutUser = () => {
  setActiveUserEmail(null);
};

export const syncSocialProfile = (email: string): UserProfile => {
  const normalizedEmail = email.trim().toLowerCase();
  const profiles = readProfiles();
  const existing = profiles[normalizedEmail];

  if (existing) {
    existing.lastLogin = new Date().toISOString();
    profiles[normalizedEmail] = existing;
    persistProfiles(profiles);
    setActiveUserEmail(normalizedEmail);
    return existing;
  }

  const newProfile: UserProfile = {
    email: normalizedEmail,
    passwordHash: 'SOCIAL_LOGIN_ONLY', // Special marker
    createdAt: new Date().toISOString(),
    lastLogin: new Date().toISOString(),
    sessions: [],
    schemaVersion: CURRENT_SCHEMA_VERSION,
    isLegacy: false,
  };

  profiles[normalizedEmail] = newProfile;
  persistProfiles(profiles);
  setActiveUserEmail(normalizedEmail);
  return newProfile;
};

export const updatePhysicalProfile = (email: string, physicalProfile: UserPhysicalProfile): UserProfile | null => {
  const normalizedEmail = email.trim().toLowerCase();
  const profiles = readProfiles();
  const profile = profiles[normalizedEmail];  if (!profile) {
    return null;
  }  profile.physicalProfile = physicalProfile;
  profiles[normalizedEmail] = profile;
  persistProfiles(profiles);  return profile;
};