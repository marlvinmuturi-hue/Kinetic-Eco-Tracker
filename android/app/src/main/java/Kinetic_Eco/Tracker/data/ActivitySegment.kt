package Kinetic_Eco.Tracker.data

data class ActivitySegment(
    val type: ActivityType,
    val startTime: Long,
    val endTime: Long,
    val distance: Double,
    val avgSpeed: Double,
    val comment: String = "",
    val userCorrectedType: ActivityType? = null
) {
    /** User override wins for display and CO₂/calorie calculations; falls back to detected type. */
    val effectiveType: ActivityType get() = userCorrectedType ?: type
}