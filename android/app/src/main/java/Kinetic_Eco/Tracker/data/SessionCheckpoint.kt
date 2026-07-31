package Kinetic_Eco.Tracker.data

/**
 * A snapshot of an in-flight tracking session, written to disk periodically so a
 * trip survives the service being killed.
 *
 * `TrackingService` returns `START_STICKY` and holds the entire session in
 * memory. When Android or an OEM battery manager kills it mid-trip, the service
 * is restarted with a null intent and every accumulator is back at zero — the
 * trip up to that point is gone with no crash and no log. This is what makes it
 * recoverable.
 *
 * **Only accumulated results are captured, never detector state.** The Kalman
 * filter, activity history, jitter counters, hover anchors and speed buffers are
 * all deliberately excluded: they are transient signal-processing state that is
 * safe to cold-start, and persisting them would make the payload far larger and
 * the restore far more fragile for no gain. After a restore, classification
 * simply warms up again while the distance, steps and route already banked stay
 * intact.
 */
data class SessionCheckpoint(
    /** Bumped when the payload shape changes; older files are discarded on read. */
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    /** Wall-clock start of the session, preserved so the saved date stays correct. */
    val sessionStartTimeMs: Long,
    /** When this snapshot was taken — used to judge how much a restore lost. */
    val savedAtMs: Long,
    /**
     * Live accumulator snapshot: duration, distance, calories, CO₂, steps,
     * elevation, altitudes, milestones, top speed and the per-activity breakdown.
     */
    val stats: SessionStats,
    /** GPS trace so far. Held separately because the service keeps it apart from [stats]. */
    val routePath: List<RoutePoint>,
    /** Segments already closed. The still-open one is the three fields below. */
    val segments: List<ActivitySegment>,
    val openSegmentActivity: ActivityType,
    val openSegmentStartTimeMs: Long,
    val openSegmentStartDistanceM: Double
) {
    /** Distance banked at snapshot time — the amount a kill would otherwise lose. */
    val distanceMeters: Double get() = stats.totalDistance

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}