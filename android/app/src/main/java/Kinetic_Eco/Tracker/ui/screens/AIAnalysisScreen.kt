package Kinetic_Eco.Tracker.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import Kinetic_Eco.Tracker.R
import Kinetic_Eco.Tracker.data.ActivityAnalysis
import Kinetic_Eco.Tracker.data.ActivityType
import Kinetic_Eco.Tracker.data.SessionStats
import Kinetic_Eco.Tracker.ui.components.AnalysisPeriodSelector
import Kinetic_Eco.Tracker.ui.components.AnalysisPeriodSelectorMode
import Kinetic_Eco.Tracker.ui.theme.*
import Kinetic_Eco.Tracker.viewmodel.AIAnalysisState
import Kinetic_Eco.Tracker.viewmodel.AnalyticsViewModel
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun AIAnalysisScreen(viewModel: AnalyticsViewModel, userId: String) {
    val colorScheme = MaterialTheme.colorScheme
    val analysisState by viewModel.aiAnalysisState.collectAsStateWithLifecycle()
    val rollingAnalysisDays by viewModel.rollingAnalysisDays.collectAsStateWithLifecycle()
    val analysisSessionDateKey by viewModel.analysisSessionDateKey.collectAsStateWithLifecycle()
    // Sessions feed the locally-computed Activity Mix / Trends / Watch-outs
    // cards; the AI provides Wins / Recommendations / Context narrative.
    val allSessions by viewModel.getAllSessions(userId).collectAsStateWithLifecycle(initialValue = emptyList())

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = colorScheme.surfaceVariant.copy(alpha = 0.55f)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = stringResource(R.string.ai),
                            tint = colorScheme.onSurface,
                            modifier = Modifier.size(32.dp)
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.ai_powered_analysis),
                                style = MaterialTheme.typography.headlineSmall,
                                color = colorScheme.onSurface,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.ai_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Timeframe Selector
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.select_timeframe),
                        style = MaterialTheme.typography.titleMedium,
                        color = colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 12.dp)
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
                }
            }
        }

        // Analyze Button
        item {
            Button(
                onClick = { viewModel.analyzeActivity(userId) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = analysisState !is AIAnalysisState.Loading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colorScheme.surfaceVariant.copy(alpha = 0.95f),
                    contentColor = colorScheme.onSurface,
                    disabledContainerColor = colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    disabledContentColor = colorScheme.onSurfaceVariant
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (analysisState is AIAnalysisState.Loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = colorScheme.onSurface,
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
        }

        // Content based on state
        when (val state = analysisState) {
            is AIAnalysisState.Success -> {
                item {
                    AnalysisResultsContent(
                        analysis = state.analysis,
                        allSessions = allSessions,
                        windowDays = rollingAnalysisDays,
                    )
                }
            }
            is AIAnalysisState.Error -> {
                item {
                    ErrorCard(state.message)
                }
            }
            is AIAnalysisState.Idle -> {
                item {
                    IdleStateCard()
                }
            }
            is AIAnalysisState.Loading -> {
                // Loading state is shown in button
            }
        }
    }
}

/**
 * Detail-focused analysis layout. Five sections, no single 0–10 score:
 *   Activity Mix (local)  ·  Trends (local)  ·  Wins (AI + local)
 *   Watch-outs (local)    ·  Recommendations (AI)  ·  Context (AI narrative)
 *
 * Local sections use the live [allSessions] feed so they update immediately
 * after a new session even if the AI cache is older than [windowDays].
 */
@Composable
fun AnalysisResultsContent(
    analysis: ActivityAnalysis,
    allSessions: List<SessionStats> = emptyList(),
    windowDays: Int = 7,
) {
    val colorScheme = MaterialTheme.colorScheme
    val metrics = remember(allSessions, windowDays) { computeMetrics(allSessions, windowDays) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (analysis.cached) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = colorScheme.surfaceVariant.copy(alpha = 0.6f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = stringResource(R.string.cached_analysis, analysis.cacheAge),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        ActivityMixCard(metrics, windowDays)
        TrendsCard(metrics)
        WinsCard(analysis, metrics)
        WatchOutsCard(metrics)

        if (analysis.recommendations.isNotEmpty()) {
            RecommendationsCard(analysis.recommendations)
        }

        ContextCard(analysis.motivation, analysis.environmentalImpact)
    }
}

// ── Section cards ───────────────────────────────────────────────────────────

@Composable
private fun ActivityMixCard(metrics: AnalysisMetrics, windowDays: Int) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = scheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionHeader(icon = Icons.Default.PieChart, title = "Activity mix")
            Text(
                text = "Distance share over the last $windowDays days",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            if (metrics.totalKm <= 0.0) {
                Text(
                    text = "No tracked distance in this window yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
                return@Column
            }
            // Stacked bar; legend lines below.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp))
            ) {
                metrics.mix.forEach { mix ->
                    Box(
                        modifier = Modifier
                            .weight(mix.km.toFloat().coerceAtLeast(0.001f))
                            .fillMaxHeight()
                            .background(mix.color)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            metrics.mix.forEach { mix ->
                MixLegendRow(mix = mix, totalKm = metrics.totalKm)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = String.format(Locale.getDefault(), "%.1f km total", metrics.totalKm),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MixLegendRow(mix: MixSlice, totalKm: Double) {
    val pct = if (totalKm > 0.0) (mix.km / totalKm * 100.0).roundToInt() else 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(50))
                .background(mix.color)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = mix.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = String.format(Locale.getDefault(), "%.1f km · %d%%", mix.km, pct),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TrendsCard(metrics: AnalysisMetrics) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = scheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionHeader(icon = Icons.AutoMirrored.Filled.TrendingUp, title = "Trends")
            Text(
                text = "This period vs the previous one",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            if (metrics.trends.isEmpty()) {
                Text(
                    text = "Not enough history yet to compare against last period.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
                return@Column
            }
            metrics.trends.forEach { TrendRow(it) }
        }
    }
}

@Composable
private fun TrendRow(trend: TrendLine) {
    val scheme = MaterialTheme.colorScheme
    val accent = if (trend.positive) Green500 else Red400
    val icon = if (trend.deltaPct >= 0) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = trend.label,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = trend.formattedDelta,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = accent
        )
    }
}

@Composable
private fun WinsCard(analysis: ActivityAnalysis, metrics: AnalysisMetrics) {
    val scheme = MaterialTheme.colorScheme
    val highlights = analysis.highlights
    val lines = buildList {
        highlights?.bestDay?.takeIf { it.isNotBlank() }?.let { add("Best day · $it") }
        highlights?.topActivity?.takeIf { it.isNotBlank() }?.let { add("Top activity · $it") }
        metrics.bestStreakDays?.let { add("Tracked activity on $it of the last ${metrics.windowDays} days") }
        // Pull at most two AI insights so this card stays scannable.
        analysis.insights.take(2).forEach { add(it) }
    }
    if (lines.isEmpty()) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionHeader(icon = Icons.Default.EmojiEvents, title = "Wins")
            Spacer(Modifier.height(8.dp))
            lines.forEach { line ->
                BulletRow(text = line)
            }
        }
    }
}

@Composable
private fun WatchOutsCard(metrics: AnalysisMetrics) {
    val scheme = MaterialTheme.colorScheme
    if (metrics.watchOuts.isEmpty()) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = scheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionHeader(icon = Icons.Default.WarningAmber, title = "Watch-outs")
            Spacer(Modifier.height(8.dp))
            metrics.watchOuts.forEach { line -> BulletRow(text = line) }
        }
    }
}

