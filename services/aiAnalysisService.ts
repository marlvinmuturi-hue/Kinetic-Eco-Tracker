/**
 * AI Analysis Service - Calls Firebase Cloud Function for activity analysis
 */

import { getFunctions, httpsCallable } from 'firebase/functions';

export interface ActivityAnalysis {
  score: number;
  scoreReasoning: string;
  insights: string[];
  recommendations: string[];
  motivation: string;
  environmentalImpact: string;
  highlights?: {
    bestDay: string;
    topActivity: string;
    improvement: string;
  };
  cached?: boolean;
  cacheAge?: number;
}

/** Rolling window length in days (1–366). The cloud function accepts "Ndays" strings. */
export async function analyzeActivity(rollingDays: number = 30): Promise<ActivityAnalysis> {
  const n = Math.min(366, Math.max(1, Math.round(rollingDays)));
  const timeframe = `${n}days`;
  const functions = getFunctions();
  const analyzeActivityFn = httpsCallable<{ timeframe: string }, ActivityAnalysis>(
    functions,
    'analyzeActivity'
  );

  try {
    console.log('Requesting AI analysis for timeframe:', timeframe);
    const result = await analyzeActivityFn({ timeframe });
    console.log('AI analysis received:', result.data);
    return result.data;
  } catch (error: any) {
    console.error('Error analyzing activity:', error);
    throw new Error(getErrorMessage(error));
  }
}

/**
 * Get user-friendly error message
 */
function getErrorMessage(error: any): string {
  const message = error.message || 'Unknown error';
  
  if (message.includes('unauthenticated')) {
    return 'Please sign in to use activity analysis';
  }
  
  if (message.includes('resource-exhausted') || message.includes('wait')) {
    const minutesMatch = message.match(/(\d+) more minutes?/);
    if (minutesMatch) {
      return `Please wait ${minutesMatch[1]} more minutes before requesting another analysis`;
    }
    return 'Please wait before requesting another analysis (1 analysis per hour)';
  }
  
  if (message.includes('not-found') || message.includes('No activity data')) {
    return 'No activity data found. Please track some activities first!';
  }
  
  if (message.includes('DEADLINE_EXCEEDED')) {
    return 'Analysis is taking longer than expected. Please try again.';
  }
  
  if (message.includes('UNAVAILABLE')) {
    return 'Service temporarily unavailable. Please check your internet connection.';
  }
  
  return `Analysis failed: ${message.substring(0, 100)}`;
}
