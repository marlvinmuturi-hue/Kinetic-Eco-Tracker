package Kinetic_Eco.Tracker.data

/**
 * Leaderboard ranking category.
 *
 * The app intentionally exposes a single ranking dimension — CO₂ saved — so the
 * leaderboard stays focused on the carbon-impact framing the rest of the UI
 * uses. Earlier revisions had multiple categories (combined score, distance,
 * top speed, per-activity breakdowns); those were removed at the user's
 * request. The enum is kept (rather than deleted outright) so the Firestore
 * call signatures and the cloud function contract continue to take a category
 * name without churn — and so the option to reintroduce categories later
 * remains a one-line additive change.
 */
enum class LeaderboardCategory(
    val sortField: String,
    val ascending: Boolean = false
) {
    /** Total CO₂ saved (kg). Maps to the `co2Conserved` Firestore field. */
    CO2_SAVED("co2Conserved", false)
}
