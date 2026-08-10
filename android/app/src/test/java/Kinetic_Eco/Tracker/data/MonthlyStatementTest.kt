package Kinetic_Eco.Tracker.data

import Kinetic_Eco.Tracker.util.MonthWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Covers the monthly statement.
 *
 * A statement is a money document — users compare it against a bank app — so the
 * arithmetic and the month boundaries both have to be exact, and the app must decline
 * to state a figure it cannot support rather than print a plausible zero.
 */
class MonthlyStatementTest {

    private val prices = EnergyPrices(
        currencyCode = "KES",
        petrolPerLitre = 200.0,
        dieselPerLitre = 180.0,
        electricityPerKwh = 25.0,
        source = PriceSource.BUNDLED,
        effectiveMonth = "2026-08"
    )

    private fun monthStart(year: Int, month: Int): Long {
        val cal = Calendar.getInstance()
        cal.set(year, month, 1, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun trip(distanceKm: Double, motorisedKm: Double, saved: Double = 0.0, emitted: Double = 0.0) =
        StatementTrip(0L, distanceKm, motorisedKm, saved, emitted)

    private fun build(trips: List<StatementTrip>, measured: MeasuredEconomy? = null) =
        MonthlyStatementCalculator.build(
            monthStartMs = monthStart(2026, Calendar.JULY),
            trips = trips,
            profile = VehicleProfile.DEFAULT,
            prices = prices,
            measured = measured,
            isPartial = false
        )

    // ── Month boundaries ─────────────────────────────────────────────────────

    @Test
    fun `a month ends where the next one begins`() {
        val july = monthStart(2026, Calendar.JULY)
        assertEquals(monthStart(2026, Calendar.AUGUST), MonthWindow.endOfMonthMs(july))
    }

    /** Adding 30 days would land in March. Calendar arithmetic must not be faked. */
    @Test
    fun `February is handled by calendar arithmetic, not a fixed day count`() {
        val feb = monthStart(2026, Calendar.FEBRUARY)
        assertEquals(monthStart(2026, Calendar.MARCH), MonthWindow.endOfMonthMs(feb))
    }

    @Test
    fun `shifting months crosses a year boundary correctly`() {
        val jan = monthStart(2026, Calendar.JANUARY)
        assertEquals(monthStart(2025, Calendar.DECEMBER), MonthWindow.shiftMonths(jan, -1))
    }

    // ── Aggregation ──────────────────────────────────────────────────────────

    @Test
    fun `totals sum across trips`() {
        val s = build(listOf(trip(10.0, 8.0), trip(5.0, 0.0), trip(20.0, 20.0)))
        assertEquals(3, s.tripCount)
        assertEquals(35.0, s.totalDistanceKm, 1e-9)
        assertEquals(28.0, s.motorisedDistanceKm, 1e-9)
        // Active distance is what was not under engine power.
        assertEquals(7.0, s.activeDistanceKm, 1e-9)
    }

    @Test
    fun `cost is derived from motorised distance only`() {
        val allDriving = build(listOf(trip(100.0, 100.0)))
        val halfWalking = build(listOf(trip(100.0, 50.0)))
        assertEquals(allDriving.fuelCost!! / 2, halfWalking.fuelCost!!, 1e-6)
    }

    /** A zero would read as "you spent nothing", which is a different claim. */
    @Test
    fun `a month with no driving reports no cost rather than zero`() {
        val s = build(listOf(trip(12.0, 0.0)))
        assertNull(s.fuelCost)
        assertNull(s.fuelLitres)
    }

    @Test
    fun `an empty month is empty, not an error`() {
        val s = build(emptyList())
        assertEquals(0, s.tripCount)
        assertEquals(0.0, s.totalDistanceKm, 1e-9)
        assertNull(s.fuelCost)
    }

    // ── Short trips ──────────────────────────────────────────────────────────

    @Test
    fun `only short motorised trips are flagged as replaceable`() {
        val s = build(
            listOf(
                trip(2.0, 2.0),    // short drive — counts
                trip(2.5, 2.5),    // short drive — counts
                trip(2.0, 0.0),    // short walk — already green
                trip(30.0, 30.0)   // long drive — not walkable
            )
        )
        assertEquals(2, s.shortTripCount)
        assertEquals(4.5, s.shortTripDistanceKm, 1e-9)
        assertTrue(s.shortTripCost!! > 0.0)
        assertTrue("short-trip cost must be part of the total", s.shortTripCost!! < s.fuelCost!!)
    }

    @Test
    fun `the short-trip threshold matches the walking suggestion elsewhere`() {
        assertEquals(3.0, MonthlyStatementCalculator.SHORT_TRIP_KM, 1e-9)
    }

    // ── Measurement ──────────────────────────────────────────────────────────

    @Test
    fun `a measured economy changes the litres and is flagged as such`() {
        val estimated = build(listOf(trip(100.0, 100.0)))
        val measured = build(
            listOf(trip(100.0, 100.0)),
            MeasuredEconomy(lPer100Km = 5.0, intervals = 3, totalLitres = 50.0, totalDistanceKm = 1000.0)
        )
        assertEquals(5.0, measured.fuelLitres!!, 1e-6)
        assertTrue(measured.isMeasured)
        assertTrue("estimate should not equal the measured figure", estimated.fuelLitres!! > measured.fuelLitres!!)
        assertTrue(!estimated.isMeasured)
    }

    @Test
    fun `net CO2 is emissions minus savings`() {
        val s = build(listOf(trip(10.0, 10.0, saved = 1.0, emitted = 4.0)))
        assertEquals(3.0, s.netCo2Kg, 1e-9)
    }
}
