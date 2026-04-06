package Kinetic_Eco.Tracker.data

data class GeoPosition(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null, // meters above sea level (null if unavailable)
    val speed: Float, // m/s
    val timestamp: Long,
    val accuracy: Float // meters
)



