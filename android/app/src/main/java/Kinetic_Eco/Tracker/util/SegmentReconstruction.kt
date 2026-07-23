package Kinetic_Eco.Tracker.util

import Kinetic_Eco.Tracker.data.ActivitySegment
import Kinetic_Eco.Tracker.data.ActivityType
import Kinetic_Eco.Tracker.data.RoutePoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Rebuilds a session's [ActivitySegment] list from its stored [RoutePoint] geometry.
 *
 * Sessions saved before the idle-auto-stop segment fix (2026-07-19) persisted an empty segment
 * list even though each route point still carries its per-point [RoutePoint.activity] (the same
 * field the map colours by). This reconstructs a usable Mode timeline for that history by grouping
 * consecutive same-activity points into runs.
 *
 * Limitation: [RoutePoint] has no timestamp, so per-segment durations are **approximated** by
 * distributing the session's wall-clock span across points by index. Distances are exact (summed
 * Haversine). The result is deterministic for a given (routePath, start, end), which lets the edit
 * path treat the caller's reconstructed list as authoritative without re-deriving it.
 */
object SegmentReconstruction {

    /**
     * @param sessionStartMs wall-clock session start (ms); may be 0 when unknown — pass a
     *   non-negative anchor and durations stay correct even if absolute times don't.
     * @param sessionEndMs wall-clock session end (ms); must be ≥ [sessionStartMs].
     * @return segments in chronological order, or empty when there is nothing meaningful to
     *   reconstruct (fewer than 2 points, or no point carries an activity).
     */
    fun fromRoutePath(
        routePath: List<RoutePoint>,
        sessionStartMs: Long,
        sessionEndMs: Long
    ): List<ActivitySegment> {
        val n = routePath.size
        if (n < 2) return emptyList()
        // Legacy points with no activity at all → nothing to distinguish; leave it to the caller
        // to render the plain route rather than a single meaningless segment.
        if (routePath.none { it.activity != null }) return emptyList()

        val durationMs = (sessionEndMs - sessionStartMs).coerceAtLeast(0L)
        fun timeAt(idx: Int): Long = sessionStartMs + durationMs * idx / (n - 1)

        val segments = mutableListOf<ActivitySegment>()

        fun emit(startIdx: Int, endIdx: Int, activity: ActivityType) {
            var distance = 0.0
            for (i in startIdx until endIdx) {
                distance += haversineMeters(
                    routePath[i].latitude, routePath[i].longitude,
                    routePath[i + 1].latitude, routePath[i + 1].longitude
                )
            }
            val startMs = timeAt(startIdx)
            // Guarantee strictly-increasing, unique startTimes: the edit path keys segments by
            // startTime, so a collision (possible when durationMs is tiny) would drop a segment.
            val prevEnd = segments.lastOrNull()?.endTime ?: Long.MIN_VALUE
            val safeStart = if (startMs <= prevEnd) prevEnd + 1 else startMs
            val endMs = timeAt(endIdx).coerceAtLeast(safeStart + 1)
            val seconds = (endMs - safeStart) / 1000.0
            segments.add(
                ActivitySegment(
                    type = activity,
                    startTime = safeStart,
                    endTime = endMs,
                    distance = distance,
                    avgSpeed = if (seconds > 0) distance / seconds else 0.0
                )
            )
        }

        var runStart = 0
        var runActivity = routePath[0].activity ?: ActivityType.IDLE
        for (i in 1 until n) {
            val act = routePath[i].activity ?: ActivityType.IDLE
            if (act != runActivity) {
                emit(runStart, i - 1, runActivity)
                runStart = i
                runActivity = act
            }
        }
        emit(runStart, n - 1, runActivity)
        return segments
    }

    private const val EARTH_RADIUS_M = 6_371_000.0

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return EARTH_RADIUS_M * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}