@Composable
fun RecommendationsCard(recommendations: List<String>) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionHeader(icon = Icons.Default.Flag, title = stringResource(R.string.recommendations))
            Spacer(Modifier.height(8.dp))
            recommendations.forEach { rec ->
                Row(
                    modifier = Modifier.padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "✓",
                        style = MaterialTheme.typography.titleLarge,
                        color = scheme.onSurfaceVariant
                    )
                    Text(
                        text = rec,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ContextCard(motivation: String, environmentalImpact: String) {
    val scheme = MaterialTheme.colorScheme
    val parts = listOf(motivation, environmentalImpact).filter { it.isNotBlank() }
    if (parts.isEmpty()) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = scheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionHeader(
                iconPainter = painterResource(R.drawable.ic_co2_carbon_neutral),
                title = "Context"
            )
            Spacer(Modifier.height(8.dp))
            parts.forEachIndexed { i, p ->
                if (i > 0) Spacer(Modifier.height(8.dp))
                Text(
                    text = p,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    iconPainter: androidx.compose.ui.graphics.painter.Painter? = null,
    title: String,
) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            icon != null -> Icon(
                imageVector = icon,
                contentDescription = null,
                tint = scheme.onSurfaceVariant
            )
            iconPainter != null -> Icon(
                painter = iconPainter,
                contentDescription = null,
                tint = scheme.onSurfaceVariant
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = scheme.onSurface,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun BulletRow(text: String) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "•",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Red500.copy(alpha = 0.2f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = null,
                    tint = Red400,
                    modifier = Modifier.size(32.dp)
                )
                Column {
                    Text(
                        text = stringResource(R.string.analysis_error),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Red200
                    )
                }
            }
        }
    }
}

@Composable
fun IdleStateCard() {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = colorScheme.onSurfaceVariant,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.click_analyze),
                style = MaterialTheme.typography.titleMedium,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.track_first),
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── Local metrics ──────────────────────────────────────────────────────────

