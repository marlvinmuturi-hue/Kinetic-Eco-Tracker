export enum ActivityType {
  IDLE = 'IDLE',
  WALKING = 'WALKING',
  DRIVING = 'DRIVING',
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
  co2Emissions: number; // kg
  segments: ActivitySegment[];
  breakdown: Record<ActivityType, { time: number; distance: number; }>;
}

export interface GeoPosition {
  latitude: number;
  longitude: number;
  speed: number | null; // m/s
  timestamp: number;
  accuracy: number;
}

export interface StoredSession {
  id: string;
  date: string; // ISO date e.g. 2025-01-01
  stats: SessionStats;
}

export interface UserProfile {
  email: string;
  passwordHash: string;
  createdAt: string;
  lastLogin: string;
  sessions: StoredSession[];
}

export type UnitSystem = 'METRIC' | 'IMPERIAL';
