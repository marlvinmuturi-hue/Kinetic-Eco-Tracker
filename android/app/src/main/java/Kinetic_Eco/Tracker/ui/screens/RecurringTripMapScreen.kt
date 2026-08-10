package Kinetic_Eco.Tracker.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import Kinetic_Eco.Tracker.R
import Kinetic_Eco.Tracker.data.UnitSystem
import Kinetic_Eco.Tracker.ui.components.RouteMapMultiSessionView
import Kinetic_Eco.Tracker.ui.utils.usesMetricDistance
import Kinetic_Eco.Tracker.viewmodel.AnalyticsViewModel

/**
 * One repeat journey, drawn.
 *
 * Overlays several runs of the same trip rather than a single representative route,
 * because the interesting thing about a journey made 33 times is how much it varies —
 * whether the user takes the same road every time, and where the detours happen.
 *
 * Only a bounded sample is drawn. Route geometry is the one thing that reliably
 * exhausts this app's heap, and five overlaid runs already answer the question.
 */
@Composable
fun RecurringTripMapScreen(
    analyticsViewModel: AnalyticsViewModel,
    unitSystem: UnitSystem,
    onBack: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val cluster by analyticsViewModel.selectedCluster.collectAsStateWithLifecycle()
    val paths by analyticsViewModel.clusterRoutePaths.collectAsStateWithLifecycle()
    val loading by analyticsViewModel.clusterRoutesLoading.collectAsStateWithLifecycle()

    val c = cluster
    val isMetric = unitSystem.usesMetricDistance()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = colorScheme.onBackground
                )
            }
            Text(
                text = stringResource(R.string.trip_map_title),
                style = MaterialTheme.typography.headlineMedium,
                color = colorScheme.onBackground
            )
        }

        if (c == null) {
            // Reached without a selection — process death, or a deep link. Say so rather
            // than showing an empty map that looks broken.
            Text(
                text = stringResource(R.string.trip_map_no_selection),
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant
            )
            return@Column
        }

        val distance = if (isMetric) {
            "%.1f km".format(c.avgDistanceM / 1000.0)
        } else {
            "%.1f mi".format(c.avgDistanceM / 1609.344)
        }

        Text(
            text = stringResource(R.string.trip_map_headline, c.tripCount, distance),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = colorScheme.onSurface
        )

        when {
            loading -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            paths.isEmpty() -> Text(
                // Endpoints exist (that is how it clustered) but the full traces may not
                // have survived, e.g. sessions restored from Firestore before route sync.
                text = stringResource(R.string.trip_map_no_routes),
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant
            )

            else -> {
                RouteMapMultiSessionView(
                    routePaths = paths,
                    heightDp = 320,
                    showElevationProfile = false
                )
                Text(
                    text = stringResource(R.string.trip_map_showing, paths.size, c.tripCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant
                )
            }
        }

        c.greenerAlternative?.let { alt ->
            Card(
                colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.trip_map_alternative_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.onSurface
                    )
                    val cost = alt.projectedAnnualSavingsCost
                    val currency = alt.currencyCode
                    Text(
                        text = if (cost != null && currency != null) {
                            stringResource(
                                R.string.recurring_trips_row_suggestion_money,
                                stringResource(alt.suggestedMode.mapLabelRes()),
                                currency,
                                String.format("%,.0f", cost),
                                String.format("%.1f", alt.projectedAnnualSavingsKg)
                            )
                        } else {
                            stringResource(
                                R.string.recurring_trips_row_suggestion,
                                stringResource(alt.suggestedMode.mapLabelRes()),
                                String.format("%.1f", alt.projectedAnnualSavingsKg)
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF43A047),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

/** Same label set the calculator and the trips card use, so the three never disagree. */
private fun Kinetic_Eco.Tracker.data.ActivityType.mapLabelRes(): Int = when (this) {
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
