package Kinetic_Eco.Tracker.services

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*
import Kinetic_Eco.Tracker.data.*
import Kinetic_Eco.Tracker.data.database.TripEndpointRow

/**
 * Mines stored GPS trajectories to surface commute patterns, CO₂ trends,
 * and greener-alternative suggestions — all computed locally with no
 * external API dependency.
 *
 * ## How clustering works
 * For each session that has a route path we extract origin = first GPS point
 * and destination = last GPS point.  Two trips are placed in the same cluster
 * when their origins are within [ORIGIN_RADIUS_M] of each other AND their
 * destinations are within [DEST_RADIUS_M] of each other (Haversine distance).
 * The algorithm is a single-pass greedy scan: O(n²) which is fine for the
 * dozens-to-low-hundreds of sessions a typical user accumulates.
 *
 * Origins and destinations come from `SessionDao.getTripEndpoints`, a projection over
 * four denormalised columns — never from the stored route geometry. Loading routes to
 * read their first and last point is what makes this feature OOM on a long history.
 *
 * ## Suggestions are limited to what the app can verify
 * Only walking and cycling are ever suggested, because distance alone decides whether
 * they are realistic. Transit is not: knowing whether a bus or train actually serves an
 * O-D pair needs a routing API this app does not call, and a confident recommendation
 * to catch a nonexistent train costs more trust than the suggestion could ever earn.
 * If transit routing is added later, gate a TRAIN suggestion on real availability.
 */
class RouteIntelligenceService(private val sessionManager: SessionManager) {

    companion object {
        /** Minimum rows worth querying for at all — clustering needs repeats. */
        private const val MIN_TRIPS_FOR_CLUSTER = RouteClusterer.MIN_TRIPS_FOR_CLUSTER
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /**
     * Load sessions from the last [lookbackDays] days that have a recorded
     * route path, cluster them by O-D proximity, and return the clusters
     * sorted by trip frequency (highest first).
     *
     * Returns an empty list when there are fewer than [MIN_TRIPS_FOR_CLUSTER]
     * sessions with route data in the window.
     */
    suspend fun getRouteClusters(
        userId: String,
        lookbackDays: Int = 90,
        profile: VehicleProfile = VehicleProfile.DEFAULT
    ): List<RouteCluster> {
        // Endpoint projection, not full sessions: clustering only ever needed the first
        // and last GPS fix, and selecting the entity drags every route point back
        // through Converters.toRoutePath — the Room OOM this app already hits on long
        // histories. Four doubles per row keeps this flat at any session count.
        val sinceMs = System.currentTimeMillis() - lookbackDays * 86_400_000L
        val rows = sessionManager.getTripEndpoints(userId, sinceMs)
        if (rows.size < MIN_TRIPS_FOR_CLUSTER) return emptyList()
        return RouteClusterer.clusterByOD(rows, lookbackDays, profile)
    }
}
