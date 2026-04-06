import { GoogleGenAI, Type } from "@google/genai";
import { SessionStats } from "../types";

// Prefer Vite-style env variable when available, but fall back to process.env
// so the integration is portable and easy to evolve.
const apiKey =
  // Vite / browser build
  (typeof import.meta !== 'undefined' &&
    (import.meta as unknown as { env?: Record<string, string | undefined> }).env &&
    ((import.meta as unknown as { env: Record<string, string | undefined> }).env.VITE_GEMINI_API_KEY ||
      (import.meta as unknown as { env: Record<string, string | undefined> }).env.VITE_API_KEY)) ||
  // Node-style environment (tests, server-side tools)
  (typeof process !== 'undefined' && process.env && (process.env.API_KEY || process.env.GEMINI_API_KEY)) ||
  '';
const ai = new GoogleGenAI({ apiKey });

export const generateEcoInsight = async (stats: SessionStats): Promise<string> => {
  if (!apiKey || apiKey === 'your-gemini-api-key-here') {
    console.warn("Gemini API Key is missing. Please add VITE_GEMINI_API_KEY to your .env file.");
    return "**AI Coach Insights Unavailable**\n\nTo enable AI-powered insights, please add your Gemini API key to the `.env` file:\n\n1. Get your API key from: https://aistudio.google.com/app/apikey\n2. Create a `.env` file in the project root\n3. Add: `VITE_GEMINI_API_KEY=your-actual-api-key-here`\n4. Restart the development server";
  }

  const prompt = `
    Analyze the following movement data from a user's tracking session:
    
    Total Duration: ${Math.round(stats.totalDuration)} seconds
    Total Distance: ${(stats.totalDistance / 1000).toFixed(2)} km
    Calories Burned: ${Math.round(stats.caloriesBurned)} kcal
    CO2 Emissions: ${stats.co2Emissions.toFixed(2)} kg
    
    Breakdown:
    - Walking: ${(stats.breakdown.WALKING.distance / 1000).toFixed(2)} km, ${Math.round(stats.breakdown.WALKING.time)} sec
    - Driving: ${(stats.breakdown.DRIVING.distance / 1000).toFixed(2)} km, ${Math.round(stats.breakdown.DRIVING.time)} sec
    - Flying: ${(stats.breakdown.FLYING.distance / 1000).toFixed(2)} km, ${Math.round(stats.breakdown.FLYING.time)} sec

    Provide a short, engaging, and personalized summary (approx 100 words).
    Focus on the environmental impact and health benefits. 
    If they walked a lot, praise them for low emissions and high calorie burn.
    If they drove or flew a lot, suggest carbon offsetting or mention the environmental cost gently.
    Use Markdown for formatting.
  `;

  try {
    const response = await ai.models.generateContent({
      model: "gemini-2.5-flash",
      contents: prompt,
      config: {
        systemInstruction: "You are an eco-conscious fitness coach named 'EcoStep'.",
        temperature: 0.7,
      }
    });
    return response.text || "Could not generate insight.";
  } catch (error) {
    console.error("Gemini API Error:", error);
    return "Error connecting to AI service for insights.";
  }
};