private const val MS_PER_DAY = 24L * 60L * 60L * 1000L

private data class MixSlice(val label: String, val km: Double, val color: Color)
private data class TrendLine(
    val label: String,
    val deltaPct: Double,
    val positive: Boolean,
    val formattedDelta: String,
)
private data class AnalysisMetrics(
    val windowDays: Int,
    val totalKm: Double,
    val mix: List<MixSlice>,
    val trends: List<TrendLine>,
    val watchOuts: List<String>,
    val bestStreakDays: Int?,
)

private fun computeMetrics(sessions: List<SessionStats>, windowDays: Int): AnalysisMetrics {
    val now = System.currentTimeMillis()
    val windowMs = windowDays * MS_PER_DAY
    val currStart = now - windowMs
    val prevStart = now - 2 * windowMs

    val curr = sessions.filter { it.sessionEndTimeMs in currStart..now }
    val prev = sessions.filter { it.sessionEndTimeMs in prevStart until currStart }

    val currStats = aggregate(curr)
    val prevStats = aggregate(prev)

    // Distance mix per activity (current window).
    val perActivityKm = mutableMapOf<ActivityType, Double>()
    for (s in curr) {
        for ((act, b) in s.breakdown) {
            if (b.distance <= 0.0) continue
            perActivityKm[act] = (perActivityKm[act] ?: 0.0) + b.distance / 1000.0
        }
    }
    val totalKm = perActivityKm.values.sum()
    val mix = perActivityKm.entries
        .sortedByDescending { it.value }
        .map { (act, km) -> MixSlice(label = act.label(), km = km, color = act.color()) }

    // Trends — only emit lines where both windows have non-zero data so a 0→X
    // jump doesn't show up as +∞ %.
    val trends = mutableListOf<TrendLine>()
    addTrend(trends, "Total distance", currStats.km, prevStats.km, unit = "km", positiveWhenHigher = true)
    addTrend(trends, "CO₂ saved", currStats.co2Saved, prevStats.co2Saved, unit = "kg", positiveWhenHigher = true)
    addTrend(trends, "CO₂ emitted", currStats.co2Emitted, prevStats.co2Emitted, unit = "kg", positiveWhenHigher = false)
    addTrend(trends, "Sessions", currStats.sessions.toDouble(), prevStats.sessions.toDouble(), unit = "", positiveWhenHigher = true)

    // Watch-outs: simple rule-based callouts from the current window. Kept
    // short so the card stays scannable; AI handles the prose.
    val watchOuts = mutableListOf<String>()
    val driveKm = perActivityKm[ActivityType.DRIVING] ?: 0.0
    val flyKm = perActivityKm[ActivityType.FLYING] ?: 0.0
    if (driveKm > 30) {
        watchOuts += "Driving accounts for %.1f km this window — about %.1f kg of avoidable CO₂.".format(driveKm, driveKm * 0.171)
    }
    if (flyKm > 50) {
        watchOuts += "Flying logged %.1f km — that's ~%.1f kg of CO₂, the biggest emitter per km.".format(flyKm, flyKm * 0.255)
    }
    if (currStats.co2Emitted > currStats.co2Saved && (currStats.co2Saved + currStats.co2Emitted) > 0.5) {
        watchOuts += "Net emissions exceeded savings this window — try one walking/cycling swap next."
    }

    // Best streak — count distinct days with at least one session inside the
    // window. Cheap stand-in for a real longest-consecutive-day streak.
    val days = curr.map { it.sessionEndTimeMs / MS_PER_DAY }.distinct().size
    val bestStreak = if (days > 0) days else null

    return AnalysisMetrics(
        windowDays = windowDays,
        totalKm = totalKm,
        mix = mix,
        trends = trends,
        watchOuts = watchOuts,
        bestStreakDays = bestStreak,
    )
}

