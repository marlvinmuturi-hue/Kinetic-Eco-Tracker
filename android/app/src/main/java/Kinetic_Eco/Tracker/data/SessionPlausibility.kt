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

    /**
     * Fraction of direction reversals above which a route is scatter rather than travel.
     *
     * Calibrated on 17 recorded sessions at [REVERSAL_MIN_HOP_M] / [REVERSAL_ANGLE_DEG]:
     * genuine walks peaked at 0.07, drift bottomed at 0.16, with nothing between. Sits in that
     * gap with margin on both sides. The step-corroboration check above is the safety valve -
     * anything with a pedometer reading behind it never reaches this test at all.
     */
    const val DRIFT_REVERSAL_RATE = 0.12

    /**
     * A hop must be at least this long (m) for its bearing to be counted.
     *
     * Tuned on the recorded set rather than guessed. A longer filter sounds safer but is worse:
     * discarding short hops throws away the dense, coherent steps of a genuine walk while
     * keeping drift's long ones, so the two classes converge. At 1 m the separation is widest
     * (walks peak at 0.07, drift bottoms at 0.16); at 3 m it narrows to 0.14 vs 0.17, and by
     * 8 m the classes overlap and the test stops working altogether.
     */
    const val REVERSAL_MIN_HOP_M = 1.0

    /** Turn (degrees) beyond which a hop counts as a reversal - past a right angle, into doubling back. */
    const val REVERSAL_ANGLE_DEG = 120.0

    /** Bearings needed before a reversal rate is statistically worth acting on. */
    const val REVERSAL_MIN_SAMPLES = 12

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

        // Steps first: a pedometer reading consistent with the distance outranks all geometry.
        // Anything a person actually walked is kept, whatever shape the path took, so none of
        // the tests below can ever delete real pedestrian activity.
        if (stats.totalSteps >= stats.totalDistance * CORROBORATING_STEPS_PER_METRE) return false

        // Momentum: the one property multipath cannot imitate.
        //
        // Every speed, accuracy and displacement threshold this class and the tracking pipeline
        // have tried shares a weakness — drift can satisfy them, and severe drift satisfies them
        // best, because it manufactures exactly the signals the thresholds ask for. Direction is
        // different. A person or vehicle carries momentum: it cannot reverse course repeatedly
        // from one fix to the next. Multipath has no inertia, so its successive hops point
        // wherever the reflections happen to land.
        //
        // Measured across 17 recorded sessions the separation was complete: eight genuine walks
        // reversed on 1–7% of hops, nine drift sessions on 16–43%, with nothing in between.
        // Displacement-to-path ratio was tried first and overlapped (walks 0.27–0.72, drift
        // 0.06–0.25), which is why this uses heading rather than distance.
        //
        // A real drive is safe here even with no steps at all: roads are coherent, and a U-turn
        // is one reversal rather than a quarter of them.
        val reversals = directionReversalRate(route)
        if (reversals != null && reversals >= DRIFT_REVERSAL_RATE) return true

        val displacement = maxDisplacementMeters(route)

        // The hard ceiling: anything that got properly away from its start is real, whatever
        // shape the path was. This is what keeps track laps and park loops. It sits *after* the
        // momentum test deliberately — drift routinely wanders hundreds of metres from its
        // anchor, so this early return was rescuing exactly the sessions that needed catching.
        if (displacement >= DRIFT_MAX_DISPLACEMENT_M) return false

        // Below that, suspicious either because the path dwarfs the displacement, or because
        // the displacement is small in absolute terms however short the path.
        val withinRatio = displacement < DRIFT_DISPLACEMENT_RATIO * stats.totalDistance
        val withinAbsolute = displacement < DRIFT_ABSOLUTE_DISPLACEMENT_M
        if (!withinRatio && !withinAbsolute) return false

        // Step corroboration was already checked at the top of this function; reaching here
        // means it failed, so the geometry verdict stands.
        return true
    }

    /**
     * Fraction of route hops that reverse direction by more than [REVERSAL_ANGLE_DEG] from the
     * previous hop, or null when the route is too short to judge.
     *
     * Hops shorter than [REVERSAL_MIN_HOP_M] are skipped: a bearing computed across a couple of
     * metres is mostly noise, and including them would make a slow genuine walk look erratic.
     *
     * This is a shape measurement, not a speed one — it needs no timestamps and cannot be
     * influenced by whatever the chip claims about accuracy or Doppler.
     */
    fun directionReversalRate(route: List<RoutePoint>): Double? {
        if (route.size < 3) return null
        val bearings = mutableListOf<Double>()
        for (i in 1 until route.size) {
            val d = haversineMeters(
                route[i - 1].latitude, route[i - 1].longitude,
                route[i].latitude, route[i].longitude
            )
            if (d >= REVERSAL_MIN_HOP_M) {
                bearings.add(
                    bearingDegrees(
                        route[i - 1].latitude, route[i - 1].longitude,
                        route[i].latitude, route[i].longitude
                    )
                )
            }
        }
        if (bearings.size < REVERSAL_MIN_SAMPLES) return null
        var reversals = 0
        for (i in 1 until bearings.size) {
            val delta = abs(((bearings[i] - bearings[i - 1] + 540.0) % 360.0) - 180.0)
            if (delta > REVERSAL_ANGLE_DEG) reversals++
        }
        return reversals.toDouble() / (bearings.size - 1)
    }

    /** Forward bearing in degrees [0, 360). */
    private fun bearingDegrees(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val y = sin(dLon) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dLon)
        return (Math.toDegrees(kotlin.math.atan2(y, x)) + 360.0) % 360.0
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