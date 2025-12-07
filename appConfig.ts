export const APP_CONFIG = {
  appName: 'Kinetic',
  displayVersion: 'v1.0',
  // Increment this when you introduce breaking schema changes
  schemaVersion: 1,
} as const;

export const FEATURE_FLAGS = {
  // Toggle AI insights without removing the UI
  aiInsights: true,
  // Placeholder for future behavior changes that might not apply to legacy users
  experimentalMetrics: false,
} as const;








