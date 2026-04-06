/**
 * Advanced Calorie Calculation Engine
 * 
 * Uses MET (Metabolic Equivalent of Task) values with user-specific adjustments
 * for accurate calorie burn estimation.
 * 
 * Based on:
 * - Compendium of Physical Activities (Ainsworth et al., 2011)
 * - ACSM Metabolic Calculations
 * - Pandolf Equation for walking/running
 */

import { ActivityType, UserPhysicalProfile } from '../types';

// Default user profile (average adult)
export const DEFAULT_PHYSICAL_PROFILE: UserPhysicalProfile = {
  weight: 70,      // kg
  height: 170,     // cm
  age: 30,         // years
  gender: 'male'
};

/**
 * MET value lookup based on activity type and speed
 * MET = Metabolic Equivalent (1 MET = resting metabolic rate)
 */
export class CalorieEngine {
  
  /**
   * Calculate calories burned for a given activity
   * 
   * @param activityType - Type of activity
   * @param durationSeconds - Duration in seconds
   * @param speedMps - Speed in meters per second (null for stationary activities)
   * @param elevationGainMeters - Elevation gained in meters (optional)
   * @param elevationLossMeters - Elevation lost in meters (optional)
   * @param userProfile - User's physical profile (optional, uses defaults if not provided)
   * @returns Calories burned in kcal
   */
  static calculateCalories(
    activityType: ActivityType,
    durationSeconds: number,
    speedMps: number | null = null,
    elevationGainMeters: number = 0,
    elevationLossMeters: number = 0,
    userProfile?: UserPhysicalProfile
  ): number {
    const profile = userProfile || DEFAULT_PHYSICAL_PROFILE;
    const durationHours = durationSeconds / 3600;
    
    // Get base MET value
    const met = this.getMET(activityType, speedMps);
    
    // Calculate correction factor (K) based on gender and age
    const k = this.getCorrectionFactor(profile.gender, profile.age);
    
    // Base calorie calculation: MET × weight × duration × correction factor
    const baseCalories = met * profile.weight * durationHours * k;
    
    // Add elevation adjustments
    const elevationCalories = this.calculateElevationBonus(
      elevationGainMeters,
      elevationLossMeters,
      profile.weight
    );
    
    return baseCalories + elevationCalories;
  }
  
  /**
   * Get MET value for activity based on type and speed
   */
  private static getMET(activityType: ActivityType, speedMps: number | null): number {
    switch (activityType) {
      case ActivityType.IDLE:
        return 1.0;
      
      case ActivityType.WALKING:
        return this.getWalkingMET(speedMps);
      
      case ActivityType.RUNNING:
        return this.getRunningMET(speedMps);
      
      case ActivityType.CYCLING:
        return this.getCyclingMET(speedMps);
      
      case ActivityType.DRIVING:
        return 1.3; // Sitting, light stress
      
      case ActivityType.ELECTRIC_VEHICLE:
        return 1.3; // Same as driving
      
      case ActivityType.FLYING:
        return 1.3; // Sitting
      
      default:
        return 1.0;
    }
  }
  
  /**
   * Get MET value for walking based on speed
   * Reference: Compendium of Physical Activities
   */
  private static getWalkingMET(speedMps: number | null): number {
    if (!speedMps) return 3.5; // Default moderate walking
    
    const kmh = speedMps * 3.6;
    
    if (kmh < 3.2) return 2.0;       // Slow (2 mph)
    if (kmh < 4.0) return 2.8;       // 2.5 mph
    if (kmh < 4.8) return 3.5;       // 3 mph - moderate
    if (kmh < 5.6) return 4.3;       // 3.5 mph - brisk
    if (kmh < 6.4) return 5.0;       // 4 mph - very brisk
    return 6.3;                       // 4.5+ mph - very fast walking
  }
  
