package Kinetic_Eco.Tracker.data

/**
 * CO2 impact tier earned by accumulating [thresholdKg] kg of lifetime CO2 saved.
 *
 * Thresholds calibrated for a daily eco-commuter (~5 km cycling = ~0.85 kg/day):
 *   Bronze  → first real outing
 *   Silver  → ~2 weeks of daily cycling
 *   Gold    → ~2 months of daily cycling
 *   Champion → ~4 months of daily cycling
 */
enum class Co2Tier(
    val label: String,
    val thresholdKg: Double,
    val nextThresholdKg: Double?
) {
    BRONZE("Bronze",      0.0,   10.0),
    SILVER("Silver",     10.0,   50.0),
    GOLD("Gold",         50.0,  100.0),
    CHAMPION("Champion", 100.0,   null);

    companion object {
        fun forTotal(kg: Double): Co2Tier =
            entries.lastOrNull { kg >= it.thresholdKg } ?: BRONZE
    }
}

/** Behavioral pattern for sessions completed while the user was in one tier. */
data class TierPhaseStats(
    val tier: Co2Tier,
    val sessionCount: Int,
    val avgSessionMinutes: Double,
    val sessionsPerWeek: Double,
    val phaseDays: Int,
    val co2SavedKg: Double,
    val topActivity: ActivityType?
)

/** Average CO2 savings rate for one activity type (derived from session breakdown). */
data class ActivityCo2Rate(
    val activity: ActivityType,
    val co2PerHour: Double,   // kg CO2 conserved per hour of this activity
    val totalCo2Kg: Double,
    val sessionCount: Int
)

/** The date+session when the user first crossed a tier threshold. */
data class TierMilestone(
    val tier: Co2Tier,
    val reachedDate: String?,    // "yyyy-MM-dd"; null = not yet reached
    val daysFromStart: Int?,     // calendar days from first session; null = not reached
    val sessionNumber: Int?      // nth session overall; null = not reached
)

/** Top-level cohort profile for one user derived entirely from local session history. */
data class CohortProfile(
    val currentTier: Co2Tier,
    val totalCo2SavedKg: Double,
    val progressToNextTier: Double,  // 0.0 – 1.0; always 1.0 for Champion
    val kgToNextTier: Double?,       // null for Champion
    val tierPhases: List<TierPhaseStats>,
    val activityRates: List<ActivityCo2Rate>,  // sorted by co2PerHour desc
    val milestones: List<TierMilestone>
)