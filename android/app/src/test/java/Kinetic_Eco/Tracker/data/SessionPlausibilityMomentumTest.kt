package Kinetic_Eco.Tracker.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The momentum test: direction reversals as the signal that separates travel from GPS scatter.
 *
 * Why this exists rather than another speed or distance threshold. Every such threshold the
 * tracking pipeline has tried can be satisfied by drift, and severe drift satisfies them best —
 * it manufactures exactly the signals they ask for, then uses them to claim the exemptions those
 * gates grant to vehicles. Direction is categorically different: a person or vehicle carries
 * momentum and cannot reverse course repeatedly between consecutive fixes, while multipath has
 * no inertia at all.
 *
 * Calibrated on 17 real recorded sessions — eight genuine walks peaked at a 0.07 reversal rate,
 * nine drift sessions bottomed at 0.16 — so the threshold sits between with margin on each side.
 */
class SessionPlausibilityMomentumTest {

    private val baseLat = -1.2031778
    private val baseLon = 36.7774234

    /** Metres → degrees, near the calibration latitude. */
    private fun dLat(m: Double) = m / 111_320.0
    private fun dLon(m: Double) = m / (111_320.0 * cos(Math.toRadians(baseLat)))

    /** A coherent route: successive hops of [hopM] along a steady bearing with gentle wander. */
    private fun coherentRoute(points: Int, hopM: Double = 5.0, wanderDeg: Double = 12.0):
        List<RoutePoint> {
        val rnd = Random(7)
        var lat = baseLat
        var lon = baseLon
        var heading = 45.0
        return buildList {
            add(RoutePoint(lat, lon))
            repeat(points - 1) {
                heading += (rnd.nextDouble() - 0.5) * 2 * wanderDeg
                val r = Math.toRadians(heading)
                lat += dLat(hopM * cos(r))
                lon += dLon(hopM * sin(r))
                add(RoutePoint(lat, lon))
            }
        }
    }

    /** Scatter: each hop points in an unrelated direction, as multipath does. */
    private fun scatterRoute(points: Int, spreadM: Double = 8.0): List<RoutePoint> {
        val rnd = Random(11)
        return buildList {
            repeat(points) {
                val heading = rnd.nextDouble() * 360.0
                val r = Math.toRadians(heading)
                add(
                    RoutePoint(
                        baseLat + dLat(spreadM * cos(r)),
                        baseLon + dLon(spreadM * sin(r))
                    )
                )
            }
        }
    }

    private fun stats(
        route: List<RoutePoint>,
        distance: Double,
        steps: Int
    ) = SessionStats(totalDistance = distance, totalSteps = steps, routePath = route)

    // ── The measurement itself ───────────────────────────────────────────────────

    @Test
    fun `a coherent route has a low reversal rate`() {
        val rate = SessionPlausibility.directionReversalRate(coherentRoute(60))
        assertNotNull(rate)
        assertTrue(
            "coherent travel should sit well under the threshold, was $rate",
            rate!! < SessionPlausibility.DRIFT_REVERSAL_RATE
        )
    }

    @Test
    fun `scatter has a high reversal rate`() {
        val rate = SessionPlausibility.directionReversalRate(scatterRoute(60))
        assertNotNull(rate)
        assertTrue(
            "scatter should sit well above the threshold, was $rate",
            rate!! >= SessionPlausibility.DRIFT_REVERSAL_RATE
        )
    }

    @Test
    fun `a route too short to judge returns null rather than a verdict`() {
        // Absent evidence must never convict: too few bearings means unproven, not disproven.
        assertNull(SessionPlausibility.directionReversalRate(emptyList()))
        assertNull(SessionPlausibility.directionReversalRate(coherentRoute(2)))
        assertNull(
            "below the sample floor there is no statistical basis to act",
            SessionPlausibility.directionReversalRate(coherentRoute(6))
        )
    }

    @Test
    fun `sub-metre jitter is excluded from the bearing set`() {
        // A route of 0.2 m hops is pure noise; every bearing would be meaningless, so there
        // should not be enough qualifying samples to produce a verdict.
        assertNull(SessionPlausibility.directionReversalRate(coherentRoute(60, hopM = 0.2)))
    }

    // ── The verdict ──────────────────────────────────────────────────────────────

    @Test
    fun `scatter with no steps is discarded`() {
        val s = stats(scatterRoute(60), distance = 900.0, steps = 0)
        assertTrue(SessionPlausibility.looksLikeDrift(s))
        assertEquals(
            SessionPlausibility.Reason.NO_NET_DISPLACEMENT,
            SessionPlausibility.reasonToDiscard(s)
        )
    }

    @Test
    fun `steps outrank the geometry entirely`() {
        // The safety valve. Two genuine walks in the calibration set scored above the threshold
        // and were kept solely because the pedometer vouched for them. Anything a person
        // actually walked must survive, whatever shape the GPS trace took.
        val s = stats(scatterRoute(60), distance = 900.0, steps = 1200)
        assertFalse(
            "a corroborating step count must override any geometric verdict",
            SessionPlausibility.looksLikeDrift(s)
        )
    }

    @Test
    fun `a coherent drive with no steps survives`() {
        // The case the momentum test exists to protect: vehicles produce no steps, so they
        // depend entirely on their route being coherent. Roads are.
        val s = stats(coherentRoute(120, hopM = 25.0), distance = 3000.0, steps = 0)
        assertFalse(SessionPlausibility.looksLikeDrift(s))
        assertNull(SessionPlausibility.reasonToDiscard(s))
    }

    @Test
    fun `a there-and-back trip is not mistaken for scatter`() {
        // One reversal, not a quarter of them. Net displacement is near zero, which the old
        // ratio rule punished — momentum does not.
        val out = coherentRoute(40, hopM = 20.0)
        val back = out.reversed().drop(1)
        val s = stats(out + back, distance = 1500.0, steps = 0)
        assertFalse(
            "a single U-turn is not scatter",
            SessionPlausibility.looksLikeDrift(s)
        )
    }

    @Test
    fun `drift that wanders far from its anchor is still caught`() {
        // The regression this change fixes. Drift routinely reaches hundreds of metres from its
        // start, which tripped the DRIFT_MAX_DISPLACEMENT_M early return and rescued exactly the
        // sessions that needed catching. The momentum test runs first now.
        val s = stats(scatterRoute(80, spreadM = 400.0), distance = 2300.0, steps = 0)
        val displacement = SessionPlausibility.maxDisplacementMeters(s.routePath)
        assertTrue(
            "fixture must clear the old escape hatch to be meaningful",
            displacement >= SessionPlausibility.DRIFT_MAX_DISPLACEMENT_M
        )
        assertTrue(SessionPlausibility.looksLikeDrift(s))
    }

    @Test
    fun `a session with no route is kept`() {
        // Unproven is not disproven — deleting a user's trip on absent evidence is the worse
        // failure of the two.
        assertFalse(
            SessionPlausibility.looksLikeDrift(
                stats(emptyList(), distance = 5000.0, steps = 0)
            )
        )
    }

    private fun assertEquals(expected: Any?, actual: Any?) =
        org.junit.Assert.assertEquals(expected, actual)
}
