package Kinetic_Eco.Tracker.util

import Kinetic_Eco.Tracker.data.ActivitySegment
import Kinetic_Eco.Tracker.data.ActivityType

/**
 * Post-processing helper: decides which activity types a user may reclassify a recorded segment to.
 *
 * Rule ("speed band + neighbours"): a type is offered when the segment's own average speed falls in that
 * type's plausible speed band, OR an immediately adjacent segment's speed does, OR it's the (effective)
 * type of an adjacent segment. This lets a brief mis-detected idle in the middle of a drive be corrected
 * to Driving (its neighbours are fast) while still blocking physically nonsensical picks such as
 * idle → Flying.
 */
object ActivityReclassification {

    /**
     * Inclusive speed ranges (m/s) per activity type. Derived from [Kinetic_Eco.Tracker.data.Constants]
     * detection thresholds and widened slightly so a segment sitting near a boundary still "fits" the
     * neighbouring modes. Ranges overlap on purpose — at a given speed several modes are plausible.
     * (1 m/s = 3.6 km/h.)
     */
    private val SPEED_BANDS: Map<ActivityType, ClosedFloatingPointRange<Double>> = mapOf(
        ActivityType.IDLE to 0.0..0.7,                 // ~0–2.5 km/h
        ActivityType.WALKING to 0.3..2.5,              // ~1–9 km/h
        ActivityType.RUNNING to 2.0..5.5,              // ~7–20 km/h
        ActivityType.CYCLING to 3.0..13.0,             // ~11–47 km/h
        ActivityType.MOTORCYCLE to 4.0..60.0,          // ~14–216 km/h
        ActivityType.DRIVING to 4.0..55.0,             // ~14–198 km/h
        ActivityType.ELECTRIC_VEHICLE to 4.0..55.0,    // matches DRIVING band
        ActivityType.TRAIN to 4.0..90.0,               // ~14–324 km/h
        ActivityType.FLYING to 45.0..Double.MAX_VALUE  // ~160+ km/h
    )

    /**
     * Set of activity types [segment] may be reclassified to. Always includes the segment's current
     * effective type so the current selection is never missing from the list.
     *
     * @param allSegments the full segment list in any order; neighbours are resolved by start time.
     */
    fun allowedTypesFor(
        segment: ActivitySegment,
        allSegments: List<ActivitySegment>
    ): Set<ActivityType> {
        val sorted = allSegments.sortedBy { it.startTime }
        val idx = sorted.indexOfFirst { it.startTime == segment.startTime }

        val referenceSpeeds = mutableListOf(segment.avgSpeed)
        val neighbourTypes = mutableSetOf<ActivityType>()
        if (idx > 0) {
            referenceSpeeds.add(sorted[idx - 1].avgSpeed)
            neighbourTypes.add(sorted[idx - 1].effectiveType)
        }
        if (idx >= 0 && idx < sorted.lastIndex) {
            referenceSpeeds.add(sorted[idx + 1].avgSpeed)
            neighbourTypes.add(sorted[idx + 1].effectiveType)
        }

        val allowed = ActivityType.entries.filter { type ->
            val band = SPEED_BANDS[type] ?: return@filter false
            referenceSpeeds.any { it in band }
        }.toMutableSet()

        allowed.addAll(neighbourTypes)
        allowed.add(segment.effectiveType)
        return allowed
    }

    /** Minimum duration (ms) an IDLE segment must span to be shown as editable in the timeline. */
    const val IDLE_MIN_EDIT_DURATION_MS = 15_000L
}