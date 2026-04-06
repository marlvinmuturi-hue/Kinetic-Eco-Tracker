package Kinetic_Eco.Tracker.data

/** A single GPS point on a session route (for map display). */
data class RoutePoint(
    val latitude: Double,
    val longitude: Double,
    val activity: ActivityType? = null // null = legacy point (use default color)
)
