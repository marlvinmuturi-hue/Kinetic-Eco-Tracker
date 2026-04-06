package Kinetic_Eco.Tracker.data

data class ActivitySegment(
    val type: ActivityType,
    val startTime: Long, // milliseconds
    val endTime: Long, // milliseconds
    val distance: Double, // meters
    val avgSpeed: Double // m/s
)



