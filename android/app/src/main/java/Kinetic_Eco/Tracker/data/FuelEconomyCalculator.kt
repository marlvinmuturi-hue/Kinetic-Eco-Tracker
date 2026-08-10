package Kinetic_Eco.Tracker.data

/**
 * One full-to-full stretch: the fuel bought to refill it, and how far the app measured
 * the user driving over it.
 */
data class FuelInterval(
    val litres: Double,
    val distanceKm: Double
)

/**
 * The user's measured fuel economy, plus how much evidence it rests on.
 */
data class MeasuredEconomy(
    /** Litres per 100 km. */
    val lPer100Km: Double,
    /** Full-to-full intervals it was computed from. More is better. */
    val intervals: Int,
    val totalLitres: Double,
    val totalDistanceKm: Double
)

/**
 * Works out what the user's vehicle actually consumes, from fill-ups plus tracked
 * distance.
 *
 * Uses the tank-to-tank method: between two brim-full fills, the litres bought at the
 * second fill are exactly the litres burned since the first. Pair that with the
 * distance the app measured over the same window and the result is the user's real
 * economy — no odometer, no self-reporting, no manufacturer figure.
 *
 * This is the piece that lets the calculator stop hedging. Until it exists, driving
 * consumption comes from an engine-displacement band, which is a class average that
 * individual cars miss by up to 30%.
 */
object FuelEconomyCalculator {

    /**
     * Shortest interval worth trusting. Over a few kilometres, the error in "was the
     * tank really brim-full?" swamps the signal — pump auto-shutoff varies by litres,
     * not by percentage.
     */
    const val MIN_INTERVAL_KM = 50.0

    /**
     * Plausibility bounds in L/100 km. Outside these the interval is not a car: it is a
     * mis-typed litre figure, a fill for a different vehicle, or a stretch where most
     * driving happened with tracking off. Including such an interval would poison a
     * number the user is about to check against their own receipts.
     */
    const val MIN_PLAUSIBLE_L_PER_100KM = 2.0
    const val MAX_PLAUSIBLE_L_PER_100KM = 40.0

    /** Below this, say "estimated" rather than "measured". One interval is an anecdote. */
    const val MIN_INTERVALS_FOR_CONFIDENCE = 2

    /**
     * Combine [intervals] into a single economy figure, or null if none are usable.
     *
     * Totals are summed and divided once — **not** averaged as a mean of per-interval
     * ratios. A 400 km interval carries more evidence than a 60 km one, and averaging
     * ratios would weight them equally, letting one short odd tankful drag the figure.
     */
    fun economyFrom(intervals: List<FuelInterval>): MeasuredEconomy? {
        val usable = intervals.filter { it.isUsable() }
        if (usable.isEmpty()) return null

        val litres = usable.sumOf { it.litres }
        val km = usable.sumOf { it.distanceKm }
        if (km <= 0.0) return null

        return MeasuredEconomy(
            lPer100Km = litres / km * 100.0,
            intervals = usable.size,
            totalLitres = litres,
            totalDistanceKm = km
        )
    }

    /**
     * Build intervals from fill-ups in time order.
     *
     * [drivingKmBetween] is asked for the motorised distance in each window; the caller
     * supplies it from tracked sessions. Only consecutive **full** tanks bound an
     * interval — a partial fill in between leaves an unknown residue and breaks the
     * arithmetic, so the pair is skipped rather than guessed at.
     */
    fun intervalsFrom(
        fillUps: List<FillUp>,
        drivingKmBetween: (fromMs: Long, toMs: Long) -> Double
    ): List<FuelInterval> = fullTankPairs(fillUps).map { (previous, current) ->
        FuelInterval(
            // The litres that refilled the tank are the litres burned since the
            // previous brim — so the *current* fill's volume belongs to this window.
            litres = current.litres,
            distanceKm = drivingKmBetween(previous.filledAtMs, current.filledAtMs)
        )
    }

    /**
     * Consecutive brim-full fills, oldest first — the windows an economy can be read
     * from. Split out because callers that fetch distance from the database need a
     * suspending lookup, which a pure function cannot host.
     */
    fun fullTankPairs(fillUps: List<FillUp>): List<Pair<FillUp, FillUp>> {
        val fulls = fillUps.filter { it.isFullTank }.sortedBy { it.filledAtMs }
        if (fulls.size < 2) return emptyList()
        return fulls.zipWithNext()
    }

    /**
     * Real price per litre paid, averaged across [fillUps].
     *
     * A useful by-product: a receipt is also the most authoritative price the app will
     * ever see for this user, beating any published table.
     */
    fun averagePricePerLitre(fillUps: List<FillUp>): Double? {
        val valid = fillUps.filter { it.litres > 0.0 && it.amountPaid > 0.0 }
        if (valid.isEmpty()) return null
        return valid.sumOf { it.amountPaid } / valid.sumOf { it.litres }
    }

    /**
     * How long a receipt stays a better price than the published table.
     *
     * Kenyan pump prices are re-capped monthly, so a receipt from last week beats
     * anything else the app has, while one from last year is actively misleading.
     * Two months keeps a couple of fill-ups in scope for typical drivers without
     * reaching back across several price revisions.
     */
    const val PRICE_WINDOW_DAYS = 60

    /**
     * Price per litre from recent receipts, or null if none are recent enough.
     *
     * Deliberately **not** an all-time average: pooling two years of fill-ups would
     * quietly anchor the figure to prices that no longer exist. When nothing falls in
     * the window the answer is null so the caller falls back to the published table,
     * which at least claims to be current.
     */
    fun recentPricePerLitre(
        fillUps: List<FillUp>,
        nowMs: Long = System.currentTimeMillis(),
        windowDays: Int = PRICE_WINDOW_DAYS
    ): Double? {
        val cutoff = nowMs - windowDays * 24L * 60 * 60 * 1000
        return averagePricePerLitre(fillUps.filter { it.filledAtMs >= cutoff })
    }

    private fun FuelInterval.isUsable(): Boolean {
        if (litres <= 0.0 || distanceKm < MIN_INTERVAL_KM) return false
        val rate = litres / distanceKm * 100.0
        return rate in MIN_PLAUSIBLE_L_PER_100KM..MAX_PLAUSIBLE_L_PER_100KM
    }
}

/** Storage-agnostic view of a fill-up, so the maths stays free of Room. */
data class FillUp(
    val filledAtMs: Long,
    val litres: Double,
    val amountPaid: Double,
    val isFullTank: Boolean
)
