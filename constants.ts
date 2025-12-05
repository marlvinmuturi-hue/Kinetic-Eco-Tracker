import { ActivityType } from './types';

// Speed thresholds in m/s
// 1 m/s = 3.6 km/h
export const SPEED_THRESHOLDS = {
  WALKING_MIN: 0.5, // > 1.8 km/h (to filter GPS drift)
  DRIVING_MIN: 7.0, // > 25 km/h (approximate switch point)
  FLYING_MIN: 55.0, // > 200 km/h
};

// CO2 Emissions in kg per km
export const CO2_FACTORS = {
  [ActivityType.IDLE]: 0,
  [ActivityType.WALKING]: 0.0, // Negligible/Breath only
  [ActivityType.DRIVING]: 0.192, // Avg passenger vehicle
  [ActivityType.FLYING]: 0.255, // Avg domestic flight
};

// Calories burned per hour (approximate average person)
// We will convert this to per second for real-time tracking
export const CALORIE_FACTORS_PER_HOUR = {
  [ActivityType.IDLE]: 60,
  [ActivityType.WALKING]: 250,
  [ActivityType.DRIVING]: 100, // Light stress/sitting
  [ActivityType.FLYING]: 90,   // Sitting
};

export const ACTIVITY_COLORS = {
  [ActivityType.IDLE]: '#94a3b8', // slate-400
  [ActivityType.WALKING]: '#22c55e', // green-500
  [ActivityType.DRIVING]: '#f59e0b', // amber-500
  [ActivityType.FLYING]: '#3b82f6', // blue-500
};
