package Kinetic_Eco.Tracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import Kinetic_Eco.Tracker.R
import Kinetic_Eco.Tracker.data.ActivitySegment
import Kinetic_Eco.Tracker.data.ActivityType
import Kinetic_Eco.Tracker.data.SessionStats
import Kinetic_Eco.Tracker.data.UnitSystem
import Kinetic_Eco.Tracker.ui.components.ActivityDonutChart
import Kinetic_Eco.Tracker.ui.components.SingleLineValueText
import Kinetic_Eco.Tracker.ui.components.RouteMapView
import Kinetic_Eco.Tracker.ui.utils.EnergyUnit
import Kinetic_Eco.Tracker.ui.utils.format
import Kinetic_Eco.Tracker.ui.utils.formatEnergyValue
import Kinetic_Eco.Tracker.ui.utils.formatSpeedMax
import Kinetic_Eco.Tracker.ui.utils.formatTime
import Kinetic_Eco.Tracker.ui.utils.energyUnitLabel
import Kinetic_Eco.Tracker.ui.utils.usesMetricDistance
import Kinetic_Eco.Tracker.ui.utils.ShareUtils
import java.util.*

private fun formatAltitude(meters: Double, unitSystem: UnitSystem): Pair<String, String> {
    return if (!unitSystem.usesMetricDistance()) {
        val feet = (meters * 3.28084).toInt()
        Pair("$feet", "ft")
    } else {
        Pair("${meters.toInt()}", "m")
    }
}

