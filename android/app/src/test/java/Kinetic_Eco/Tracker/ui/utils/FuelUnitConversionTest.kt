package Kinetic_Eco.Tracker.ui.utils

import Kinetic_Eco.Tracker.data.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fuel unit conversions.
 *
 * Worth pinning rather than eyeballing, because every one of these has an inverse used on a
 * *write* path: the profile field, the fuel log, and the price override all convert typed
 * input back to canonical units before storing. An asymmetry between a converter and its
 * inverse would not fail a build or look wrong on screen — it would quietly rewrite the
 * user's own figures a little further from the truth on every edit.
 */
class FuelUnitConversionTest {

    private val metric = FuelUnit.LITRES_KM_PER_L
    private val us = FuelUnit.US_GALLONS_MPG

    // ── Unit selection ──────────────────────────────────────────────────────

    @Test
    fun `fuel unit follows the distance unit`() {
        assertEquals(metric, UnitSystem.METRIC.toFuelUnit())
        assertEquals(metric, UnitSystem.METRIC_WH.toFuelUnit())
        assertEquals(us, UnitSystem.IMPERIAL.toFuelUnit())
        assertEquals(us, UnitSystem.IMPERIAL_KCAL.toFuelUnit())
    }

    // ── Known values ────────────────────────────────────────────────────────

    @Test
    fun `12 km per litre is 28 point 2 US MPG`() {
        assertEquals(28.23, convertFuelEconomy(12.0, us), 0.01)
    }

    @Test
    fun `US gallon is used, not the imperial gallon`() {
        // The imperial gallon would give 33.9 here — a 21% overstatement of the same car.
        assertTrue(convertFuelEconomy(12.0, us) < 30.0)
        assertEquals(3.785411784, LITRES_PER_US_GALLON, 1e-9)
    }

    @Test
    fun `consumption and economy agree on the same car`() {
        // 12 km/L is 8.333 L/100km. Both routes must produce the same MPG, or the fuel log
        // and the calculator would disagree about one vehicle.
        val viaEconomy = convertFuelEconomy(12.0, us)
        val viaConsumption = convertConsumption(100.0 / 12.0, us)
        assertEquals(viaEconomy, viaConsumption, 0.001)
    }

    @Test
    fun `a litre price becomes a larger gallon price`() {
        // 200 per litre is 757 per US gallon. Getting this backwards would make fuel look
        // roughly a quarter of its real cost.
        assertEquals(757.08, convertPricePerVolume(200.0, us), 0.01)
    }

    @Test
    fun `volume converts litres to the smaller gallon count`() {
        assertEquals(1.0, convertFuelVolume(3.785411784, us), 1e-9)
    }

    // ── Round trips: every converter with a write path ──────────────────────

    @Test
    fun `economy round trips through its inverse`() {
        for (kmPerL in listOf(3.0, 8.5, 12.0, 20.0, 45.0)) {
            val there = convertFuelEconomy(kmPerL, us)
            assertEquals(kmPerL, fuelEconomyToKmPerL(there, us), 1e-9)
        }
    }

    @Test
    fun `volume round trips through its inverse`() {
        for (litres in listOf(0.5, 12.0, 40.0, 300.0)) {
            assertEquals(litres, fuelVolumeToLitres(convertFuelVolume(litres, us), us), 1e-9)
        }
    }

    @Test
    fun `price round trips through its inverse`() {
        for (perLitre in listOf(1.5, 42.0, 199.99)) {
            assertEquals(perLitre, pricePerVolumeToPerLitre(convertPricePerVolume(perLitre, us), us), 1e-9)
        }
    }

    // ── Metric is a no-op, not an accidental conversion ──────────────────────

    @Test
    fun `metric leaves every quantity untouched`() {
        assertEquals(12.0, convertFuelEconomy(12.0, metric), 0.0)
        assertEquals(40.0, convertFuelVolume(40.0, metric), 0.0)
        assertEquals(200.0, convertPricePerVolume(200.0, metric), 0.0)
        assertEquals(8.3, convertConsumption(8.3, metric), 0.0)
        assertEquals(40.0, fuelVolumeToLitres(40.0, metric), 0.0)
    }

    // ── Guards ──────────────────────────────────────────────────────────────

    @Test
    fun `zero consumption does not divide by zero`() {
        // A tank covering infinite distance is not a number the UI can render.
        assertEquals(0.0, convertConsumption(0.0, us), 0.0)
        assertEquals(0.0, convertConsumption(-1.0, us), 0.0)
    }
}
