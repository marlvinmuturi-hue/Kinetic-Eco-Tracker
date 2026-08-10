package Kinetic_Eco.Tracker.data

/**
 * One trip, reduced to what a statement needs. No route geometry — see
 * `SessionDao.getStatementRows`.
 */
data class StatementTrip(
    val timestampMs: Long,
    val distanceKm: Double,
    /** Distance under engine power, from the per-activity breakdown. */
    val motorisedKm: Double,
    val co2SavedKg: Double,
    val co2EmittedKg: Double
)

/**
 * A month of mobility, in money first.
 *
 * Money is the framing on purpose. Kilograms of CO₂ have never moved anybody's
 * behaviour the way a number with a currency symbol does, and the app now has the
 * inputs to state one credibly.
 */
data class MonthlyStatement(
    val monthStartMs: Long,
    /** True while the month is still running, so the UI can say "so far". */
    val isPartial: Boolean,
    val tripCount: Int,
    val totalDistanceKm: Double,
    val motorisedDistanceKm: Double,
    val activeDistanceKm: Double,
    val co2SavedKg: Double,
    val co2EmittedKg: Double,
    /** Null when there is no basis to cost the driving — never a fabricated zero. */
    val fuelLitres: Double?,
    val fuelCost: Double?,
    val currencyCode: String,
    /** True when the litres came from the user's own fill-ups, not a class average. */
    val isMeasured: Boolean,
    /** Short motorised hops — the ones most plausibly replaceable. */
    val shortTripCount: Int,
    val shortTripDistanceKm: Double,
    val shortTripCost: Double?
) {
    val netCo2Kg: Double get() = co2EmittedKg - co2SavedKg
}

/**
 * Builds a [MonthlyStatement] from a month's trips.
 *
 * Pure and dependency-free so the arithmetic behind a money figure can be tested
 * without a device — a statement a user checks against their bank app is not a good
 * place to discover an off-by-one.
 */
object MonthlyStatementCalculator {

    /**
     * A motorised trip at or under this distance is flagged as replaceable.
     *
     * Matches the walking threshold used for recurring-trip suggestions, so the app
     * does not call 3 km walkable in one place and not another.
     */
    const val SHORT_TRIP_KM = 3.0

    fun build(
        monthStartMs: Long,
        trips: List<StatementTrip>,
        profile: VehicleProfile,
        /** Null when the user's region has no known prices — the statement then omits money. */
        prices: EnergyPrices?,
        measured: MeasuredEconomy?,
        isPartial: Boolean
    ): MonthlyStatement {
        val motorisedKm = trips.sumOf { it.motorisedKm }
        val shortTrips = trips.filter { it.motorisedKm > 0.0 && it.motorisedKm <= SHORT_TRIP_KM }

        val cost = costOfDriving(motorisedKm, profile, prices, measured)
        val shortCost = costOfDriving(shortTrips.sumOf { it.motorisedKm }, profile, prices, measured)

        return MonthlyStatement(
            monthStartMs = monthStartMs,
            isPartial = isPartial,
            tripCount = trips.size,
            totalDistanceKm = trips.sumOf { it.distanceKm },
            motorisedDistanceKm = motorisedKm,
            activeDistanceKm = trips.sumOf { (it.distanceKm - it.motorisedKm).coerceAtLeast(0.0) },
            co2SavedKg = trips.sumOf { it.co2SavedKg },
            co2EmittedKg = trips.sumOf { it.co2EmittedKg },
            fuelLitres = cost?.litres,
            fuelCost = cost?.amount,
            currencyCode = prices?.currencyCode ?: "",
            isMeasured = measured != null,
            shortTripCount = shortTrips.size,
            shortTripDistanceKm = shortTrips.sumOf { it.motorisedKm },
            shortTripCost = shortCost?.amount
        )
    }

    /**
     * Reuses [MobilityCostCalculator] rather than re-deriving litres here, so a monthly
     * total and the calculator's per-trip figure can never disagree — the whole reason
     * `Co2Calculator` was centralised in the first place.
     */
    private fun costOfDriving(
        km: Double,
        profile: VehicleProfile,
        prices: EnergyPrices?,
        measured: MeasuredEconomy?
    ): TripCost? {
        if (km <= 0.0 || prices == null) return null
        val activity = if (profile.primaryFuelType == PrimaryFuelType.ELECTRIC) {
            ActivityType.ELECTRIC_VEHICLE
        } else {
            ActivityType.DRIVING
        }
        return MobilityCostCalculator.costOf(
            Co2Calculator.estimate(activity, km, profile),
            profile,
            prices,
            measured
        )
    }
}