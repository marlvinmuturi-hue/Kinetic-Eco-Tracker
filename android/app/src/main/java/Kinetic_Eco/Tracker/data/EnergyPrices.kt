package Kinetic_Eco.Tracker.data

/** Where a price came from. Shown in the UI — a price with no provenance is a guess. */
enum class PriceSource {
    /** Compiled-in seed. Correct on the day it was written and decaying ever since. */
    BUNDLED,

    /** Fetched from the `energyPrices` collection, refreshed monthly. */
    REMOTE,

    /** Typed by the user. Always wins — they know what they actually paid. */
    USER
}

/**
 * Unit energy prices for one region.
 *
 * Kenya is the primary market and unusually easy to be accurate in: EPRA publishes
 * capped maximum pump prices monthly, and Kenya Power's tariffs are regulated too.
 * Elsewhere the honest answer is to let the user type what they pay, which is more
 * accurate than any table anyway.
 */
data class EnergyPrices(
    val currencyCode: String,
    val petrolPerLitre: Double,
    val dieselPerLitre: Double,
    val electricityPerKwh: Double,
    val source: PriceSource,
    /** `yyyy-MM` the figures apply to, so the UI can show — and age — them. */
    val effectiveMonth: String,
    /**
     * Who published these figures — "EPRA" for Kenya, the relevant ministry or
     * regulator elsewhere.
     *
     * Carried per region rather than hardcoded: the UI used to print "EPRA" beside
     * every remote price, so a French user would have been told the Kenyan regulator
     * set their fuel price. Null falls back to a neutral "published price".
     */
    val sourceName: String? = null
) {
    companion object {
        /**
         * Seed values, Kenya.
         *
         * **These go stale.** They exist so a first launch with no network shows
         * something rather than nothing, and every surface that uses them says
         * where they came from and when. Real values arrive from `energyPrices/KE`
         * in Firestore, which should be refreshed each month from EPRA's published
         * maximum pump prices and the current Kenya Power tariff.
         */
        /**
         * The only region we ship a seed for.
         *
         * Deliberately not a global default. Quoting Kenyan shillings to someone in
         * France is not a rounding error — it is a confidently wrong number in the wrong
         * currency, and this app's whole argument for the cost feature was that a wrong
         * money figure is worse than none.
         */
        const val SEEDED_REGION = "KE"

        /** Seed prices for [region], or null when we have no basis to quote any. */
        fun seedFor(region: String): EnergyPrices? =
            if (region.equals(SEEDED_REGION, ignoreCase = true)) KENYA_SEED else null

        val KENYA_SEED = EnergyPrices(
            currencyCode = "KES",
            // EPRA maximum pump prices, Nairobi, August 2026. Note diesel above petrol
            // — that ordering has flipped before and will again, so never infer one
            // from the other.
            petrolPerLitre = 214.00,
            dieselPerLitre = 222.00,
            // Unknown, deliberately. No verified Kenya Power tariff was to hand, and a
            // plausible-looking invented figure is exactly how a cost feature loses
            // credibility. Zero means "we do not know": MobilityCostCalculator declines
            // to cost EV trips rather than quoting a made-up number, and the moment a
            // real tariff lands in energyPrices/KE it takes over.
            electricityPerKwh = 0.0,
            source = PriceSource.BUNDLED,
            effectiveMonth = "2026-08",
            sourceName = "EPRA"
        )
    }
}

/**
 * What a trip costs in energy the user actually buys.
 *
 * [litres] and [kWh] are mutually exclusive — a trip is fuelled or charged, not both.
 */
data class TripCost(
    val litres: Double?,
    val kWh: Double?,
    /** Total in [currencyCode]. */
    val amount: Double,
    val currencyCode: String,
    /** Price per litre or per kWh used, so the figure is checkable. */
    val unitPrice: Double,
    val source: PriceSource,
    val effectiveMonth: String,
    val sourceName: String? = null,
    /**
     * True when the volume came from the user's own fill-ups rather than the
     * engine-displacement average. Drives whether the UI hedges or commits.
     */
    val isMeasured: Boolean = false,
    /** True when the volume used the economy figure the owner typed for their car. */
    val isOwnerStated: Boolean = false
)

