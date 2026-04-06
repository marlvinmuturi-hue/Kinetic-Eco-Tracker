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
    @ColumnInfo(name = "routePathJson") val routePath: List<RoutePoint> = emptyList(),
    val createdAt: Long, // timestamp
    val breakdown: Map<ActivityType, ActivityBreakdownEntity> // JSON stored as String
)

data class ActivityBreakdownEntity(
    val time: Long,
    val distance: Double,
    val steps: Int = 0 // step count for this activity
)



