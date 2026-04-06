# AI Analysis Implementation Guide (Kotlin)

## Prerequisites Checklist
- [x] Firebase project already set up ✓
- [x] Android app connected to Firebase ✓
- [ ] Node.js installed (for Cloud Functions)
- [ ] Firebase CLI installed
- [ ] Gemini API key

---

## Step 1: Get Gemini API Key (5 minutes)

### 1.1 Visit Google AI Studio
1. Go to: https://aistudio.google.com/app/apikey
2. Sign in with your Google account (same one used for Firebase)
3. Click **"Get API key"** or **"Create API key"**
4. Select your Firebase project (or create in new project)
5. Copy the API key (starts with `AIza...`)

### 1.2 Store API Key Securely
**Important**: Never commit API keys to Git!

Open terminal in your project root and run:
```bash
firebase functions:config:set gemini.key="YOUR_API_KEY_HERE"
```

Verify it's set:
```bash
firebase functions:config:get
```

Should show:
```json
{
  "gemini": {
    "key": "AIza..."
  }
}
```

---

## Step 2: Set Up Firebase Cloud Functions (30 minutes)

### 2.1 Check if Functions Folder Exists

You already have a `functions/` folder in your project! Let me check what's in it...

### 2.2 Initialize Functions (if needed)

If `functions/` doesn't have proper structure, run:
```bash
cd functions
npm init -y
```

### 2.3 Install Dependencies

```bash
cd functions
npm install firebase-functions firebase-admin @google/generative-ai
```

### 2.4 Create/Update functions/package.json

Make sure it looks like this:
```json
{
  "name": "kinetic-functions",
  "version": "1.0.0",
  "description": "Cloud Functions for Kinetic Activity Tracker",
  "main": "index.js",
  "engines": {
    "node": "18"
  },
  "dependencies": {
    "firebase-functions": "^4.5.0",
    "firebase-admin": "^11.11.0",
    "@google/generative-ai": "^0.1.3"
  }
}
```

### 2.5 Create functions/index.js

