package Kinetic_Eco.Tracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [Co2Calculator].
 *
 * These are cheap only because the calculator is deliberately free of Android,
 * Room and prefs dependencies — that property is worth preserving.
 */
class Co2CalculatorTest {

    private val delta = 0.0001

    private fun profile(
        ccBand: DrivingEngineCcBand = DrivingEngineCcBand.CC_1801_2500,
        body: VehicleBodyType = VehicleBodyType.SEDAN,
        fuel: IceFuel = IceFuel.PETROL,
        evClass: ElectricVehicleClass = ElectricVehicleClass.CAR,
        motor: ElectricMotorPowerBand = ElectricMotorPowerBand.KW_10_TO_60
    ) = VehicleProfile.DEFAULT.copy(
        drivingCcBand = ccBand,
        bodyType = body,
        iceFuel = fuel,
        electricVehicleClass = evClass,
        electricMotorPower = motor
    )

    // ── CO₂ ───────────────────────────────────────────────────────────────────

    @Test
    fun `driving emits and never saves`() {
        val e = Co2Calculator.estimate(ActivityType.DRIVING, 40.0, profile())
        // 0.21 kg/km reference sedan
        assertEquals(8.4, e.emittedKg, delta)
        assertEquals(0.0, e.savedKg, delta)
        assertFalse(e.isSaving)
    }

    @Test
    fun `walking saves and never emits`() {
        val e = Co2Calculator.estimate(ActivityType.WALKING, 10.0, profile())
        assertEquals(0.0, e.emittedKg, delta)
        assertEquals(2.1, e.savedKg, delta)
        assertTrue(e.isSaving)
    }

    @Test
    fun `body type scales the driving factor`() {
        val sedan = Co2Calculator.estimate(ActivityType.DRIVING, 100.0, profile(body = VehicleBodyType.SEDAN))
        val pickup = Co2Calculator.estimate(ActivityType.DRIVING, 100.0, profile(body = VehicleBodyType.PICKUP))
        // PICKUP carries a 1.30 multiplier over SEDAN's 1.00
        assertEquals(sedan.emittedKg * 1.30, pickup.emittedKg, delta)
    }

    // ── Vehicle energy ────────────────────────────────────────────────────────

    @Test
    fun `driving energy matches the stated band factor`() {
        val e = Co2Calculator.estimate(ActivityType.DRIVING, 40.0, profile())
        // CC_1801_2500 = 880 Wh/km, sedan 1.0x, petrol 1.0x
        assertEquals(880.0, e.energyWhPerKm, delta)
        assertEquals(35_200.0, e.energyWh, delta)
    }

    @Test
    fun `ev energy is far below combustion for the same distance`() {
        val petrol = Co2Calculator.estimate(ActivityType.DRIVING, 40.0, profile())
        val ev = Co2Calculator.estimate(ActivityType.ELECTRIC_VEHICLE, 40.0, profile())
        // 165 Wh/km at the reference motor band
        assertEquals(6_600.0, ev.energyWh, delta)
        assertTrue(ev.energyWh < petrol.energyWh / 4)
    }

    @Test
    fun `diesel energy premium is smaller than its co2 premium`() {
        val petrol = Co2Calculator.estimate(ActivityType.DRIVING, 100.0, profile(fuel = IceFuel.PETROL))
        val diesel = Co2Calculator.estimate(ActivityType.DRIVING, 100.0, profile(fuel = IceFuel.DIESEL))
        val co2Ratio = diesel.emittedKg / petrol.emittedKg
        val energyRatio = diesel.energyWh / petrol.energyWh
        assertEquals(1.10, co2Ratio, delta)
        assertEquals(1.05, energyRatio, delta)
        assertTrue(energyRatio < co2Ratio)
    }

    @Test
    fun `human powered modes report no vehicle energy`() {
        listOf(ActivityType.WALKING, ActivityType.RUNNING, ActivityType.CYCLING).forEach { mode ->
            val e = Co2Calculator.estimate(mode, 25.0, profile())
            assertEquals("$mode should have no vehicle energy", 0.0, e.energyWh, delta)
            assertFalse(e.hasVehicleEnergy)
        }
    }

    @Test
    fun `train saves co2 but still consumes energy`() {
        val e = Co2Calculator.estimate(ActivityType.TRAIN, 100.0, profile())
        assertTrue("rail should score as a saving", e.isSaving)
        assertTrue("rail still burns energy", e.energyWh > 0.0)
    }

    // ── Input handling ────────────────────────────────────────────────────────

    @Test
    fun `negative and non finite distances collapse to zero`() {
        listOf(-5.0, Double.NaN, Double.NEGATIVE_INFINITY).forEach { bad ->
            val e = Co2Calculator.estimate(ActivityType.DRIVING, bad, profile())
            assertEquals(0.0, e.distanceKm, delta)
            assertEquals(0.0, e.emittedKg, delta)
            assertEquals(0.0, e.energyWh, delta)
        }
    }

    @Test
    fun `distance is clamped to the maximum`() {
        val e = Co2Calculator.estimate(ActivityType.DRIVING, 999_999.0, profile())
        assertEquals(Co2Calculator.MAX_DISTANCE_KM, e.distanceKm, delta)
    }

    @Test
    fun `metric input parses as kilometres`() {
        assertEquals(40.0, Co2Calculator.parseDistanceToKm("40", UnitSystem.METRIC)!!, delta)
        assertEquals(40.5, Co2Calculator.parseDistanceToKm("40.5", UnitSystem.METRIC)!!, delta)
    }

    @Test
    fun `comma is accepted as a decimal separator`() {
        assertEquals(40.5, Co2Calculator.parseDistanceToKm("40,5", UnitSystem.METRIC)!!, delta)
    }

    @Test
    fun `imperial input converts miles to kilometres`() {
        assertEquals(16.09344, Co2Calculator.parseDistanceToKm("10", UnitSystem.IMPERIAL)!!, delta)
    }

    @Test
    fun `unparseable and out of range input returns null`() {
        listOf("", "   ", "abc", "-3", "0", "1e9").forEach { bad ->
            assertNull("'$bad' should not parse", Co2Calculator.parseDistanceToKm(bad, UnitSystem.METRIC))
        }
    }

    // ── Agreement with the tracker ────────────────────────────────────────────

    @Test
    fun `metres variant agrees with the kilometres variant`() {
        val fromKm = Co2Calculator.estimate(ActivityType.DRIVING, 12.0, profile())
        val fromM = Co2Calculator.estimateFromMeters(12_000.0, ActivityType.DRIVING, profile())
        assertEquals(fromKm.emittedKg, fromM.emittedKg, delta)
        assertEquals(fromKm.energyWh, fromM.energyWh, delta)
    }
}