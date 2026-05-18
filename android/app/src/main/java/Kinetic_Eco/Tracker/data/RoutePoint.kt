package Kinetic_Eco.Tracker.data

/** A single GPS point on a session route (for map display). */
data class RoutePoint(
    val latitude: Double,
    val longitude: Double,
    val activity: ActivityType? = null, // null = legacy point (use default color)
    /** Meters above sea level when known (GPS or baro); null for legacy sessions. */
    val altitudeMeters: Double? = null
)
