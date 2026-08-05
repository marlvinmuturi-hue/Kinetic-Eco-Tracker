package Kinetic_Eco.Tracker.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import Kinetic_Eco.Tracker.data.ActivityType
import Kinetic_Eco.Tracker.data.RoutePoint

@Entity(tableName = "sessions")
@TypeConverters(Converters::class)
data class SessionEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val date: String, // ISO date string
    val totalDuration: Long, // seconds
    val totalDistance: Double, // meters
    val caloriesBurned: Double, // kcal
    val co2Emissions: Double, // kg
    val co2Conserved: Double, // kg
    val totalSteps: Int = 0, // total step count
    val elevationGain: Double = 0.0, // meters
    val elevationLoss: Double = 0.0, // meters
    val startingAltitude: Double? = null, // meters
    val stoppingAltitude: Double? = null, // meters
    val minAltitude: Double? = null, // meters (null = not available)
    val maxAltitude: Double? = null, // meters (null = not available)
    val topSpeedMps: Double = 0.0, // max raw GPS speed in m/s
    val kmMilestonesJson: String = "[]", // JSON list of KmMilestone
    val segmentsJson: String = "[]", // JSON list of ActivitySegment
    @ColumnInfo(name = "routePathJson") val routePath: List<RoutePoint> = emptyList(),
    val createdAt: Long, // timestamp
    val breakdown: Map<ActivityType, ActivityBreakdownEntity>, // JSON stored as String
    /**
     * True once this session has been confirmed written to Firestore (server-acked).
     * New rows start false; a background [SessionSyncWorker] backfills any that are still false
     * (e.g. the fire-and-forget sync at save time was killed with the process). Defaults to 0 for
     * rows migrated from schema v7, so existing local sessions are (idempotently) re-uploaded.
     */
    @ColumnInfo(name = "synced", defaultValue = "0") val synced: Boolean = false,

    // ── Denormalised route endpoints (schema v9) ─────────────────────────────
    // First and last GPS fix of [routePath], copied out at save time.
    //
    // These exist so recurring-trip detection can group thousands of sessions by
    // where they started and ended **without ever loading `routePathJson`**.
    // Selecting the entity deserialises every route point through
    // `Converters.toRoutePath`, which already OOMs on large histories (see
    // `SessionDao.getTripEndpoints` and the projection it feeds). Four doubles per
    // row is the difference between a query that scales and one that crashes.
    //
    // Null means either no GPS fix was recorded (indoor/flying/permission denied)
    // or the row predates v9 and has not been backfilled yet — callers must treat
    // null as "unknown", never as 0,0 (which is a real location in the Atlantic).
    val startLat: Double? = null,
    val startLng: Double? = null,
    val endLat: Double? = null,
    val endLng: Double? = null
)

data class ActivityBreakdownEntity(
    val time: Long,
    val distance: Double,
    val steps: Int = 0 // step count for this activity
)



