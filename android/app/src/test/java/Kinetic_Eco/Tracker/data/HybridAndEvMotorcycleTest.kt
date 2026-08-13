package Kinetic_Eco.Tracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The electric-motorcycle class and the two hybrid fuel types.
 *
 * [CO2Factors.getFactor] and [EnergyFactors.getWhPerKm] are maintained as parallel `when`
 * blocks that the compiler cannot check against each other — the file says as much. These
 * tests are what actually holds them in step: every case below asserts the two agree about
 * which branch a profile lands in.
 */
class HybridAndEvMotorcycleTest {

    private fun profile(
        fuel: PrimaryFuelType,
        cc: DrivingEngineCcBand = DrivingEngineCcBand.CC_1801_2500,
        body: VehicleBodyType = VehicleBodyType.SEDAN,
        ice: IceFuel = IceFuel.PETROL,
        motor: ElectricMotorPowerBand = ElectricMotorPowerBand.KW_10_TO_60,
        evClass: ElectricVehicleClass = ElectricVehicleClass.CAR
    ) = VehicleProfile.DEFAULT.copy(
        primaryFuelType = fuel,
        drivingCcBand = cc,
        bodyType = body,
        iceFuel = ice,
        electricMotorPower = motor,
        electricVehicleClass = evClass
    )

    // ── Electric motorcycle ─────────────────────────────────────────────────

    @Test
    fun `electric motorcycle class states 40 Wh per km`() {
        assertEquals(40.0, ElectricVehicleClass.MOTORCYCLE.baseWhPerKm, 0.0)
    }

    @Test
    fun `motorcycle on electricity draws the motorcycle class, not the scooter class`() {
        // The mid band is 1.0x, so the base shows through unchanged.
        val wh = EnergyFactors.getWhPerKm(ActivityType.MOTORCYCLE, profile(PrimaryFuelType.ELECTRIC))
        assertEquals(40.0, wh, 1e-9)
        assertTrue(wh > ElectricVehicleClass.TWO_WHEELER.baseWhPerKm)
    }

    @Test
    fun `the motor power band scales the 40 Wh base`() {
        val small = EnergyFactors.getWhPerKm(
            ActivityType.MOTORCYCLE,
            profile(PrimaryFuelType.ELECTRIC, motor = ElectricMotorPowerBand.UP_TO_3_KW)
        )
        // 40 x 0.55 — the stated figure is a base, not a fixed value.
        assertEquals(22.0, small, 1e-9)
    }

    @Test
    fun `electric motorcycle CO2 keeps the two-wheeler energy ratio`() {
        val co2 = CO2Factors.getFactor(ActivityType.MOTORCYCLE, profile(PrimaryFuelType.ELECTRIC))
        assertEquals(0.024, co2, 1e-9)
        val twoWheelerRatio =
            ElectricVehicleClass.TWO_WHEELER.baseCo2KgPerKm / ElectricVehicleClass.TWO_WHEELER.baseWhPerKm
        val motorcycleRatio =
            ElectricVehicleClass.MOTORCYCLE.baseCo2KgPerKm / ElectricVehicleClass.MOTORCYCLE.baseWhPerKm
        assertEquals(twoWheelerRatio, motorcycleRatio, 1e-9)
    }

    // ── Hybrid ──────────────────────────────────────────────────────────────

    @Test
    fun `hybrid emits 70 percent of the equivalent petrol car`() {
        val petrol = CO2Factors.getFactor(ActivityType.DRIVING, profile(PrimaryFuelType.PETROL))
        val hybrid = CO2Factors.getFactor(ActivityType.DRIVING, profile(PrimaryFuelType.HYBRID))
        assertEquals(petrol * 0.70, hybrid, 1e-9)
    }