```javascript
const functions = require('firebase-functions');
const admin = require('firebase-admin');
const { GoogleGenerativeAI } = require('@google/generative-ai');

// Initialize Firebase Admin
admin.initializeApp();
const db = admin.firestore();

// Initialize Gemini AI
const genAI = new GoogleGenerativeAI(functions.config().gemini.key);

/**
 * Analyze user's activity data
 * Callable from Android app
 */
exports.analyzeActivity = functions.https.onCall(async (data, context) => {
  // Step 1: Authentication check
  if (!context.auth) {
    throw new functions.https.HttpsError(
      'unauthenticated',
      'User must be authenticated to analyze activity'
    );
  }

  const userId = context.auth.uid;
  const timeframe = data.timeframe || '7days'; // Default to 7 days

  try {
    console.log(`Analyzing activity for user: ${userId}, timeframe: ${timeframe}`);

    // Step 2: Check rate limiting (1 analysis per hour)
    const rateLimitDoc = await db.collection('analysisRateLimit').doc(userId).get();
    if (rateLimitDoc.exists) {
      const lastAnalysis = rateLimitDoc.data().timestamp;
      const hourAgo = Date.now() - (60 * 60 * 1000);
      
      if (lastAnalysis > hourAgo) {
        throw new functions.https.HttpsError(
          'resource-exhausted',
          'Please wait at least 1 hour between analyses'
        );
      }
    }

    // Step 3: Check cache (24-hour TTL)
    const cacheDoc = await db.collection('analysisCache').doc(userId).get();
    if (cacheDoc.exists) {
      const cached = cacheDoc.data();
      const dayAgo = Date.now() - (24 * 60 * 60 * 1000);
      
      if (cached.timestamp > dayAgo && cached.timeframe === timeframe) {
        console.log('Returning cached analysis');
        return {
          ...cached.analysis,
          cached: true,
          cacheAge: Math.floor((Date.now() - cached.timestamp) / 1000 / 60) // minutes
        };
      }
    }

    // Step 4: Fetch user sessions from Firestore
    const sessions = await fetchUserSessions(userId, timeframe);
    
    if (!sessions || sessions.length === 0) {
      throw new functions.https.HttpsError(
        'not-found',
        'No activity data found for analysis'
      );
    }

    // Step 5: Calculate aggregate stats
    const stats = calculateAggregateStats(sessions);

    // Step 6: Build prompt for Gemini
    const prompt = buildAnalysisPrompt(stats, timeframe);

    // Step 7: Call Gemini API
    console.log('Calling Gemini API...');
    const model = genAI.getGenerativeModel({ model: 'gemini-1.5-flash' });
    const result = await model.generateContent(prompt);
    const responseText = result.response.text();
    
    // Step 8: Parse AI response (extract JSON from markdown if needed)
    let analysis;
    try {
      // Try to extract JSON from markdown code blocks
      const jsonMatch = responseText.match(/```json\n([\s\S]*?)\n```/);
      if (jsonMatch) {
        analysis = JSON.parse(jsonMatch[1]);
      } else {
        analysis = JSON.parse(responseText);
      }
    } catch (parseError) {
      console.error('Failed to parse AI response:', responseText);
      throw new functions.https.HttpsError(
        'internal',
        'Failed to parse AI response'
      );
    }

    // Step 9: Update rate limit
    await db.collection('analysisRateLimit').doc(userId).set({
      timestamp: Date.now()
    });

    // Step 10: Cache the result
    await db.collection('analysisCache').doc(userId).set({
      analysis: analysis,
      timestamp: Date.now(),
      timeframe: timeframe
    });

    console.log('Analysis complete');
    return {
      ...analysis,
      cached: false
    };

  } catch (error) {
    console.error('Error in analyzeActivity:', error);
    
    if (error instanceof functions.https.HttpsError) {
      throw error;
    }
    
    throw new functions.https.HttpsError(
      'internal',
      'An error occurred during analysis: ' + error.message
    );
  }
});

/**
 * Fetch user sessions from Firestore
 */
async function fetchUserSessions(userId, timeframe) {
  const daysMap = {
    '7days': 7,
    '30days': 30,
    '90days': 90
  };
  
  const days = daysMap[timeframe] || 7;
  const cutoffDate = new Date();
  cutoffDate.setDate(cutoffDate.getDate() - days);
  
  try {
    const snapshot = await db.collection('users')
      .doc(userId)
      .collection('sessions')
      .where('timestamp', '>=', cutoffDate.getTime())
      .orderBy('timestamp', 'desc')
      .get();
    
    if (snapshot.empty) {
      return [];
    }
    
    return snapshot.docs.map(doc => ({
      id: doc.id,
      ...doc.data()
    }));
  } catch (error) {
    console.error('Error fetching sessions:', error);
    return [];
  }
}

/**
 * Calculate aggregate statistics from sessions
 */
function calculateAggregateStats(sessions) {
  const stats = {
    totalSessions: sessions.length,
    totalDistance: 0,
    totalDuration: 0,
    totalCalories: 0,
    co2Emissions: 0,
    co2Conserved: 0,
    activityBreakdown: {
      IDLE: { count: 0, distance: 0, duration: 0 },
      WALKING: { count: 0, distance: 0, duration: 0 },
      RUNNING: { count: 0, distance: 0, duration: 0 },
      CYCLING: { count: 0, distance: 0, duration: 0 },
      DRIVING: { count: 0, distance: 0, duration: 0 },
      FLYING: { count: 0, distance: 0, duration: 0 }
    },
    activeDays: new Set(),
    avgDistance: 0,
    avgDuration: 0
  };

  sessions.forEach(session => {
    // Add to date set
    const date = new Date(session.timestamp).toDateString();
    stats.activeDays.add(date);

    // Aggregate totals
    stats.totalDistance += session.totalDistance || 0;
    stats.totalDuration += session.totalDuration || 0;
    stats.totalCalories += session.caloriesBurned || 0;
    stats.co2Emissions += session.co2Emissions || 0;
    stats.co2Conserved += session.co2Conserved || 0;

    // Activity breakdown
    if (session.breakdown) {
      Object.keys(session.breakdown).forEach(activity => {
        if (stats.activityBreakdown[activity]) {
          stats.activityBreakdown[activity].count++;
          stats.activityBreakdown[activity].distance += session.breakdown[activity].distance || 0;
          stats.activityBreakdown[activity].duration += session.breakdown[activity].time || 0;
        }
      });
    }
  });

  stats.activeDaysCount = stats.activeDays.size;
  stats.avgDistance = stats.totalDistance / stats.totalSessions;
  stats.avgDuration = stats.totalDuration / stats.totalSessions;

  return stats;
}

/**
 * Build analysis prompt for Gemini
 */
function buildAnalysisPrompt(stats, timeframe) {
  const days = timeframe === '7days' ? 7 : timeframe === '30days' ? 30 : 90;
  
  return `You are a fitness and health analyst. Analyze this user's activity data and provide insights.

