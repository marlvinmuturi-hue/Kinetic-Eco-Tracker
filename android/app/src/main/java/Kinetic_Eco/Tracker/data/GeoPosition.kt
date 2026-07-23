package Kinetic_Eco.Tracker.data

data class GeoPosition(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null, // meters above sea level (null if unavailable)
    val speed: Float, // m/s
    val timestamp: Long,
    val accuracy: Float, // horizontal accuracy, meters
    /**
     * Vertical (altitude) accuracy in meters at 68% confidence, or null when the platform does not
     * report it (API < 26, or a provider that omits it). GPS vertical error is typically 1.5–3× the
     * horizontal, so this is gated separately before altitude is trusted — see the altitude pipeline
     * in TrackingService.
     */
    val verticalAccuracy: Float? = null
)



