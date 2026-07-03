package Kinetic_Eco.Tracker.data.database

/**
 * Lightweight projection returned by the DAO's time-series aggregation queries.
 *
 * [period] holds whatever the GROUP BY key is:
 *  - daily   → "yyyy-MM-dd"
 *  - weekly  → "yyyy-WW"  (SQLite %Y-%W on date−1day, week starts Monday)
 *  - monthly → "yyyy-MM"
 */
data class Co2ByPeriod(
    val period: String,
    val co2Conserved: Double,
    val co2Emissions: Double
)