    @Test
    fun `hybrid consumes 70 percent of the equivalent petrol car`() {
        val petrol = EnergyFactors.getWhPerKm(ActivityType.DRIVING, profile(PrimaryFuelType.PETROL))
        val hybrid = EnergyFactors.getWhPerKm(ActivityType.DRIVING, profile(PrimaryFuelType.HYBRID))
        assertEquals(petrol * 0.70, hybrid, 1e-9)
    }

    @Test
    fun `hybrid tracks body type and displacement like any combustion car`() {
        // The whole reason for scaling the petrol figure rather than tabulating hybrids:
        // an SUV hybrid must still read higher than a hatchback hybrid.
        val hatch = CO2Factors.getFactor(
            ActivityType.DRIVING, profile(PrimaryFuelType.HYBRID, body = VehicleBodyType.HATCHBACK)
        )
        val suv = CO2Factors.getFactor(
            ActivityType.DRIVING, profile(PrimaryFuelType.HYBRID, body = VehicleBodyType.SUV_CROSSOVER)
        )
        assertTrue(suv > hatch)
    }

    // ── Plug-in hybrid ──────────────────────────────────────────────────────

    @Test
    fun `plug-in sits between a hybrid and a pure EV`() {
        val hybrid = CO2Factors.getFactor(ActivityType.DRIVING, profile(PrimaryFuelType.HYBRID))
        val phev = CO2Factors.getFactor(ActivityType.DRIVING, profile(PrimaryFuelType.PLUG_IN_HYBRID))
        val ev = CO2Factors.getFactor(ActivityType.ELECTRIC_VEHICLE, profile(PrimaryFuelType.ELECTRIC))
        assertTrue("PHEV should beat a plain hybrid", phev < hybrid)
        assertTrue("PHEV should not beat a pure EV", phev > ev)
    }

    @Test
    fun `plug-in is the stated blend of battery and engine`() {
        val p = profile(PrimaryFuelType.PLUG_IN_HYBRID)
        val petrol = CO2Factors.getFactor(ActivityType.DRIVING, profile(PrimaryFuelType.PETROL))
        val expected = 0.5 * (ElectricVehicleClass.CAR.baseCo2KgPerKm * 1.0) +
            0.5 * (petrol * 0.70)
        assertEquals(expected, CO2Factors.getFactor(ActivityType.DRIVING, p), 1e-9)
    }

    // ── The two tables agree ────────────────────────────────────────────────

    @Test
    fun `every fuel type yields both a CO2 and an energy figure for driving`() {
        // Guards the hand-maintained parallel between CO2Factors and EnergyFactors: a
        // branch added to one and forgotten in the other shows up here as a zero.
        for (fuel in PrimaryFuelType.entries) {
            val p = profile(fuel)
            assertTrue("no CO2 for $fuel", CO2Factors.getFactor(ActivityType.DRIVING, p) > 0.0)
            assertTrue("no energy for $fuel", EnergyFactors.getWhPerKm(ActivityType.DRIVING, p) > 0.0)
            assertTrue("no CO2 for $fuel on two wheels",
                CO2Factors.getFactor(ActivityType.MOTORCYCLE, p) > 0.0)
            assertTrue("no energy for $fuel on two wheels",
                EnergyFactors.getWhPerKm(ActivityType.MOTORCYCLE, p) > 0.0)
        }
    }

    @Test
    fun `petrol and diesel are unchanged by the new branches`() {
        // Existing users must not silently re-rate.
        val petrol = profile(PrimaryFuelType.PETROL)
        assertEquals(
            IceFuel.co2FromCcBandAndBody(petrol.drivingCcBand, petrol.bodyType, IceFuel.PETROL),
            CO2Factors.getFactor(ActivityType.DRIVING, petrol),
            1e-9
        )
        val diesel = profile(PrimaryFuelType.DIESEL, ice = IceFuel.DIESEL)
        assertEquals(
            IceFuel.co2FromCcBandAndBody(diesel.drivingCcBand, diesel.bodyType, IceFuel.DIESEL),
            CO2Factors.getFactor(ActivityType.DRIVING, diesel),
            1e-9
        )
    }
}
