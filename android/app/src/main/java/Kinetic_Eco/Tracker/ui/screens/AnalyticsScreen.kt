package Kinetic_Eco.Tracker.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import Kinetic_Eco.Tracker.R
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import Kinetic_Eco.Tracker.data.*
import Kinetic_Eco.Tracker.services.Co2EquivalencyService
import Kinetic_Eco.Tracker.ui.components.ActivityDonutChart
import Kinetic_Eco.Tracker.ui.components.AnalysisPeriodSelector
import Kinetic_Eco.Tracker.ui.components.AnalysisPeriodSelectorMode
import Kinetic_Eco.Tracker.ui.components.RouteMapView
import Kinetic_Eco.Tracker.ui.components.SingleLineValueText
import Kinetic_Eco.Tracker.ui.components.StatCard
import Kinetic_Eco.Tracker.ui.components.StatCardCompact
import Kinetic_Eco.Tracker.ui.theme.*
import Kinetic_Eco.Tracker.ui.utils.EnergyUnit
import Kinetic_Eco.Tracker.ui.utils.format
import Kinetic_Eco.Tracker.ui.utils.formatEnergy
import Kinetic_Eco.Tracker.ui.utils.formatSpeedMax
import Kinetic_Eco.Tracker.ui.utils.formatTime
import Kinetic_Eco.Tracker.ui.utils.usesMetricDistance
import Kinetic_Eco.Tracker.viewmodel.AnalyticsViewModel
import Kinetic_Eco.Tracker.viewmodel.AIAnalysisState

@Composable
fun AnalyticsScreen(
    viewModel: AnalyticsViewModel,
    userId: String,
    currentSessionStats: SessionStats,
    unitSystem: UnitSystem = UnitSystem.METRIC,
    energyUnit: EnergyUnit = EnergyUnit.KCAL,
    onNavigateToFeedback: () -> Unit = {}
) {
    val colorScheme = MaterialTheme.colorScheme
    val allSessions by viewModel.getAllSessions(userId).collectAsStateWithLifecycle(initialValue = emptyList())
    val displayStats = allSessions.firstOrNull() ?: currentSessionStats

    var sessionExpanded by remember { mutableStateOf(false) }
    var aiExpanded by remember { mutableStateOf(false) }

    val shouldExpandSession by viewModel.shouldExpandSessionSummary.collectAsStateWithLifecycle(initialValue = false)
    LaunchedEffect(shouldExpandSession) {
        if (shouldExpandSession) {
            sessionExpanded = true
            viewModel.clearExpandSessionSummary()
        }
    }

    // Column + verticalScroll (not LazyColumn): only a few sections, and LazyColumn
    // disposes off-screen items — recycling Osmdroid MapView + heavy composables causes
    // native crashes when scrolling back up.
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
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
                    heightDp = 220
                )
            }
        }

        CollapsibleCard(
            title = stringResource(R.string.session_summary),
            iconPainter = painterResource(R.drawable.ic_session_summary),
            expanded = sessionExpanded,
            onToggle = { sessionExpanded = !sessionExpanded }
        ) {
            SessionSummaryContentInner(
                displayStats = displayStats,
                unitSystem = unitSystem,
                energyUnit = energyUnit
            )
        }

        CollapsibleCard(
            title = stringResource(R.string.ai_powered_analysis),
            icon = Icons.Default.AutoAwesome,
            expanded = aiExpanded,
            onToggle = { aiExpanded = !aiExpanded }
        ) {
            AIAnalysisContent(viewModel = viewModel, userId = userId)
        }

        SettingsItem(
            title = stringResource(R.string.send_feedback),
            icon = Icons.Default.Feedback,
            onClick = onNavigateToFeedback
        )
    }
}

