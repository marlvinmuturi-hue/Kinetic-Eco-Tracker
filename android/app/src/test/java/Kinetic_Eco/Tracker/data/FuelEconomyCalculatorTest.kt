package Kinetic_Eco.Tracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the measured-economy maths behind the fuel log.
 *
 * This figure replaces the engine-displacement class average outright, so an error
 * here propagates straight into every litre and shilling the app shows — numbers the
 * user checks against real receipts. The guards against bad intervals matter as much
 * as the arithmetic: one mis-typed tankful must not poison the result.
 */
class FuelEconomyCalculatorTest {

    private val day = 24 * 60 * 60 * 1000L

    private fun fill(dayIndex: Int, litres: Double, paid: Double = 0.0, full: Boolean = true) =
        FillUp(
            filledAtMs = dayIndex * day,
            litres = litres,
            amountPaid = paid,
            isFullTank = full
        )

    // ── economyFrom ──────────────────────────────────────────────────────────

    @Test
    fun `litres over distance gives litres per 100km`() {
        val e = FuelEconomyCalculator.economyFrom(listOf(FuelInterval(45.0, 500.0)))!!
        assertEquals(9.0, e.lPer100Km, 1e-9)
        assertEquals(1, e.intervals)
    }

    @Test
    fun `intervals are pooled, not averaged as ratios`() {
        // 10 L/100km over 500 km, then 20 L/100km over 100 km.
        // Pooled: 70 L / 600 km = 11.67. Mean of ratios would wrongly say 15.
        val e = FuelEconomyCalculator.economyFrom(
            listOf(FuelInterval(50.0, 500.0), FuelInterval(20.0, 100.0))
        )!!
        assertEquals(11.667, e.lPer100Km, 0.01)
    }

    @Test
    fun `short intervals are discarded as unreliable`() {
        // 30 km is below MIN_INTERVAL_KM — brim-full error dominates at that range.
        assertNull(FuelEconomyCalculator.economyFrom(listOf(FuelInterval(3.0, 30.0))))
    }

    @Test
    fun `implausible consumption is rejected rather than averaged in`() {
        // 100 L over 100 km = 100 L/100km. A mis-typed litre figure, not a car.
        assertNull(FuelEconomyCalculator.economyFrom(listOf(FuelInterval(100.0, 100.0))))
        // 0.5 L over 200 km = 0.25 L/100km. Also not a car.
        assertNull(FuelEconomyCalculator.economyFrom(listOf(FuelInterval(0.5, 200.0))))
    }

    @Test
    fun `one bad interval does not poison the good ones`() {
        val e = FuelEconomyCalculator.economyFrom(
            listOf(
                FuelInterval(45.0, 500.0),   // 9.0, good
                FuelInterval(200.0, 150.0),  // 133 L/100km, rejected
                FuelInterval(40.0, 450.0)    // 8.9, good
            )
        )!!
        assertEquals(2, e.intervals)
        assertTrue("should stay near 9 L/100km, got ${e.lPer100Km}", e.lPer100Km in 8.0..10.0)
    }

    @Test
    fun `no usable intervals yields null rather than zero`() {
        assertNull(FuelEconomyCalculator.economyFrom(emptyList()))
        assertNull(FuelEconomyCalculator.economyFrom(listOf(FuelInterval(0.0, 500.0))))
    }

    @Test
    fun `totals are reported so the UI can show the evidence`() {
        val e = FuelEconomyCalculator.economyFrom(
            listOf(FuelInterval(45.0, 500.0), FuelInterval(40.0, 450.0))
        )!!
        assertEquals(85.0, e.totalLitres, 1e-9)
        assertEquals(950.0, e.totalDistanceKm, 1e-9)
    }

    // ── pairing ──────────────────────────────────────────────────────────────

    @Test
    fun `a single fill-up cannot bound an interval`() {
        assertTrue(FuelEconomyCalculator.fullTankPairs(listOf(fill(1, 40.0))).isEmpty())
    }

    @Test
    fun `consecutive full tanks form pairs in time order`() {
        val pairs = FuelEconomyCalculator.fullTankPairs(
            listOf(fill(3, 42.0), fill(1, 40.0), fill(2, 41.0))
        )
        assertEquals(2, pairs.size)
        assertEquals(1 * day, pairs[0].first.filledAtMs)
        assertEquals(2 * day, pairs[0].second.filledAtMs)
        assertEquals(3 * day, pairs[1].second.filledAtMs)
    }

    /** A partial fill leaves unknown residue — the pair around it is not measurable. */
    @Test
    fun `partial fills are excluded from pairing`() {
        val pairs = FuelEconomyCalculator.fullTankPairs(
            listOf(fill(1, 40.0), fill(2, 15.0, full = false), fill(3, 42.0))
        )
        assertEquals(1, pairs.size)
        assertEquals(1 * day, pairs[0].first.filledAtMs)
        assertEquals(3 * day, pairs[0].second.filledAtMs)
    }

    @Test
    fun `the refill volume belongs to the window it closes`() {
        val intervals = FuelEconomyCalculator.intervalsFrom(
            listOf(fill(1, 40.0), fill(2, 37.5))
        ) { _, _ -> 400.0 }
        // 37.5 L (the second fill) refilled what was burned over those 400 km.
        assertEquals(37.5, intervals.single().litres, 1e-9)
    }

    // ── observed price ───────────────────────────────────────────────────────

    @Test
    fun `price per litre comes from what was actually paid`() {
        val price = FuelEconomyCalculator.averagePricePerLitre(
            listOf(fill(1, 40.0, paid = 8000.0), fill(2, 20.0, paid = 3800.0))
        )!!
        // 11800 / 60 = 196.67 — pooled, so the bigger fill weighs more.
        assertEquals(196.667, price, 0.01)
    }

    @Test
    fun `price ignores entries with no amount recorded`() {
        val price = FuelEconomyCalculator.averagePricePerLitre(
            listOf(fill(1, 40.0, paid = 8000.0), fill(2, 20.0, paid = 0.0))
        )!!
        assertEquals(200.0, price, 1e-9)
    }

    @Test
    fun `no fill-ups yields no price`() {
        assertNull(FuelEconomyCalculator.averagePricePerLitre(emptyList()))
    }

    // ── recent price window ──────────────────────────────────────────────────

    @Test
    fun `recent price uses only fill-ups inside the window`() {
        val now = 100L * day
        val price = FuelEconomyCalculator.recentPricePerLitre(
            listOf(
                // 90 days ago, at a much older price — must be ignored.
                fill(10, 40.0, paid = 4000.0),
                // 5 days ago.
                fill(95, 40.0, paid = 8000.0)
            ),
            nowMs = now,
            windowDays = 60
        )!!
        assertEquals(200.0, price, 1e-9)
    }

    /** Stale receipts are worse than the published cap, which at least claims to be current. */
    @Test
    fun `nothing recent yields null so the published price wins`() {
        val now = 400L * day
        assertNull(
            FuelEconomyCalculator.recentPricePerLitre(
                listOf(fill(10, 40.0, paid = 8000.0)),
                nowMs = now,
                windowDays = 60
            )
        )
    }

    @Test
    fun `several recent receipts are pooled by volume`() {
        val now = 100L * day
        val price = FuelEconomyCalculator.recentPricePerLitre(
            listOf(fill(90, 40.0, paid = 8000.0), fill(98, 10.0, paid = 2100.0)),
            nowMs = now,
            windowDays = 60
        )!!
        // 10100 / 50 = 202 — the 40 L fill carries four times the weight.
        assertEquals(202.0, price, 1e-9)
    }
}
