package Kinetic_Eco.Tracker.services

/**
 * CO2 Equivalency Service
 *
 * Turns abstract kg-CO₂ numbers into concrete, relatable comparisons across
 * food, drinks, transport, digital habits, household appliances, lifestyle
 * items, and nature-based absorption — so users always get fresh, meaningful
 * context for their impact.
 *
 * Sources:
 *  - ADEME Base Carbone / impactco2.fr — food, drink, transport, household
 *  - UK DEFRA GHG Conversion Factors (2023) — transport, hotel, flights
 *  - Carbon Trust (2021) — digital/streaming (revised methodology)
 *  - Mike Berners-Lee, "How Bad Are Bananas?" (2nd ed. 2020) — lifestyle
 *  - Our World in Data — food, transport cross-reference
 *  - IPCC AR6 / FAO — nature absorption rates
 */
object Co2EquivalencyService {

    /**
     * Public, UI-facing equivalency. `funFact` is an optional second line of
     * context shown below the main description.
     */
    data class Equivalency(
        val icon: String,
        val label: String,
        val value: Double,
        val unit: String,
        val description: String,
        val funFact: String? = null
    )

    /** Internal item categories — used to enforce variety in the result set. */
    private enum class Category {
        FOOD,
        DRINK,
        TRANSPORT,
        DIGITAL,
        HOUSEHOLD,
        LIFESTYLE,
        NATURE       // CO₂ absorption items — only shown for net-positive impact
    }

    /**
     * One row in the dataset.
     *
     * [isPositive] flips the description framing from "equivalent to emitting X"
     * to "that's the CO₂ absorbed by X" — used for tree/forest items.
     */
    private data class ImpactEquivalent(
        val singular: String,
        val plural: String,
        val kgCo2e: Double,
        val icon: String,
        val category: Category,
        val funFact: String? = null,
        val isPositive: Boolean = false
    )

