package Kinetic_Eco.Tracker.services

/**
 * CO2 Equivalency Service
 *
 * Turns abstract kg-CO₂ numbers into concrete, relatable comparisons spread
 * across multiple categories — food, drinks, drives, trips, digital habits,
 * household appliances, and lifestyle items — so the dashboard never feels
 * like "13% of a Game of Thrones episode every single time".
 *
 * Sources for kg CO₂e values:
 *  - Impact CO₂ / ADEME Base Carbone (https://impactco2.fr) — primary source
 *  - Mike Berners-Lee, "How Bad Are Bananas?" — supplementary food/lifestyle items
 *  - Our World in Data — transport modes
 *
 * Public API ([Equivalency], [getNetImpactEquivalencies]) is unchanged so
 * existing callers (DashboardScreen, AnalysisScreen, AnalyticsScreen,
 * CarbonTripHistoryScreen) keep working without modification.
 */
object Co2EquivalencyService {

    /**
     * Public, UI-facing equivalency. `icon` is rendered as-is (emoji), and
     * `description` is the full sentence shown to the user.
     */
    data class Equivalency(
        val icon: String,
        val label: String,
        val value: Double,
        val unit: String,
        val description: String
    )

    /** Internal item categories — used to enforce variety in the result set. */
    private enum class Category {
        FOOD,
        DRINK,
        TRANSPORT,    // cars, motorbikes, buses, metros, trains, planes
        DIGITAL,      // streaming, charging, devices
        HOUSEHOLD,    // appliances, energy at home
        LIFESTYLE     // hotels, clothes, books, etc.
    }

    /**
     * One row in the dataset. `singular` and `plural` are kept explicit so the
     * generated copy never reads awkwardly ("3.4 cup of coffees"). For items
     * that don't pluralise — e.g. "km in a petrol car" — the two forms are
     * identical.
     */
    private data class ImpactEquivalent(
        val singular: String,
        val plural: String,
        val kgCo2e: Double,
        val icon: String,
        val category: Category
    )