/**
 * Turns a [Co2Estimate]'s energy figure into fuel volume and money.
 *
 * ## What this is not
 * `EnergyFactors` is explicitly "illustrative reference values… good enough to rank
 * modes against each other, not a certified LCA" — driving consumption comes from an
 * engine-displacement band times a body-type multiplier. That is a *class average*, and
 * two cars in the same band can differ by 30%.
 *
 * A CO₂ figure that is 30% out is invisible. A shilling figure that is 30% out gets
 * checked against a fuel receipt and destroys trust in every other number in the app.
 * So everything produced here must be presented as an estimate until a measured fuel
 * economy replaces the class average (see the fuel log, phase 3).
 *
 * The arithmetic itself is sound: 880 Wh/km for a 1.8–2.5 L car works out to
 * 9.1 L/100 km, which is a believable figure for that class.
 */
object MobilityCostCalculator {

    /**
     * Lower heating values. Petrol and diesel differ by about 10% per litre, which is
     * why a diesel's litres-per-km is lower than its energy-per-km would suggest.
     */
    const val PETROL_KWH_PER_LITRE = 9.7
    const val DIESEL_KWH_PER_LITRE = 10.7

    /**
     * Cost of [estimate], or null when there is no honest number to give.
     *
     * Null for walking, running and cycling (no purchased energy), and deliberately
     * null for **train and flying**: the user pays a fare, not a share of the vehicle's
     * energy bill, and quoting the latter as "cost" would be wrong by an order of
     * magnitude in either direction. Better to say nothing than to invent a ticket price.
     */
    fun costOf(
        estimate: Co2Estimate,
        profile: VehicleProfile,
        prices: EnergyPrices,
        measured: MeasuredEconomy? = null
    ): TripCost? {
        if (estimate.energyWh <= 0.0) return null
        val kWh = estimate.energyWh / 1000.0

        return when (estimate.activity) {
            ActivityType.DRIVING, ActivityType.MOTORCYCLE -> {
                val perLitre = when (profile.iceFuel) {
                    IceFuel.DIESEL -> prices.dieselPerLitre
                    else -> prices.petrolPerLitre
                }
                val kWhPerLitre = when (profile.iceFuel) {
                    IceFuel.DIESEL -> DIESEL_KWH_PER_LITRE
                    else -> PETROL_KWH_PER_LITRE
                }
                if (perLitre <= 0.0) return null
                // Three tiers, weakest last. A measured economy replaces the energy
                // model outright — once the user's own fill-ups say 11.4 L/100 km,
                // going back through an engine-displacement average would only
                // reintroduce the error the measurement exists to remove. A figure the
                // owner typed is likewise a claim about *this* car, and beats a class
                // average about cars in general.
                val ownedLPer100Km = profile.fuelEconomyKmPerL
                    ?.takeIf { it > 0.0 }
                    ?.let { 100.0 / it }
                val litres = when {
                    measured != null -> estimate.distanceKm * measured.lPer100Km / 100.0
                    ownedLPer100Km != null -> estimate.distanceKm * ownedLPer100Km / 100.0
                    else -> kWh / kWhPerLitre
                }
                TripCost(
                    litres = litres,
                    kWh = null,
                    amount = litres * perLitre,
                    currencyCode = prices.currencyCode,
                    unitPrice = perLitre,
                    source = prices.source,
                    effectiveMonth = prices.effectiveMonth,
                    sourceName = prices.sourceName,
                    isMeasured = measured != null,
                    isOwnerStated = measured == null && ownedLPer100Km != null
                )
            }

            ActivityType.ELECTRIC_VEHICLE -> if (prices.electricityPerKwh <= 0.0) null else TripCost(
                litres = null,
                kWh = kWh,
                amount = kWh * prices.electricityPerKwh,
                currencyCode = prices.currencyCode,
                unitPrice = prices.electricityPerKwh,
                source = prices.source,
                effectiveMonth = prices.effectiveMonth,
                sourceName = prices.sourceName
            )

            // Fare-based or unpowered — see the KDoc above.
            ActivityType.TRAIN,
            ActivityType.FLYING,
            ActivityType.WALKING,
            ActivityType.RUNNING,
            ActivityType.CYCLING,
            ActivityType.IDLE -> null
        }
    }
}
