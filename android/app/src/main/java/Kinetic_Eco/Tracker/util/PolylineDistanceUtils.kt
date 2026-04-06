package Kinetic_Eco.Tracker.util

import kotlin.math.hypot
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.atan2
import Kinetic_Eco.Tracker.data.ActivityBreakdown
import Kinetic_Eco.Tracker.data.RoutePoint
import Kinetic_Eco.Tracker.data.SessionStats

/**
 * Haversine distance between two WGS84 points (meters).
 */
fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val R = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        sin(dLon / 2) * sin(dLon / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return R * c
}

fun polylineLengthMeters(points: List<RoutePoint>): Double {
    if (points.size < 2) return 0.0
    var sum = 0.0
    for (i in 1 until points.size) {
        val a = points[i - 1]
        val b = points[i]
        sum += haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)
    }
    return sum
}

private fun toLocalMeters(lat: Double, lon: Double, refLat: Double, refLon: Double): Pair<Double, Double> {
    val R = 6371000.0
    val dLat = Math.toRadians(lat - refLat) * R
    val dLon = Math.toRadians(lon - refLon) * R * cos(Math.toRadians(refLat))
    return Pair(dLon, dLat)
}

private fun pointToSegmentDistanceMeters(p: RoutePoint, a: RoutePoint, b: RoutePoint): Double {
    val (bx, by) = toLocalMeters(b.latitude, b.longitude, a.latitude, a.longitude)
    val (px, py) = toLocalMeters(p.latitude, p.longitude, a.latitude, a.longitude)
    val lenSq = bx * bx + by * by
    val t = if (lenSq > 1e-12) ((px * bx + py * by) / lenSq).coerceIn(0.0, 1.0) else 0.0
    val sx = t * bx
    val sy = t * by
    return hypot(px - sx, py - sy)
}

/**
 * Douglas–Peucker simplification in a local tangent plane (meters).
 */
fun douglasPeuckerRoute(points: List<RoutePoint>, epsilonMeters: Double): List<RoutePoint> {
    if (points.size < 3) return points
    var maxDist = 0.0
    var maxIndex = 0
    val first = points.first()
    val last = points.last()
    for (i in 1 until points.size - 1) {
        val d = pointToSegmentDistanceMeters(points[i], first, last)
        if (d > maxDist) {
            maxDist = d
            maxIndex = i
        }
    }
    return if (maxDist > epsilonMeters) {
        val left = douglasPeuckerRoute(points.subList(0, maxIndex + 1), epsilonMeters)
        val right = douglasPeuckerRoute(points.subList(maxIndex, points.size), epsilonMeters)
        left + right.drop(1)
    } else {
        listOf(first, last)
    }
}

private const val EPSILON_SIMPLIFY_METERS = 5.0
private const val MIN_FACTOR_CHANGE = 0.003

/**
 * Reduce total distance and per-activity breakdown using a Douglas–Peucker path length vs raw path length.
 * Keeps steps and times; scales CO2 and calories proportionally.
 */
fun adjustStatsForSimplifiedPath(stats: SessionStats, rawPath: List<RoutePoint>): SessionStats {
    if (rawPath.size < 2) return stats
    val rawLen = polylineLengthMeters(rawPath)
    if (rawLen < 1.0) return stats
    val simplified = douglasPeuckerRoute(rawPath, EPSILON_SIMPLIFY_METERS)
    val simpLen = polylineLengthMeters(simplified)
    if (simpLen <= 0) return stats
    var factor = simpLen / rawLen
    factor = factor.coerceIn(0.0, 1.0)
    if (factor >= 1.0 - MIN_FACTOR_CHANGE) return stats

    val newBreakdown = stats.breakdown.mapValues { (_, b) ->
        ActivityBreakdown(
            time = b.time,
            distance = b.distance * factor,
            steps = b.steps
        )
    }
    return stats.copy(
        totalDistance = stats.totalDistance * factor,
        co2Emissions = stats.co2Emissions * factor,
        co2Conserved = stats.co2Conserved * factor,
        caloriesBurned = stats.caloriesBurned * factor,
        breakdown = newBreakdown
    )
}
