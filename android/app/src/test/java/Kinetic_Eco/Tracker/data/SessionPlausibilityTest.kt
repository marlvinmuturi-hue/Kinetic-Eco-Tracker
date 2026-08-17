package Kinetic_Eco.Tracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

/**
 * The save-time filter that rejects a phone sitting still.
 *
 * The true positive — indoor drift — is the easy case. The tests that matter are the false
 * positives: this filter deletes the user's trip, and a trip wrongly deleted is invisible
 * (nobody notices a session that never appeared) while a trip wrongly kept is merely wrong.
 * Track laps, tight park loops and out-and-back walks are all deliberately pinned below.
 */
class SessionPlausibilityTest {

    // Nairobi-ish, so the longitude scaling is realistic rather than equatorial-degenerate.
    private val baseLat = -1.2921
    private val baseLon = 36.8219

    /** Metres north/east of the base point, as a RoutePoint. */
    private fun point(northM: Double, eastM: Double): RoutePoint {
        val dLat = northM / 111_320.0
        val dLon = eastM / (111_320.0 * cos(Math.toRadians(baseLat)))
        return RoutePoint(latitude = baseLat + dLat, longitude = baseLon + dLon)
    }

    private fun session(
        distanceM: Double,
        route: List<RoutePoint>,
        steps: Int = 0
    ) = SessionStats(totalDistance = distanceM, routePath = route, totalSteps = steps)

    /** Scatter inside [radiusM] of the origin — what a phone on a table records. */
    private fun scatter(radiusM: Double, n: Int = 60): List<RoutePoint> =
        (0 until n).map { i ->
            val a = i * 2.399963 // golden-angle walk, so points don't fall in a line
            point(radiusM * cos(a), radiusM * cos(a + 1.57))
        }

    // ── The reported bug ────────────────────────────────────────────────────

    @Test
    fun `the 4-minute indoor session is rejected`() {
        // 390 m of accumulated path, never more than ~20 m from the start, no steps.
        val stats = session(390.0, scatter(20.0), steps = 0)
        assertEquals(
            SessionPlausibility.Reason.NO_NET_DISPLACEMENT,
            SessionPlausibility.reasonToDiscard(stats)
        )
    }

    @Test
    fun `drift is rejected however long it accumulates`() {
        // The old distance floor got weaker the longer you sat still; this must not.
        for (metres in listOf(250.0, 500.0, 1500.0, 5000.0)) {
            assertEquals(
                "drift of $metres m should be rejected",
                SessionPlausibility.Reason.NO_NET_DISPLACEMENT,
                SessionPlausibility.reasonToDiscard(session(metres, scatter(20.0)))
            )
        }
    }

    // ── False positives: real trips that must survive ───────────────────────

    @Test
    fun `laps of a 400m running track are kept`() {
        // A 400 m track is ~85 m across its long axis; four laps is 1600 m of path but the
        // runner is never far from the start. This is the case a naive ratio rule deletes.
        val lap = listOf(
            point(0.0, 0.0), point(60.0, 0.0), point(85.0, 25.0),
            point(60.0, 50.0), point(0.0, 50.0), point(-25.0, 25.0)
        )
        val route = lap + lap + lap + lap
        assertNull(SessionPlausibility.reasonToDiscard(session(1600.0, route, steps = 1800)))
    }

    @Test
    fun `an out-and-back walk is kept`() {
        val out = (0..20).map { point(it * 30.0, 0.0) }
        val back = out.reversed()
        assertNull(SessionPlausibility.reasonToDiscard(session(1200.0, out + back, steps = 1500)))
    }

    @Test
    fun `a normal commute is kept`() {
        val route = (0..50).map { point(it * 120.0, it * 40.0) }
        assertNull(SessionPlausibility.reasonToDiscard(session(6300.0, route)))
    }

    @Test
    fun `pacing a small yard is kept when the pedometer agrees`() {
        // Inside the 75 m circle and inside the ratio — geometry alone would reject it. The
        // step count is what saves it, and it should.
        val stats = session(400.0, scatter(15.0), steps = 500)
        assertNull(SessionPlausibility.reasonToDiscard(stats))
    }

    @Test
    fun `a low step count does not rescue drift`() {
        // Phone jostled on a desk registers a few steps; nowhere near a walk's worth.
        val stats = session(390.0, scatter(20.0), steps = 30)
        assertEquals(
            SessionPlausibility.Reason.NO_NET_DISPLACEMENT,
            SessionPlausibility.reasonToDiscard(stats)
        )
    }

    // ── Absent evidence is not evidence of absence ──────────────────────────

    @Test
    fun `a session with no route is kept`() {
        // Unproven is not disproven. Deleting a real trip because geometry was not recorded
        // is the worse of the two mistakes.
        assertNull(SessionPlausibility.reasonToDiscard(session(3000.0, emptyList())))
        assertNull(SessionPlausibility.reasonToDiscard(session(3000.0, listOf(point(0.0, 0.0)))))
    }

    // ── The existing floor still applies ────────────────────────────────────

    @Test
    fun `short sessions are still rejected as too short`() {
        assertEquals(
            SessionPlausibility.Reason.TOO_SHORT,
            SessionPlausibility.reasonToDiscard(session(199.0, emptyList()))
        )
        assertNull(SessionPlausibility.reasonToDiscard(session(201.0, emptyList())))
    }

    // ── Displacement helper ─────────────────────────────────────────────────

    @Test
    fun `max displacement measures from the start, not the bounding box`() {
        val route = listOf(point(0.0, 0.0), point(100.0, 0.0), point(50.0, 0.0))
        assertEquals(100.0, SessionPlausibility.maxDisplacementMeters(route), 1.0)
    }

    @Test
    fun `max displacement survives doubling back`() {
        val route = listOf(point(0.0, 0.0), point(500.0, 0.0), point(0.0, 0.0))
        assertEquals(500.0, SessionPlausibility.maxDisplacementMeters(route), 1.0)
    }

    @Test
    fun `displacement of a single point is zero`() {
        assertEquals(0.0, SessionPlausibility.maxDisplacementMeters(listOf(point(0.0, 0.0))), 0.0)
        assertEquals(0.0, SessionPlausibility.maxDisplacementMeters(emptyList()), 0.0)
    }

    @Test
    fun `haversine is sane over a known separation`() {
        // 1 km north should measure ~1 km.
        val d = SessionPlausibility.maxDisplacementMeters(listOf(point(0.0, 0.0), point(1000.0, 0.0)))
        assertTrue("expected ~1000 m, got $d", d in 995.0..1005.0)
    }
}