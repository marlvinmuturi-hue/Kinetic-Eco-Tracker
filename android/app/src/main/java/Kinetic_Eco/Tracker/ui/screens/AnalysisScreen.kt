package Kinetic_Eco.Tracker.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import Kinetic_Eco.Tracker.R
import Kinetic_Eco.Tracker.data.ActivityType
import Kinetic_Eco.Tracker.data.SessionStats
import Kinetic_Eco.Tracker.data.UnitSystem
import Kinetic_Eco.Tracker.services.Co2EquivalencyService
import Kinetic_Eco.Tracker.ui.components.RouteMapMultiSessionView
import Kinetic_Eco.Tracker.ui.theme.Green500
import Kinetic_Eco.Tracker.ui.utils.format
import Kinetic_Eco.Tracker.ui.utils.usesMetricDistance
import Kinetic_Eco.Tracker.viewmodel.AnalyticsViewModel
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Tab 3 — Analysis.
 *
 * Combines:
 *   • AI-powered analysis of recent activity (uses [AIAnalysisContent] from existing screen).
 *   • CO₂ equivalence card showing the user's net impact translated into relatable equivalents
 *     plus a 7-day bar chart of saved vs emitted kgCO₂.
 *
 * Floating Settings icon at top-right opens [Kinetic_Eco.Tracker.navigation.Screen.Settings].
 */
@Composable
fun AnalysisScreen(
    analyticsViewModel: AnalyticsViewModel,
    userId: String,
    unitSystem: UnitSystem,
    onSettingsClick: () -> Unit,
    onSessionClick: ((SessionStats) -> Unit)? = null
) {
    val colorScheme = MaterialTheme.colorScheme
    val scroll = rememberScrollState()

    val allSessions by analyticsViewModel.getAllSessions(userId)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    // Last-7-day aggregates
    val sevenDayMs = TimeUnit.DAYS.toMillis(7)
    val cutoff = System.currentTimeMillis() - sevenDayMs
    val weekSessions = remember(allSessions, cutoff) {
        allSessions.filter { (it.sessionEndTimeMs.takeIf { t -> t > 0 } ?: 0L) >= cutoff }
    }
    val weekCo2Saved = weekSessions.sumOf { it.co2Conserved }
    val weekCo2Emit  = weekSessions.sumOf { it.co2Emissions }
    val net = weekCo2Saved - weekCo2Emit

    // 7-day daily buckets for chart (today is index 6, oldest is 0)
    val perDay = remember(allSessions) { computeWeeklyCo2Buckets(allSessions) }

    val hasGpsInRollingWeek = remember(weekSessions) {
        weekSessions.any { it.routePath.size >= 2 }
    }

    val thisWeekCutoff = remember {
        val cal = Calendar.getInstance()
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        val daysFromMonday = (dow - Calendar.MONDAY + 7) % 7
        cal.add(Calendar.DAY_OF_YEAR, -daysFromMonday)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.timeInMillis
    }
    val calWeekSessions = remember(allSessions, thisWeekCutoff) {
        allSessions.filter { (it.sessionEndTimeMs.takeIf { t -> t > 0 } ?: 0L) >= thisWeekCutoff }
    }
    val weeklyReport = remember(calWeekSessions) { computeWeeklyReportData(calWeekSessions) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(12.dp))

        // ── Top bar: title + settings icon ────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Analysis",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.onBackground
                )
                Text(
                    text = "AI insights & carbon footprint",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onSettingsClick) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = colorScheme.onBackground
                )
            }
        }

        // ── Weekly report card ────────────────────────────────────────────────
        WeeklyReportCard(
            data = weeklyReport,
            weekStartMs = thisWeekCutoff,
            unitSystem = unitSystem
        )

        // ── AI Analysis section (reuses existing AnalyticsScreen helpers) ─────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "AI activity analysis",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.onSurface
                    )
                }
                Spacer(Modifier.height(10.dp))
                AIAnalysisInline(viewModel = analyticsViewModel, userId = userId)
            }
        }

        // ── Latest route map ──────────────────────────────────────────────────
        // Shows the most recent saved session that actually has a GPS route.
        // Sessions without GPS (e.g. flying, indoor, missing permissions) are
        // skipped via `latestRouteSession` so we never render an empty map.
        if (hasGpsInRollingWeek) {
            WeeklyRouteMapCard(
                weekSessions = weekSessions,
                unitSystem = unitSystem,
                onSessionClick = onSessionClick
            )
        }

        // ── 7-day CO2 bar chart ───────────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.BarChart,
                        contentDescription = null,
                        tint = colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "CO₂ this week",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.onSurface
                    )
                }
                Spacer(Modifier.height(8.dp))
                // Saved column uses brand green; emitted uses a neutral gray wash.
                val emittedTone = colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                Co2WeeklyChart(
                    daily = perDay,
                    savedColor = Green500,
                    emittedColor = emittedTone,
                    axisColor = colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    LegendDot(
                        color = Green500,
                        label = AnnotatedString("Saved (${weekCo2Saved.format(2)} kg)")
                    )
                    // "Emitted" gets de-emphasised typography (smaller, italic)
                    // so the eye lands on the saved number first; the kg value
                    // stays at full legend weight so it's still legible.
                    LegendDot(
                        color = emittedTone,
                        label = buildAnnotatedString {
                            withStyle(
                                SpanStyle(
                                    fontSize = 10.sp,
                                    fontStyle = FontStyle.Italic
                                )
                            ) { append("Emitted ") }
                            append("(${weekCo2Emit.format(2)} kg)")
                        }
                    )
                }
            }
        }

        // ── CO₂ equivalence card ──────────────────────────────────────────────
        val netAccent = colorScheme.onSurface
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorScheme.surfaceVariant.copy(alpha = 0.55f)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (net >= 0) Icons.Default.Forest else Icons.Default.Eco,
                        contentDescription = null,
                        tint = colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (net >= 0) "Net CO₂ saved" else "This week's footprint",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.onSurface
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "${kotlin.math.abs(net).format(2)} kg",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = netAccent
                )

                val equivalents = remember(net) { Co2EquivalencyService.getNetImpactEquivalencies(net) }
                if (equivalents.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "That's equivalent to:",
                        style = MaterialTheme.typography.labelMedium,
                        color = colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    equivalents.forEach { eq ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                        ) {
                            Text(
                                text = "${eq.icon}  ${eq.description}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colorScheme.onSurface,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                    Text(
                        text = "Source: ADEME Base Carbone (impactco2.fr)",
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(80.dp))
    }
}

