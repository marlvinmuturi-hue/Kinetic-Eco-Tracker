package Kinetic_Eco.Tracker.data

/**
 * Leaderboard ranking categories. Each has a distinct crown/trophy for top 3.
 */
enum class LeaderboardCategory(
    val sortField: String,
    val ascending: Boolean = false
) {
    /** Combined score: distance + co2 + sessions */
    COMBINED("score", false),
    /** Total distance traveled */
    DISTANCE("totalDistance", false),
    /** Top speed achieved (m/s) */
    TOP_SPEED("topSpeedMps", false),
    /** Distance while walking */
    WALKING("distanceWalking", false),
    /** Distance while running */
    RUNNING("distanceRunning", false),
    /** Distance while cycling */
    CYCLING("distanceCycling", false)
}
