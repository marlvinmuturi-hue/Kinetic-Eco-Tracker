import { Achievement, AggregatedStats } from '../types';

/**
 * Achievement Definitions
 * Each achievement has a condition that is checked against user stats
 */
export const ACHIEVEMENTS: Omit<Achievement, 'unlocked' | 'unlockedAt'>[] = [
  // Distance Achievements
  {
    id: 'first_km',
    name: 'First Steps',
    description: 'Complete your first kilometer',
    icon: '🚶',
    category: 'distance',
    condition: (stats) => stats.totalDistance >= 1000,
  },
  {
    id: 'marathon',
    name: 'Marathon Runner',
    description: 'Travel 42.2 km in total',
    icon: '🏃',
    category: 'distance',
    condition: (stats) => stats.totalDistance >= 42200,
  },
  {
    id: 'century_rider',
    name: 'Century Rider',
    description: 'Cycle 100 km in total',
    icon: '🚴',
    category: 'distance',
    condition: (stats) => stats.totalDistance >= 100000,
  },
  {
    id: 'world_traveler',
    name: 'World Traveler',
    description: 'Travel 1,000 km total distance',
    icon: '🌍',
    category: 'distance',
    condition: (stats) => stats.totalDistance >= 1000000,
  },

  // Carbon Achievements
  {
    id: 'first_tree',
    name: 'Tree Saver',
    description: 'Save CO2 equivalent to 1 tree (21.7 kg/year)',
    icon: '🌳',
    category: 'carbon',
    condition: (stats) => stats.totalConserved >= 21.7,
  },
  {
    id: 'carbon_neutral_week',
    name: 'Carbon Neutral Week',
    description: 'Achieve net zero emissions for a week',
    icon: '♻️',
    category: 'carbon',
    condition: (stats) => stats.totalConserved >= stats.totalEmissions && stats.totalSessions >= 7,
  },
  {
    id: 'green_warrior',
    name: 'Green Warrior',
    description: 'Save 100 kg of CO2',
    icon: '🛡️',
    category: 'carbon',
    condition: (stats) => stats.totalConserved >= 100,
  },
  {
    id: 'eco_champion',
    name: 'Eco Champion',
    description: 'Save 500 kg of CO2',
    icon: '🏆',
    category: 'carbon',
    condition: (stats) => stats.totalConserved >= 500,
  },

  // Health Achievements
  {
    id: 'calorie_crusher',
    name: 'Calorie Crusher',
    description: 'Burn 10,000 calories total',
    icon: '🔥',
    category: 'health',
    condition: (stats) => stats.totalCalories >= 10000,
  },
  {
    id: 'fitness_warrior',
    name: 'Fitness Warrior',
    description: 'Burn 50,000 calories total',
    icon: '💪',
    category: 'health',
    condition: (stats) => stats.totalCalories >= 50000,
  },
  {
    id: 'marathon_calories',
    name: 'Marathon Burner',
    description: 'Burn equivalent calories to running a marathon (2,600 kcal)',
    icon: '⚡',
    category: 'health',
    condition: (stats) => stats.totalCalories >= 2600,
  },

  // Time Achievements
  {
    id: 'active_hour',
    name: 'Active Hour',
    description: 'Accumulate 1 hour of activity',
    icon: '⏰',
    category: 'time',
    condition: (stats) => stats.totalDuration >= 3600,
  },
  {
    id: 'dedicated_athlete',
    name: 'Dedicated Athlete',
    description: 'Accumulate 24 hours of activity',
    icon: '🎯',
    category: 'time',
    condition: (stats) => stats.totalDuration >= 86400,
  },
  {
    id: 'time_master',
    name: 'Time Master',
    description: 'Accumulate 100 hours of activity',
    icon: '⏳',
    category: 'time',
    condition: (stats) => stats.totalDuration >= 360000,
  },

  // Session Achievements
  {
    id: 'getting_started',
    name: 'Getting Started',
    description: 'Complete your first session',
    icon: '🎬',
    category: 'streak',
    condition: (stats) => stats.totalSessions >= 1,
  },
  {
    id: 'committed',
    name: 'Committed',
    description: 'Complete 10 sessions',
    icon: '💯',
    category: 'streak',
    condition: (stats) => stats.totalSessions >= 10,
  },
  {
    id: 'veteran',
    name: 'Veteran',
    description: 'Complete 50 sessions',
    icon: '🏅',
    category: 'streak',
    condition: (stats) => stats.totalSessions >= 50,
  },
  {
    id: 'legend',
    name: 'Legend',
    description: 'Complete 100 sessions',
    icon: '👑',
    category: 'streak',
    condition: (stats) => stats.totalSessions >= 100,
  },
];

/**
 * Check all achievements and update unlock status
 */
export function checkAchievements(
  currentAchievements: Achievement[] | undefined,
  stats: AggregatedStats
): Achievement[] {
  const achievements: Achievement[] = ACHIEVEMENTS.map((achDef) => {
    // Find if this achievement already exists in user's profile
    const existing = currentAchievements?.find((a) => a.id === achDef.id);

    // If already unlocked, keep it as is
    if (existing?.unlocked) {
      return existing;
    }

    // Check if conditions are met
    const shouldUnlock = achDef.condition(stats);

    return {
      ...achDef,
      unlocked: shouldUnlock,
      unlockedAt: shouldUnlock ? new Date().toISOString() : undefined,
    };
  });

  return achievements;
}

/**
 * Get newly unlocked achievements (for notifications)
 */
export function getNewlyUnlocked(
  previousAchievements: Achievement[] | undefined,
  currentAchievements: Achievement[]
): Achievement[] {
  const previousUnlocked = new Set(
    previousAchievements?.filter((a) => a.unlocked).map((a) => a.id) || []
  );

  return currentAchievements.filter(
    (a) => a.unlocked && !previousUnlocked.has(a.id)
  );
}

/**
 * Get achievement progress as percentage
 */
export function getAchievementProgress(stats: AggregatedStats): {
  unlocked: number;
  total: number;
  percentage: number;
} {
  const checked = checkAchievements([], stats);
  const unlocked = checked.filter((a) => a.unlocked).length;
  const total = checked.length;

  return {
    unlocked,
    total,
    percentage: Math.round((unlocked / total) * 100),
  };
}