**Time Period**: Past ${days} days

**Activity Summary**:
- Total Sessions: ${stats.totalSessions}
- Active Days: ${stats.activeDaysCount} out of ${days} days
- Total Distance: ${(stats.totalDistance / 1000).toFixed(2)} km
- Total Duration: ${(stats.totalDuration / 3600).toFixed(1)} hours
- Calories Burned: ${Math.round(stats.totalCalories)} kcal
- CO2 Emissions: ${stats.co2Emissions.toFixed(2)} kg
- CO2 Conserved: ${stats.co2Conserved.toFixed(2)} kg

**Activity Breakdown**:
${Object.entries(stats.activityBreakdown)
  .filter(([_, data]) => data.count > 0)
  .map(([activity, data]) => 
    `- ${activity}: ${data.count} sessions, ${(data.distance / 1000).toFixed(2)} km, ${(data.duration / 60).toFixed(0)} minutes`
  )
  .join('\n')}

**Average Per Session**:
- Distance: ${(stats.avgDistance / 1000).toFixed(2)} km
- Duration: ${(stats.avgDuration / 60).toFixed(0)} minutes

Please provide a comprehensive analysis in the following JSON format:

{
  "score": 8.5,
  "scoreReasoning": "Brief explanation of the score",
  "insights": [
    "First key insight with specific numbers",
    "Second key insight with specific numbers",
    "Third key insight with specific numbers"
  ],
  "recommendations": [
    "First actionable recommendation",
    "Second actionable recommendation"
  ],
  "motivation": "One encouraging message celebrating their achievements",
  "environmentalImpact": "Brief summary of their CO2 impact",
  "highlights": {
    "bestDay": "Day with most activity",
    "topActivity": "Most frequent activity type",
    "improvement": "Area showing most improvement"
  }
}

Guidelines:
- Be specific with numbers and percentages
- Be encouraging and positive
- Provide actionable recommendations
- Consider activity consistency, variety, and intensity
- Score out of 10 (0-3: needs improvement, 4-6: fair, 7-8: good, 9-10: excellent)
- Keep insights brief but meaningful

Return ONLY the JSON object, no additional text.`;
}

/**
 * Health check endpoint
 */
exports.healthCheck = functions.https.onRequest((req, res) => {
  res.json({ 
    status: 'ok', 
    timestamp: Date.now(),
    version: '1.0.0'
  });
});
```

---

## Step 3: Deploy Cloud Functions (10 minutes)

### 3.1 Login to Firebase
```bash
firebase login
```

### 3.2 Initialize Firebase (if not already done)
```bash
firebase init functions
```

Select:
- Use existing project (your Kinetic project)
- JavaScript
- No ESLint (or Yes if you prefer)
- Yes to install dependencies

### 3.3 Deploy the Function
```bash
firebase deploy --only functions
```

