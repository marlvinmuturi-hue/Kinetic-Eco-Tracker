package Kinetic_Eco.Tracker.data

/**
 * Everything the AI analysis needs that the server cannot see for itself.
 *
 * Sessions live in Firestore, so the backend can derive distance, modes and timing on
 * its own. The vehicle profile, the CO₂ goal, energy prices and measured fuel economy
 * are all device-local — so without this the model was reasoning about a stranger: it
 * did not know whether they drove a diesel SUV or rode an electric scooter, and every
 * recommendation it produced was necessarily generic.
 *
 * Every field is optional. A user who has not set a vehicle or whose region has no
 * prices simply yields fewer facts, and the prompt omits those sections rather than
 * inventing them.
 */
data class AnalysisContext(
    /**
     * Minutes to add to UTC for the user's local time.
     *
     * Needed for the time-of-day breakdown. The server only has epoch milliseconds, so
     * without this a Nairobi user's 07:00 commute would be bucketed as 04:00 and the
     * model would confidently describe them as a pre-dawn athlete.
     */
    val timeZoneOffsetMinutes: Int,
    val unitSystem: String,
    val isPremium: Boolean,
    /** Weekly CO₂ reduction target in kg, so progress can be spoken to. */
    val weeklyCo2GoalKg: Double,
    val vehicle: Vehicle?,
    val money: Money?
) {
    data class Vehicle(
        val primaryFuel: String,
        val iceFuel: String,
        val engineCcBand: String,
        val bodyType: String,
        /** Owner-stated km/L, null when they have not entered one. */
        val kmPerLitre: Double?
    )

    /**
     * What energy actually costs this user, and how well we know it.
     *
     * [measuredLPer100Km] is the strongest fact in here — it comes from their own
     * fill-ups rather than a class average, and it is what lets the model quote a cost
     * without hedging.
     */
    data class Money(
        val currencyCode: String,
        val petrolPerLitre: Double,
        val dieselPerLitre: Double,
        val electricityPerKwh: Double,
        val priceSource: String,
        val region: String,
        val measuredLPer100Km: Double?,
        val measuredFromFillUps: Int
    )
}
