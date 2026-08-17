package Kinetic_Eco.Tracker.data

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Whether a finished session describes real travel, or a phone sitting still.
 *
 * The one place the "should this be saved?" rule lives. It used to be a bare
 * `totalDistance < 200.0` repeated at three call sites, with a fourth — crash recovery —
 * having no check at all. Repeating a rule is how the four paths drift apart; a session
 * that survives one route and dies on another is worse than either outcome.
 *
 * Deliberately pure: no Android, no Location, no prefs, so the thresholds below can be
 * tested against real recorded routes on the JVM.
 */
object SessionPlausibility {

    /** Minimum accumulated path length worth keeping. The long-standing rule. */
    const val MIN_DISTANCE_M = 200.0

    /**
     * Furthest-from-start distance, as a share of accumulated path, below which a session
     * looks like scatter rather than travel.
     *
     * GPS noise accumulates **monotonically** — every jitter adds a positive segment and
     * nothing ever subtracts — so path length alone cannot separate 390 m of walking from
     * 390 m of a phone on a table. Net displacement can: a stationary phone never leaves
     * its error circle, whatever the clock says.
     */
    const val DRIFT_DISPLACEMENT_RATIO = 0.15

    /**
     * Absolute displacement above which a session is always kept, whatever the ratio says.
     *
     * This is what protects genuine tight loops. Laps of a 400 m running track reach roughly
     * 125 m from the start line, and a park circuit further still; both clear 75 m easily and
     * are never examined again. Only something that stayed inside a 75 m circle — a house, a
     * desk — can be rejected here.
     */
    const val DRIFT_MAX_DISPLACEMENT_M = 75.0

    /**
     * Displacement below which a session is drift regardless of the ratio.
     *
     * The ratio alone is too weak on short sessions: a 20 m-wide scatter can sit 40 m from
     * its own first fix — the first fix being as noisy as every other — which clears
     * `0.15 × 250 m`. Nothing genuine covers 200 m of ground while never getting 50 m from
     * where it started, except circling a car park, and that is both rare and worth little.
     */
    const val DRIFT_ABSOLUTE_DISPLACEMENT_M = 50.0

    /**
     * Steps per metre below which the pedometer is treated as *not* corroborating the
     * distance. One step per two metres is far under any real gait, so exceeding it is
     * strong evidence of genuine movement even inside a small radius.
     */
    const val CORROBORATING_STEPS_PER_METRE = 0.5

    enum class Reason {
        /** Shorter than [MIN_DISTANCE_M] of accumulated path. */
        TOO_SHORT,

        /**
         * Accumulated a plausible path length without ever getting far from where it
         * started, and the pedometer did not corroborate it. Indoor multipath.
         */
        NO_NET_DISPLACEMENT
    }

    /**
     * The reason this session should be dropped, or null to keep it.
     *
     * Order matters only for the log line; the rules are independent.
     */
    fun reasonToDiscard(stats: SessionStats): Reason? {
        if (stats.totalDistance < MIN_DISTANCE_M) return Reason.TOO_SHORT
        if (looksLikeDrift(stats)) return Reason.NO_NET_DISPLACEMENT
        return null
    }

    /**
     * True when the route never left a small circle around its own start, and no step count
     * argues otherwise.
     *
     * Returns false whenever the route is missing or too short to judge. A session recorded
     * without geometry is *unproven*, not *disproven*, and silently deleting the user's trip
     * on absent evidence is a worse failure than keeping a bad one.
     */
    fun looksLikeDrift(stats: SessionStats): Boolean {
        val route = stats.routePath
        if (route.size < 2) return false

        val displacement = maxDisplacementMeters(route)

        // The hard ceiling first: anything that got properly away from its start is real,
        // whatever shape the path was. This is what keeps track laps and park loops.
        if (displacement >= DRIFT_MAX_DISPLACEMENT_M) return false

        // Below that, suspicious either because the path dwarfs the displacement, or because
        // the displacement is small in absolute terms however short the path.
        val withinRatio = displacement < DRIFT_DISPLACEMENT_RATIO * stats.totalDistance
        val withinAbsolute = displacement < DRIFT_ABSOLUTE_DISPLACEMENT_M
        if (!withinRatio && !withinAbsolute) return false

        // A pedometer reading consistent with the distance outranks the geometry: someone
        // pacing a small yard really did walk, however little ground they covered.
        val stepsCorroborate =
            stats.totalSteps >= stats.totalDistance * CORROBORATING_STEPS_PER_METRE
        return !stepsCorroborate
    }

    /**
     * Greatest distance, in metres, that any point on the route reached from the first point.
     *
     * Measured from the start rather than as a bounding box: it is the quantity that
     * distinguishes "went somewhere" from "wandered near the sofa", and it survives a route
     * that doubles back on itself.
     */
    fun maxDisplacementMeters(route: List<RoutePoint>): Double {
        if (route.size < 2) return 0.0
        val start = route.first()
        var furthest = 0.0
        for (i in 1 until route.size) {
            val d = haversineMeters(
                start.latitude, start.longitude,
                route[i].latitude, route[i].longitude
            )
            if (d > furthest) furthest = d
        }
        return furthest
    }

    /** Mean Earth radius, metres. */
    private const val EARTH_RADIUS_M = 6_371_008.8

    /**
     * Great-circle distance. Hand-rolled rather than `Location.distanceBetween` so this
     * object stays JVM-testable; at these distances the difference is millimetres.
     */
    private fun haversineMeters(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(abs(a))))
    }
}