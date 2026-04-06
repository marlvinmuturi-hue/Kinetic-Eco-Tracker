import { Goal, AggregatedStats, ActivityType } from '../types';

/**
 * Calculate current progress for a goal based on aggregated stats
 */
export function calculateGoalProgress(goal: Goal, stats: AggregatedStats): number {
  switch (goal.type) {
    case 'distance':
      return stats.totalDistance / 1000; // Convert to km
    case 'carbon':
      return stats.totalConserved;
    case 'calories':
      return stats.totalCalories;
    case 'sessions':
      return stats.totalSessions;
    default:
      return 0;
  }
}

/**
 * Check if a goal is completed
 */
export function isGoalCompleted(goal: Goal, stats: AggregatedStats): boolean {
  const current = calculateGoalProgress(goal, stats);
  return current >= goal.target;
}

/**
 * Get goal progress percentage
 */
export function getGoalProgressPercentage(goal: Goal, stats: AggregatedStats): number {
  const current = calculateGoalProgress(goal, stats);
  return Math.min(Math.round((current / goal.target) * 100), 100);
}

/**
 * Create a new goal
 */
export function createGoal(
  name: string,
  type: Goal['type'],
  target: number,
  timeframe: Goal['timeframe']
): Goal {
  const now = new Date();
  const startDate = now.toISOString();
  
  let endDate: string | undefined;
  if (timeframe === 'daily') {
    const end = new Date(now);
    end.setDate(end.getDate() + 1);
    endDate = end.toISOString();
  } else if (timeframe === 'weekly') {
    const end = new Date(now);
    end.setDate(end.getDate() + 7);
    endDate = end.toISOString();
  } else if (timeframe === 'monthly') {
    const end = new Date(now);
    end.setMonth(end.getMonth() + 1);
    endDate = end.toISOString();
  }

  return {
    id: `goal-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`,
    name,
    description: generateGoalDescription(type, target, timeframe),
    type,
    target,
    current: 0,
    timeframe,
    startDate,
    endDate,
    completed: false,
  };
}

/**
 * Generate a user-friendly description for a goal
 */
function generateGoalDescription(
  type: Goal['type'],
  target: number,
  timeframe: Goal['timeframe']
): string {
  const timeframeText = timeframe === 'daily' ? 'today' : timeframe === 'weekly' ? 'this week' : 'this month';
  
  switch (type) {
    case 'distance':
      return `Travel ${target} km ${timeframeText}`;
    case 'carbon':
      return `Save ${target} kg of CO2 ${timeframeText}`;
    case 'calories':
      return `Burn ${target} calories ${timeframeText}`;
    case 'sessions':
      return `Complete ${target} sessions ${timeframeText}`;
    default:
      return '';
  }
}

/**
 * Suggested goals for users
 */
export const SUGGESTED_GOALS = [
  // Distance goals
  { name: 'Walk 5km Today', type: 'distance' as const, target: 5, timeframe: 'daily' as const },
  { name: 'Cycle 50km This Week', type: 'distance' as const, target: 50, timeframe: 'weekly' as const },
  { name: 'Travel 200km This Month', type: 'distance' as const, target: 200, timeframe: 'monthly' as const },
  
  // Carbon goals
  { name: 'Save 1kg CO2 Today', type: 'carbon' as const, target: 1, timeframe: 'daily' as const },
  { name: 'Save 10kg CO2 This Week', type: 'carbon' as const, target: 10, timeframe: 'weekly' as const },
  { name: 'Save 50kg CO2 This Month', type: 'carbon' as const, target: 50, timeframe: 'monthly' as const },
  
  // Calorie goals
  { name: 'Burn 500 Calories Today', type: 'calories' as const, target: 500, timeframe: 'daily' as const },
  { name: 'Burn 3000 Calories This Week', type: 'calories' as const, target: 3000, timeframe: 'weekly' as const },
  { name: 'Burn 10000 Calories This Month', type: 'calories' as const, target: 10000, timeframe: 'monthly' as const },
  
  // Session goals
  { name: '3 Sessions Today', type: 'sessions' as const, target: 3, timeframe: 'daily' as const },
  { name: '10 Sessions This Week', type: 'sessions' as const, target: 10, timeframe: 'weekly' as const },
  { name: '30 Sessions This Month', type: 'sessions' as const, target: 30, timeframe: 'monthly' as const },
];

/**
 * Update goals with current stats
 */
export function updateGoals(goals: Goal[], stats: AggregatedStats): Goal[] {
  return goals.map(goal => {
    const current = calculateGoalProgress(goal, stats);
    const completed = current >= goal.target;
    
    return {
      ...goal,
      current,
      completed,
      completedAt: completed && !goal.completedAt ? new Date().toISOString() : goal.completedAt,
    };
  });
}

/**
 * Check if a goal is expired
 */
export function isGoalExpired(goal: Goal): boolean {
  if (!goal.endDate) return false;
  return new Date() > new Date(goal.endDate);
}

/**
 * Remove expired goals
 */
export function removeExpiredGoals(goals: Goal[]): Goal[] {
  return goals.filter(goal => !isGoalExpired(goal));
}
