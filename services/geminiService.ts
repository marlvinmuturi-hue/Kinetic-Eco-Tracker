import { auth } from './firebaseConfig';
import { SessionStats } from "../types";

const FUNCTIONS_BASE_URL = `https://us-central1-${import.meta.env.VITE_FIREBASE_PROJECT_ID || 'gen-lang-client-0114974661'}.cloudfunctions.net`;

export const generateEcoInsight = async (stats: SessionStats): Promise<string> => {
  const currentUser = auth.currentUser;
  if (!currentUser) {
    return "**AI Coach Unavailable**\n\nSign in to unlock AI-powered eco insights.";
  }

  let idToken: string;
  try {
    idToken = await currentUser.getIdToken();
  } catch {
    return "Error connecting to AI service. Please try again.";
  }

  try {
    const response = await fetch(`${FUNCTIONS_BASE_URL}/generateSessionInsight`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${idToken}`,
      },
      body: JSON.stringify({
        totalDuration: stats.totalDuration,
        totalDistance: stats.totalDistance,
        caloriesBurned: stats.caloriesBurned,
        co2Emissions: stats.co2Emissions,
        breakdown: stats.breakdown,
      }),
    });

    if (response.status === 429) {
      return "**AI Coach Unavailable**\n\nToo many requests. Please wait a moment and try again.";
    }
    if (!response.ok) {
      return "Error connecting to AI service for insights.";
    }

    const data = await response.json();
    return data.insight || "Could not generate insight.";
  } catch (error) {
    console.error("Session insight error:", error);
    return "Error connecting to AI service for insights.";
  }
};