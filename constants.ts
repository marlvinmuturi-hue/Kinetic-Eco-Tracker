import { ActivityType } from './types';

// Speed thresholds in m/s
// 1 m/s = 3.6 km/h
export const SPEED_THRESHOLDS = {
  WALKING_MIN: 0.5, // > 1.8 km/h (to filter GPS drift)
  IDLE_OVERRIDE: 0.833, // 3 km/h - never IDLE above this speed
  RUNNING_MIN: 2.0, // > 7.2 km/h
  CYCLING_MIN: 4.0, // > 14.4 km/h
  DRIVING_MIN: 10.0, // > 36 km/h (higher threshold to avoid misclassification when slowing down)
  FLYING_MIN: 55.0, // > 200 km/h
};

// Layer 2: High altitude = always flying (meters)
export const HIGH_ALTITUDE_M = 1000;

// CO2 Emissions in kg per km (positive = emissions, negative = conservation/savings)
export const CO2_FACTORS = {
  [ActivityType.IDLE]: 0,
  [ActivityType.WALKING]: -0.192, // Negative = CO2 saved vs driving (0.192 kg/km saved)
  [ActivityType.RUNNING]: -0.192, // Same as walking - conserving by not driving
  [ActivityType.CYCLING]: -0.192, // Conserving CO2 vs driving
  [ActivityType.DRIVING]: 0.192, // Avg passenger vehicle emissions (gas/diesel)
  [ActivityType.ELECTRIC_VEHICLE]: 0.053, // EV emissions (much lower - ~70% reduction vs gas cars)
  [ActivityType.FLYING]: 0.255, // Avg domestic flight emissions
};

// Baseline CO2 per km that would be emitted if driving (for conservation calculations)
export const BASELINE_DRIVING_CO2_PER_KM = 0.192;

// Calories burned per hour (approximate average person)
// We will convert this to per second for real-time tracking
export const CALORIE_FACTORS_PER_HOUR = {
  [ActivityType.IDLE]: 60,
  [ActivityType.WALKING]: 250,
  [ActivityType.RUNNING]: 600,
  [ActivityType.CYCLING]: 400,
  [ActivityType.DRIVING]: 100, // Light stress/sitting
  [ActivityType.ELECTRIC_VEHICLE]: 100, // Same as driving (sitting)
  [ActivityType.FLYING]: 90,   // Sitting
};

export const ACTIVITY_COLORS = {
  [ActivityType.IDLE]: '#94a3b8', // slate-400
  [ActivityType.WALKING]: '#22c55e', // green-500
  [ActivityType.RUNNING]: '#10b981', // emerald-500
  [ActivityType.CYCLING]: '#06b6d4', // cyan-500
  [ActivityType.DRIVING]: '#f59e0b', // amber-500
  [ActivityType.ELECTRIC_VEHICLE]: '#8b5cf6', // violet-500
  [ActivityType.FLYING]: '#3b82f6', // blue-500
};
