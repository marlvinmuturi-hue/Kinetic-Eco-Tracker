package Kinetic_Eco.Tracker.ui.components

import Kinetic_Eco.Tracker.data.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [hasUsableElevation] exists so a route card can skip the elevation chart entirely when there
 * is nothing to plot, instead of laying out its ~170dp "no elevation data" placeholder.
 *
 * Its contract is agreement with [routeElevationSamples]: it must return true exactly when that
 * function returns non-null. If the two ever disagree, either a session with a perfectly good
 * profile is silently hidden, or the placeholder comes back — so the agreement cases below
 * matter more than the individual ones.
 */
class ElevationProfileGateTest {

    private fun pt(lat: Double, lon: Double, alt: Double?) =
        RoutePoint(latitude = lat, longitude = lon, altitudeMeters = alt)

    /** A short route where every point carries altitude. */
    private fun denseRoute() = listOf(
        pt(0.0000, 0.0, 100.0),
        pt(0.0010, 0.0, 110.0),
        pt(0.0020, 0.0, 105.0),
        pt(0.0030, 0.0, 130.0)
    )

    @Test
    fun `route with altitude on every point is usable`() {
        assertTrue(hasUsableElevation(denseRoute()))
    }

    @Test
    fun `route with no altitude anywhere is not usable`() {
        val route = List(6) { pt(it * 0.001, 0.0, null) }
        assertFalse(hasUsableElevation(route))
    }

    @Test
    fun `a single altitude reading is enough - fill propagates it`() {
        // filledAltitudesAlongRoute fills forwards then backwards, so one anchor populates the
        // whole series. The cheap gate must agree rather than being stricter.
        val route = listOf(
            pt(0.0000, 0.0, null),
            pt(0.0010, 0.0, null),
            pt(0.0020, 0.0, 87.5),
            pt(0.0030, 0.0, null)
        )
        assertTrue(hasUsableElevation(route))

        val samples = routeElevationSamples(route)
        assertNotNull("one anchor should still yield a profile", samples)
        // Every sample takes the anchor's value, so the profile is flat but well-formed.
        assertTrue(samples!!.all { it.second == 87.5 })
    }

    @Test
    fun `fewer than two points is not usable`() {
        assertFalse(hasUsableElevation(emptyList()))
        assertFalse(hasUsableElevation(listOf(pt(0.0, 0.0, 100.0))))
    }

    @Test
    fun `non-finite altitudes do not count as data`() {
        val route = listOf(
            pt(0.0000, 0.0, Double.NaN),
            pt(0.0010, 0.0, Double.POSITIVE_INFINITY),
            pt(0.0020, 0.0, Double.NEGATIVE_INFINITY)
        )
        assertFalse(hasUsableElevation(route))
        assertNull(routeElevationSamples(route))
    }

    // ── The contract that actually matters: the two must agree ───────────────────

    @Test
    fun `gate agrees with sample generation across representative routes`() {
        val cases = mapOf(
            "dense" to denseRoute(),
            "all null" to List(5) { pt(it * 0.001, 0.0, null) },
            "one anchor" to listOf(pt(0.0, 0.0, null), pt(0.001, 0.0, 12.0)),
            "leading nulls" to listOf(
                pt(0.000, 0.0, null), pt(0.001, 0.0, null), pt(0.002, 0.0, 50.0), pt(0.003, 0.0, 55.0)
            ),
            "trailing nulls" to listOf(
                pt(0.000, 0.0, 50.0), pt(0.001, 0.0, 55.0), pt(0.002, 0.0, null), pt(0.003, 0.0, null)
            ),
            "interior gap" to listOf(
                pt(0.000, 0.0, 50.0), pt(0.001, 0.0, null), pt(0.002, 0.0, null), pt(0.003, 0.0, 70.0)
            ),
            "all NaN" to List(4) { pt(it * 0.001, 0.0, Double.NaN) },
            "single point" to listOf(pt(0.0, 0.0, 100.0)),
            "empty" to emptyList()
        )
        cases.forEach { (name, route) ->
            assertEquals(
                "gate and sample generation disagree for: $name",
                routeElevationSamples(route) != null,
                hasUsableElevation(route)
            )
        }
    }