// ── 7-day CO2 bar chart ──────────────────────────────────────────────────────

private data class DailyCo2(val savedKg: Double, val emittedKg: Double)

private fun computeWeeklyCo2Buckets(sessions: List<SessionStats>): List<DailyCo2> {
    val buckets = MutableList(7) { DailyCo2(0.0, 0.0) }
    val now = System.currentTimeMillis()
    sessions.forEach { s ->
        val ts = s.sessionEndTimeMs.takeIf { it > 0 } ?: return@forEach
        val idx = rollingWeekDayIndex(ts, now) ?: return@forEach
        val cur = buckets[idx]
        buckets[idx] = DailyCo2(
            savedKg = cur.savedKg + s.co2Conserved,
            emittedKg = cur.emittedKg + s.co2Emissions
        )
    }
    return buckets
}

@Composable
private fun Co2WeeklyChart(
    daily: List<DailyCo2>,
    savedColor: Color,
    emittedColor: Color,
    axisColor: Color
) {
    val maxVal = daily.maxOfOrNull { max(it.savedKg, it.emittedKg) } ?: 0.0
    val maxScale = max(maxVal, 0.5)  // floor scale so empty days still render axis

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
    ) {
        val barGroupWidth = size.width / 7f
        val barWidth = barGroupWidth * 0.32f
        val gap = barGroupWidth * 0.10f
        val baseY = size.height - 24f
        val chartHeight = baseY - 8f

        // Baseline axis
        drawLine(
            color = axisColor.copy(alpha = 0.45f),
            start = Offset(0f, baseY),
            end = Offset(size.width, baseY),
            strokeWidth = 2f
        )

        daily.forEachIndexed { index, d ->
            val groupCenter = barGroupWidth * index + barGroupWidth / 2f
            val savedH = ((d.savedKg / maxScale) * chartHeight).toFloat().coerceAtLeast(0f)
            val emitH = ((d.emittedKg / maxScale) * chartHeight).toFloat().coerceAtLeast(0f)

            // Saved bar (left)
            drawRoundedBar(
                color = savedColor,
                left = groupCenter - barWidth - gap / 2f,
                width = barWidth,
                top = baseY - savedH,
                baseY = baseY
            )
            // Emitted bar (right)
            drawRoundedBar(
                color = emittedColor,
                left = groupCenter + gap / 2f,
                width = barWidth,
                top = baseY - emitH,
                baseY = baseY
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRoundedBar(
    color: Color,
    left: Float,
    width: Float,
    top: Float,
    baseY: Float
) {
    if (baseY - top < 1f) return
    drawRoundRect(
        color = color,
        topLeft = Offset(left, top),
        size = Size(width, baseY - top),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(width / 2f, width / 2f)
    )
}

@Composable
private fun LegendDot(color: Color, label: AnnotatedString) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color = color, shape = androidx.compose.foundation.shape.CircleShape)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

// ── Weekly route map (rolling 7 days, day chips) ────────────────────────────

/**
 * Day index 0 = six calendar days ago, 6 = today (device local timezone) — aligned with
 * [computeWeeklyCo2Buckets] bar order. Uses calendar dates so a session ending "yesterday
 * evening" is not mis-bucketed as "today".
 */
private fun rollingWeekDayIndex(sessionEndTimeMs: Long, now: Long): Int? {
    if (sessionEndTimeMs <= 0) return null
    val zone = ZoneId.systemDefault()
    val sessionDate = Instant.ofEpochMilli(sessionEndTimeMs).atZone(zone).toLocalDate()
    val todayDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val daysBetweenSessionAndToday =
        ChronoUnit.DAYS.between(sessionDate, todayDate).toInt()
    if (daysBetweenSessionAndToday !in 0..6) return null
    return 6 - daysBetweenSessionAndToday
}

private fun sessionsForRollingDayIndex(
    weekSessions: List<SessionStats>,
    dayIndex: Int,
    now: Long
): List<SessionStats> =
    weekSessions.filter { rollingWeekDayIndex(it.sessionEndTimeMs, now) == dayIndex }
        .sortedBy { it.sessionEndTimeMs }

private fun dateLabelForRollingDayIndex(dayIndex: Int, now: Long): String {
    val daysAgo = 6 - dayIndex
    val cal = Calendar.getInstance()
    cal.timeInMillis = now
    cal.add(Calendar.DAY_OF_YEAR, -daysAgo)
    return SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(cal.time)
}

/** Short weekday + day-of-month label for chip (index 0 = oldest in rolling week). */
private fun chipLabelForRollingDayIndex(dayIndex: Int, now: Long): Pair<String, Int> {
    val daysAgo = 6 - dayIndex
    val cal = Calendar.getInstance()
    cal.timeInMillis = now
    cal.add(Calendar.DAY_OF_YEAR, -daysAgo)
    val short = SimpleDateFormat("EEE", Locale.getDefault()).format(cal.time)
    return short.replace(".", "") to cal.get(Calendar.DAY_OF_MONTH)
}

@Composable
private fun WeeklyRouteMapCard(
    weekSessions: List<SessionStats>,
    unitSystem: UnitSystem,
    onSessionClick: ((SessionStats) -> Unit)?
) {
    val colorScheme = MaterialTheme.colorScheme
    var selectedDayIndex by rememberSaveable { mutableIntStateOf(6) }

    // One clock snapshot per data load so day bucketing and chip labels stay consistent.
    val snapshotNow = remember(weekSessions) { System.currentTimeMillis() }

    val daySessions = remember(weekSessions, selectedDayIndex, snapshotNow) {
        sessionsForRollingDayIndex(weekSessions, selectedDayIndex, snapshotNow)
    }
    val dateLabel = remember(selectedDayIndex, snapshotNow) {
        dateLabelForRollingDayIndex(selectedDayIndex, snapshotNow)
    }

    val hasGpsByDayIndex = remember(weekSessions, snapshotNow) {
        BooleanArray(7) { dayIdx ->
            weekSessions.any { s ->
                rollingWeekDayIndex(s.sessionEndTimeMs, snapshotNow) == dayIdx &&
                    s.routePath.size >= 2
            }
        }
    }

    val routePaths = remember(daySessions) {
        daySessions.filter { it.routePath.size >= 2 }.map { it.routePath }
    }
    val gpsCount = routePaths.size

    val totalDistance = daySessions.sumOf { it.totalDistance }
    val totalDuration = daySessions.sumOf { it.totalDuration }
    val totalCo2Saved = daySessions.sumOf { it.co2Conserved }

    val detailSession = remember(daySessions) {
        daySessions.maxByOrNull { it.sessionEndTimeMs }
    }

    val cardModifier = Modifier
        .fillMaxWidth()
        .let { mod ->
            if (onSessionClick != null && detailSession != null) {
                mod.clickable { onSessionClick(detailSession) }
            } else mod
        }

    Card(
        modifier = cardModifier,
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Map,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.analysis_week_map_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.analysis_week_map_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
                if (onSessionClick != null && detailSession != null) {
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = dateLabel,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.analysis_week_map_gps_sessions, gpsCount),
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (dayIdx in 0..6) {
                    val (abbr, dom) = chipLabelForRollingDayIndex(dayIdx, snapshotNow)
                    val hasTracks = hasGpsByDayIndex[dayIdx]
                    FilterChip(
                        selected = selectedDayIndex == dayIdx,
                        onClick = { selectedDayIndex = dayIdx },
                        label = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = abbr,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = dom.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        leadingIcon = if (hasTracks) {
                            {
                                Icon(
                                    imageVector = Icons.Default.Place,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        } else null
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                LatestRouteStat(
                    label = stringResource(R.string.distance),
                    value = formatRouteDistance(totalDistance, unitSystem)
                )
                LatestRouteStat(
                    label = stringResource(R.string.duration_label),
                    value = formatRouteDuration(totalDuration)
                )
                LatestRouteStat(
                    label = stringResource(R.string.co2_saved),
                    value = "${totalCo2Saved.format(2)} kg",
                    valueColor = colorScheme.onSurface
                )
            }

            Spacer(Modifier.height(12.dp))

            RouteMapMultiSessionView(
                routePaths = routePaths,
                modifier = Modifier.fillMaxWidth(),
                heightDp = 200,
                showElevationProfile = routePaths.size == 1
            )
        }
    }
}

@Composable
private fun LatestRouteStat(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = valueColor
        )
    }
}

private fun formatRouteDistance(meters: Double, unitSystem: UnitSystem): String {
    return if (unitSystem.usesMetricDistance()) {
        "${(meters / 1000.0).format(2)} km"
    } else {
        "${(meters / 1609.344).format(2)} mi"
    }
}

private fun formatRouteDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

// ── AI Analysis inline (slimmed-down version of AnalyticsScreen.AIAnalysisContent) ──

@Composable
private fun AIAnalysisInline(
    viewModel: AnalyticsViewModel,
    userId: String
) {
    val colorScheme = MaterialTheme.colorScheme
    val analysisState by viewModel.aiAnalysisState.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = { viewModel.analyzeActivity(userId) },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            enabled = analysisState !is Kinetic_Eco.Tracker.viewmodel.AIAnalysisState.Loading,
            colors = ButtonDefaults.buttonColors(
                containerColor = colorScheme.surfaceVariant.copy(alpha = 0.95f),
                contentColor = colorScheme.onSurface,
                disabledContainerColor = colorScheme.surfaceVariant.copy(alpha = 0.4f),
                disabledContentColor = colorScheme.onSurfaceVariant
            ),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (analysisState is Kinetic_Eco.Tracker.viewmodel.AIAnalysisState.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = colorScheme.onSurface,
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(10.dp))
                Text("Analyzing…")
            } else {
                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Run AI analysis")
            }
        }

        when (val s = analysisState) {
            is Kinetic_Eco.Tracker.viewmodel.AIAnalysisState.Success -> {
                AnalysisResultsContent(s.analysis)
            }
            is Kinetic_Eco.Tracker.viewmodel.AIAnalysisState.Error -> {
                ErrorCard(s.message)
            }
            else -> {
                Text(
                    text = "Tap the button to get a personalised AI breakdown of your last 7 days.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ── Weekly report ─────────────────────────────────────────────────────────────

private data class WeeklyReportData(
    val totalDistance: Double,
    val totalDuration: Long,
    val topSpeedMps: Double,
    val avgSpeedMps: Double,
    val mainActivity: ActivityType?,
    val totalSteps: Int,
    val totalCalories: Double,
    val co2Saved: Double,
    val co2Emitted: Double,
    val sessionCount: Int
)

private fun computeWeeklyReportData(sessions: List<SessionStats>): WeeklyReportData {
    val totalDistance = sessions.sumOf { it.totalDistance }
    val totalDuration = sessions.sumOf { it.totalDuration }
    val topSpeedMps = sessions.maxOfOrNull { it.topSpeedMps } ?: 0.0
    val avgSpeedMps = if (totalDuration > 0) totalDistance / totalDuration else 0.0
    val totalSteps = sessions.sumOf { it.totalSteps }
    val totalCalories = sessions.sumOf { it.caloriesBurned }
    val co2Saved = sessions.sumOf { it.co2Conserved }
    val co2Emitted = sessions.sumOf { it.co2Emissions }

    val activityTime = mutableMapOf<ActivityType, Long>()
    sessions.forEach { s ->
        s.breakdown.forEach { (type, bd) ->
            if (type != ActivityType.IDLE) {
                activityTime[type] = (activityTime[type] ?: 0L) + bd.time
            }
        }
    }
    val mainActivity = activityTime.maxByOrNull { it.value }?.key

    return WeeklyReportData(
        totalDistance = totalDistance,
        totalDuration = totalDuration,
        topSpeedMps = topSpeedMps,
        avgSpeedMps = avgSpeedMps,
        mainActivity = mainActivity,
        totalSteps = totalSteps,
        totalCalories = totalCalories,
        co2Saved = co2Saved,
        co2Emitted = co2Emitted,
        sessionCount = sessions.size
    )
}

@Composable
private fun WeeklyReportCard(
    data: WeeklyReportData,
    weekStartMs: Long,
    unitSystem: UnitSystem
) {
    val colorScheme = MaterialTheme.colorScheme
    val weekLabel = remember(weekStartMs) {
        val startCal = Calendar.getInstance().also { it.timeInMillis = weekStartMs }
        val endCal = Calendar.getInstance()
        val fmt = SimpleDateFormat("MMM d", Locale.getDefault())
        "${fmt.format(startCal.time)} – ${fmt.format(endCal.time)}"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.DateRange,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Weekly report",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.onSurface
                    )
                    Text(
                        text = weekLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = colorScheme.primary.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "${data.sessionCount} session${if (data.sessionCount == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            if (data.sessionCount == 0) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "No sessions recorded this week yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant
                )
            } else {
                Spacer(Modifier.height(14.dp))

                val isMetric = unitSystem.usesMetricDistance()
                val co2Net = data.co2Saved - data.co2Emitted

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        WeeklyMetricTile(
                            label = "Distance",
                            value = weeklyFormatDistance(data.totalDistance, unitSystem),
                            icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                            modifier = Modifier.weight(1f)
                        )
                        WeeklyMetricTile(
                            label = "Duration",
                            value = formatRouteDuration(data.totalDuration),
                            icon = Icons.Default.Timer,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        WeeklyMetricTile(
                            label = "Top speed",
                            value = weeklyFormatSpeed(data.topSpeedMps, isMetric),
                            icon = Icons.AutoMirrored.Filled.TrendingUp,
                            modifier = Modifier.weight(1f)
                        )
                        WeeklyMetricTile(
                            label = "Avg speed",
                            value = weeklyFormatSpeed(data.avgSpeedMps, isMetric),
                            icon = Icons.Default.Speed,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        WeeklyMetricTile(
                            label = "Main activity",
                            value = data.mainActivity?.weeklyDisplayName() ?: "—",
                            icon = Icons.Default.DirectionsBike,
                            modifier = Modifier.weight(1f)
                        )
                        WeeklyMetricTile(
                            label = "Steps",
                            value = if (data.totalSteps > 0) "%,d".format(data.totalSteps) else "—",
                            icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        WeeklyMetricTile(
                            label = "Calories",
                            value = "%.0f kcal".format(data.totalCalories),
                            icon = Icons.Default.LocalFireDepartment,
                            modifier = Modifier.weight(1f)
                        )
                        WeeklyMetricTile(
                            label = "CO₂ impact",
                            value = "${kotlin.math.abs(co2Net).format(2)} kg ${if (co2Net >= 0) "saved" else "net"}",
                            icon = Icons.Default.Eco,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WeeklyMetricTile(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = colorScheme.surface.copy(alpha = 0.6f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colorScheme.onSurface
            )
        }
    }
}

private fun weeklyFormatDistance(meters: Double, unitSystem: UnitSystem): String =
    if (unitSystem.usesMetricDistance()) "%.2f km".format(meters / 1000.0)
    else "%.2f mi".format(meters / 1609.344)

private fun weeklyFormatSpeed(mps: Double, isMetric: Boolean): String =
    if (mps <= 0.0) "—"
    else if (isMetric) "%.1f km/h".format(mps * 3.6)
    else "%.1f mph".format(mps * 2.237)

private fun ActivityType.weeklyDisplayName(): String = when (this) {
    ActivityType.IDLE             -> "Idle"
    ActivityType.WALKING          -> "Walking"
    ActivityType.RUNNING          -> "Running"
    ActivityType.CYCLING          -> "Cycling"
    ActivityType.MOTORCYCLE       -> "Motorcycle"
    ActivityType.TRAIN            -> "Train"
    ActivityType.DRIVING          -> "Driving"
    ActivityType.ELECTRIC_VEHICLE -> "EV"
    ActivityType.FLYING           -> "Flying"
}
