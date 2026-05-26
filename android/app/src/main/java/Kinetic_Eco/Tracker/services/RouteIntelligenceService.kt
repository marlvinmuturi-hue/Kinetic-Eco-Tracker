package Kinetic_Eco.Tracker.services

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*
import Kinetic_Eco.Tracker.data.*

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
 * ## Transit alternative
 * "If you took the bus instead" savings are estimated using an electric-transit
 * proxy of [TRANSIT_CO2_KG_PER_KM] (0.04 kg/km — the same value as electric
 * rail in the app's CO₂ factors).  Whether an actual bus/rail service exists
 * for a given O-D pair requires a transit-routing API call (e.g. the Google
 * Maps Directions API with `mode=transit`) which is outside the scope of this
 * service.  Add a `fetchTransitAvailability(oLat, oLon, dLat, dLon)` call
 * and gate the TRAIN suggestion on it when ready.
 */
class RouteIntelligenceService(private val sessionManager: SessionManager) {

    companion object {
        /** Radius within which two trip start-points count as the "same origin". */
        private const val ORIGIN_RADIUS_M = 400.0
        /** Radius within which two trip end-points count as the "same destination". */
        private const val DEST_RADIUS_M = 400.0
        /** Sessions with fewer GPS points are too sparse to extract a reliable O-D. */
        private const val MIN_ROUTE_POINTS = 3
        /** Clusters with fewer trips are not worth surfacing. */
        private const val MIN_TRIPS_FOR_CLUSTER = 2
        /** Electric bus/metro proxy for transit CO₂ savings estimate. */
        private const val TRANSIT_CO2_KG_PER_KM = 0.04
        /** Up to this distance, walking is a realistic alternative to driving. */
        private const val WALK_THRESHOLD_M = 2_500.0
        /** Up to this distance, cycling is a realistic alternative to driving. */
        private const val CYCLE_THRESHOLD_M = 10_000.0
        /** Earth radius in metres for Haversine computation. */
        private const val EARTH_RADIUS_M = 6_371_000.0
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
        lookbackDays: Int = 90
    ): List<RouteCluster> {
        val toDate = dateFormat.format(Date())
        val fromDate = dateFormat.format(Date(System.currentTimeMillis() - lookbackDays * 86_400_000L))
        val sessions = sessionManager.getSessionsWithRoutes(userId, fromDate, toDate)
        if (sessions.size < MIN_TRIPS_FOR_CLUSTER) return emptyList()
        return clusterByOD(sessions, lookbackDays)
    }

    // ── Clustering ────────────────────────────────────────────────────────────

    private fun clusterByOD(sessions: List<SessionStats>, lookbackDays: Int): List<RouteCluster> {
        data class OdSession(
            val session: SessionStats,
            val oLat: Double, val oLon: Double,
            val dLat: Double, val dLon: Double
        )

        val odSessions = sessions.mapNotNull { s ->
            val path = s.routePath.filter { it.latitude != 0.0 && it.longitude != 0.0 }
            if (path.size < MIN_ROUTE_POINTS) return@mapNotNull null
            val o = path.first()
            val d = path.last()
            OdSession(s, o.latitude, o.longitude, d.latitude, d.longitude)
        }

        val assigned = BooleanArray(odSessions.size)
        val clusters = mutableListOf<RouteCluster>()
        var nextId = 0

        for (i in odSessions.indices) {
            if (assigned[i]) continue
            val seed = odSessions[i]
            val members = mutableListOf(seed.session)
            assigned[i] = true

            for (j in (i + 1) until odSessions.size) {
                if (assigned[j]) continue
                val c = odSessions[j]
                val originClose = haversineM(seed.oLat, seed.oLon, c.oLat, c.oLon) <= ORIGIN_RADIUS_M
                val destClose = haversineM(seed.dLat, seed.dLon, c.dLat, c.dLon) <= DEST_RADIUS_M
                if (originClose && destClose) {
                    members.add(c.session)
                    assigned[j] = true
                }
            }

            if (members.size >= MIN_TRIPS_FOR_CLUSTER) {
                clusters.add(
                    buildCluster(nextId++, seed.oLat, seed.oLon, seed.dLat, seed.dLon, members, lookbackDays)
                )
            }
        }

        return clusters.sortedByDescending { it.tripCount }
    }

    private fun buildCluster(
        id: Int,
        oLat: Double, oLon: Double,
        dLat: Double, dLon: Double,
        sessions: List<SessionStats>,
        lookbackDays: Int
    ): RouteCluster {
        val sorted = sessions.sortedBy { it.date }
        val avgDistanceM = sessions.map { it.totalDistance }.average()

        // Dominant activity: activity with most cumulative distance across all sessions (IDLE excluded)
        val distanceByActivity = mutableMapOf<ActivityType, Double>()
        for (s in sessions) {
            for ((type, breakdown) in s.breakdown) {
                if (type != ActivityType.IDLE) {
                    distanceByActivity[type] = (distanceByActivity[type] ?: 0.0) + breakdown.distance
                }
            }
        }
        val dominant = distanceByActivity.maxByOrNull { it.value }?.key ?: ActivityType.WALKING

        val avgCo2Conserved = sessions.map { it.co2Conserved }.average()

        val timeline = sorted.map { s ->
            Co2DataPoint(
                date = s.date,
                co2Conserved = s.co2Conserved,
                co2Emissions = s.co2Emissions,
                distanceM = s.totalDistance,
                activityType = dominant
            )
        }

        val alternative = suggestAlternative(dominant, avgDistanceM, sessions.size, lookbackDays)

        return RouteCluster(
            id = id,
            originLat = oLat,
            originLon = oLon,
            destLat = dLat,
            destLon = dLon,
            tripCount = sessions.size,
            dominantActivity = dominant,
            avgDistanceM = avgDistanceM,
            avgCo2ConservedKg = avgCo2Conserved,
            co2Timeline = timeline,
            greenerAlternative = alternative
        )
    }

    // ── Greener alternative ───────────────────────────────────────────────────

    private fun suggestAlternative(
        dominant: ActivityType,
        avgDistanceM: Double,
        tripCount: Int,
        lookbackDays: Int
    ): GreenerAlternative? {
        val avgKm = avgDistanceM / 1000.0
        val tripsPerYear = tripCount * (365.0 / lookbackDays)

        return when (dominant) {
            ActivityType.DRIVING, ActivityType.MOTORCYCLE -> {
                when {
                    avgDistanceM <= WALK_THRESHOLD_M -> {
                        val savingsPerTrip = abs(CO2Factors.WALKING) * avgKm
                        GreenerAlternative(
                            suggestedMode = ActivityType.WALKING,
                            estimatedSavingsKgPerTrip = savingsPerTrip,
                            projectedAnnualSavingsKg = savingsPerTrip * tripsPerYear
                        )
                    }
                    avgDistanceM <= CYCLE_THRESHOLD_M -> {
                        val savingsPerTrip = abs(CO2Factors.CYCLING) * avgKm
                        GreenerAlternative(
                            suggestedMode = ActivityType.CYCLING,
                            estimatedSavingsKgPerTrip = savingsPerTrip,
                            projectedAnnualSavingsKg = savingsPerTrip * tripsPerYear
                        )
                    }
                    else -> {
                        // Longer trip — suggest public transit.
                        // TODO: gate on transit availability for this O-D pair via a
                        //   transit-routing API (e.g. Google Maps Directions API,
                        //   mode=transit) before surfacing this suggestion.
                        val savingsPerTrip = (CO2Factors.DRIVING - TRANSIT_CO2_KG_PER_KM) * avgKm
                        GreenerAlternative(
                            suggestedMode = ActivityType.TRAIN,
                            estimatedSavingsKgPerTrip = savingsPerTrip,
                            projectedAnnualSavingsKg = savingsPerTrip * tripsPerYear
                        )
                    }
                }
            }

            ActivityType.ELECTRIC_VEHICLE -> {
                // EVs are already low-carbon; only suggest active transport for short hops
                if (avgDistanceM <= WALK_THRESHOLD_M) {
                    val savingsPerTrip = abs(CO2Factors.WALKING) * avgKm
                    GreenerAlternative(
                        suggestedMode = ActivityType.WALKING,
                        estimatedSavingsKgPerTrip = savingsPerTrip,
                        projectedAnnualSavingsKg = savingsPerTrip * tripsPerYear
                    )
                } else if (avgDistanceM <= CYCLE_THRESHOLD_M) {
                    val savingsPerTrip = abs(CO2Factors.CYCLING) * avgKm
                    GreenerAlternative(
                        suggestedMode = ActivityType.CYCLING,
                        estimatedSavingsKgPerTrip = savingsPerTrip,
                        projectedAnnualSavingsKg = savingsPerTrip * tripsPerYear
                    )
                } else null
            }

            // Already among the greenest choices — no suggestion
            ActivityType.WALKING, ActivityType.RUNNING, ActivityType.CYCLING,
            ActivityType.TRAIN, ActivityType.IDLE, ActivityType.FLYING -> null
        }
    }

    // ── Geometry ─────────────────────────────────────────────────────────────

    private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return EARTH_RADIUS_M * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
    }
}