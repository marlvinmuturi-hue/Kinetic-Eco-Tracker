package Kinetic_Eco.Tracker.data

data class ActivityAnalysis(
    val score: Double = 0.0,
    val scoreReasoning: String = "",
    val insights: List<String> = emptyList(),
    val recommendations: List<String> = emptyList(),
    val motivation: String = "",
    val environmentalImpact: String = "",
    val highlights: Highlights? = null,
    val cached: Boolean = false,
    val cacheAge: Int = 0
) {
    data class Highlights(
        val bestDay: String = "",
        val topActivity: String = "",
        val improvement: String = ""
    )
}

enum class Timeframe(val value: String, val displayName: String) {
    SEVEN_DAYS("7days", "Last 7 Days"),
    THIRTY_DAYS("30days", "Last 30 Days"),
    NINETY_DAYS("90days", "Last 90 Days")
}