@Composable
private fun CollapsibleCard(
    title: String,
    iconPainter: Painter,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        painter = iconPainter,
                        contentDescription = null,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) stringResource(R.string.collapse) else stringResource(R.string.expand),
                    tint = colorScheme.onSurfaceVariant
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun CollapsibleCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) stringResource(R.string.collapse) else stringResource(R.string.expand),
                    tint = colorScheme.onSurfaceVariant
                )
            }
            // Fade only avoids height-animation layout glitches inside scrollable content.
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun SessionSummaryContentInner(
    displayStats: SessionStats,
    unitSystem: UnitSystem,
    energyUnit: EnergyUnit
) {
    val colorScheme = MaterialTheme.colorScheme

    // CO2 Saved: prominent, full width (enlarged)
    StatCard(
        title = stringResource(R.string.co2_conserved_label) + "*",
        value = "${displayStats.co2Conserved.format(3)} kg",
        iconPainter = painterResource(R.drawable.ic_co2_carbon_neutral),
        color = colorScheme.secondary,
        modifier = Modifier.fillMaxWidth(),
        emphasize = true
    )
    Text(
        text = stringResource(R.string.co2_baseline_disclaimer),
        style = MaterialTheme.typography.bodySmall,
        color = colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp)
    )
    // Steps and CO2 Emitted: minimized, side by side
    if (displayStats.totalSteps > 0 || displayStats.co2Emissions > 0) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (displayStats.totalSteps > 0) {
                StatCardCompact(
                    title = stringResource(R.string.total_steps),
                    value = String.format("%,d", displayStats.totalSteps),
                    icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                    color = colorScheme.secondary,
                    modifier = Modifier.weight(1f)
                )
            }
            if (displayStats.co2Emissions > 0) {
                StatCardCompact(
                    title = stringResource(R.string.co2_emitted_label) + "*",
                    value = "${displayStats.co2Emissions.format(3)} kg",
                    icon = Icons.Default.LocalFireDepartment,
                    color = Red500,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatCard(
            title = if (energyUnit == EnergyUnit.KCAL) stringResource(R.string.calories) else stringResource(R.string.energy),
            value = formatEnergy(displayStats.caloriesBurned, energyUnit),
            icon = Icons.Default.FitnessCenter,
            color = Amber500,
            modifier = Modifier.weight(1f)
        )
        StatCard(
            title = stringResource(R.string.total_time),
            value = formatTime(displayStats.totalDuration),
            icon = Icons.Default.Timer,
            color = colorScheme.tertiary,
            modifier = Modifier.weight(1f)
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatCard(
            title = stringResource(R.string.top_speed),
            value = formatSpeedMax(displayStats.topSpeedMps, unitSystem),
            icon = Icons.Default.ShowChart,
            color = colorScheme.tertiary,
            modifier = Modifier.weight(1f)
        )
        StatCard(
            title = stringResource(R.string.distance),
            value = if (unitSystem.usesMetricDistance()) {
                "${(displayStats.totalDistance / 1000.0).format(2)} km"
            } else {
                "${(displayStats.totalDistance / 1609.344).format(2)} mi"
            },
            icon = Icons.Default.Straighten,
            color = colorScheme.tertiary,
            modifier = Modifier.weight(1f)
        )
    }
    ActivityDonutChart(stats = displayStats)
    val netImpact = displayStats.co2Conserved - displayStats.co2Emissions
    val equivalencies = Co2EquivalencyService.getNetImpactEquivalencies(netImpact)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (netImpact >= 0) colorScheme.secondary.copy(alpha = 0.2f) else Red500.copy(alpha = 0.2f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.net_impact),
                style = MaterialTheme.typography.titleMedium,
                color = colorScheme.onSurface
            )
            SingleLineValueText(
                text = if (netImpact >= 0) {
                    stringResource(R.string.co2_conserved, netImpact)
                } else {
                    stringResource(R.string.co2_emitted_val, netImpact)
                },
                style = MaterialTheme.typography.headlineMedium,
                color = if (netImpact >= 0) colorScheme.secondary else Red500,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                textAlign = TextAlign.Start
            )
            if (equivalencies.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                equivalencies.forEach { eq ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        Text(
                            text = "${eq.icon} ${eq.description}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorScheme.onSurface,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.equivalent_source),
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun AIAnalysisContent(viewModel: AnalyticsViewModel, userId: String) {
    val colorScheme = MaterialTheme.colorScheme
    val analysisState by viewModel.aiAnalysisState.collectAsStateWithLifecycle()
    val rollingAnalysisDays by viewModel.rollingAnalysisDays.collectAsStateWithLifecycle()
    val analysisSessionDateKey by viewModel.analysisSessionDateKey.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.select_timeframe),
            style = MaterialTheme.typography.titleMedium,
            color = colorScheme.onSurface
        )
        AnalysisPeriodSelector(
            mode = AnalysisPeriodSelectorMode.AiAnalysis,
            rollingDays = rollingAnalysisDays,
            allTimeSelected = false,
            selectedSessionDateKey = analysisSessionDateKey,
            includeAllTimeOption = false,
            onAiRollingDaysChanged = { viewModel.setRollingAnalysisDays(it) },
            onAllTimeSelected = null,
            onSessionDaySelected = { viewModel.setAnalysisSessionDay(it) },
            onClearSessionDaySelection = { viewModel.clearAnalysisSessionDay() }
        )
        Button(
            onClick = { viewModel.analyzeActivity(userId) },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = analysisState !is AIAnalysisState.Loading,
            colors = ButtonDefaults.buttonColors(
                containerColor = colorScheme.primary,
                disabledContainerColor = colorScheme.primary.copy(alpha = 0.5f)
            )
        ) {
            if (analysisState is AIAnalysisState.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(stringResource(R.string.analyzing), style = MaterialTheme.typography.labelLarge)
            } else {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.analyze_activity), style = MaterialTheme.typography.labelLarge)
            }
        }
        when (val state = analysisState) {
            is AIAnalysisState.Success -> AnalysisResultsContent(state.analysis)
            is AIAnalysisState.Error -> ErrorCard(state.message)
            is AIAnalysisState.Idle -> IdleStateCard()
            is AIAnalysisState.Loading -> { /* Shown in button */ }
        }
    }
}