@Composable
fun SessionDetailScreen(
    session: SessionStats?,
    allSessions: List<SessionStats>,
    unitSystem: UnitSystem = UnitSystem.METRIC,
    energyUnit: EnergyUnit = EnergyUnit.KCAL,
    onBack: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val locales = LocalConfiguration.current.locales
    val locale = if (locales.isEmpty) Locale.getDefault() else (locales.get(0) ?: Locale.getDefault())
    // If a specific session is provided, show only that session's data
    // Otherwise, show aggregated data from all sessions
    val displayStats = session ?: aggregateSessions(allSessions)
    val isIndividualSession = session != null
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
    ) {
        // Route map - fixed at top (individual sessions only; aggregated view has no single route)
        if (isIndividualSession) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = stringResource(R.string.route),
                        style = MaterialTheme.typography.titleMedium,
                        color = colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    RouteMapView(
                        routePath = displayStats.routePath,
                        modifier = Modifier.fillMaxWidth(),
                        heightDp = 160
                    )
                }
            }
        }
        
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = colorScheme.onBackground
                        )
                    }
                    Text(
                        text = if (isIndividualSession) stringResource(R.string.session_details) else stringResource(R.string.all_sessions_overview),
                        style = MaterialTheme.typography.headlineLarge,
                        color = colorScheme.onBackground,
                        modifier = Modifier.weight(1f)
                    )
                    if (isIndividualSession) {
                        IconButton(
                            onClick = {
                                ShareUtils.launchShareSheet(
                                    context,
                                    ShareUtils.buildSessionShareText(displayStats, unitSystem)
                                )
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share session",
                                tint = colorScheme.onBackground
                            )
                        }
                    }
                }
            }
            
            item {
                Text(
                    text = if (isIndividualSession) {
                        formatSessionDate(session?.date ?: "", locale)
                    } else {
                        stringResource(R.string.total_from_sessions, allSessions.size)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            
            // CO2 Saved: prominent, enlarged
            item {
                SimplifiedStatCard(
                    title = stringResource(R.string.co2_conserved_label),
                    iconPainter = painterResource(R.drawable.ic_co2_carbon_neutral),
                    color = colorScheme.onSurfaceVariant,
                    value = displayStats.co2Conserved.format(2),
                    unit = "kg",
                    emphasize = true
                )
            }

            // CO2 Emitted: minimized (compact)
            if (displayStats.co2Emissions > 0) {
                item {
                    SimplifiedStatCardCompact(
                        title = stringResource(R.string.co2_emissions),
                        icon = Icons.Default.LocalFireDepartment,
                        color = colorScheme.onSurfaceVariant,
                        value = displayStats.co2Emissions.format(2),
                        unit = "kg"
                    )
                }
            }
            
            item {
                val (distValue, distUnit) = if (unitSystem.usesMetricDistance()) {
                    Pair((displayStats.totalDistance / 1000.0).format(2), "km")
                } else {
                    Pair((displayStats.totalDistance / 1609.344).format(2), "mi")
                }
                SimplifiedStatCard(
                    title = if (isIndividualSession) stringResource(R.string.distance) else stringResource(R.string.total_distance_label),
                    icon = Icons.Default.Straighten,
                    color = colorScheme.onSurfaceVariant,
                    value = distValue,
                    unit = distUnit
                )
            }
            
            item {
                SimplifiedStatCard(
                    title = if (isIndividualSession) stringResource(R.string.duration_label) else stringResource(R.string.total_duration),
                    icon = Icons.Default.Timer,
                    color = colorScheme.onSurfaceVariant,
                    value = formatTime(displayStats.totalDuration),
                    unit = ""
                )
            }
            
            item {
                SimplifiedStatCard(
                    title = stringResource(R.string.top_speed),
                    icon = Icons.Default.ShowChart,
                    color = colorScheme.onSurfaceVariant,
                    value = formatSpeedMax(displayStats.topSpeedMps, unitSystem),
                    unit = ""
                )
            }
            
            item {
                SimplifiedStatCard(
                    title = if (energyUnit == EnergyUnit.KCAL) stringResource(R.string.calories_burned) else stringResource(R.string.energy_burned),
                    icon = Icons.Default.FitnessCenter,
                    color = colorScheme.onSurfaceVariant,
                    value = formatEnergyValue(displayStats.caloriesBurned, energyUnit),
                    unit = energyUnitLabel(energyUnit)
                )
            }
            
            // Altitude section - same style as CO2 and Calories
            val hasAltitudeData = displayStats.startingAltitude != null || displayStats.stoppingAltitude != null ||
                displayStats.elevationGain > 0 || displayStats.elevationLoss > 0
            if (hasAltitudeData) {
                // Starting/Stopping altitude only for individual sessions
                if (isIndividualSession) {
                    displayStats.startingAltitude?.let { alt ->
                        val (value, unit) = formatAltitude(alt, unitSystem)
                        item {
                            SimplifiedStatCard(
                                title = stringResource(R.string.starting_altitude),
                                icon = Icons.Default.Place,
                                color = colorScheme.onSurfaceVariant,
                                value = value,
                                unit = unit
                            )
                        }
                    }
                    displayStats.stoppingAltitude?.let { alt ->
                        val (value, unit) = formatAltitude(alt, unitSystem)
                        item {
                            SimplifiedStatCard(
                                title = stringResource(R.string.stopping_altitude),
                                icon = Icons.Default.Place,
                                color = colorScheme.onSurfaceVariant,
                                value = value,
                                unit = unit
                            )
                        }
                    }
                }
                // Ascent/Descent for both individual and aggregated
                if (displayStats.elevationGain > 0) {
                    val (value, unit) = formatAltitude(displayStats.elevationGain, unitSystem)
                    item {
                        SimplifiedStatCard(
                            title = stringResource(R.string.ascent),
                            icon = Icons.AutoMirrored.Filled.TrendingUp,
                            color = colorScheme.onSurfaceVariant,
                            value = value,
                            unit = unit
                        )
                    }
                }
                if (displayStats.elevationLoss > 0) {
                    val (value, unit) = formatAltitude(displayStats.elevationLoss, unitSystem)
                    item {
                        SimplifiedStatCard(
                            title = stringResource(R.string.descent),
                            icon = Icons.AutoMirrored.Filled.TrendingDown,
                            color = colorScheme.onSurfaceVariant,
                            value = value,
                            unit = unit
                        )
                    }
                }
            }

            // Mode timeline — only shown when a session had 2+ distinct activity segments
            val meaningfulSegments = displayStats.segments.filter {
                it.type != ActivityType.IDLE && (it.distance >= 50.0 || (it.endTime - it.startTime) >= 15_000L)
            }
            if (meaningfulSegments.map { it.type }.toSet().size >= 2) {
                item {
                    ModeTimelineCard(segments = meaningfulSegments, unitSystem = unitSystem)
                }
            }

            // Activity breakdown donut chart
            item {
                ActivityDonutChart(stats = displayStats, unitSystem = unitSystem)
            }
        }
    }
}

@Composable
fun SimplifiedStatCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: androidx.compose.ui.graphics.Color,
    value: String,
    unit: String,
    emphasize: Boolean = false
) {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (emphasize) 24.dp else 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = color,
                    modifier = Modifier.size(if (emphasize) 36.dp else 28.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = title,
                    style = if (emphasize) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                    color = colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SingleLineValueText(
                    text = value,
                    style = if (emphasize) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
                    color = color,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End
                )
                if (unit.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(4.dp))
                    SingleLineValueText(
                        text = unit,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End
                    )
                }
            }
        }
    }
}

