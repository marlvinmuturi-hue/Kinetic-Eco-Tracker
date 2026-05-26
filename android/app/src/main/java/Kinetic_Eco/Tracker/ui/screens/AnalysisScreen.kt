package Kinetic_Eco.Tracker.ui.screens

import android.content.Context
import android.graphics.Paint as AndroidPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.TwoWheeler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
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

    val recentSessions = remember(allSessions) {
        allSessions.sortedByDescending { it.sessionEndTimeMs }.take(5)
    }

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
                    text = stringResource(R.string.analysis_tab_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.onBackground
                )
                Text(
                    text = stringResource(R.string.analysis_tab_subtitle),
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
                        text = stringResource(R.string.analysis_ai_card_title),
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
                        text = stringResource(R.string.analysis_co2_this_week),
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
                        label = AnnotatedString(stringResource(R.string.analysis_legend_saved, weekCo2Saved))
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
                            ) { append(stringResource(R.string.analysis_legend_emitted) + " ") }
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
                        text = if (net >= 0) stringResource(R.string.analysis_net_co2_saved) else stringResource(R.string.analysis_weeks_footprint),
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
                        text = stringResource(R.string.analysis_equivalent_to),
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
                        text = stringResource(R.string.analysis_source_ademe),
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }

        // ── Recent sessions ──────────────────────────────────────────────────
        if (recentSessions.isNotEmpty()) {
            RecentSessionsCard(
                sessions = recentSessions,
                unitSystem = unitSystem,
                onSessionClick = onSessionClick
            )
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
    // Independent scales: each axis sized to its own series so neither is dwarfed
    val niceMaxSaved = niceChartMax(max(daily.maxOfOrNull { it.savedKg } ?: 0.0, 0.5))
    val niceMaxEmit  = niceChartMax(max(daily.maxOfOrNull { it.emittedKg } ?: 0.0, 0.5))

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
    ) {
        val yLeftWidth  = 40.dp.toPx()
        val yRightWidth = 40.dp.toPx()
        val barAreaWidth = size.width - yLeftWidth - yRightWidth
        val barGroupWidth = barAreaWidth / 7f
        val barWidth = barGroupWidth * 0.32f
        val gap = barGroupWidth * 0.10f
        val baseY = size.height - 20f
        val topY = 8f
        val chartHeight = baseY - topY
        val chartRight = size.width - yRightWidth

        val savedLabelPaint = AndroidPaint().apply {
            isAntiAlias = true
            textSize = 9.sp.toPx()
            textAlign = AndroidPaint.Align.RIGHT
            color = savedColor.copy(alpha = 0.85f).toArgb()
        }
        val emitLabelPaint = AndroidPaint().apply {
            isAntiAlias = true
            textSize = 9.sp.toPx()
            textAlign = AndroidPaint.Align.LEFT
            // emittedColor is already muted; boost alpha so labels are legible
            color = axisColor.copy(alpha = 0.75f).toArgb()
        }

        // Baseline
        drawLine(
            color = axisColor.copy(alpha = 0.45f),
            start = Offset(yLeftWidth, baseY),
            end = Offset(chartRight, baseY),
            strokeWidth = 2f
        )

        // Left axis rule (saved — green tint)
        drawLine(
            color = savedColor.copy(alpha = 0.35f),
            start = Offset(yLeftWidth, topY),
            end = Offset(yLeftWidth, baseY),
            strokeWidth = 1f
        )

        // Right axis rule (emitted — muted)
        drawLine(
            color = axisColor.copy(alpha = 0.25f),
            start = Offset(chartRight, topY),
            end = Offset(chartRight, baseY),
            strokeWidth = 1f
        )

        // Faint gridlines at 50% and 100% of the saved scale
        listOf(0.5f, 1.0f).forEach { fraction ->
            drawLine(
                color = axisColor.copy(alpha = 0.09f),
                start = Offset(yLeftWidth, baseY - fraction * chartHeight),
                end = Offset(chartRight, baseY - fraction * chartHeight),
                strokeWidth = 1f
            )
        }

        // Bars — each series scaled to its own axis
        daily.forEachIndexed { index, d ->
            val groupCenter = yLeftWidth + barGroupWidth * index + barGroupWidth / 2f
            val savedH = ((d.savedKg / niceMaxSaved) * chartHeight).toFloat().coerceAtLeast(0f)
            val emitH  = ((d.emittedKg / niceMaxEmit) * chartHeight).toFloat().coerceAtLeast(0f)

            drawRoundedBar(savedColor, groupCenter - barWidth - gap / 2f, barWidth, baseY - savedH, baseY)
            drawRoundedBar(emittedColor, groupCenter + gap / 2f, barWidth, baseY - emitH, baseY)
        }

        // Axis tick labels
        drawIntoCanvas { composeCanvas ->
            val nc = composeCanvas.nativeCanvas

            // Left axis — saved (right-aligned, green)
            listOf(0.0f to 0.0, 0.5f to niceMaxSaved * 0.5, 1.0f to niceMaxSaved)
                .forEach { (fraction, kg) ->
                    val y = baseY - fraction * chartHeight
                    val label = when {
                        kg == 0.0        -> "0"
                        fraction == 1.0f -> co2TickLabel(kg) + " kg"
                        else             -> co2TickLabel(kg)
                    }
                    nc.drawText(label, yLeftWidth - 4.dp.toPx(), y + savedLabelPaint.textSize * 0.35f, savedLabelPaint)
                }

            // Right axis — emitted (left-aligned, muted)
            listOf(0.0f to 0.0, 0.5f to niceMaxEmit * 0.5, 1.0f to niceMaxEmit)
                .forEach { (fraction, kg) ->
                    val y = baseY - fraction * chartHeight
                    val label = when {
                        kg == 0.0        -> "0"
                        fraction == 1.0f -> co2TickLabel(kg) + " kg"
                        else             -> co2TickLabel(kg)
                    }
                    nc.drawText(label, chartRight + 4.dp.toPx(), y + emitLabelPaint.textSize * 0.35f, emitLabelPaint)
                }
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

// ── Recent sessions ──────────────────────────────────────────────────────────

@Composable
private fun RecentSessionsCard(
    sessions: List<SessionStats>,
    unitSystem: UnitSystem,
    onSessionClick: ((SessionStats) -> Unit)?
) {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.recent_sessions),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                sessions.forEach { session ->
                    AnalysisSessionRow(
                        session = session,
                        unitSystem = unitSystem,
                        onClick = { onSessionClick?.invoke(session) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AnalysisSessionRow(
    session: SessionStats,
    unitSystem: UnitSystem,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val (icon, tint) = sessionActivityIconAndTint(session, colorScheme)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = colorScheme.surface.copy(alpha = 0.6f)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.date.ifBlank { stringResource(R.string.session) },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colorScheme.onSurface
                )
                Text(
                    text = weeklyFormatDistance(session.totalDistance, unitSystem) +
                        " • " + formatRouteDuration(session.totalDuration),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant
                )
            }
            if (session.co2Conserved > 0.001) {
                Text(
                    text = "+${session.co2Conserved.format(2)} kg",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Green500
                )
            } else if (session.co2Emissions > 0.001) {
                Text(
                    text = "${session.co2Emissions.format(2)} kg",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun sessionActivityIconAndTint(
    s: SessionStats,
    colorScheme: androidx.compose.material3.ColorScheme
): Pair<ImageVector, Color> {
    val dominant = s.breakdown.maxByOrNull { it.value.distance }?.key ?: ActivityType.WALKING
    val icon: ImageVector = when (dominant) {
        ActivityType.WALKING          -> Icons.AutoMirrored.Filled.DirectionsWalk
        ActivityType.RUNNING          -> Icons.AutoMirrored.Filled.DirectionsRun
        ActivityType.CYCLING          -> Icons.AutoMirrored.Filled.DirectionsBike
        ActivityType.MOTORCYCLE       -> Icons.Outlined.TwoWheeler
        ActivityType.DRIVING          -> Icons.Default.DirectionsCar
        ActivityType.ELECTRIC_VEHICLE -> Icons.Default.ElectricCar
        ActivityType.TRAIN            -> Icons.Default.Train
        ActivityType.FLYING           -> Icons.Default.Flight
        ActivityType.IDLE             -> Icons.Default.PauseCircle
    }
    return icon to colorScheme.onSurfaceVariant
}

// ── Chart helpers ─────────────────────────────────────────────────────────────

private fun niceChartMax(value: Double): Double = when {
    value <= 0.5  -> 0.5
    value <= 1.0  -> 1.0
    value <= 2.0  -> 2.0
    value <= 5.0  -> 5.0
    value <= 10.0 -> 10.0
    value <= 20.0 -> 20.0
    else -> kotlin.math.ceil(value / 10.0) * 10.0
}

private fun co2TickLabel(kg: Double): String = when {
    kg >= 10  -> "%.0f".format(kg)
    kg >= 1   -> "%.1f".format(kg)
    else      -> "%.2f".format(kg)
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
                Text(stringResource(R.string.analyzing))
            } else {
                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.analysis_run_ai_button))
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
                    text = stringResource(R.string.analysis_ai_cta_text),
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
                        text = stringResource(R.string.dashboard_weekly_report),
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
                        text = if (data.sessionCount == 1) stringResource(R.string.analysis_session_one) else stringResource(R.string.analysis_sessions_count, data.sessionCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            if (data.sessionCount == 0) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.analysis_no_sessions_week),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant
                )
            } else {
                Spacer(Modifier.height(14.dp))

                val ctx = LocalContext.current
                val isMetric = unitSystem.usesMetricDistance()
                val co2Net = data.co2Saved - data.co2Emitted
                val co2ImpactValue = if (co2Net >= 0) {
                    stringResource(R.string.stat_co2_impact_saved, kotlin.math.abs(co2Net).format(2))
                } else {
                    stringResource(R.string.stat_co2_impact_net, kotlin.math.abs(co2Net).format(2))
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        WeeklyMetricTile(
                            label = stringResource(R.string.stat_distance),
                            value = weeklyFormatDistance(data.totalDistance, unitSystem),
                            icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                            iconColor = Color(0xFF4CAF50),
                            modifier = Modifier.weight(1f)
                        )
                        WeeklyMetricTile(
                            label = stringResource(R.string.duration_label),
                            value = formatRouteDuration(data.totalDuration),
                            icon = Icons.Default.Timer,
                            iconColor = Color(0xFF2196F3),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        WeeklyMetricTile(
                            label = stringResource(R.string.top_speed),
                            value = weeklyFormatSpeed(data.topSpeedMps, isMetric),
                            icon = Icons.AutoMirrored.Filled.TrendingUp,
                            iconColor = Color(0xFFFF9800),
                            modifier = Modifier.weight(1f)
                        )
                        WeeklyMetricTile(
                            label = stringResource(R.string.stat_avg_speed),
                            value = weeklyFormatSpeed(data.avgSpeedMps, isMetric),
                            icon = Icons.Default.Speed,
                            iconColor = Color(0xFFFFC107),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        WeeklyMetricTile(
                            label = stringResource(R.string.stat_main_activity),
                            value = data.mainActivity?.weeklyDisplayName(ctx) ?: "—",
                            icon = Icons.Default.DirectionsBike,
                            iconColor = Color(0xFF00BCD4),
                            modifier = Modifier.weight(1f)
                        )
                        WeeklyMetricTile(
                            label = stringResource(R.string.stat_steps),
                            value = if (data.totalSteps > 0) "%,d".format(data.totalSteps) else "—",
                            icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                            iconColor = Color(0xFF009688),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        WeeklyMetricTile(
                            label = stringResource(R.string.stat_calories),
                            value = "%.0f kcal".format(data.totalCalories),
                            icon = Icons.Default.LocalFireDepartment,
                            iconColor = Color(0xFFFF5722),
                            modifier = Modifier.weight(1f)
                        )
                        WeeklyMetricTile(
                            label = stringResource(R.string.stat_co2_impact),
                            value = co2ImpactValue,
                            icon = Icons.Default.Eco,
                            iconColor = Color(0xFF43A047),
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
    modifier: Modifier = Modifier,
    iconColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
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
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(iconColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(13.dp)
                    )
                }
                Spacer(Modifier.width(6.dp))
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

private fun ActivityType.weeklyDisplayName(context: Context): String = when (this) {
    ActivityType.IDLE             -> context.getString(R.string.activity_idle)
    ActivityType.WALKING          -> context.getString(R.string.walking)
    ActivityType.RUNNING          -> context.getString(R.string.running)
    ActivityType.CYCLING          -> context.getString(R.string.cycling)
    ActivityType.MOTORCYCLE       -> context.getString(R.string.motorcycle)
    ActivityType.TRAIN            -> context.getString(R.string.train)
    ActivityType.DRIVING          -> context.getString(R.string.driving)
    ActivityType.ELECTRIC_VEHICLE -> context.getString(R.string.activity_ev_short)
    ActivityType.FLYING           -> context.getString(R.string.flying)
}
