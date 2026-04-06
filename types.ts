export enum ActivityType {
  IDLE = 'IDLE',
  WALKING = 'WALKING',
  RUNNING = 'RUNNING',
  CYCLING = 'CYCLING',
  DRIVING = 'DRIVING',
  ELECTRIC_VEHICLE = 'ELECTRIC_VEHICLE',
  FLYING = 'FLYING'
}

export interface ActivitySegment {
  type: ActivityType;
  startTime: number;
  endTime: number;
  distance: number; // in meters
  avgSpeed: number; // in m/s
}

export interface SessionStats {
  totalDuration: number; // seconds
  totalDistance: number; // meters
  caloriesBurned: number; // kcal
  co2Emissions: number; // kg (positive value = emissions)
  co2Conserved: number; // kg (positive value = conservation/savings)
  segments: ActivitySegment[];
  breakdown: Record<ActivityType, { time: number; distance: number; }>;
}

export interface GeoPosition {
  latitude: number;
  longitude: number;
  altitude: number | null; // meters
  speed: number | null; // m/s
  timestamp: number;
  accuracy: number;
}

export interface StoredSession {
  id: string;
  date: string; // ISO date e.g. 2025-01-01
  stats: SessionStats;
  /**
   * Optional schema version to support future changes without
   * breaking legacy data. Undefined implies the first version.
   */
  schemaVersion?: number;
}

export interface UserPhysicalProfile {
  weight: number;        // kg
  height: number;        // cm
  age: number;           // years
  gender: 'male' | 'female' | 'other';
}

export interface UserProfile {
  email: string;
  passwordHash: string;
  createdAt: string;
  lastLogin: string;
  sessions: StoredSession[];
  /**
   * Optional schema version for forward compatibility.
   * Legacy users may not have this set in existing storage.
   */
  schemaVersion?: number;
  /**
   * Marker for users created before certain breaking changes,
   * so we can keep legacy behavior for them if needed.
   */
  isLegacy?: boolean;
  /**
   * Physical attributes for accurate calorie calculations.
   * Defaults will be used if not provided.
   */
  physicalProfile?: UserPhysicalProfile;
  /**
   * User's achievements
   */
  achievements?: Achievement[];
  /**
   * User's active goals
   */
  goals?: Goal[];
}

export type UnitSystem = 'METRIC' | 'IMPERIAL';

export type Timeframe = 'Daily' | 'Weekly' | 'Monthly' | 'Yearly' | 'Since Beginning';

export interface Achievement {
  id: string;
  name: string;
  description: string;
  icon: string;
  condition: (stats: AggregatedStats) => boolean;
  unlocked: boolean;
  unlockedAt?: string;
  category: 'distance' | 'time' | 'carbon' | 'health' | 'streak';
}

export interface AggregatedStats {
  totalSessions: number;
  totalDistance: number;
  totalDuration: number;
  totalEmissions: number;
  totalConserved: number;
  totalCalories: number;
  breakdown: Record<ActivityType, { time: number; distance: number }>;
}

export interface Goal {
  id: string;
  name: string;
  description: string;
  type: 'distance' | 'carbon' | 'calories' | 'sessions';
  target: number;
  current: number;
  timeframe: 'daily' | 'weekly' | 'monthly';
  startDate: string;
  endDate?: string;
  completed: boolean;
  completedAt?: string;
}

export interface TrendData {
  date: string;
  distance: number;
  co2Saved: number;
  co2Emitted: number;
  calories: number;
  duration: number;
}
