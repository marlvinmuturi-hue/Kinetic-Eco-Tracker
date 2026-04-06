package Kinetic_Eco.Tracker.services

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await
import Kinetic_Eco.Tracker.data.ActivityAnalysis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AIAnalysisService {
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val functionUrl = "https://us-central1-gen-lang-client-0114974661.cloudfunctions.net/analyzeActivity"
    
    suspend fun analyzeActivity(
        rollingWindowDays: Int,
        locale: String = "en",
        sessionDateKey: String? = null
    ): Result<ActivityAnalysis> = withContext(Dispatchers.IO) {
        try {
            // Check if user is authenticated
            val currentUser = auth.currentUser
            if (currentUser == null) {
                Log.w(TAG, "User not authenticated locally")
                return@withContext Result.failure(Exception("Please sign in to the app first"))
            }
            
            Log.d(TAG, "===== AI ANALYSIS REQUEST (HTTP) =====")
            Log.d(TAG, "User authenticated: ${currentUser.uid}")
            Log.d(TAG, "User email: ${currentUser.email}")
            val n = rollingWindowDays.coerceIn(1, 366)
            val sk = sessionDateKey?.trim()?.takeIf { it.isNotEmpty() }
            val isSingleDayKey = sk != null && Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(sk)
            val effectiveTf = if (isSingleDayKey) "1day" else "${n}days"
            Log.d(TAG, "Timeframe: $effectiveTf, sessionDateKey: ${sessionDateKey ?: "n/a"}, Locale: $locale")
            Log.d(TAG, "Endpoint: $functionUrl")
            
            // Get fresh ID token
            Log.d(TAG, "Getting fresh ID token...")
            val idToken = currentUser.getIdToken(true).await()
            val token = idToken.token ?: return@withContext Result.failure(Exception("Failed to get auth token"))
            Log.d(TAG, "✅ Token obtained: ${token.take(20)}...")
            
            // Prepare request body (locale: en/fr/de/es/zh for AI to respond in that language)
            val jsonBody = JSONObject().apply {
                put("timeframe", effectiveTf)
                put("locale", locale)
                if (isSingleDayKey) sk?.let { put("sessionDateKey", it) }
            }
            val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
            
            // Build HTTP request with Authorization header
            val request = Request.Builder()
                .url(functionUrl)
                .post(requestBody)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .build()
            
            Log.d(TAG, "📡 Sending HTTP POST request...")
            
            // Make the request
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            
            Log.d(TAG, "📥 Response code: ${response.code}")
            Log.d(TAG, "📥 Response body: ${responseBody.take(200)}")
            
            if (!response.isSuccessful) {
                val errorMsg = try {
                    JSONObject(responseBody).optString("error", "Unknown error")
                } catch (e: Exception) {
                    "HTTP ${response.code}: $responseBody"
                }
                Log.e(TAG, "❌ Request failed: $errorMsg")
                return@withContext Result.failure(Exception(errorMsg))
            }
            
            // Parse successful response
            val jsonResponse = JSONObject(responseBody)
            val analysis = parseAnalysisResponse(jsonResponse)
            Log.d(TAG, "✅ Analysis successful! Score: ${analysis.score}")
            
            Result.success(analysis)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error analyzing activity", e)
            Result.failure(Exception(getErrorMessage(e)))
        }
    }
    
    private fun parseAnalysisResponse(json: JSONObject): ActivityAnalysis {
        val highlightsJson = json.optJSONObject("highlights")
        val highlights = highlightsJson?.let {
            ActivityAnalysis.Highlights(
                bestDay = it.optString("bestDay", ""),
                topActivity = it.optString("topActivity", ""),
                improvement = it.optString("improvement", "")
            )
        }
        
        val insightsList = mutableListOf<String>()
        val insightsArray = json.optJSONArray("insights")
        if (insightsArray != null) {
            for (i in 0 until insightsArray.length()) {
                insightsList.add(insightsArray.getString(i))
            }
        }
        
        val recommendationsList = mutableListOf<String>()
        val recommendationsArray = json.optJSONArray("recommendations")
        if (recommendationsArray != null) {
            for (i in 0 until recommendationsArray.length()) {
                recommendationsList.add(recommendationsArray.getString(i))
            }
        }
        
        return ActivityAnalysis(
            score = json.optDouble("score", 0.0),
            scoreReasoning = json.optString("scoreReasoning", ""),
            insights = insightsList,
            recommendations = recommendationsList,
            motivation = json.optString("motivation", ""),
            environmentalImpact = json.optString("environmentalImpact", ""),
            highlights = highlights,
            cached = json.optBoolean("cached", false),
            cacheAge = json.optInt("cacheAge", 0)
        )
    }
    
    private fun getErrorMessage(error: Exception): String {
        val message = error.message ?: "Unknown error"
        
        return when {
            message.contains("unauthenticated", ignoreCase = true) ->
                "Please sign in to use activity analysis"
            
            message.contains("resource-exhausted", ignoreCase = true) || 
            message.contains("wait", ignoreCase = true) -> {
                val minutesMatch = Regex("""(\d+) more minutes?""").find(message)
                if (minutesMatch != null) {
                    "Please wait ${minutesMatch.groupValues[1]} more minutes before requesting another analysis"
                } else {
                    "Please wait before requesting another analysis (1 analysis per hour)"
                }
            }
            
            message.contains("not-found", ignoreCase = true) || 
            message.contains("No activity data", ignoreCase = true) ->
                "No activity data found. Please track some activities first!"
            
            message.contains("DEADLINE_EXCEEDED", ignoreCase = true) ->
                "Analysis is taking longer than expected. Please try again."
            
            message.contains("UNAVAILABLE", ignoreCase = true) ->
                "Service temporarily unavailable. Please check your internet connection."
            
            else -> "Analysis failed: ${message.take(100)}"
        }
    }
    
    companion object {
        private const val TAG = "AIAnalysisService"
        
        @Volatile
        private var instance: AIAnalysisService? = null
        
        fun getInstance(): AIAnalysisService {
            return instance ?: synchronized(this) {
                instance ?: AIAnalysisService().also { instance = it }
            }
        }
    }
}