    private val ITEMS = listOf(
        // ── FOOD ─────────────────────────────────────────────────────────────
        ImpactEquivalent("vegan meal", "vegan meals", 0.39, "🥗", Category.FOOD),
        ImpactEquivalent("vegetarian meal", "vegetarian meals", 0.51, "🥦", Category.FOOD),
        ImpactEquivalent("chicken meal", "chicken meals", 1.65, "🍗", Category.FOOD),
        ImpactEquivalent("beef steak (200 g)", "beef steaks (200 g each)", 7.0, "🥩", Category.FOOD),
        ImpactEquivalent("cheeseburger", "cheeseburgers", 18.83, "🍔", Category.FOOD),
        ImpactEquivalent("slice of pizza", "slices of pizza", 1.0, "🍕", Category.FOOD),
        ImpactEquivalent("bowl of pasta", "bowls of pasta", 0.83, "🍝", Category.FOOD),
        ImpactEquivalent("traditional baguette", "traditional baguettes", 0.78, "🥖", Category.FOOD),
        ImpactEquivalent("croissant", "croissants", 0.50, "🥐", Category.FOOD),
        ImpactEquivalent("apple", "apples", 0.05, "🍎", Category.FOOD),
        ImpactEquivalent("banana", "bananas", 0.08, "🍌", Category.FOOD),

        // ── DRINK ────────────────────────────────────────────────────────────
        ImpactEquivalent("cup of coffee", "cups of coffee", 0.64, "☕", Category.DRINK),
        ImpactEquivalent("cup of tea", "cups of tea", 0.044, "🍵", Category.DRINK),
        ImpactEquivalent("pint of beer", "pints of beer", 0.85, "🍺", Category.DRINK),
        ImpactEquivalent("glass of wine", "glasses of wine", 0.40, "🍷", Category.DRINK),
        ImpactEquivalent("litre of milk", "litres of milk", 1.4, "🥛", Category.DRINK),
        ImpactEquivalent("bottled water (1.5 L)", "bottles of water (1.5 L)", 0.34, "💧", Category.DRINK),

        // ── TRANSPORT (drives & trips) ───────────────────────────────────────
        ImpactEquivalent("km in a petrol car", "km in a petrol car", 0.218, "🚗", Category.TRANSPORT),
        ImpactEquivalent("km in a diesel car", "km in a diesel car", 0.171, "🚙", Category.TRANSPORT),
        ImpactEquivalent("km in an electric car", "km in an electric car", 0.103, "⚡", Category.TRANSPORT),
        ImpactEquivalent("km on a motorbike", "km on a motorbike", 0.155, "🏍️", Category.TRANSPORT),
        ImpactEquivalent("km by city bus", "km by city bus", 0.103, "🚌", Category.TRANSPORT),
        ImpactEquivalent("km by metro/subway", "km by metro/subway", 0.0035, "🚇", Category.TRANSPORT),
        ImpactEquivalent("km by intercity train", "km by intercity train", 0.0149, "🚆", Category.TRANSPORT),
        ImpactEquivalent("Paris–Marseille TGV (round trip)", "Paris–Marseille TGV round trips", 4.4, "🚄", Category.TRANSPORT),
        ImpactEquivalent("hour on a short-haul flight", "hours on a short-haul flight", 100.0, "✈️", Category.TRANSPORT),
        ImpactEquivalent("Paris–New York flight (round trip)", "Paris–New York flights (round trip)", 2060.0, "🛫", Category.TRANSPORT),

        // ── DIGITAL ──────────────────────────────────────────────────────────
        ImpactEquivalent("hour of video streaming", "hours of video streaming", 0.064, "📺", Category.DIGITAL),
        ImpactEquivalent("hour of music streaming", "hours of music streaming", 0.0055, "🎧", Category.DIGITAL),
        ImpactEquivalent("hour of laptop use", "hours of laptop use", 0.032, "💻", Category.DIGITAL),
        ImpactEquivalent("smartphone charge", "smartphone charges", 0.005, "📱", Category.DIGITAL),
        ImpactEquivalent("Game of Thrones episode (streaming)", "Game of Thrones episodes (streaming)", 0.0317, "🎬", Category.DIGITAL),
        ImpactEquivalent("Harry Potter marathon (streaming)", "Harry Potter marathons (streaming)", 0.63, "🎥", Category.DIGITAL),
        ImpactEquivalent("Friends series (full streaming)", "Friends series (full streaming)", 7.86, "📽️", Category.DIGITAL),

        // ── HOUSEHOLD ────────────────────────────────────────────────────────
        ImpactEquivalent("hour of A/C use", "hours of A/C use", 0.5, "❄️", Category.HOUSEHOLD),
        ImpactEquivalent("dishwasher cycle", "dishwasher cycles", 0.4, "🍽️", Category.HOUSEHOLD),
        ImpactEquivalent("washing machine cycle", "washing machine cycles", 0.6, "🫧", Category.HOUSEHOLD),
        ImpactEquivalent("tumble dryer cycle", "tumble dryer cycles", 1.5, "👕", Category.HOUSEHOLD),
        ImpactEquivalent("hour of electric heating", "hours of electric heating", 1.2, "🔥", Category.HOUSEHOLD),

        // ── LIFESTYLE ────────────────────────────────────────────────────────
        ImpactEquivalent("night camping", "nights camping", 1.4, "⛺", Category.LIFESTYLE),
        ImpactEquivalent("night in a hotel", "nights in a hotel", 4.3, "🏨", Category.LIFESTYLE),
        ImpactEquivalent("newspaper", "newspapers", 0.2, "📰", Category.LIFESTYLE),
        ImpactEquivalent("paperback book", "paperback books", 1.0, "📖", Category.LIFESTYLE),
        ImpactEquivalent("cotton t-shirt", "cotton t-shirts", 8.7, "👔", Category.LIFESTYLE),
        ImpactEquivalent("pair of jeans", "pairs of jeans", 33.4, "👖", Category.LIFESTYLE),
        ImpactEquivalent("pair of running shoes", "pairs of running shoes", 14.0, "👟", Category.LIFESTYLE)
    )