    private val ITEMS = listOf(

        // ── FOOD ─────────────────────────────────────────────────────────────
        ImpactEquivalent("vegan meal", "vegan meals", 0.39, "🥗", Category.FOOD,
            funFact = "Whole plant-based meals emit 10–50× less CO₂ than beef-based ones"),
        ImpactEquivalent("vegetarian meal", "vegetarian meals", 0.51, "🥦", Category.FOOD),
        ImpactEquivalent("chicken meal", "chicken meals", 1.65, "🍗", Category.FOOD,
            funFact = "Chicken produces ~4× less CO₂ per kg than beef"),
        ImpactEquivalent("beef steak (200 g)", "beef steaks (200 g each)", 7.0, "🥩", Category.FOOD,
            funFact = "Beef produces ~20× more CO₂ than lentils per gram of protein"),
        ImpactEquivalent("cheeseburger", "cheeseburgers", 18.83, "🍔", Category.FOOD),
        ImpactEquivalent("slice of pizza", "slices of pizza", 1.0, "🍕", Category.FOOD),
        ImpactEquivalent("bowl of pasta", "bowls of pasta", 0.83, "🍝", Category.FOOD),
        ImpactEquivalent("traditional baguette", "traditional baguettes", 0.78, "🥖", Category.FOOD),
        ImpactEquivalent("croissant", "croissants", 0.50, "🥐", Category.FOOD),
        ImpactEquivalent("apple", "apples", 0.05, "🍎", Category.FOOD),
        ImpactEquivalent("banana", "bananas", 0.08, "🍌", Category.FOOD),
        ImpactEquivalent("sushi meal (8 pieces)", "sushi meals (8 pieces)", 2.5, "🍣", Category.FOOD,
            funFact = "Wild-caught fish has ~3× lower carbon footprint than farmed salmon"),
        ImpactEquivalent("dozen eggs", "dozens of eggs", 2.7, "🥚", Category.FOOD,
            funFact = "Free-range eggs can emit up to 20% more CO₂ than caged due to lower feed efficiency"),
        ImpactEquivalent("avocado", "avocados", 0.85, "🥑", Category.FOOD,
            funFact = "Growing one avocado requires ~200 litres of water"),
        ImpactEquivalent("chocolate bar (50 g)", "chocolate bars (50 g)", 3.4, "🍫", Category.FOOD,
            funFact = "Cocoa farming drives ~2 million tonnes of deforestation annually"),
        ImpactEquivalent("ice cream scoop", "ice cream scoops", 0.30, "🍦", Category.FOOD),

        // ── DRINK ────────────────────────────────────────────────────────────
        ImpactEquivalent("cup of coffee", "cups of coffee", 0.64, "☕", Category.DRINK,
            funFact = "Espresso has ~3× lower carbon footprint than a latte due to milk content"),
        ImpactEquivalent("cup of tea", "cups of tea", 0.044, "🍵", Category.DRINK),
        ImpactEquivalent("pint of beer", "pints of beer", 0.85, "🍺", Category.DRINK),
        ImpactEquivalent("glass of wine", "glasses of wine", 0.40, "🍷", Category.DRINK),
        ImpactEquivalent("litre of milk", "litres of milk", 1.4, "🥛", Category.DRINK,
            funFact = "Oat milk emits ~80% less CO₂ per litre than cow's milk"),
        ImpactEquivalent("bottled water (1.5 L)", "bottles of water (1.5 L)", 0.34, "💧", Category.DRINK),

        // ── TRANSPORT ────────────────────────────────────────────────────────
        ImpactEquivalent("km in a petrol car", "km in a petrol car", 0.218, "🚗", Category.TRANSPORT),
        ImpactEquivalent("km in a diesel car", "km in a diesel car", 0.171, "🚙", Category.TRANSPORT),
        ImpactEquivalent("km in an electric car", "km in an electric car", 0.103, "⚡", Category.TRANSPORT,
            funFact = "EVs emit ~3× less CO₂ per km even when charged from a typical mixed grid"),
        ImpactEquivalent("km on a motorbike", "km on a motorbike", 0.155, "🏍️", Category.TRANSPORT),
        ImpactEquivalent("km by city bus", "km by city bus", 0.103, "🚌", Category.TRANSPORT),
        ImpactEquivalent("km by metro/subway", "km by metro/subway", 0.0035, "🚇", Category.TRANSPORT),
        ImpactEquivalent("km by intercity train", "km by intercity train", 0.0149, "🚆", Category.TRANSPORT),
        ImpactEquivalent("Paris–Marseille TGV (round trip)", "Paris–Marseille TGV round trips", 4.4, "🚄", Category.TRANSPORT),
        ImpactEquivalent("hour on a short-haul flight", "hours on a short-haul flight", 100.0, "✈️", Category.TRANSPORT),
        ImpactEquivalent("Paris–New York flight (round trip)", "Paris–New York flights (round trip)", 2060.0, "🛫", Category.TRANSPORT,
            funFact = "Aviation accounts for ~2.5% of global CO₂ but ~3.5% of effective climate warming"),

        // ── DIGITAL ──────────────────────────────────────────────────────────
        // Streaming rate updated to 0.036 kg/hr (Carbon Trust 2021 revised methodology,
        // average across device types). The 2019 Shift Project figure of ~0.064 kg/hr
        // significantly overstated data-centre energy; your device dominates the total.
        ImpactEquivalent("hour of video streaming", "hours of video streaming", 0.036, "📺", Category.DIGITAL,
            funFact = "Your screen uses ~97% of streaming's energy — the data centre is a small fraction"),
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
        ImpactEquivalent("tumble dryer cycle", "tumble dryer cycles", 1.5, "👕", Category.HOUSEHOLD,
            funFact = "Line-drying your clothes instead saves ~150 kg CO₂ per year"),
        ImpactEquivalent("hour of electric heating", "hours of electric heating", 1.2, "🔥", Category.HOUSEHOLD),

        // ── LIFESTYLE ────────────────────────────────────────────────────────
        ImpactEquivalent("night camping", "nights camping", 1.4, "⛺", Category.LIFESTYLE),
        ImpactEquivalent("night in a hotel", "nights in a hotel", 4.3, "🏨", Category.LIFESTYLE),
        ImpactEquivalent("newspaper", "newspapers", 0.2, "📰", Category.LIFESTYLE),
        ImpactEquivalent("paperback book", "paperback books", 1.0, "📖", Category.LIFESTYLE),
        ImpactEquivalent("cotton t-shirt", "cotton t-shirts", 8.7, "👔", Category.LIFESTYLE,
            funFact = "The fashion industry produces ~10% of global CO₂ emissions"),
        ImpactEquivalent("pair of jeans", "pairs of jeans", 33.4, "👖", Category.LIFESTYLE,
            funFact = "Making one pair of jeans uses ~7,000 litres of water"),
        ImpactEquivalent("pair of running shoes", "pairs of running shoes", 14.0, "👟", Category.LIFESTYLE),

        // ── NATURE ───────────────────────────────────────────────────────────
        // Absorption rates from IPCC AR6 / FAO: mature broadleaf tree ~21 kg CO₂/year.
        // These items only appear for net-positive (conservation) impact.
        ImpactEquivalent(
            singular = "day of a mature tree absorbing CO₂",
            plural   = "days of a mature tree absorbing CO₂",
            kgCo2e   = 0.058,
            icon     = "🌳",
            category = Category.NATURE,
            funFact  = "A mature oak absorbs ~21 kg of CO₂ per year — about the weight of a car tyre",
            isPositive = true
        ),
        ImpactEquivalent(
            singular = "week of a mature tree absorbing CO₂",
            plural   = "weeks of a mature tree absorbing CO₂",
            kgCo2e   = 0.403,
            icon     = "🌲",
            category = Category.NATURE,
            isPositive = true
        ),
        ImpactEquivalent(
            singular = "month of a mature tree absorbing CO₂",
            plural   = "months of a mature tree absorbing CO₂",
            kgCo2e   = 1.75,
            icon     = "🌿",
            category = Category.NATURE,
            isPositive = true
        ),
        ImpactEquivalent(
            singular = "year of a mature tree absorbing CO₂",
            plural   = "years of a mature tree absorbing CO₂",
            kgCo2e   = 21.0,
            icon     = "🌳",
            category = Category.NATURE,
            funFact  = "Globally, forests absorb ~2.6 billion tonnes of CO₂ per year (FAO 2022)",
            isPositive = true
        )
    )

