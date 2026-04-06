package Kinetic_Eco.Tracker.data

/**
 * Leaderboard entry for a user. Stored in Firestore at leaderboard/{userId}.
 * Score: totalDistance + co2Conserved*100 + totalSessions*500 (combined ranking).
 * Supports multi-category ranking: distance, top speed, per-activity.
 */
data class LeaderboardEntry(
    val userId: String = "",
    val displayName: String? = null,
    val photoUrl: String? = null,
    val totalDistance: Double = 0.0,
    val totalSessions: Int = 0,
    val co2Conserved: Double = 0.0,
    val co2Emissions: Double = 0.0,
    val score: Double = 0.0,
    val rank: Int = 0,
    val topSpeedMps: Double = 0.0,
    val distanceWalking: Double = 0.0,
    val distanceRunning: Double = 0.0,
    val distanceCycling: Double = 0.0,
    /** Map of reactorUserId -> emoji code (fire, sweat, clap, joy, thumbs, cool) */
    val reactions: Map<String, String> = emptyMap()
) {
    companion object {
        /** Combined score: distance (m) + co2Conserved*100 + sessions*500 */
        fun computeScore(totalDistance: Double, co2Conserved: Double, totalSessions: Int): Double {
            return totalDistance + co2Conserved * 100.0 + totalSessions * 500.0
        }
    }
}
