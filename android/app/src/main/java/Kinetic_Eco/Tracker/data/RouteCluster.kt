package Kinetic_Eco.Tracker.data

/**
 * A single trip's CO₂ contribution within a [RouteCluster] timeline.
 */
data class Co2DataPoint(
    val date: String,           // "yyyy-MM-dd"
    val co2Conserved: Double,   // kg saved this trip
    val co2Emissions: Double,   // kg emitted this trip (>0 only for motorised modes)
    val distanceM: Double,
    val activityType: ActivityType
)

/**
 * A "greener alternative" suggestion for a commute cluster where the dominant
 * mode has a higher carbon footprint than alternatives.
 *
 * [estimatedSavingsKgPerTrip] and [projectedAnnualSavingsKg] are computed from
 * the app's own CO₂ factors — no external API required.
 *
 * For a transit suggestion ([suggestedMode] == TRAIN), the savings estimate
 * uses an electric-transit proxy (0.04 kg/km).  Whether an actual bus or rail
 * service runs on this O-D pair requires a transit-routing API call (e.g.,
 * Google Maps Directions API with transit mode) — not implemented here.
 */
data class GreenerAlternative(
    val suggestedMode: ActivityType,
    val estimatedSavingsKgPerTrip: Double,
    val projectedAnnualSavingsKg: Double
)

/**
 * A group of trips sharing a similar origin-destination pair.
 *
 * Origin/destination coordinates are taken from the first/last [RoutePoint]
 * of each session's route path.  Two trips are "the same commute" when their
 * starts are within 400 m of each other AND their ends are within 400 m.
 *
 * [co2Timeline] is sorted by date ascending — use it to draw a trend line
 * showing how CO₂ impact for this commute has changed over time (mode shifts,
 * seasonal cycling, etc.).
 */
data class RouteCluster(
    val id: Int,
    val originLat: Double,
    val originLon: Double,
    val destLat: Double,
    val destLon: Double,
    val tripCount: Int,
    val dominantActivity: ActivityType,
    val avgDistanceM: Double,
    val avgCo2ConservedKg: Double,   // kg saved per trip on average
    val co2Timeline: List<Co2DataPoint>,
    val greenerAlternative: GreenerAlternative?
)