    /**
     * Returns up to [limit] equivalencies for a net-impact value, biased toward
     * variety across categories.
     *
     * Nature (tree absorption) items are only included for net-positive values —
     * they don't make sense as a comparison for net emissions.
     *
     * @param netImpactKg positive = conservation, negative = emissions
     * @param rotationSeed when ≠ 0, rotates category order by `seed % n`.
     *                     Pass `daysSinceEpoch` for once-per-day variety.
     * @param limit max equivalencies to return. Pass [Int.MAX_VALUE] to get every
     *              viable category leader (used by the dashboard tap-to-cycle).
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

        data class Scored(val item: ImpactEquivalent, val multiplier: Double, val score: Double)

        val scored = ITEMS
            .filter { if (isEmission) !it.isPositive else true }
            .mapNotNull { e ->
                if (e.kgCo2e <= 0.0) return@mapNotNull null
                val multiplier = absKg / e.kgCo2e
                val score = scoreFitness(multiplier)
                if (score < 5.0) null else Scored(e, multiplier, score)
            }
        if (scored.isEmpty()) return emptyList()

        val perCategory = scored
            .groupBy { it.item.category }
            .map { (_, list) -> list.maxBy { it.score } }
            .sortedByDescending { it.score }

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

    private fun scoreFitness(multiplier: Double): Double = when {
        multiplier <= 0.0  -> 0.0
        multiplier in 0.2..20.0 -> 100.0
        multiplier < 0.2   -> multiplier * 500.0
        else               -> 2000.0 / multiplier
    }

    private fun formatEquivalency(
        item: ImpactEquivalent,
        multiplier: Double,
        prefix: String
    ): Equivalency {
        val description = when {
            item.isPositive -> {
                // Nature items: "That's the CO₂ absorbed by X days of a mature tree"
                when {
                    multiplier < 0.1 ->
                        "That's ${(multiplier * 100).toInt()}% of a ${item.singular}"
                    kotlin.math.abs(multiplier - 1.0) < 0.05 ->
                        "That's 1 ${item.singular}"
                    else ->
                        "That's the CO₂ absorbed by ${"%.1f".format(multiplier)} ${item.plural}"
                }
            }
            multiplier < 0.1 ->
                "$prefix ${(multiplier * 100).toInt()}% of a ${item.singular}"
            kotlin.math.abs(multiplier - 1.0) < 0.05 ->
                "$prefix 1 ${item.singular}"
            else ->
                "$prefix ${"%.1f".format(multiplier)} ${item.plural}"
        }
        return Equivalency(
            icon        = item.icon,
            label       = item.singular,
            value       = multiplier,
            unit        = "units",
            description = description,
            funFact     = item.funFact
        )
    }
}