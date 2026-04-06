package Kinetic_Eco.Tracker.services

import android.util.Log
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.ktx.functions
import com.google.firebase.ktx.Firebase
import com.google.gson.Gson
import Kinetic_Eco.Tracker.models.ActivityAnalysis
import kotlinx.coroutines.tasks.await

/**
 * Service for interacting with AI Analysis Cloud Functions
 * Provides activity insights using Gemini AI
 */
class AnalysisService private constructor() {
    
    private val functions: FirebaseFunctions = Firebase.functions
    private val gson = Gson()
    
    companion object {
        private const val TAG = "AnalysisService"
        private const val FUNCTION_NAME = "analyzeActivity"
        
        @Volatile
        private var instance: AnalysisService? = null
        
        fun getInstance(): AnalysisService {
            return instance ?: synchronized(this) {
                instance ?: AnalysisService().also { instance = it }
            }
        }
    }
    
    /**
     * Time frames available for analysis
     */
    enum class Timeframe(val value: String, val displayName: String) {
        WEEK("7days", "Last 7 Days"),
        MONTH("30days", "Last 30 Days"),
        QUARTER("90days", "Last 90 Days")
    }
    
    /**
     * Request activity analysis from Cloud Function
     * 
     * @param timeframe Time period to analyze (default: 7 days)
     * @return Result containing ActivityAnalysis or error
     */
    suspend fun analyzeActivity(timeframe: Timeframe = Timeframe.WEEK): Result<ActivityAnalysis> {
        return try {
            Log.d(TAG, "Requesting analysis for timeframe: ${timeframe.value}")
            
            val data = hashMapOf(
                "timeframe" to timeframe.value
            )
            
            val result = functions
                .getHttpsCallable(FUNCTION_NAME)
                .call(data)
                .await()
            
            Log.d(TAG, "Analysis response received")
            
            // Parse the result
            val json = gson.toJson(result.data)
            Log.d(TAG, "Response JSON: $json")
            
            val analysis = gson.fromJson(json, ActivityAnalysis::class.java)
            
            if (analysis.cached) {
                Log.d(TAG, "Returned cached analysis (age: ${analysis.cacheAge} minutes)")
            }
            
            Result.success(analysis)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing activity", e)
            Result.failure(e)
        }
    }
    
    /**
     * Get user-friendly error message from exception
     */
    fun getErrorMessage(exception: Exception): String {
        val message = exception.message ?: "Unknown error"
        
        return when {
            message.contains("unauthenticated") -> 
                "Please sign in to use activity analysis"
            
            message.contains("resource-exhausted") -> 
                "Please wait before requesting another analysis. ${extractWaitTime(message)}"
            
            message.contains("not-found") -> 
                "No activity data found. Track some activities first!"
            
            message.contains("DEADLINE_EXCEEDED") -> 
                "Analysis is taking longer than expected. Please try again."
            
            message.contains("UNAVAILABLE") -> 
                "Service temporarily unavailable. Please check your internet connection."
            
            else -> 
                "Analysis failed: ${message.take(100)}"
        }
    }
    
    /**
     * Extract wait time from rate limit error message
     */
    private fun extractWaitTime(message: String): String {
        val minutesMatch = Regex("(\\d+) more minutes?").find(message)
        return minutesMatch?.groupValues?.get(0) ?: ""
    }
    
    /**
     * Check if error is due to rate limiting
     */
    fun isRateLimitError(exception: Exception): Boolean {
        return exception.message?.contains("resource-exhausted") == true ||
               exception.message?.contains("wait") == true
    }
    
    /**
     * Check if error is due to no data
     */
    fun isNoDataError(exception: Exception): Boolean {
        return exception.message?.contains("not-found") == true ||
               exception.message?.contains("No activity data") == true
    }
}