@Composable
fun SimplifiedStatCard(
    title: String,
    iconPainter: Painter,
    color: androidx.compose.ui.graphics.Color,
    value: String,
    unit: String,
    emphasize: Boolean = false
) {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (emphasize) 24.dp else 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    painter = iconPainter,
                    contentDescription = title,
                    tint = color,
                    modifier = Modifier.size(if (emphasize) 36.dp else 28.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = title,
                    style = if (emphasize) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                    color = colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SingleLineValueText(
                    text = value,
                    style = if (emphasize) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
                    color = color,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End
                )
                if (unit.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(4.dp))
                    SingleLineValueText(
                        text = unit,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End
                    )
                }
            }
        }
    }
}

@Composable
fun SimplifiedStatCardCompact(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: androidx.compose.ui.graphics.Color,
    value: String,
    unit: String
) {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = color,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SingleLineValueText(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    color = color,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End
                )
                if (unit.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(4.dp))
                    SingleLineValueText(
                        text = unit,
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End
                    )
                }
            }
        }
    }
}

private fun ActivityType.toTimelineLabel(): Int = when (this) {
    ActivityType.IDLE -> R.string.activity_idle
    ActivityType.WALKING -> R.string.walking
    ActivityType.RUNNING -> R.string.running
    ActivityType.CYCLING -> R.string.cycling
    ActivityType.MOTORCYCLE -> R.string.motorcycle
    ActivityType.TRAIN -> R.string.train
    ActivityType.DRIVING -> R.string.driving
    ActivityType.ELECTRIC_VEHICLE -> R.string.electric_vehicle
    ActivityType.FLYING -> R.string.flying
}

@Composable
fun ModeTimelineCard(segments: List<ActivitySegment>, unitSystem: UnitSystem) {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Mode timeline",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            segments.forEachIndexed { index, segment ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Connector line + dot
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(24.dp)
                    ) {
                        if (index > 0) {
                            Box(
                                modifier = Modifier
                                    .width(2.dp)
                                    .height(12.dp)
                                    .background(colorScheme.outlineVariant)
                            )
                        } else {
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(colorScheme.onSurface, shape = RoundedCornerShape(5.dp))
                        )
                        if (index < segments.lastIndex) {
                            Box(
                                modifier = Modifier
                                    .width(2.dp)
                                    .height(12.dp)
                                    .background(colorScheme.outlineVariant)
                            )
                        } else {
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    // Label + distance
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(segment.type.toTimelineLabel()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorScheme.onSurface,
                            fontWeight = FontWeight.Medium
                        )
                        val durationSec = (segment.endTime - segment.startTime) / 1000L
                        val distText = if (unitSystem.usesMetricDistance()) {
                            if (segment.distance >= 1000.0) "${(segment.distance / 1000.0).format(1)} km"
                            else "${segment.distance.toInt()} m"
                        } else {
                            if (segment.distance >= 1609.0) "${(segment.distance / 1609.344).format(1)} mi"
                            else "${(segment.distance * 3.28084).toInt()} ft"
                        }
                        Text(
                            text = "$distText · ${formatTime(durationSec)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

fun aggregateSessions(sessions: List<SessionStats>): SessionStats {
    return sessions.fold(SessionStats()) { acc, session ->
        SessionStats(
            totalDuration = acc.totalDuration + session.totalDuration,
            totalDistance = acc.totalDistance + session.totalDistance,
            caloriesBurned = acc.caloriesBurned + session.caloriesBurned,
            co2Emissions = acc.co2Emissions + session.co2Emissions,
            co2Conserved = acc.co2Conserved + session.co2Conserved,
            elevationGain = acc.elevationGain + session.elevationGain,
            elevationLoss = acc.elevationLoss + session.elevationLoss,
            startingAltitude = acc.startingAltitude ?: session.startingAltitude,
            stoppingAltitude = session.stoppingAltitude ?: acc.stoppingAltitude,
            topSpeedMps = maxOf(acc.topSpeedMps, session.topSpeedMps),
            segments = acc.segments + session.segments,
            breakdown = mergeBreakdowns(acc.breakdown, session.breakdown)
        )
    }
}

fun mergeBreakdowns(
    breakdown1: Map<Kinetic_Eco.Tracker.data.ActivityType, Kinetic_Eco.Tracker.data.ActivityBreakdown>,
    breakdown2: Map<Kinetic_Eco.Tracker.data.ActivityType, Kinetic_Eco.Tracker.data.ActivityBreakdown>
): Map<Kinetic_Eco.Tracker.data.ActivityType, Kinetic_Eco.Tracker.data.ActivityBreakdown> {
    val result = breakdown1.toMutableMap()
    breakdown2.forEach { (activity, breakdown) ->
        val existing = result[activity] ?: Kinetic_Eco.Tracker.data.ActivityBreakdown()
        result[activity] = Kinetic_Eco.Tracker.data.ActivityBreakdown(
            time = existing.time + breakdown.time,
            distance = existing.distance + breakdown.distance
        )
    }
    return result
}



