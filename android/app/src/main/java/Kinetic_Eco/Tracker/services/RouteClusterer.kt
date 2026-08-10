package Kinetic_Eco.Tracker.services

import Kinetic_Eco.Tracker.data.*
import Kinetic_Eco.Tracker.data.database.TripEndpointRow
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The pure half of route intelligence: endpoints in, clusters out.
 *
 * Split from [RouteIntelligenceService] so the interesting logic — what counts as the
 * same journey, which mode to suggest, what the saving is worth — can be tested on the
 * JVM. The service half needs a `SessionManager`, which needs an `Application`, which
 * would drag every one of these assertions onto a device for no benefit.
 *
 * Nothing here touches Android, Room, or the network.
 */
internal object RouteClusterer {

    /** Radius within which two trip start-points count as the "same origin". */
    const val ORIGIN_RADIUS_M = 400.0

    /** Radius within which two trip end-points count as the "same destination". */
    const val DEST_RADIUS_M = 400.0

    /** Trips shorter than this are GPS jitter in a car park, not journeys. */
    const val MIN_TRIP_DISTANCE_M = 500.0

    /**
     * Clusters with fewer trips are not surfaced. Three, not two: two trips to the same
     * place is a coincidence, and calling that a habit invites the user to distrust
     * everything else on the card.
     */
    const val MIN_TRIPS_FOR_CLUSTER = 3

    /** Up to this distance, walking is a realistic alternative. */
    const val WALK_THRESHOLD_M = 2_500.0

    /** Up to this distance, cycling is a realistic alternative. */
    const val CYCLE_THRESHOLD_M = 10_000.0

    private const val EARTH_RADIUS_M = 6_371_000.0

