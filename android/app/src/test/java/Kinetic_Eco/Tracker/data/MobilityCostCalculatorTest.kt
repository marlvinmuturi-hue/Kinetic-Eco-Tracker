package Kinetic_Eco.Tracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the litres-and-money layer of the calculator.
 *
 * These numbers are the ones users will check against a fuel receipt, so the tests
 * pin both the arithmetic and — just as importantly — the cases where the app must
 * decline to quote a figure at all.
 */
class MobilityCostCalculatorTest {

    private val prices = EnergyPrices(
        currencyCode = "KES",
        petrolPerLitre = 200.0,
        dieselPerLitre = 180.0,
        electricityPerKwh = 25.0,
        source = PriceSource.BUNDLED,
        effectiveMonth = "2026-08"
    )

    private fun cost(
        activity: ActivityType,
        km: Double,
        profile: VehicleProfile = VehicleProfile.DEFAULT
    ) = MobilityCostCalculator.costOf(
        Co2Calculator.estimate(activity, km, profile),
        profile,
        prices
    )

    @Test
    fun `a default petrol car burns a believable amount per 100km`() {
        val c = cost(ActivityType.DRIVING, 100.0)!!
        // 880 Wh/km × 100 km ÷ 9.7 kWh/L ≈ 9.1 L — a sane figure for a 1.8–2.5 L car.
        // If this ever drifts far from single digits the energy model has broken.
        assertTrue("expected ~9 L/100km, got ${c.litres}", c.litres!! in 7.0..12.0)
    }

    @Test
    fun `cost is litres times the unit price`() {
        val c = cost(ActivityType.DRIVING, 100.0)!!
        assertEquals(c.litres!! * 200.0, c.amount, 1e-6)
        assertEquals(200.0, c.unitPrice, 1e-9)
        assertEquals("KES", c.currencyCode)
    }

    @Test
    fun `diesel is priced per diesel litre, not petrol`() {
        val diesel = VehicleProfile.DEFAULT.copy(iceFuel = IceFuel.DIESEL)
        val c = cost(ActivityType.DRIVING, 100.0, diesel)!!
        assertEquals(180.0, c.unitPrice, 1e-9)
    }

    @Test
    fun `a diesel litre carries more energy, so fewer litres per kWh`() {
        val petrolKwh = 100.0
        val litresPetrol = petrolKwh / MobilityCostCalculator.PETROL_KWH_PER_LITRE
        val litresDiesel = petrolKwh / MobilityCostCalculator.DIESEL_KWH_PER_LITRE
        assertTrue("diesel should need fewer litres for the same energy", litresDiesel < litresPetrol)
    }

    @Test
    fun `an EV is quoted in kWh at the electricity tariff, never in litres`() {
        val ev = VehicleProfile.DEFAULT.copy(primaryFuelType = PrimaryFuelType.ELECTRIC)
        val c = cost(ActivityType.ELECTRIC_VEHICLE, 100.0, ev)!!
        assertNull("an EV has no litres", c.litres)
        assertNotNull(c.kWh)
        assertEquals(25.0, c.unitPrice, 1e-9)
        assertEquals(c.kWh!! * 25.0, c.amount, 1e-6)
    }

    /** Fares are not a share of the vehicle's energy bill — better silent than wrong. */
    @Test
    fun `train and flying are not given an energy cost`() {
        assertNull(cost(ActivityType.TRAIN, 100.0))
        assertNull(cost(ActivityType.FLYING, 500.0))
    }

    @Test
    fun `human-powered modes cost nothing to fuel`() {
        assertNull(cost(ActivityType.WALKING, 5.0))
        assertNull(cost(ActivityType.RUNNING, 5.0))
        assertNull(cost(ActivityType.CYCLING, 20.0))
        assertNull(cost(ActivityType.IDLE, 0.0))
    }

    @Test
    fun `zero distance yields no cost rather than a zero-shilling row`() {
        assertNull(cost(ActivityType.DRIVING, 0.0))
    }

