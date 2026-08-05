package Kinetic_Eco.Tracker.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import Kinetic_Eco.Tracker.R
import Kinetic_Eco.Tracker.data.ActivityColors
import Kinetic_Eco.Tracker.data.RouteCluster
import Kinetic_Eco.Tracker.data.UnitSystem
import Kinetic_Eco.Tracker.ui.utils.usesMetricDistance

private val PremiumGold = Color(0xFFFFB300)

/**
 * Repeat journeys mined from the user's own tracked routes, with what switching mode
 * would be worth.
 *
 * This is the one insight the app can offer that a generic carbon calculator cannot:
 * it is built from trips the user actually took, not trips they were asked to imagine.
 * That is also why it is the premium feature — it needs their history to exist.
 *
 * Non-subscribers see the locked state rather than nothing at all. Hiding the feature
 * entirely would leave the paywall's "deeper insights" claim unevidenced; showing what
 * it does, without the numbers, is the honest version of an upsell.
 */
@Composable
fun RecurringTripsCard(
    clusters: List<RouteCluster>,
    loading: Boolean,
    isPremium: Boolean,
    unitSystem: UnitSystem,
    onGoPremium: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    var expanded by rememberSaveable { mutableStateOf(false) }

    // Only clusters with something actionable are worth a row. A repeated walk is a
    // nice habit, but it is not advice, and padding the list with it would bury the
    // trips where a change actually pays.
    val actionable = remember(clusters) {
        clusters.filter { it.greenerAlternative != null }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = isPremium) { expanded = !expanded }
            ) {
                Icon(
                    imageVector = Icons.Default.Repeat,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.recurring_trips_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.onSurface
                    )
                    Text(
                        text = when {
                            !isPremium -> stringResource(R.string.recurring_trips_locked_subtitle)
                            loading -> stringResource(R.string.recurring_trips_loading)
                            actionable.isEmpty() -> stringResource(R.string.recurring_trips_empty)
                            else -> stringResource(
                                R.string.recurring_trips_summary,
                                actionable.size,
                                formatKg(actionable.sumOf { it.greenerAlternative!!.projectedAnnualSavingsKg })
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
                if (isPremium && actionable.isNotEmpty()) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = colorScheme.onSurfaceVariant
                    )
                }
            }

            if (!isPremium) {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onGoPremium,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PremiumGold,
                        contentColor = Color(0xFF1A1200)
                    )
                ) {
                    Icon(Icons.Default.WorkspacePremium, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.recurring_trips_unlock))
                }
                return@Column
            }

            if (loading) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            AnimatedVisibility(visible = expanded && actionable.isNotEmpty()) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    actionable.forEach { cluster ->
                        RecurringTripRow(cluster = cluster, unitSystem = unitSystem)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecurringTripRow(cluster: RouteCluster, unitSystem: UnitSystem) {
    val colorScheme = MaterialTheme.colorScheme
    val alternative = cluster.greenerAlternative ?: return
    val isMetric = unitSystem.usesMetricDistance()
    val distance = if (isMetric) {
        "%.1f km".format(cluster.avgDistanceM / 1000.0)
    } else {
        "%.1f mi".format(cluster.avgDistanceM / 1609.344)
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colorScheme.surface.copy(alpha = 0.6f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Repeat,
                    contentDescription = null,
                    tint = ActivityColors.getColor(cluster.dominantActivity),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(
                        R.string.recurring_trips_row_headline,
                        cluster.tripCount,
                        distance,
                        stringResource(cluster.dominantActivity.tripLabelRes())
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(
                    R.string.recurring_trips_row_suggestion,
                    stringResource(alternative.suggestedMode.tripLabelRes()),
                    formatKg(alternative.projectedAnnualSavingsKg)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF43A047),
                fontWeight = FontWeight.Medium
            )
        }
    }
}

private fun formatKg(value: Double): String = "%.1f".format(value)

/** Mode name — the same string set the calculator uses, so the two never disagree. */
private fun Kinetic_Eco.Tracker.data.ActivityType.tripLabelRes(): Int = when (this) {
    Kinetic_Eco.Tracker.data.ActivityType.IDLE -> R.string.activity_idle
    Kinetic_Eco.Tracker.data.ActivityType.WALKING -> R.string.walking
    Kinetic_Eco.Tracker.data.ActivityType.RUNNING -> R.string.running
    Kinetic_Eco.Tracker.data.ActivityType.CYCLING -> R.string.cycling
    Kinetic_Eco.Tracker.data.ActivityType.MOTORCYCLE -> R.string.motorcycle
    Kinetic_Eco.Tracker.data.ActivityType.TRAIN -> R.string.train
    Kinetic_Eco.Tracker.data.ActivityType.DRIVING -> R.string.driving
    Kinetic_Eco.Tracker.data.ActivityType.ELECTRIC_VEHICLE -> R.string.electric_vehicle
    Kinetic_Eco.Tracker.data.ActivityType.FLYING -> R.string.flying
}