private data class Aggregate(val km: Double, val co2Saved: Double, val co2Emitted: Double, val sessions: Int)

private fun aggregate(list: List<SessionStats>): Aggregate {
    var km = 0.0; var saved = 0.0; var emitted = 0.0
    for (s in list) {
        km += s.totalDistance / 1000.0
        saved += s.co2Conserved
        emitted += s.co2Emissions
    }
    return Aggregate(km, saved, emitted, list.size)
}

private fun addTrend(
    out: MutableList<TrendLine>,
    label: String,
    curr: Double,
    prev: Double,
    unit: String,
    positiveWhenHigher: Boolean,
) {
    if (curr <= 0 && prev <= 0) return
    val delta = curr - prev
    val pct = if (prev > 0) (delta / prev) * 100.0 else 100.0
    val higher = curr > prev
    val positive = if (positiveWhenHigher) higher else !higher
    val sign = if (delta >= 0) "+" else "−"
    val formatted = if (unit.isEmpty()) {
        "$sign${abs(delta).roundToInt()}"
    } else {
        String.format(Locale.getDefault(), "%s%.1f %s", sign, abs(delta), unit)
    }
    out += TrendLine(
        label = label,
        deltaPct = pct,
        positive = positive,
        formattedDelta = "$formatted (${pct.roundToInt()}%)"
    )
}

private fun ActivityType.label(): String = when (this) {
    ActivityType.WALKING -> "Walking"
    ActivityType.RUNNING -> "Running"
    ActivityType.CYCLING -> "Cycling"
    ActivityType.MOTORCYCLE -> "Motorcycle"
    ActivityType.TRAIN -> "Train"
    ActivityType.DRIVING -> "Driving"
    ActivityType.ELECTRIC_VEHICLE -> "EV"
    ActivityType.FLYING -> "Flying"
    ActivityType.IDLE -> "Idle"
}

private fun ActivityType.color(): Color = when (this) {
    ActivityType.WALKING -> Color(0xFF4CAF50)
    ActivityType.RUNNING -> Color(0xFFFF7043)
    ActivityType.CYCLING -> Color(0xFF29B6F6)
    ActivityType.TRAIN -> Color(0xFF7E57C2)
    ActivityType.ELECTRIC_VEHICLE -> Color(0xFF26A69A)
    ActivityType.MOTORCYCLE -> Color(0xFFEC407A)
    ActivityType.DRIVING -> Color(0xFFEF5350)
    ActivityType.FLYING -> Color(0xFFEF9A9A)
    ActivityType.IDLE -> Color(0xFFBDBDBD)
}