    /**
     * Group journeys sharing an origin *and* a destination.
     *
     * Greedy single pass: each unassigned trip seeds a cluster and absorbs every later
     * trip whose start and end both fall within the radii. O(n²) worst case, which is
     * fine for the hundreds of sessions a device holds and avoids a clustering library
     * for what is really a bucketing problem.
     */
    fun clusterByOD(
        sessions: List<TripEndpointRow>,
        lookbackDays: Int,
        profile: VehicleProfile,
        prices: EnergyPrices? = null,
        measured: MeasuredEconomy? = null
    ): List<RouteCluster> {
        data class OdSession(
            val session: TripEndpointRow,
            val oLat: Double, val oLon: Double,
            val dLat: Double, val dLon: Double
        )

        val odSessions = sessions.mapNotNull { s ->
            // 0,0 is a real coordinate in the Atlantic, so a session that recorded no
            // usable fix must be dropped rather than clustered there with every other
            // GPS-less session.
            if (s.startLat == 0.0 && s.startLng == 0.0) return@mapNotNull null
            if (s.endLat == 0.0 && s.endLng == 0.0) return@mapNotNull null
            if (s.totalDistance < MIN_TRIP_DISTANCE_M) return@mapNotNull null
            OdSession(s, s.startLat, s.startLng, s.endLat, s.endLng)
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
                    buildCluster(nextId++, seed.oLat, seed.oLon, seed.dLat, seed.dLon, members, lookbackDays, profile, prices, measured)
                )
            }
        }

        return clusters.sortedByDescending { it.tripCount }
    }

    private fun buildCluster(
        id: Int,
        oLat: Double, oLon: Double,
        dLat: Double, dLon: Double,
        sessions: List<TripEndpointRow>,
        lookbackDays: Int,
        profile: VehicleProfile,
        prices: EnergyPrices?,
        measured: MeasuredEconomy?
    ): RouteCluster {
        val sorted = sessions.sortedBy { it.date }
        val avgDistanceM = sessions.map { it.totalDistance }.average()

        // Dominant activity = most cumulative distance across the cluster, IDLE excluded.
        val distanceByActivity = mutableMapOf<ActivityType, Double>()
        for (s in sessions) {
            for ((type, breakdown) in s.breakdown) {
                if (type != ActivityType.IDLE) {
                    distanceByActivity[type] = (distanceByActivity[type] ?: 0.0) + breakdown.distance
                }
            }
        }
        val dominant = distanceByActivity.maxByOrNull { it.value }?.key ?: ActivityType.WALKING

        return RouteCluster(
            id = id,
            originLat = oLat,
            originLon = oLon,
            destLat = dLat,
            destLon = dLon,
            tripCount = sessions.size,
            dominantActivity = dominant,
            avgDistanceM = avgDistanceM,
            avgCo2ConservedKg = sessions.map { it.co2Conserved }.average(),
            co2Timeline = sorted.map { s ->
                Co2DataPoint(
                    date = s.date,
                    co2Conserved = s.co2Conserved,
                    co2Emissions = s.co2Emissions,
                    distanceM = s.totalDistance,
                    activityType = dominant
                )
            },
            greenerAlternative = suggestAlternative(dominant, avgDistanceM, sessions.size, lookbackDays, profile, prices, measured),
            memberSessionIds = sessions.sortedByDescending { it.createdAt }.map { it.id }
        )
    }

    /**
     * The greenest mode that is genuinely plausible for this trip, or null.
     *
     * **Savings go through [Co2Calculator], not raw [CO2Factors].** An earlier version
     * multiplied bare constants, so a 2.5 L SUV and a small diesel were quoted identical
     * savings. `Co2Calculator` is the app's single source of truth precisely so a
     * hand-entered trip, a tracked trip and a suggestion cannot disagree.
     *
     * **Transit is never suggested.** An earlier version recommended a train beyond
     * 10 km using a generic 0.04 kg/km proxy, with its own TODO conceding it had no idea
     * whether a service exists on that route. Walking and cycling need no infrastructure
     * lookup — distance alone decides whether they are realistic.
     */
    fun suggestAlternative(
        dominant: ActivityType,
        avgDistanceM: Double,
        tripCount: Int,
        lookbackDays: Int,
        profile: VehicleProfile,
        prices: EnergyPrices? = null,
        measured: MeasuredEconomy? = null
    ): GreenerAlternative? {
        val avgKm = avgDistanceM / 1000.0
        val tripsPerYear = tripCount * (365.0 / lookbackDays)

        val candidate = when (dominant) {
            ActivityType.DRIVING,
            ActivityType.MOTORCYCLE,
            ActivityType.ELECTRIC_VEHICLE -> when {
                avgDistanceM <= WALK_THRESHOLD_M -> ActivityType.WALKING
                avgDistanceM <= CYCLE_THRESHOLD_M -> ActivityType.CYCLING
                else -> null
            }
            ActivityType.WALKING, ActivityType.RUNNING, ActivityType.CYCLING,
            ActivityType.TRAIN, ActivityType.IDLE, ActivityType.FLYING -> null
        } ?: return null

        // Emissions avoided, not the difference of net figures.
        //
        // `netKg` is `emittedKg - savedKg`, and a human-powered mode's `savedKg` *is*
        // the emissions it avoided versus a car baseline. Subtracting one net from the
        // other therefore counted the same avoided kilograms twice: driving 3.8 km
        // scored +0.80, cycling it scored -0.80, and the "saving" came out at 1.60 kg
        // instead of 0.80. Every premium user has been shown roughly double, and the
        // error was invisible until the figure appeared next to the money — which is
        // derived from the fuel bill alone and was always right.
        val savingsPerTrip = Co2Calculator.estimate(dominant, avgKm, profile).emittedKg -
            Co2Calculator.estimate(candidate, avgKm, profile).emittedKg

        // An EV on a short hop can already beat the alternative once the profile is
        // applied. Suggesting a "greener" option that saves nothing is noise.
        if (savingsPerTrip <= 0.0) return null

        // Money saved is the fuel the avoided trip would have burned. The candidate is
        // always human-powered, so it costs nothing to run and the whole of the current
        // mode's fuel bill is the saving.
        val costPerTrip = prices?.let {
            MobilityCostCalculator.costOf(
                Co2Calculator.estimate(dominant, avgKm, profile), profile, it, measured
            )?.amount
        }

        return GreenerAlternative(
            suggestedMode = candidate,
            estimatedSavingsKgPerTrip = savingsPerTrip,
            projectedAnnualSavingsKg = savingsPerTrip * tripsPerYear,
            projectedAnnualSavingsCost = costPerTrip?.let { it * tripsPerYear },
            currencyCode = prices?.currencyCode
        )
    }

    fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return EARTH_RADIUS_M * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
    }
}