    /**
     * Returns up to 3 equivalencies for a net-impact value, *biased toward
     * variety*. The algorithm:
     *
     *  1. Scores every item by how cleanly the ratio (`absKg / item.kgCo2e`)
     *     fits a comfortable display range (~0.2–20×).
     *  2. Groups scored items by category and keeps the best per category —
     *     this is what guarantees we don't return three streaming items in a
     *     row.
     *  3. Sorts categories by their leader's score, optionally rotates by
     *     [rotationSeed] so callers like the dashboard hero (which only shows
     *     one) get a fresh category each day.
     *  4. Takes up to 3 categories and formats their leader as an [Equivalency].
     *
     * @param netImpactKg positive = conservation, negative = emissions
     * @param rotationSeed when ≠ 0, rotates the category order by `seed % n`.
     *                     Pass `daysSinceEpoch` for once-per-day variety; pass 0
     *                     for the canonical (highest-scored-first) order.
     * @param limit max number of equivalencies to return. Default 3 keeps the
     *              existing list-style UIs unchanged; pass [Int.MAX_VALUE] (or
     *              any large number) to receive every viable category leader,
     *              which is what the dashboard hero uses for "tap to cycle".
     */
    fun getNetImpactEquivalencies(
        netImpactKg: Double,
        rotationSeed: Int = 0,
        limit: Int = 3
    ): List<Equivalency> {
        val absKg = kotlin.math.abs(netImpactKg)
        if (absKg < 0.001) return emptyList()

        val isEmission = netImpactKg < 0
        val prefix = if (isEmission) "Equivalent to" else "Equivalent to the CO₂ of"

        // Score every item, drop very poor fits.
        data class Scored(val item: ImpactEquivalent, val multiplier: Double, val score: Double)

        val scored = ITEMS.mapNotNull { e ->
            if (e.kgCo2e <= 0.0) return@mapNotNull null
            val multiplier = absKg / e.kgCo2e
            val score = scoreFitness(multiplier)
            if (score < 5.0) null else Scored(e, multiplier, score)
        }
        if (scored.isEmpty()) return emptyList()

        // Best item per category — this is the variety-enforcement step.
        val perCategory = scored
            .groupBy { it.item.category }
            .map { (_, list) -> list.maxBy { it.score } }
            .sortedByDescending { it.score }

        // Optional daily rotation so a hero card showing only the first item
        // doesn't display the same category every single day.
        val n = perCategory.size
        val rotated = if (rotationSeed != 0 && n > 1) {
            val k = ((rotationSeed % n) + n) % n
            perCategory.drop(k) + perCategory.take(k)
        } else {
            perCategory
        }

        return rotated.take(limit.coerceAtLeast(0)).map { (item, multiplier, _) ->
            formatEquivalency(item, multiplier, prefix)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * How comfortable does this multiplier read on screen?
     *   - 0.2..20× is the sweet spot ("3.4 cups of coffee", "0.4 cheeseburgers")
     *   - smaller fractions still readable as "X% of Y"
     *   - very large multipliers (1000+) are unhelpful ("1290 cups of tea")
     */
    private fun scoreFitness(multiplier: Double): Double = when {
        multiplier <= 0.0 -> 0.0
        multiplier in 0.2..20.0 -> 100.0
        multiplier < 0.2 -> multiplier * 500.0          // 0.05 → 25, 0.1 → 50, 0.15 → 75
        else -> 2000.0 / multiplier                     // 50 → 40, 100 → 20, 500 → 4
    }

    private fun formatEquivalency(
        item: ImpactEquivalent,
        multiplier: Double,
        prefix: String
    ): Equivalency {
        val description = when {
            multiplier < 0.1 -> {
                // "13% of a cheeseburger" — note the article so it reads naturally.
                "$prefix ${(multiplier * 100).toInt()}% of a ${item.singular}"
            }
            kotlin.math.abs(multiplier - 1.0) < 0.05 -> {
                // Round trip: "Equivalent to the CO₂ of 1 cheeseburger" — no decimal,
                // singular form, more natural than "1.0 cheeseburgers".
                "$prefix 1 ${item.singular}"
            }
            else -> {
                "$prefix ${"%.1f".format(multiplier)} ${item.plural}"
            }
        }
        return Equivalency(
            icon = item.icon,
            label = item.singular,
            value = multiplier,
            unit = "units",
            description = description
        )
    }
}