Wait for deployment... Should see:
```
✔  functions[analyzeActivity(us-central1)] Successful create operation.
✔  functions[healthCheck(us-central1)] Successful create operation.
Function URL (healthCheck): https://us-central1-YOUR-PROJECT.cloudfunctions.net/healthCheck
```

### 3.4 Test the Health Check
Visit the healthCheck URL in your browser. Should see:
```json
{
  "status": "ok",
  "timestamp": 1234567890,
  "version": "1.0.0"
}
```

---

## Step 4: Test with Sample Data (15 minutes)

### 4.1 Create Test Data in Firestore

Go to Firebase Console → Firestore Database

Create a test session:
```
Collection: users
  Document: YOUR_USER_ID
    Collection: sessions
      Document: test_session_1
        Fields:
          - timestamp: 1706356800000 (number)
          - totalDistance: 5000 (number) // 5km in meters
          - totalDuration: 1800 (number) // 30 minutes in seconds
          - caloriesBurned: 200 (number)
          - co2Emissions: 0 (number)
          - co2Conserved: 1.5 (number)
          - breakdown: (map)
            - WALKING: (map)
              - distance: 5000
              - time: 1800
```

### 4.2 Test from Firebase Console

1. Go to Firebase Console → Functions
2. Click on `analyzeActivity` function
3. Go to "Testing" tab
4. Use this test data:

```json
{
  "timeframe": "7days"
}
```

5. Click "Test the function"

Should see response with insights!

---

## Step 5: Integrate with Kotlin Android App (30 minutes)

### 5.1 Add Dependencies to app/build.gradle

```gradle
dependencies {
    // ... existing dependencies ...
    
    // Firebase Functions
    implementation 'com.google.firebase:firebase-functions-ktx:20.4.0'
    
    // Coroutines (if not already added)
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3'
}
```

Sync project.

### 5.2 Create Data Classes

Create file: `app/src/main/java/com/yourpackage/models/ActivityAnalysis.kt`

```kotlin
package com.yourpackage.models

import com.google.gson.annotations.SerializedName

data class ActivityAnalysis(
    @SerializedName("score")
    val score: Double = 0.0,
    
    @SerializedName("scoreReasoning")
    val scoreReasoning: String = "",
    
    @SerializedName("insights")
    val insights: List<String> = emptyList(),
    
    @SerializedName("recommendations")
    val recommendations: List<String> = emptyList(),
    
    @SerializedName("motivation")
    val motivation: String = "",
    
    @SerializedName("environmentalImpact")
    val environmentalImpact: String = "",
    
    @SerializedName("highlights")
    val highlights: Highlights? = null,
    
    @SerializedName("cached")
    val cached: Boolean = false,
    
    @SerializedName("cacheAge")
    val cacheAge: Int? = null
) {
    data class Highlights(
        @SerializedName("bestDay")
        val bestDay: String = "",
        
        @SerializedName("topActivity")
        val topActivity: String = "",
        
        @SerializedName("improvement")
        val improvement: String = ""
    )
}
```

### 5.3 Create Analysis Service

Create file: `app/src/main/java/com/yourpackage/services/analysisService.kt`

```kotlin
package com.yourpackage.services

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.ktx.functions
import com.google.firebase.ktx.Firebase
import com.google.gson.Gson
import com.yourpackage.models.ActivityAnalysis
import kotlinx.coroutines.tasks.await

class AnalysisService {
    private val functions: FirebaseFunctions = Firebase.functions
    private val gson = Gson()
    
    /**
     * Request activity analysis from Cloud Function
     * @param timeframe "7days", "30days", or "90days"
     * @return ActivityAnalysis object with insights
     */
    suspend fun analyzeActivity(timeframe: String = "7days"): Result<ActivityAnalysis> {
        return try {
            val data = hashMapOf(
                "timeframe" to timeframe
            )
            
            val result = functions
                .getHttpsCallable("analyzeActivity")
                .call(data)
                .await()
            
            // Parse the result
            val json = gson.toJson(result.data)
            val analysis = gson.fromJson(json, ActivityAnalysis::class.java)
            
            Result.success(analysis)
            
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    companion object {
        @Volatile
        private var instance: AnalysisService? = null
        
        fun getInstance(): AnalysisService {
            return instance ?: synchronized(this) {
                instance ?: AnalysisService().also { instance = it }
            }
        }
    }
}
```