    @Test
    fun `cost scales linearly with distance`() {
        val ten = cost(ActivityType.DRIVING, 10.0)!!
        val twenty = cost(ActivityType.DRIVING, 20.0)!!
        assertEquals(ten.amount * 2, twenty.amount, 1e-6)
    }

    @Test
    fun `a bigger engine costs more to run over the same distance`() {
        val small = VehicleProfile.DEFAULT.copy(drivingCcBand = DrivingEngineCcBand.UP_TO_1000)
        val big = VehicleProfile.DEFAULT.copy(drivingCcBand = DrivingEngineCcBand.OVER_2500)
        assertTrue(cost(ActivityType.DRIVING, 50.0, small)!!.amount < cost(ActivityType.DRIVING, 50.0, big)!!.amount)
    }

    @Test
    fun `provenance is carried through so the UI can show where the price came from`() {
        val c = cost(ActivityType.DRIVING, 10.0)!!
        assertEquals(PriceSource.BUNDLED, c.source)
        assertEquals("2026-08", c.effectiveMonth)
    }

    // ── Unknown prices ───────────────────────────────────────────────────────

    /**
     * A zero price is "we do not know", not "fuel is free". Returning a zero-cost trip
     * would print a confident "0" next to a real litre figure.
     */
    @Test
    fun `an unknown fuel price yields no cost rather than a free trip`() {
        val unknown = prices.copy(petrolPerLitre = 0.0)
        assertNull(
            MobilityCostCalculator.costOf(
                Co2Calculator.estimate(ActivityType.DRIVING, 50.0, VehicleProfile.DEFAULT),
                VehicleProfile.DEFAULT,
                unknown
            )
        )
    }

    @Test
    fun `an unknown electricity tariff yields no cost for an EV`() {
        val ev = VehicleProfile.DEFAULT.copy(primaryFuelType = PrimaryFuelType.ELECTRIC)
        assertNull(
            MobilityCostCalculator.costOf(
                Co2Calculator.estimate(ActivityType.ELECTRIC_VEHICLE, 50.0, ev),
                ev,
                prices.copy(electricityPerKwh = 0.0)
            )
        )
    }

    /** Knowing petrol but not electricity must not suppress the petrol figure. */
    @Test
    fun `a partially known table still costs the modes it knows`() {
        val partial = prices.copy(electricityPerKwh = 0.0)
        assertNotNull(
            MobilityCostCalculator.costOf(
                Co2Calculator.estimate(ActivityType.DRIVING, 50.0, VehicleProfile.DEFAULT),
                VehicleProfile.DEFAULT,
                partial
            )
        )
    }

    @Test
    fun `a seed exists only for the region it was sourced for`() {
        assertNotNull(EnergyPrices.seedFor("KE"))
        assertNotNull(EnergyPrices.seedFor("ke"))
        // France, Germany, the US: no seed, so the app shows no money at all.
        assertNull(EnergyPrices.seedFor("FR"))
        assertNull(EnergyPrices.seedFor("DE"))
        assertNull(EnergyPrices.seedFor(""))
    }

    // ── The shipped Kenyan seed ──────────────────────────────────────────────

    /**
     * Guards the figures users will actually check against a pump receipt. If the seed
     * drifts from reality the cost feature loses the credibility it exists to have.
     */
    @Test
    fun `the Kenya seed carries the August 2026 EPRA prices`() {
        val seed = EnergyPrices.KENYA_SEED
        assertEquals("KES", seed.currencyCode)
        assertEquals(214.00, seed.petrolPerLitre, 1e-9)
        assertEquals(222.00, seed.dieselPerLitre, 1e-9)
        assertEquals("EPRA", seed.sourceName)
        assertEquals("2026-08", seed.effectiveMonth)
    }

    /** Diesel above petrol is the current Kenyan reality — nothing may assume otherwise. */
    @Test
    fun `diesel being dearer than petrol is costed correctly`() {
        val diesel = VehicleProfile.DEFAULT.copy(iceFuel = IceFuel.DIESEL)
        val seed = EnergyPrices.KENYA_SEED
        val petrolCost = MobilityCostCalculator.costOf(
            Co2Calculator.estimate(ActivityType.DRIVING, 100.0, VehicleProfile.DEFAULT),
            VehicleProfile.DEFAULT, seed
        )!!
        val dieselCost = MobilityCostCalculator.costOf(
            Co2Calculator.estimate(ActivityType.DRIVING, 100.0, diesel), diesel, seed
        )!!
        assertEquals(214.00, petrolCost.unitPrice, 1e-9)
        assertEquals(222.00, dieselCost.unitPrice, 1e-9)
    }

