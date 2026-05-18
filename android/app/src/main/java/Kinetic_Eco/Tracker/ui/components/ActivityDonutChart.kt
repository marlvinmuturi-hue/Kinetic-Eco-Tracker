package Kinetic_Eco.Tracker.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import Kinetic_Eco.Tracker.R
import Kinetic_Eco.Tracker.data.ActivityBreakdown
import Kinetic_Eco.Tracker.data.ActivityType
import Kinetic_Eco.Tracker.data.SessionStats
import Kinetic_Eco.Tracker.data.UnitSystem
import Kinetic_Eco.Tracker.ui.utils.format
import Kinetic_Eco.Tracker.ui.utils.formatTime
import Kinetic_Eco.Tracker.ui.utils.usesMetricDistance

enum class DonutMetric { TIME, DISTANCE }

/**
 * A reusable donut chart that visualises the activity breakdown
 * from a [SessionStats] object.
 *
 * - Hollow / donut style drawn with Compose Canvas.
 * - Toggle between time and distance.
 * - Shows dominant activity + percentage in the center.
 * - Legend below with colour dot, name, and percentage.
 */
@Composable
fun ActivityDonutChart(
    stats: SessionStats,
    unitSystem: UnitSystem = UnitSystem.METRIC,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    var metric by remember { mutableStateOf(DonutMetric.TIME) }

    val slicePalette = remember(colorScheme) {
        listOf(
            colorScheme.onSurface.copy(alpha = 0.90f),
            colorScheme.onSurface.copy(alpha = 0.72f),
            colorScheme.onSurface.copy(alpha = 0.54f),
            colorScheme.onSurface.copy(alpha = 0.38f),
            colorScheme.onSurface.copy(alpha = 0.82f),
            colorScheme.onSurface.copy(alpha = 0.64f)
        )
    }

    // Build slices based on the chosen metric
    val slices = remember(stats, metric, slicePalette) { buildSlices(stats, metric, slicePalette) }

    // Animate sweep on first appearance / data change
    val animationProgress = remember { Animatable(0f) }
    LaunchedEffect(slices) {
        animationProgress.snapTo(0f)
        animationProgress.animateTo(1f, animationSpec = tween(durationMillis = 800))
    }

    // Dominant slice
    val dominant = slices.maxByOrNull { it.percentage }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Text(
                text = stringResource(R.string.activity_breakdown),
                style = MaterialTheme.typography.titleLarge,
                color = colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            )

            // Metric toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = metric == DonutMetric.TIME,
                    onClick = { metric = DonutMetric.TIME },
                    label = { Text(stringResource(R.string.by_time)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = colorScheme.onSurface.copy(alpha = 0.10f),
                        selectedLabelColor = colorScheme.onSurface,
                        containerColor = colorScheme.surfaceVariant,
                        labelColor = colorScheme.onSurfaceVariant
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilterChip(
                    selected = metric == DonutMetric.DISTANCE,
                    onClick = { metric = DonutMetric.DISTANCE },
                    label = { Text(stringResource(R.string.by_distance)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = colorScheme.onSurface.copy(alpha = 0.10f),
                        selectedLabelColor = colorScheme.onSurface,
                        containerColor = colorScheme.surfaceVariant,
                        labelColor = colorScheme.onSurfaceVariant
                    )
                )
            }

            if (slices.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.no_activity_yet),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                // Donut chart with center text
                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val strokeWidth = 36f

                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val diameter = size.minDimension - strokeWidth
                        val topLeft = Offset(
                            (size.width - diameter) / 2f,
                            (size.height - diameter) / 2f
                        )
                        val arcSize = Size(diameter, diameter)

                        // Background ring
                        drawArc(
                            color = colorScheme.surfaceVariant,
                            startAngle = 0f,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                        )

                        // Slices
                        var currentAngle = -90f // start at 12 o'clock
                        slices.forEach { slice ->
                            val sweep = slice.sweepAngle * animationProgress.value
                            drawArc(
                                color = slice.color,
                                startAngle = currentAngle,
                                sweepAngle = sweep,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                            )
                            currentAngle += sweep
                        }
                    }

                    // Center text: dominant activity + percentage
                    if (dominant != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            SingleLineValueText(
                                text = "${dominant.percentage.toInt()}%",
                                style = MaterialTheme.typography.headlineMedium,
                                color = colorScheme.onSurface,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center
                            )
                            DonutCenterActivityLabel(
                                text = stringResource(dominant.activity.toActivityStringResId()),
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Legend
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    slices.forEach { slice ->
                        LegendRow(
                            color = slice.color,
                            label = stringResource(slice.activity.toActivityStringResId()),
                            percentage = slice.percentage,
                            colorScheme = colorScheme,
                            detail = when (metric) {
                                DonutMetric.TIME -> formatTime(slice.rawValue.toLong())
                                DonutMetric.DISTANCE -> if (unitSystem.usesMetricDistance()) {
                                    "${(slice.rawValue / 1000.0).format(2)} km"
                                } else {
                                    "${(slice.rawValue / 1609.344).format(2)} mi"
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

// ───────────────────────── internal helpers ─────────────────────────

private data class DonutSlice(
    val activity: ActivityType,
    val color: Color,
    val percentage: Float,   // 0-100
    val sweepAngle: Float,   // 0-360
    val rawValue: Float      // seconds or meters
)

private fun buildSlices(
    stats: SessionStats,
    metric: DonutMetric,
    sliceColors: List<Color>
): List<DonutSlice> {
    val entries: List<Pair<ActivityType, Float>> = stats.breakdown
        .map { (type, bd) ->
            val value = when (metric) {
                DonutMetric.TIME -> bd.time.toFloat()
                DonutMetric.DISTANCE -> bd.distance.toFloat()
            }
            type to value
        }
        .filter { it.second > 0f }
        .sortedByDescending { it.second }

    val total = entries.sumOf { it.second.toDouble() }.toFloat()
    if (total <= 0f) return emptyList()

    return entries.mapIndexed { index, (type, value) ->
        val pct = (value / total) * 100f
        DonutSlice(
            activity = type,
            color = sliceColors[index % sliceColors.size],
            percentage = pct,
            sweepAngle = (value / total) * 360f,
            rawValue = value
        )
    }
}

private fun ActivityType.toActivityStringResId(): Int = when (this) {
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
private fun DonutCenterActivityLabel(text: String, color: Color) {
    val fs = LocalDensity.current.fontScale
    val factor = if (fs > 1.1f) (1.15f / fs).coerceIn(0.45f, 1f) else 1f
    val base = MaterialTheme.typography.bodySmall
    Text(
        text = text,
        style = base,
        color = color,
        fontSize = base.fontSize * factor,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun DonutLegendLabel(text: String, color: Color) {
    val fs = LocalDensity.current.fontScale
    val factor = if (fs > 1.1f) (1.15f / fs).coerceIn(0.45f, 1f) else 1f
    val base = MaterialTheme.typography.bodyMedium
    Text(
        text = text,
        style = base,
        color = color,
        fontSize = base.fontSize * factor,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun LegendRow(
    color: Color,
    label: String,
    percentage: Float,
    colorScheme: androidx.compose.material3.ColorScheme,
    detail: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(color, shape = CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(modifier = Modifier.weight(1f)) {
                DonutLegendLabel(text = label, color = colorScheme.onSurfaceVariant)
            }
        }
        Row(
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SingleLineValueText(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp),
                textAlign = TextAlign.End
            )
            SingleLineValueText(
                text = "${percentage.toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End
            )
        }
    }
}