    // ── Distance-axis rescaling ──────────────────────────────────────────────────

    @Test
    fun `axis is rescaled to the credited distance`() {
        // The raw polyline is whatever the stored points sum to; the credited distance is what
        // the app reports. On an indoor session those diverge badly, and the axis must follow
        // the credited figure so it agrees with the rest of the UI.
        val raw = routeElevationSamples(denseRoute())!!
        val rawTotal = raw.last().first
        assertTrue("fixture should span real ground", rawTotal > 0.0)

        val credited = rawTotal / 6.0
        val scaled = routeElevationSamples(denseRoute(), credited)!!
        assertEquals(credited, scaled.last().first, 1e-6)
        assertEquals(0.0, scaled.first().first, 1e-9)
    }

    @Test
    fun `rescaling preserves profile shape and elevations`() {
        val raw = routeElevationSamples(denseRoute())!!
        val scaled = routeElevationSamples(denseRoute(), raw.last().first / 4.0)!!
        assertEquals(raw.size, scaled.size)
        raw.indices.forEach { i ->
            // Elevations untouched…
            assertEquals(raw[i].second, scaled[i].second, 1e-9)
            // …and each point keeps its proportional position along the route.
            val rawFrac = raw[i].first / raw.last().first
            val scaledFrac = scaled[i].first / scaled.last().first
            assertEquals(rawFrac, scaledFrac, 1e-9)
        }
    }

    @Test
    fun `unusable credited distances leave the raw axis alone`() {
        val rawTotal = routeElevationSamples(denseRoute())!!.last().first
        listOf(null, 0.0, -5.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { bad ->
            val out = routeElevationSamples(denseRoute(), bad)!!
            assertEquals(
                "credited distance $bad must not rescale the axis",
                rawTotal, out.last().first, 1e-9
            )
        }
    }

    // ── Axis ticks ───────────────────────────────────────────────────────────────

    @Test
    fun `final tick never crowds its neighbour`() {
        // The regression: 419 m produced ticks [0, 200, 400, 419], and the last two labels
        // overlapped into unreadable glyphs on device.
        listOf(419.0, 401.0, 2448.0, 1050.0, 97.0, 5.0, 12345.0).forEach { distMax ->
            val ticks = distanceAxisTicks(distMax)
            assertEquals(
                "axis for $distMax must end at the true maximum",
                distMax, ticks.last(), 1e-9
            )
            val step = ticks.zipWithNext { a, b -> b - a }
            step.forEach { gap ->
                assertTrue("adjacent ticks too close for $distMax: gaps=$step", gap > 0.0)
            }
            if (step.size >= 2) {
                // No gap may be a small fraction of the widest one — that is the collision case.
                val widest = step.max()
                assertTrue(
                    "a tick gap is far smaller than the others for $distMax: gaps=$step",
                    step.min() >= widest * 0.3
                )
            }
        }
    }

    @Test
    fun `419m case produces readable ticks`() {
        val ticks = distanceAxisTicks(419.0)
        // 400 should have been replaced by 419 rather than both being printed.
        assertFalse("400 and 419 must not both appear", ticks.contains(400.0))
        assertEquals(419.0, ticks.last(), 1e-9)
    }

    @Test
    fun `samples are ordered by cumulative distance starting at zero`() {
        val samples = routeElevationSamples(denseRoute())
        assertNotNull(samples)
        assertEquals(0.0, samples!!.first().first, 1e-9)
        val distances = samples.map { it.first }
        assertEquals(
            "cumulative distance must be non-decreasing",
            distances.sorted(), distances
        )
    }
}