    /**
     * No verified Kenya Power tariff shipped, so EV cost must stay hidden. An invented
     * figure here would be indistinguishable from a real one on screen.
     */
    @Test
    fun `the seed quotes no EV cost because no tariff was verified`() {
        val ev = VehicleProfile.DEFAULT.copy(primaryFuelType = PrimaryFuelType.ELECTRIC)
        assertEquals(0.0, EnergyPrices.KENYA_SEED.electricityPerKwh, 1e-9)
        assertNull(
            MobilityCostCalculator.costOf(
                Co2Calculator.estimate(ActivityType.ELECTRIC_VEHICLE, 100.0, ev),
                ev, EnergyPrices.KENYA_SEED
            )
        )
    }

    // ── Owner-stated economy ─────────────────────────────────────────────────

    private fun litresFor(profile: VehicleProfile, measured: MeasuredEconomy? = null) =
        MobilityCostCalculator.costOf(
            Co2Calculator.estimate(ActivityType.DRIVING, 100.0, profile),
            profile, prices, measured
        )!!.litres!!

    @Test
    fun `a typed km per litre is used instead of the engine-size average`() {
        val stated = VehicleProfile.DEFAULT.copy(fuelEconomyKmPerL = 20.0)
        // 20 km/L over 100 km = 5 L, regardless of what the cc band would have guessed.
        assertEquals(5.0, litresFor(stated), 1e-9)
        assertTrue("class average should differ", litresFor(VehicleProfile.DEFAULT) != 5.0)
    }

    @Test
    fun `the typed figure is flagged so the UI can say where it came from`() {
        val stated = VehicleProfile.DEFAULT.copy(fuelEconomyKmPerL = 20.0)
        val cost = MobilityCostCalculator.costOf(
            Co2Calculator.estimate(ActivityType.DRIVING, 100.0, stated), stated, prices
        )!!
        assertTrue(cost.isOwnerStated)
        assertTrue(!cost.isMeasured)
    }

    /** Fill-ups are the truth; a typed claim must not outrank them. */
    @Test
    fun `measured fill-ups beat the typed figure`() {
        val stated = VehicleProfile.DEFAULT.copy(fuelEconomyKmPerL = 20.0)
        val measured = MeasuredEconomy(lPer100Km = 12.0, intervals = 3, totalLitres = 120.0, totalDistanceKm = 1000.0)
        assertEquals(12.0, litresFor(stated, measured), 1e-9)
        val cost = MobilityCostCalculator.costOf(
            Co2Calculator.estimate(ActivityType.DRIVING, 100.0, stated), stated, prices, measured
        )!!
        assertTrue(cost.isMeasured)
        assertTrue("measured must not also claim owner-stated", !cost.isOwnerStated)
    }

    @Test
    fun `a blank or nonsensical figure falls back to the class average`() {
        val baseline = litresFor(VehicleProfile.DEFAULT)
        assertEquals(baseline, litresFor(VehicleProfile.DEFAULT.copy(fuelEconomyKmPerL = null)), 1e-9)
        assertEquals(baseline, litresFor(VehicleProfile.DEFAULT.copy(fuelEconomyKmPerL = 0.0)), 1e-9)
        assertEquals(baseline, litresFor(VehicleProfile.DEFAULT.copy(fuelEconomyKmPerL = -5.0)), 1e-9)
    }

    @Test
    fun `a thirstier car costs more than an efficient one`() {
        val thirsty = VehicleProfile.DEFAULT.copy(fuelEconomyKmPerL = 6.0)
        val frugal = VehicleProfile.DEFAULT.copy(fuelEconomyKmPerL = 25.0)
        assertTrue(litresFor(thirsty) > litresFor(frugal))
    }
}
