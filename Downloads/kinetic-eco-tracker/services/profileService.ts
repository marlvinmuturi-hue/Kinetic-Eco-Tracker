import { SessionStats, StoredSession, UserProfile } from '../types';

const STORAGE_KEY = 'kinetic_profiles';
const ACTIVE_USER_KEY = 'kinetic_active_user';

type ProfileRegistry = Record<string, UserProfile>;

const generateId = () => {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID();
  }
  return `session-${Date.now()}-${Math.random().toString(16).slice(2)}`;
};

const readProfiles = (): ProfileRegistry => {
  if (typeof window === 'undefined') return {};
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? JSON.parse(raw) : {};
  } catch {
    return {};
  }
};

const persistProfiles = (profiles: ProfileRegistry) => {
  if (typeof window === 'undefined') return;
  localStorage.setItem(STORAGE_KEY, JSON.stringify(profiles));
};

const hashPassword = (password: string) => btoa(password);

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

export const authenticateOrCreateProfile = (email: string, password: string): { profile: UserProfile | null; error?: string; created?: boolean } => {
  const normalizedEmail = email.trim().toLowerCase();
  if (!normalizedEmail || !password) {
    return { profile: null, error: 'Email and password are required.' };
  }

  const profiles = readProfiles();
  const existing = profiles[normalizedEmail];
  const passwordHash = hashPassword(password);

  if (existing) {
    if (existing.passwordHash !== passwordHash) {
      return { profile: null, error: 'Incorrect password.' };
    }
    existing.lastLogin = new Date().toISOString();
    profiles[normalizedEmail] = existing;
    persistProfiles(profiles);
    setActiveUserEmail(normalizedEmail);
    return { profile: existing };
  }

  const newProfile: UserProfile = {
    email: normalizedEmail,
    passwordHash,
    createdAt: new Date().toISOString(),
    lastLogin: new Date().toISOString(),
    sessions: [],
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

export const saveSessionForUser = (email: string, stats: SessionStats): UserProfile | null => {
  const profiles = readProfiles();
  const profile = profiles[email];
  if (!profile) return null;

  const session: StoredSession = {
    id: generateId(),
    date: new Date().toISOString().split('T')[0],
    stats,
  };

  profile.sessions = [session, ...profile.sessions];
  profiles[email] = profile;
  persistProfiles(profiles);
  return profile;
};

export const logoutUser = () => {
  setActiveUserEmail(null);
};