### 5.4 Create Simple Test UI

Add a button to your Analytics component (Analytics.tsx equivalent in Kotlin)

```kotlin
// In your Analytics Fragment/Activity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class AnalyticsFragment : Fragment() {
    private val analysisService = AnalysisService.getInstance()
    
    private fun setupAnalyzeButton() {
        binding.btnAnalyze.setOnClickListener {
            analyzeActivity()
        }
    }
    
    private fun analyzeActivity() {
        // Show loading
        binding.progressBar.visibility = View.VISIBLE
        binding.btnAnalyze.isEnabled = false
        
        lifecycleScope.launch {
            val result = analysisService.analyzeActivity("7days")
            
            result.onSuccess { analysis ->
                // Hide loading
                binding.progressBar.visibility = View.GONE
                binding.btnAnalyze.isEnabled = true
                
                // Display analysis
                displayAnalysis(analysis)
                
            }.onFailure { error ->
                // Hide loading
                binding.progressBar.visibility = View.GONE
                binding.btnAnalyze.isEnabled = true
                
                // Show error
                showError(error.message ?: "Analysis failed")
            }
        }
    }
    
    private fun displayAnalysis(analysis: ActivityAnalysis) {
        // For now, just log it
        Log.d("Analytics", "Score: ${analysis.score}")
        Log.d("Analytics", "Insights: ${analysis.insights.joinToString()}")
        
        // TODO: Create beautiful UI to display this
        // For testing, show in a dialog
        AlertDialog.Builder(requireContext())
            .setTitle("Activity Analysis (Score: ${analysis.score}/10)")
            .setMessage("""
                ${analysis.motivation}
                
                Insights:
                ${analysis.insights.joinToString("\n• ", "• ")}
                
                Recommendations:
                ${analysis.recommendations.joinToString("\n• ", "• ")}
            """.trimIndent())
            .setPositiveButton("OK", null)
            .show()
    }
    
    private fun showError(message: String) {
        Toast.makeText(requireContext(), "Error: $message", Toast.LENGTH_LONG).show()
    }
}
```

---

## Testing Checklist

### Firebase Function Test:
- [ ] Function deploys without errors
- [ ] Health check endpoint returns 200 OK
- [ ] Can call analyzeActivity from Firebase Console
- [ ] Returns valid JSON with insights

### Android App Test:
- [ ] App compiles without errors
- [ ] Button shows loading state
- [ ] Analysis completes and shows result
- [ ] Error handling works (test with no data)

---

## Troubleshooting

### Common Issues:

1. **"API key not set" error**
   ```bash
   firebase functions:config:set gemini.key="YOUR_KEY"
   firebase deploy --only functions
   ```

2. **"User must be authenticated" error**
   - Make sure user is logged in before calling function
   - Check Firebase Auth is initialized

3. **"No activity data found" error**
   - Add test data to Firestore (see Step 4.1)
   - Check userId matches authenticated user

4. **Function timeout**
   - Gemini API can take 5-10 seconds
   - Increase timeout in Firebase Console if needed

5. **Gradle sync fails**
   - Check internet connection
   - Update Google Services plugin
   - Invalidate caches and restart Android Studio

---

## Next Steps

Once Steps 1-4 are working:
1. Create beautiful UI for displaying insights
2. Add different timeframe options (7/30/90 days)
3. Add pull-to-refresh
4. Add caching on Android side
5. Add loading animations

---

**Current Status**: Ready to implement!
**Estimated Time**: 2-3 hours for steps 1-4

Let me know when you're ready to proceed with each step!
