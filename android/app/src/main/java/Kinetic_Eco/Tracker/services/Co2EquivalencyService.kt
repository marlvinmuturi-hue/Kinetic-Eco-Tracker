package Kinetic_Eco.Tracker.services

/**
 * CO2 Equivalency Service
 * Provides relatable comparisons for CO2 emissions and conservation.
 * Data from Impact CO₂ (https://impactco2.fr) - ADEME Base Carbone.
 * Source: https://github.com/incubateur-ademe/impactco2
 */
object Co2EquivalencyService {

    data class Equivalency(
        val icon: String,
        val label: String,
        val value: Double,
        val unit: String,
        val description: String
    )

    /** Impact CO₂ equivalents (kg CO2e per unit) - from impactco2.fr equivalents.csv */
    private data class ImpactEquivalent(val name: String, val kgCo2e: Double, val icon: String)

    private val IMPACT_CO2_EQUIVALENTS = listOf(
        ImpactEquivalent("Harry Potter marathon (streaming)", 0.63, "🎬"),
        ImpactEquivalent("Game of Thrones episode (streaming)", 0.0317, "📺"),
        ImpactEquivalent("1 hour video streaming", 0.064, "📺"),
        ImpactEquivalent("Traditional baguette", 0.78, "🥖"),
        ImpactEquivalent("Coffee", 0.64, "☕"),
        ImpactEquivalent("Tea", 0.044, "🍵"),
        ImpactEquivalent("Vegan meal", 0.39, "🥗"),
        ImpactEquivalent("Smartphone charger", 0.42, "📱"),
        ImpactEquivalent("Paris–Marseille TGV (round trip)", 4.4, "🚄"),
        ImpactEquivalent("Night camping", 1.4, "⛺"),
        ImpactEquivalent("Night in a hotel", 4.3, "🏨"),
        ImpactEquivalent("Friends series (full streaming)", 7.86, "📺"),
        ImpactEquivalent("Cheeseburger", 18.83, "🍔"),
        ImpactEquivalent("km by car (petrol)", 0.218, "🚗"),
        ImpactEquivalent("Paris–New York flight (round trip)", 2060.0, "✈️")
    )

    /**
     * Get top 3 equivalencies for net impact from Impact CO₂ data.
     * Positive net = conservation, negative net = emissions.
     */
    fun getNetImpactEquivalencies(netImpactKg: Double): List<Equivalency> {
        val absKg = kotlin.math.abs(netImpactKg)
        if (absKg < 0.001) return emptyList()

        val isEmission = netImpactKg < 0
        val prefix = if (isEmission) "Equivalent to" else "Equivalent to the CO₂ of"

        val scored = IMPACT_CO2_EQUIVALENTS
            .filter { absKg >= it.kgCo2e * 0.05 }
            .map { e ->
                val value = absKg / e.kgCo2e
                val desc = if (value < 0.1) {
                    "$prefix ${(value * 100).toInt()}% of ${e.name}"
                } else if (e.name.startsWith("km by")) {
                    "$prefix ${"%.1f".format(value)} ${e.name}"
                } else {
                    val suffix = if (value == 1.0) "" else "s"
                    "$prefix ${"%.1f".format(value)} ${e.name}$suffix"
                }
                val score = when {
                    value in 0.2..20.0 -> 100.0
                    value < 0.2 -> value * 300
                    else -> 80 / (value / 20)
                }
                Pair(
                    Equivalency(icon = e.icon, label = e.name, value = value, unit = "units", description = desc),
                    score
                )
            }

        return scored
            .sortedByDescending { (_, score) -> score }
            .take(3)
            .map { (eq, _) -> eq }
    }
}
