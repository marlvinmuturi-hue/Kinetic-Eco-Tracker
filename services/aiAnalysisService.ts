/**
 * AI Analysis Service - Calls Firebase Cloud Function for activity analysis
 *
 * The Cloud Function `analyzeActivity` is deployed as an HTTP onRequest function
 * (not an onCall callable), so we authenticate manually with a Bearer token and
 * use fetch — NOT httpsCallable.
 */

import { auth } from './firebaseConfig';

const FUNCTIONS_BASE_URL = `https://us-central1-${import.meta.env.VITE_FIREBASE_PROJECT_ID || 'gen-lang-client-0114974661'}.cloudfunctions.net`;

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

  const currentUser = auth.currentUser;
  if (!currentUser) {
    throw new Error('Please sign in to use activity analysis');
  }

  let idToken: string;
  try {
    idToken = await currentUser.getIdToken();
  } catch {
    throw new Error('Failed to get authentication token. Please sign in again.');
  }

  try {
    console.log('Requesting AI analysis for timeframe:', timeframe);

    const response = await fetch(`${FUNCTIONS_BASE_URL}/analyzeActivity`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${idToken}`,
      },
      body: JSON.stringify({ timeframe }),
    });

    if (response.status === 429) {
      const data = await response.json().catch(() => ({}));
      const minutesMatch = (data.error || '').match(/(\d+) more minutes?/);
      if (minutesMatch) {
        throw new Error(`Please wait ${minutesMatch[1]} more minutes before requesting another analysis`);
      }
      throw new Error('Please wait before requesting another analysis (5 analyses per hour)');
    }

    if (response.status === 401) {
      throw new Error('Please sign in to use activity analysis');
    }

    if (response.status === 404) {
      throw new Error('No activity data found. Please track some activities first!');
    }

    if (!response.ok) {
      const data = await response.json().catch(() => ({}));
      throw new Error(data.error || `Server error (${response.status}). Please try again.`);
    }

    const result: ActivityAnalysis = await response.json();
    console.log('AI analysis received:', result);
    return result;
  } catch (error: any) {
    // Re-throw errors we already formatted
    if (error instanceof Error) throw error;
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
    return 'Please wait before requesting another analysis (5 analyses per hour)';
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
