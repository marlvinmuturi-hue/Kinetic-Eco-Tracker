package Kinetic_Eco.Tracker.data

/** Time taken to complete a kilometer (for session summary). */
data class KmMilestone(
    val km: Int,
    val secondsForKm: Long
)

data class ActivityBreakdown(
    val time: Long = 0, // seconds
    val distance: Double = 0.0, // meters
    val steps: Int = 0 // step count for this activity
)

fun ActivityBreakdown.toEntity(): Kinetic_Eco.Tracker.data.database.ActivityBreakdownEntity {
    return Kinetic_Eco.Tracker.data.database.ActivityBreakdownEntity(
        time = this.time,
        distance = this.distance
    )
}

data class SessionStats(
    val date: String = "", // ISO date string (yyyy-MM-dd)
    /** Wall-clock end of session (ms). Used for time-of-day charts; 0 if unknown. */
    val sessionEndTimeMs: Long = 0L,
    val totalDuration: Long = 0, // seconds
    val totalDistance: Double = 0.0, // meters
    val caloriesBurned: Double = 0.0, // kcal
    val co2Emissions: Double = 0.0, // kg (positive value = emissions)
    val co2Conserved: Double = 0.0, // kg (positive value = conservation/savings)
    val totalSteps: Int = 0, // total step count across all activities
    val elevationGain: Double = 0.0, // meters (ascent/total uphill)
    val elevationLoss: Double = 0.0, // meters (descent/total downhill)
    val startingAltitude: Double? = null, // meters (altitude when tracking started)
    val stoppingAltitude: Double? = null, // meters (altitude when tracking stopped)
    val minAltitude: Double? = null, // meters (lowest point)
    val maxAltitude: Double? = null, // meters (highest point)
    val kmMilestones: List<KmMilestone> = emptyList(), // time per km for session summary
    val topSpeedMps: Double = 0.0, // max raw GPS speed in m/s (0 = no movement)
    val segments: List<ActivitySegment> = emptyList(),
    val routePath: List<RoutePoint> = emptyList(), // GPS points for map display (stored per session)
    val breakdown: Map<ActivityType, ActivityBreakdown> = mapOf(
        ActivityType.IDLE to ActivityBreakdown(),
        ActivityType.WALKING to ActivityBreakdown(),
        ActivityType.RUNNING to ActivityBreakdown(),
        ActivityType.CYCLING to ActivityBreakdown(),
        ActivityType.MOTORCYCLE to ActivityBreakdown(),
        ActivityType.TRAIN to ActivityBreakdown(),
        ActivityType.DRIVING to ActivityBreakdown(),
        ActivityType.ELECTRIC_VEHICLE to ActivityBreakdown(),
        ActivityType.FLYING to ActivityBreakdown()
    )
)