  /**
   * Get MET value for running based on speed
   * Reference: Compendium of Physical Activities
   */
  private static getRunningMET(speedMps: number | null): number {
    if (!speedMps) return 9.8; // Default moderate running
    
    const kmh = speedMps * 3.6;
    
    if (kmh < 6.4) return 6.0;       // Jogging (4 mph)
    if (kmh < 8.0) return 8.3;       // 5 mph
    if (kmh < 9.7) return 9.8;       // 6 mph - moderate
    if (kmh < 11.3) return 11.0;     // 7 mph - fast
    if (kmh < 12.9) return 11.5;     // 8 mph - very fast
    if (kmh < 14.5) return 12.3;     // 9 mph
    return 12.8;                      // 10+ mph - sprinting
  }
  
  /**
   * Get MET value for cycling based on speed
   * Reference: Compendium of Physical Activities
   */
  private static getCyclingMET(speedMps: number | null): number {
    if (!speedMps) return 6.8; // Default moderate cycling
    
    const kmh = speedMps * 3.6;
    
    if (kmh < 16) return 4.0;        // <10 mph - leisure
    if (kmh < 19) return 6.8;        // 10-12 mph - moderate
    if (kmh < 22) return 8.0;        // 12-14 mph
    if (kmh < 26) return 10.0;       // 14-16 mph - vigorous
    if (kmh < 31) return 12.0;       // 16-19 mph - fast
    return 15.6;                      // 19+ mph - racing
  }
  
  /**
   * Calculate correction factor based on gender and age
   * 
   * - Females have ~10% lower BMR than males
   * - Metabolism decreases ~1% per year after age 30
   */
  private static getCorrectionFactor(gender: string, age: number): number {
    let k = 1.0;
    
    // Gender adjustment
    if (gender === 'female') {
      k *= 0.9;
    }
    
    // Age adjustment (decline after 30)
    if (age > 30) {
      const ageYearsOver30 = age - 30;
      k *= (1.0 - (ageYearsOver30 * 0.01));
    }
    
    return k;
  }
  
  /**
   * Calculate additional calories from elevation changes
   * 
   * Physics: Work = mass × gravity × height
   * - Climbing: full energy cost
   * - Descending: ~30% energy cost (eccentric muscle contraction)
   */
  private static calculateElevationBonus(
    gainMeters: number,
    lossMeters: number,
    weightKg: number
  ): number {
    const GRAVITY = 9.8; // m/s²
    const DESCENT_FACTOR = 0.3; // Descending costs ~30% of ascending
    const JOULES_TO_KCAL = 0.000239006; // Conversion factor
    
    // Work done climbing (Joules)
    const climbWork = gainMeters * weightKg * GRAVITY;
    
    // Work done descending (Joules) - less than climbing
    const descendWork = lossMeters * weightKg * GRAVITY * DESCENT_FACTOR;
    
    // Convert to kcal
    const totalKcal = (climbWork + descendWork) * JOULES_TO_KCAL;
    
    return totalKcal;
  }
  
  /**
   * Estimate BMR (Basal Metabolic Rate) using Harris-Benedict Equation
   * This is the number of calories burned at rest per day
   */
  static calculateBMR(profile: UserPhysicalProfile): number {
    const { weight, height, age, gender } = profile;
    
    if (gender === 'female') {
      // BMR (women) = 655 + (9.6 × weight in kg) + (1.8 × height in cm) - (4.7 × age in years)
      return 655 + (9.6 * weight) + (1.8 * height) - (4.7 * age);
    } else {
      // BMR (men) = 66 + (13.7 × weight in kg) + (5 × height in cm) - (6.8 × age in years)
      return 66 + (13.7 * weight) + (5 * height) - (6.8 * age);
    }
  }
  
  /**
   * Get calories burned per hour for display purposes
   * (Used for real-time display in UI)
   */
  static getCaloriesPerHour(
    activityType: ActivityType,
    speedMps: number | null = null,
    userProfile?: UserPhysicalProfile
  ): number {
    return this.calculateCalories(
      activityType,
      3600, // 1 hour in seconds
      speedMps,
      0, // No elevation
      0,
      userProfile
    );
  }
}
