package Kinetic_Eco.Tracker.models

import com.google.gson.annotations.SerializedName

/**
 * Data class for AI-generated activity analysis
 * Corresponds to the response from Cloud Function analyzeActivity
 */
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
    /**
     * Highlights of the analysis
     */
    data class Highlights(
        @SerializedName("bestDay")
        val bestDay: String = "",
        
        @SerializedName("topActivity")
        val topActivity: String = "",
        
        @SerializedName("improvement")
        val improvement: String = ""
    )
    
    /**
     * Check if this is a good score (7.0+)
     */
    fun isGoodScore(): Boolean = score >= 7.0
    
    /**
     * Check if this is an excellent score (9.0+)
     */
    fun isExcellentScore(): Boolean = score >= 9.0
    
    /**
     * Get score as percentage (0-100)
     */
    fun getScorePercentage(): Int = (score * 10).toInt()
    
    /**
     * Get formatted score string
     */
    fun getFormattedScore(): String = String.format("%.1f/10", score)
}
