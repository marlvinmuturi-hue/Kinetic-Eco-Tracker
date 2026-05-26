package Kinetic_Eco.Tracker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import Kinetic_Eco.Tracker.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import Kinetic_Eco.Tracker.data.ActivityType
import Kinetic_Eco.Tracker.data.SessionStats
import kotlin.math.roundToInt

/**
 * Pie chart of CO₂ saved per activity over the last 7 days. Replaces the goal
 * tile on the dashboard. Activities that emit (DRIVING, FLYING) and IDLE are
 * excluded since they don't contribute to savings — those swap targets show up
 * in the suggestions copy elsewhere.
 *
 * The chart is drawn with Compose [Canvas] (no external dependency). Slice
 * colours come from a small static palette so the rendering is deterministic.
 */
@Composable
fun Co2DistributionPieChart(
    allSessions: List<SessionStats>,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val slices = remember(allSessions) { computeSlices(ctx, allSessions) }
    val total = slices.sumOf { it.kg }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.co2_saved_by_activity),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface,
            )
            Spacer(Modifier.height(16.dp))

            if (total <= 0.0 || slices.isEmpty()) {
                Text(
                    text = stringResource(R.string.co2_pie_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                return@Column
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                PieChartCanvas(
                    slices = slices,
                    total = total,
                    ringColor = scheme.surface,
                    modifier = Modifier.size(140.dp),
                )
                Spacer(Modifier.width(20.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.co2_pie_kg_total, total),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    slices.forEach { slice ->
                        LegendRow(slice = slice, total = total)
                    }
                }
            }
        }
    }
}

@Composable
private fun PieChartCanvas(
    slices: List<Slice>,
    total: Double,
    ringColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        var start = -90f // start at 12 o'clock
        val box = Size(size.minDimension, size.minDimension)
        val offset = Offset(
            x = (size.width - box.width) / 2f,
            y = (size.height - box.height) / 2f,
        )
        slices.forEach { slice ->
            val sweep = (slice.kg / total).toFloat() * 360f
            drawArc(
                color = slice.color,
                startAngle = start,
                sweepAngle = sweep,
                useCenter = true,
                topLeft = offset,
                size = box,
            )
            start += sweep
        }
        // Centre hole so it reads as a donut — easier to scan than a filled pie.
        val holeRadius = box.minDimension * 0.28f
        drawCircle(
            color = ringColor,
            radius = holeRadius,
            center = Offset(offset.x + box.width / 2f, offset.y + box.height / 2f),
        )
        // Subtle outer stroke to separate slices on similar-coloured themes.
        drawArc(
            color = ringColor.copy(alpha = 0.0f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = offset,
            size = box,
            style = Stroke(width = 0f),
        )
    }
}

@Composable
private fun LegendRow(slice: Slice, total: Double) {
    val pct = (slice.kg / total * 100.0).roundToInt()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(slice.color),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = slice.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "$pct%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Domain ──────────────────────────────────────────────────────────────────

private const val MS_PER_DAY = 24L * 60L * 60L * 1000L

private const val CAR_KG_PER_KM = 0.21       // matches BASELINE_DRIVING_CO2_PER_KM
private const val EV_KG_PER_KM = 0.053
private const val TRAIN_KG_PER_KM = 0.04     // electric-rail direct; savings = 0.21 − 0.04 = 0.17
private const val MOTORCYCLE_KG_PER_KM = 0.100

private data class Slice(val label: String, val kg: Double, val color: Color)

private fun computeSlices(context: Context, sessions: List<SessionStats>): List<Slice> {
    val cutoff = System.currentTimeMillis() - 7 * MS_PER_DAY
    val perActivity = mutableMapOf<ActivityType, Double>()
    for (s in sessions) {
        if (s.sessionEndTimeMs < cutoff) continue
        for ((act, b) in s.breakdown) {
            val km = b.distance / 1000.0
            val saved = km * savedKgPerKm(act)
            if (saved <= 0.0) continue
            perActivity[act] = (perActivity[act] ?: 0.0) + saved
        }
    }
    return perActivity.entries
        .sortedByDescending { it.value }
        .map { (act, kg) -> Slice(label = act.displayName(context), kg = kg, color = act.color()) }
}

private fun savedKgPerKm(activity: ActivityType): Double = when (activity) {
    ActivityType.WALKING, ActivityType.RUNNING, ActivityType.CYCLING -> CAR_KG_PER_KM
    ActivityType.TRAIN -> CAR_KG_PER_KM - TRAIN_KG_PER_KM
    ActivityType.ELECTRIC_VEHICLE -> CAR_KG_PER_KM - EV_KG_PER_KM
    ActivityType.MOTORCYCLE -> CAR_KG_PER_KM - MOTORCYCLE_KG_PER_KM
    else -> 0.0
}

private fun ActivityType.displayName(context: Context): String = when (this) {
    ActivityType.WALKING -> context.getString(R.string.walking)
    ActivityType.RUNNING -> context.getString(R.string.running)
    ActivityType.CYCLING -> context.getString(R.string.cycling)
    ActivityType.MOTORCYCLE -> context.getString(R.string.motorcycle)
    ActivityType.TRAIN -> context.getString(R.string.train)
    ActivityType.DRIVING -> context.getString(R.string.driving)
    ActivityType.ELECTRIC_VEHICLE -> context.getString(R.string.activity_ev_short)
    ActivityType.FLYING -> context.getString(R.string.flying)
    ActivityType.IDLE -> context.getString(R.string.activity_idle)
}

private fun ActivityType.color(): Color = when (this) {
    ActivityType.WALKING -> Color(0xFF4CAF50)         // green
    ActivityType.RUNNING -> Color(0xFFFF7043)         // deep orange
    ActivityType.CYCLING -> Color(0xFF29B6F6)         // light blue
    ActivityType.TRAIN -> Color(0xFF7E57C2)           // purple
    ActivityType.ELECTRIC_VEHICLE -> Color(0xFF26A69A) // teal
    ActivityType.MOTORCYCLE -> Color(0xFFEC407A)       // pink
    ActivityType.DRIVING -> Color(0xFFEF5350)          // red
    ActivityType.FLYING -> Color(0xFFEF9A9A)           // light red
    ActivityType.IDLE -> Color(0xFFBDBDBD)             // grey
}
