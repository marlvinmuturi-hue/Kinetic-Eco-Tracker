package Kinetic_Eco.Tracker.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.TwoWheeler
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.google.firebase.auth.FirebaseUser
import Kinetic_Eco.Tracker.data.ActivityType
import Kinetic_Eco.Tracker.data.LeaderboardEntry
import Kinetic_Eco.Tracker.data.SessionStats
import Kinetic_Eco.Tracker.data.UnitSystem
import Kinetic_Eco.Tracker.services.LeaderboardPeriod
import Kinetic_Eco.Tracker.services.UserPreferencesManager
import Kinetic_Eco.Tracker.ui.components.Co2DistributionPieChart
import Kinetic_Eco.Tracker.ui.theme.Green500
import Kinetic_Eco.Tracker.ui.utils.format
import Kinetic_Eco.Tracker.ui.utils.usesMetricDistance
import Kinetic_Eco.Tracker.viewmodel.AnalyticsViewModel
import Kinetic_Eco.Tracker.viewmodel.ProfileViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

/**
 * Tab 1 — Dashboard.
 * Shows last-7-days stats, AI score, leaderboard rank, CO₂ highlights, steps, distance,
 * and a list of recent sessions.
 *
 * The floating Settings icon at the top-right opens [Kinetic_Eco.Tracker.navigation.Screen.Settings].
 */
@Composable
fun DashboardScreen(
    user: FirebaseUser?,
    analyticsViewModel: AnalyticsViewModel,
    profileViewModel: ProfileViewModel,
    unitSystem: UnitSystem,
    onSettingsClick: () -> Unit,
    onProfileClick: () -> Unit = {},
    onSessionsClick: () -> Unit,
    onAnalysisClick: () -> Unit,
    onSessionClick: (SessionStats) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val userId = user?.uid ?: ""

    val allSessions by analyticsViewModel.getAllSessions(userId)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    // Two rolling windows side-by-side so we can compute "vs last week" deltas
    // without needing calendar-aware week boundaries.
    val now = System.currentTimeMillis()
    // Local clock hour drives the time-of-day greeting. Recomputed on each
    // recomposition (cheap) so a dashboard left open across noon/dusk updates.
    val hourOfDay = remember(now) {
        java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    }
    val sevenDayMs = TimeUnit.DAYS.toMillis(7)
    // Cutoff = Monday 00:00:00 of the current calendar week, so "this week" resets each Monday.
    val thisWeekCutoff = run {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val daysFromMonday = (cal.get(java.util.Calendar.DAY_OF_WEEK) - java.util.Calendar.MONDAY + 7) % 7
        cal.add(java.util.Calendar.DAY_OF_YEAR, -daysFromMonday)
        cal.timeInMillis
    }
    val lastWeekCutoff = thisWeekCutoff - sevenDayMs

    val weekSessions = remember(allSessions, thisWeekCutoff) {
        allSessions.filter { (it.sessionEndTimeMs.takeIf { t -> t > 0 } ?: 0L) >= thisWeekCutoff }
    }
    val lastWeekSessions = remember(allSessions, thisWeekCutoff, lastWeekCutoff) {
        allSessions.filter {
            val ts = it.sessionEndTimeMs.takeIf { t -> t > 0 } ?: 0L
            ts in lastWeekCutoff until thisWeekCutoff
        }
    }

    // This-week aggregates for the dashboard tiles (distance, steps).
    val weekDistanceM = weekSessions.sumOf { it.totalDistance }
    val weekCo2Saved = weekSessions.sumOf { it.co2Conserved }
    val weekSteps    = weekSessions.sumOf { it.totalSteps.toLong() }

    // Last-week aggregates for week-over-week delta on tiles and hero card.
    val lastWeekCo2Saved = lastWeekSessions.sumOf { it.co2Conserved }
    val lastWeekDistanceM = lastWeekSessions.sumOf { it.totalDistance }

    // Per-day CO2 saved buckets for the mini chart inside the hero card.
    // Index 0 = 6 days ago, index 6 = today (matches the day-label generator).
    val dailyCo2Saved = remember(weekSessions, now) {
        computeDailyCo2Saved(weekSessions, now)
    }

    // Highlights derived from this week's sessions only. Null fields are hidden
    // (e.g. no sessions yet → no highlights, but the hero card still renders
    // the 0.00 kg headline so the user has a visible "you're at zero" cue).
    val highlights = remember(weekSessions, dailyCo2Saved) {
        computeWeekHighlights(weekSessions, dailyCo2Saved, now)
    }

    // Friendly equivalency line beneath the hero number — turns abstract kg
    // into something concrete (e.g. "🥖 3.2 baguettes worth of CO₂"). We pull
    // every viable category leader (limit = MAX_VALUE) so users can also
    // tap-to-cycle through them; the daily seed picks the starting category so
    // a passive viewer still sees something new each day, while an engaged
    // user can browse all available comparisons by tapping.
    val allHeroEquivalencies = remember(weekCo2Saved) {
        if (weekCo2Saved < 0.05) emptyList()
        else Kinetic_Eco.Tracker.services.Co2EquivalencyService
            .getNetImpactEquivalencies(weekCo2Saved, limit = Int.MAX_VALUE)
    }
    var heroTapOffset by remember(weekCo2Saved) { mutableIntStateOf(0) }
    val daysSinceEpoch = remember(now) { (now / TimeUnit.DAYS.toMillis(1)).toInt() }
    val heroEquivalency: String? = if (allHeroEquivalencies.isEmpty()) null else {
        val n = allHeroEquivalencies.size
        val idx = (((daysSinceEpoch + heroTapOffset) % n) + n) % n
        val eq = allHeroEquivalencies[idx]
        "${eq.icon} ${eq.description.removePrefix("Equivalent to the CO₂ of ")}"
    }
    val canCycleHeroEquivalency = allHeroEquivalencies.size > 1

    // First-time discovery hint for the tap-to-cycle gesture. We read the
    // pref synchronously once and mirror it into Compose state so the hint
    // disappears immediately after the first tap (no recomposition lag) and
    // also persists across app restarts.
    val context = LocalContext.current
    val userPrefsManager = remember(context) { UserPreferencesManager(context.applicationContext) }
    var heroHintSeen by remember { mutableStateOf(userPrefsManager.hasSeenHeroEquivalencyHint()) }

    // Personal weekly CO₂-saved goal. Loaded once into Compose state so the
    // hero ring + plant + chip all reflect the latest value without an extra
    // round-trip to SharedPreferences on every recomposition. Changes from
    // the in-card "Set goal" dialog write through and update local state.
    var weeklyGoalKg by remember { mutableFloatStateOf(userPrefsManager.getWeeklyCo2GoalKg()) }
    var showGoalDialog by remember { mutableStateOf(false) }
    val showHeroEquivalencyHint = canCycleHeroEquivalency && !heroHintSeen

    val onCycleHeroEquivalency: (() -> Unit)? = if (canCycleHeroEquivalency) {
        {
            // First tap doubles as hint dismissal — mark the discovery and
            // persist so the pulse + caption never appear again.
            if (!heroHintSeen) {
                userPrefsManager.setHeroEquivalencyHintSeen()
                heroHintSeen = true
            }
            heroTapOffset++
        }
    } else null

    // Green-streak count: number of consecutive days (ending today) where
    // CO₂ saved was at least CO₂ emitted. Days with no sessions count as
    // "neutral / not bad" so rest days never break the streak — the goal is
    // celebration, never pressure.
    val greenStreakDays = remember(allSessions, now) {
        computeGreenStreak(allSessions, now)
    }

    // Profile + leaderboard
    val profile by profileViewModel.profile.collectAsStateWithLifecycle()
    val leaderboardEntries by profileViewModel.leaderboardEntries.collectAsStateWithLifecycle()
    val leaderboardOptIn   by profileViewModel.leaderboardOptedIn.collectAsStateWithLifecycle()

    LaunchedEffect(userId) {
        if (userId.isNotEmpty()) {
            profileViewModel.loadProfile(userId)
            // Category is fixed (CO₂ saved) — no setLeaderboardCategory call needed.
            profileViewModel.setLeaderboardPeriod(LeaderboardPeriod.Rolling(7))
            // refresh own entry if opted in (so co2Conserved7d reflects the latest
            // synced sessions), then reload the list. This is the safety net for
            // users who opted in before sessions had a chance to sync to Firestore.
            profileViewModel.refreshLeaderboardIfOptedIn(userId)
        }
    }

    val displayName = profile?.displayName?.takeIf { it.isNotBlank() }
        ?: user?.email?.substringBefore('@')
        ?: "Athlete"
    val photoUrl = profile?.photoUrl

    // Cached AI analysis (if any). The dashboard shows the first personal
    // insight as a teaser plus the score chip; if there's no cache yet, the
    // card converts to a "Run analysis" CTA.
    val aiState by analyticsViewModel.aiAnalysisState.collectAsStateWithLifecycle()
    val aiAnalysis = (aiState as? Kinetic_Eco.Tracker.viewmodel.AIAnalysisState.Success)
        ?.analysis

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Top bar: greeting + larger avatar + floating settings icon
        item {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (photoUrl.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(colorScheme.primaryContainer)
                            .clickable { onProfileClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = displayName.firstOrNull()?.uppercase() ?: "K",
                            style = MaterialTheme.typography.headlineSmall,
                            color = colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    AsyncImage(
                        model = photoUrl,
                        contentDescription = "Go to profile",
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(colorScheme.surfaceVariant)
                            .clickable { onProfileClick() }
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${greetingForHour(hourOfDay)}, ${displayName.firstName()}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.onBackground,
                        lineHeight = 30.sp
                    )
                    Text(
                        text = "Last 7 days",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onSettingsClick, modifier = Modifier.size(52.dp)) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        modifier = Modifier.size(28.dp),
                        tint = colorScheme.onBackground
                    )
                }
            }
        }

        // ── Weekly Report hero card ───────────────────────────────────────────
        // Headlines CO2 saved this week as the focal metric, with delta vs last
        // week, a 7-day mini bar chart, and an expandable highlights section.
        // Tapping the card opens the Analysis tab for the full breakdown.
        item {
            WeeklyReportHeroCard(
                co2SavedThisWeek = weekCo2Saved,
                co2SavedLastWeek = lastWeekCo2Saved,
                dailyCo2Saved = dailyCo2Saved,
                highlights = highlights,
                equivalencyLine = heroEquivalency,
                onCycleEquivalency = onCycleHeroEquivalency,
                showCycleHint = showHeroEquivalencyHint,
                greenStreakDays = greenStreakDays,
                weeklyGoalKg = weeklyGoalKg,
                onAdjustGoal = { showGoalDialog = true },
                onClick = onAnalysisClick
            )
        }

        // ── Compact secondary stats: distance + steps ─────────────────────────
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CompactStatCard(
                    title = "Distance",
                    value = formatDistance(weekDistanceM, unitSystem),
                    icon = Icons.Default.Straighten,
                    accent = colorScheme.onSurfaceVariant,
                    deltaPct = percentDelta(weekDistanceM, lastWeekDistanceM),
                    modifier = Modifier.weight(1f)
                )
                CompactStatCard(
                    title = "Steps",
                    value = formatStepCount(weekSteps),
                    icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                    accent = colorScheme.onSurfaceVariant,
                    deltaPct = null,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // ── AI insight teaser card (tappable → Analysis tab) ──────────────────
        // Shows the first personal insight from the cached AI analysis with
        // the score chip beside it. When no analysis is cached, becomes a
        // "Run AI analysis" CTA so users always have an actionable next step.
        item {
            AIInsightCard(
                analysis = aiAnalysis,
                onClick = onAnalysisClick
            )
        }

        // ── Leaderboard rank card ─────────────────────────────────────────────
        item {
            LeaderboardOverviewCard(
                entries = leaderboardEntries,
                currentUserId = userId,
                optedIn = leaderboardOptIn ?: false,
                onSettingsClick = onSettingsClick
            )
        }

        // ── CO₂ distribution pie chart (replaces goal & annual tiles) ────────
        // Goal setting moved to Vehicle Profile (Settings); annual footprint
        // moved to the Profile tab. The dashboard now visualises last-7-days
        // CO₂ savings split per activity.
        item {
            Co2DistributionPieChart(allSessions = allSessions)
        }

        item { Spacer(Modifier.height(80.dp)) }
    }

    // ── Weekly goal dialog ──────────────────────────────────────────────────
    // Lives outside the LazyColumn so it can overlay the entire screen as a
    // modal. Persists immediately on confirm so the ring + plant + chip
    // refresh on the very next composition.
    if (showGoalDialog) {
        WeeklyGoalDialog(
            currentGoalKg = weeklyGoalKg,
            onDismiss = { showGoalDialog = false },
            onConfirm = { newGoal ->
                userPrefsManager.setWeeklyCo2GoalKg(newGoal)
                weeklyGoalKg = newGoal
                showGoalDialog = false
            }
        )
    }
}

/**
 * Modal slider dialog for adjusting the weekly CO₂ goal from the dashboard
 * hero chip. Mirrors the slider in onboarding (0.5 kg increments, 0.5–20 kg
 * range) so users see the same affordance whichever entry point they used.
 */
@Composable
private fun WeeklyGoalDialog(
    currentGoalKg: Float,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit
) {
    var draft by remember(currentGoalKg) { mutableFloatStateOf(currentGoalKg) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Weekly CO₂ goal") },
        text = {
            Column {
                Text(
                    text = "How much CO₂ would you like to save each week? Your dashboard plant grows toward this.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "${"%.1f".format(draft)} kg / week",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Slider(
                    value = draft,
                    onValueChange = { draft = it },
                    valueRange = 0.5f..20f,
                    steps = 39
                )
                Text(
                    text = "Light starter (0.5–2 kg)  ·  Solid (3–7 kg)  ·  Ambitious (8+ kg)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(draft) }) { Text("Save goal") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// ── Building blocks ─────────────────────────────────────────────────────────

/**
 * Hero "Weekly Report" card — full-width, gradient background.
 *
 * Shows CO₂ saved as the headline metric with a delta vs. last week, a 7-day
 * bar chart of CO₂ saved per day, and an expandable highlights section.
 *
 * @param co2SavedThisWeek kg saved across the rolling last-7-days window
 * @param co2SavedLastWeek kg saved across the previous 7-day window (used for delta)
 * @param dailyCo2Saved 7 entries, index 0 = 6 days ago … index 6 = today
 * @param highlights computed highlights (best day, longest trip, top activity)
 * @param equivalencyLine pre-formatted positive equivalency string (e.g. "🥖 3.2 baguettes worth of CO₂"); null hides the line
 * @param onCycleEquivalency optional handler invoked when the user taps the equivalency line; non-null adds a small refresh affordance and makes the line tappable. Pass null to disable cycling (e.g. when only one equivalency is available).
 * @param showCycleHint when true, the equivalency line renders a pulsing refresh icon + a "Tap to switch comparison" caption to advertise the gesture. Should be wired to a one-time persisted flag so the hint disappears after the first tap.
 * @param greenStreakDays current trailing-day streak where savings ≥ emissions; only rendered as a chip at 3, 7, 30+ thresholds
 * @param onClick navigates to the Analysis tab for the full breakdown
 */
@Composable
private fun WeeklyReportHeroCard(
    co2SavedThisWeek: Double,
    co2SavedLastWeek: Double,
    dailyCo2Saved: List<Double>,
    highlights: WeekHighlights,
    equivalencyLine: String?,
    onCycleEquivalency: (() -> Unit)?,
    showCycleHint: Boolean,
    greenStreakDays: Int,
    weeklyGoalKg: Float,
    onAdjustGoal: () -> Unit,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    var highlightsExpanded by remember { mutableStateOf(false) }
    val deltaPct = percentDelta(co2SavedThisWeek, co2SavedLastWeek)
    val hasAnySessions = co2SavedThisWeek > 0.0 || co2SavedLastWeek > 0.0
    // Goal-tracking: ring + plant animate toward the weekly CO₂ goal.
    val goalProgressRaw = (co2SavedThisWeek / weeklyGoalKg.toDouble())
        .toFloat()
        .coerceIn(0f, 1f)
    val animatedGoalProgress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = goalProgressRaw,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = 900,
            easing = androidx.compose.animation.core.FastOutSlowInEasing
        ),
        label = "hero_goal_progress"
    )
    val goalReached = co2SavedThisWeek + 0.005 >= weeklyGoalKg.toDouble()
    val ringColor = colorScheme.onSurface.copy(alpha = if (goalReached) 0.92f else 0.52f)

    val gradient = Brush.verticalGradient(
        colors = listOf(
            colorScheme.surface,
            colorScheme.surfaceVariant.copy(alpha = 0.9f)
        )
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(gradient)
            .clickable(onClick = onClick)
            .padding(20.dp)
    ) {
        Column {
            // ── Top row: small caption + chevron ──────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Insights,
                    contentDescription = null,
                    tint = colorScheme.onSurface,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Weekly report",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Open analysis",
                    tint = colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }

            Spacer(Modifier.height(12.dp))

            // ── Hero number: progress ring + sprouting plant + big CO2 saved ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(72.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Track ring (faint, full sweep)
                    CircularProgressIndicator(
                        progress = { 1f },
                        modifier = Modifier.fillMaxSize(),
                        color = colorScheme.onSurface.copy(alpha = 0.12f),
                        strokeWidth = 4.dp,
                        trackColor = androidx.compose.ui.graphics.Color.Transparent
                    )
                    // Active progress ring on top
                    CircularProgressIndicator(
                        progress = { animatedGoalProgress },
                        modifier = Modifier.fillMaxSize(),
                        color = ringColor,
                        strokeWidth = 4.dp,
                        trackColor = androidx.compose.ui.graphics.Color.Transparent
                    )
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(colorScheme.surfaceVariant.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center
                    ) {
                        SproutingPlantScene(
                            co2SavedKg = co2SavedThisWeek,
                            goalKg = weeklyGoalKg.toDouble(),
                            leafColor = colorScheme.onSurface.copy(
                                alpha = if (goalReached) 0.88f else 0.42f
                            ),
                            stemColor = colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                            modifier = Modifier.size(44.dp)
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    // Big "1.23 kg" with a smaller, slightly de-emphasised "CO₂"
                    // sitting alongside it. Single Text + AnnotatedString keeps the
                    // baseline aligned (a Row of two Texts would need explicit
                    // baseline alignment to match) and preserves the displaySmall
                    // line-height for the hero number.
                    Text(
                        text = buildAnnotatedString {
                            withStyle(
                                SpanStyle(
                                    fontSize = 36.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            ) { append("${co2SavedThisWeek.format(2)} kg") }
                            withStyle(
                                SpanStyle(
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colorScheme.onSurface.copy(alpha = 0.75f)
                                )
                            ) { append(" CO₂") }
                        },
                        style = MaterialTheme.typography.displaySmall,
                        color = colorScheme.onSurface
                    )
                    Text(
                        text = motivationalHeroSubtitle(co2SavedThisWeek),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = colorScheme.onSurface.copy(alpha = 0.9f)
                    )
                    // Friendly equivalency, only when we actually have something
                    // worth comparing against. Keeps the hero feeling concrete
                    // instead of abstract "how much is X kg". When [onCycleEquivalency]
                    // is supplied, the row becomes tappable and shows a small
                    // refresh affordance so users can browse alternative
                    // category comparisons without leaving the dashboard.
                    if (!equivalencyLine.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        EquivalencyLine(
                            line = equivalencyLine,
                            onCycle = onCycleEquivalency,
                            showHint = showCycleHint,
                            textColor = colorScheme.onSurface.copy(alpha = 0.85f),
                            iconColor = colorScheme.onSurface.copy(alpha = 0.55f),
                            hintColor = colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                    // ── Goal chip ─────────────────────────────────────────
                    Spacer(Modifier.height(6.dp))
                    AssistChip(
                        onClick = onAdjustGoal,
                        label = {
                            val savedFmt  = co2SavedThisWeek.format(1)
                            val goalFmt   = "%.1f".format(weeklyGoalKg)
                            Text(
                                text = if (goalReached) "🎉 Goal hit · $savedFmt / $goalFmt kg"
                                       else            "$savedFmt / $goalFmt kg goal",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (goalReached) {
                                colorScheme.surfaceVariant.copy(alpha = 0.95f)
                            } else {
                                colorScheme.onSurface.copy(alpha = 0.08f)
                            },
                            labelColor = colorScheme.onSurface
                        ),
                        border = null
                    )
                }
            }

            // Delta + streak chips on a single row, only rendered when there
            // is actually something positive to celebrate / compare. Streak
            // milestones are intentionally rare (3 / 7 / 30) so the chip feels
            // like an unexpected reward, not an everyday counter.
            val streakLabel = streakMilestoneLabel(greenStreakDays)
            if (hasAnySessions || streakLabel != null) {
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (hasAnySessions) {
                        DeltaBadge(
                            deltaPct = deltaPct,
                            suffix = " vs last week",
                            invertPolarity = false  // higher CO2 saved vs last week
                        )
                    }
                    if (streakLabel != null) {
                        StreakChip(label = streakLabel)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── 7-day mini bar chart ──────────────────────────────────────────
            Co2DailyMiniChart(
                dailyCo2Saved = dailyCo2Saved,
                barColor = Green500,
                axisColor = colorScheme.onSurface.copy(alpha = 0.4f),
                labelColor = colorScheme.onSurface.copy(alpha = 0.75f),
                today = System.currentTimeMillis()
            )

            // ── Expandable highlights ─────────────────────────────────────────
            if (highlights.hasAny) {
                Spacer(Modifier.height(12.dp))
                TextButton(
                    onClick = { highlightsExpanded = !highlightsExpanded },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = if (highlightsExpanded) Icons.Default.ExpandLess
                                      else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = colorScheme.onSurface
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (highlightsExpanded) "Hide your wins" else "See your wins",
                        style = MaterialTheme.typography.labelLarge,
                        color = colorScheme.onSurface
                    )
                }
                AnimatedVisibility(
                    visible = highlightsExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    HighlightsList(
                        highlights = highlights,
                        textColor = colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun DeltaBadge(
    deltaPct: Double?,
    suffix: String,
    invertPolarity: Boolean
) {
    val colorScheme = MaterialTheme.colorScheme

    // null delta = no last-week data (everything this week is "new")
    if (deltaPct == null) {
        Surface(
            shape = RoundedCornerShape(50),
            color = colorScheme.surfaceVariant.copy(alpha = 0.72f),
            modifier = Modifier.padding(top = 2.dp)
        ) {
            Text(
                text = "✨ NEW THIS WEEK",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
        return
    }

    val rising = deltaPct >= 0
    val positive = if (invertPolarity) !rising else rising
    val chipColor =
        colorScheme.onSurfaceVariant.copy(alpha = if (positive) 1f else 0.62f)
    val arrow = if (rising) "▲" else "▼"
    val pctLabel = "${abs(deltaPct).format(0)}%"

    Surface(
        shape = RoundedCornerShape(50),
        color = chipColor.copy(alpha = 0.18f)
    ) {
        Text(
            text = "$arrow $pctLabel$suffix",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = chipColor,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/**
 * Equivalency line under the hero number. When [onCycle] is non-null, the
 * row becomes a small tap target with a quiet refresh icon, animating the
 * label change with a fade so the new comparison feels like a "swap" rather
 * than a jump. When [onCycle] is null we render a plain Text — same look,
 * no ripple, no affordance — for cases where there's only one viable
 * comparison and tapping wouldn't change anything.
 *
 * When [showHint] is true (first-time discovery), the refresh icon gently
 * pulses and a small italic "Tap to switch comparison" caption is rendered
 * below the line. Both disappear permanently once the user taps — the
 * caller is expected to flip [showHint] to false on first tap and persist
 * that decision so the hint never reappears.
 *
 * The clickable here is intentionally inside the hero's parent clickable.
 * Compose's gesture detector resolves nested clickables by giving the
 * innermost one the event, so tapping the equivalency line cycles it
 * without triggering the parent's "go to Analysis" navigation.
 */
@Composable
private fun EquivalencyLine(
    line: String,
    onCycle: (() -> Unit)?,
    showHint: Boolean,
    textColor: Color,
    iconColor: Color,
    hintColor: Color
) {
    if (onCycle == null) {
        Text(
            text = line,
            style = MaterialTheme.typography.bodySmall,
            color = textColor
        )
        return
    }

    // Gentle pulse for the refresh icon when the discovery hint is active.
    // 0.95–1.18× scale on a 950ms cycle is loud enough to draw an eye but
    // soft enough to fade into the layout once the user has dismissed the
    // hint (we collapse the animation to a static 1f as soon as showHint
    // flips to false).
    val iconScale = if (showHint) {
        val pulse = rememberInfiniteTransition(label = "equivalency-hint-pulse")
        val anim by pulse.animateFloat(
            initialValue = 0.95f,
            targetValue = 1.18f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 950, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "equivalency-hint-pulse-scale"
        )
        anim
    } else 1f

    Surface(
        onClick = onCycle,
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent
    ) {
        Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Crossfade between the previous and next comparison so the
                // swap reads as a deliberate change rather than a flicker.
                Crossfade(
                    targetState = line,
                    label = "equivalency-crossfade"
                ) { current ->
                    Text(
                        text = current,
                        style = MaterialTheme.typography.bodySmall,
                        color = textColor
                    )
                }
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Show another comparison",
                    tint = iconColor,
                    modifier = Modifier
                        .size(14.dp)
                        .graphicsLayer {
                            scaleX = iconScale
                            scaleY = iconScale
                        }
                )
            }
            // The first-time caption sits underneath the line and fades in /
            // out on hint state changes so dismissal feels deliberate rather
            // than abrupt.
            AnimatedVisibility(
                visible = showHint,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Text(
                    text = "Tap to switch comparison",
                    style = MaterialTheme.typography.labelSmall,
                    fontStyle = FontStyle.Italic,
                    color = hintColor,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/**
 * Small chip rendered alongside the weekly delta when the user hits streak milestones.
 */
@Composable
private fun StreakChip(label: String) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(50),
        color = colorScheme.surfaceVariant.copy(alpha = 0.72f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/**
 * Single-bar 7-day chart of CO₂ saved per day. Renders as a row of rounded
 * vertical bars with three-letter weekday labels below. The today column is
 * always at the right edge (matches `dailyCo2Saved` index 6 = today).
 */
@Composable
private fun Co2DailyMiniChart(
    dailyCo2Saved: List<Double>,
    barColor: Color,
    axisColor: Color,
    labelColor: Color,
    today: Long
) {
    val colorScheme = MaterialTheme.colorScheme
    val maxVal = dailyCo2Saved.maxOrNull() ?: 0.0
    // Floor at 0.5 so an empty / very-quiet week still shows a visible baseline,
    // not a single 100%-tall bar from a tiny rounding-error value.
    val maxScale = max(maxVal, 0.5)

    val dayLabels = remember(today) { weekdayLabels(today) }

    Column {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
        ) {
            val barSlot = size.width / 7f
            val barWidth = barSlot * 0.55f
            val baseY = size.height - 4f
            val chartHeight = baseY - 6f

            // baseline
            drawLine(
                color = axisColor,
                start = Offset(0f, baseY),
                end = Offset(size.width, baseY),
                strokeWidth = 1.5f
            )

            dailyCo2Saved.forEachIndexed { index, value ->
                val centerX = barSlot * index + barSlot / 2f
                val barHeight = ((value / maxScale) * chartHeight)
                    .toFloat()
                    .coerceAtLeast(2f) // keep a minimum nub so empty days are still visible
                val left = centerX - barWidth / 2f
                val top = baseY - barHeight
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(left, top),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barWidth / 2.5f, barWidth / 2.5f)
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            dayLabels.forEachIndexed { index, label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    fontWeight = if (index == 6) FontWeight.Bold else FontWeight.Normal,
                    color = if (index == 6) colorScheme.onSurface else labelColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun HighlightsList(
    highlights: WeekHighlights,
    textColor: Color
) {
    Column(
        modifier = Modifier.padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        highlights.bestDayLabel?.let {
            HighlightRow(icon = Icons.Default.EmojiEvents, label = "Best day", value = it, textColor = textColor)
        }
        highlights.longestTripLabel?.let {
            HighlightRow(icon = Icons.Default.Straighten, label = "Longest trip", value = it, textColor = textColor)
        }
        highlights.topActivityLabel?.let {
            HighlightRow(icon = Icons.Default.AutoAwesome, label = "Top activity", value = it, textColor = textColor)
        }
        highlights.topTripSavingsLabel?.let {
            HighlightRow(icon = Icons.Default.Forest, label = "Best single trip", value = it, textColor = textColor)
        }
    }
}

@Composable
private fun HighlightRow(
    icon: ImageVector,
    label: String,
    value: String,
    textColor: Color
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = textColor.copy(alpha = 0.7f),
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = textColor.copy(alpha = 0.7f),
            modifier = Modifier.width(110.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = textColor
        )
    }
}

/**
 * Compact 1/3-width stat card used for the secondary-metrics row below the
 * hero. Optionally renders a small delta badge under the value when
 * [deltaPct] is non-null.
 */
@Composable
private fun CompactStatCard(
    title: String,
    value: String,
    icon: ImageVector,
    accent: Color,
    deltaPct: Double?,
    modifier: Modifier = Modifier,
    invertDeltaPolarity: Boolean = false
) {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colorScheme.onSurface,
                maxLines = 1
            )
            if (deltaPct != null) {
                Spacer(Modifier.height(2.dp))
                CompactDeltaText(deltaPct = deltaPct, invertPolarity = invertDeltaPolarity)
            }
        }
    }
}

@Composable
private fun CompactDeltaText(deltaPct: Double, invertPolarity: Boolean) {
    val colorScheme = MaterialTheme.colorScheme
    val rising = deltaPct >= 0
    val positive = if (invertPolarity) !rising else rising
    // No red anywhere — the delta is a fact, not a verdict. Highlights the
    // "good direction" with the brand accent and leaves the other direction
    // in a muted neutral so it informs without scolding.
    val color = colorScheme.onSurfaceVariant
    val arrow = if (rising) "▲" else "▼"
    Text(
        text = "$arrow ${abs(deltaPct).format(0)}%",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = color,
        maxLines = 1
    )
}

/**
 * Personalised AI insight teaser. Surfaces the first item from the cached
 * `analysis.insights` list as a one-line headline so the dashboard feels
 * like it knows the user, not just like a stats screen. The numeric score
 * is preserved as a small chip beside the AI sparkle so users who liked
 * the old "x.x / 10" still get it without it being the main attraction.
 *
 * If `analysis` is null (no cache, fresh user, or just-evicted), the card
 * collapses to a "Run AI analysis" CTA — the dashboard never shows a dead
 * tile.
 */
@Composable
private fun AIInsightCard(
    analysis: Kinetic_Eco.Tracker.data.ActivityAnalysis?,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    // Pick the first non-blank insight; fall back to the motivation line if
    // for some reason insights came back empty (Gemini occasionally returns
    // motivation but no insights for very small datasets).
    val teaser = analysis?.insights?.firstOrNull { it.isNotBlank() }
        ?: analysis?.motivation?.takeIf { it.isNotBlank() }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "AI insight",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))
            if (teaser != null) {
                Text(
                    text = "“$teaser”",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurface,
                    maxLines = 3,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Tap for the full breakdown →",
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = "Run an AI analysis to unlock personalised insights",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Tap to analyze →",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun LeaderboardOverviewCard(
    entries: List<LeaderboardEntry>,
    currentUserId: String,
    optedIn: Boolean,
    onSettingsClick: () -> Unit
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
                    imageVector = Icons.Default.EmojiEvents,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Leaderboard (7d)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(10.dp))

            if (!optedIn) {
                Text(
                    text = "Opt in via Settings to see your rank and compete with friends.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onSettingsClick) { Text("Open settings") }
                return@Card
            }

            if (entries.isEmpty()) {
                Text(
                    text = "Loading leaderboard…",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant
                )
                return@Card
            }

            entries.take(3).forEachIndexed { index, e ->
                LeaderboardCompactRow(rank = index + 1, entry = e, isMe = e.userId == currentUserId)
            }

            // If user not in top-3, show their own row
            val myEntry = entries.firstOrNull { it.userId == currentUserId }
            if (myEntry != null && entries.indexOf(myEntry) > 2) {
                Spacer(Modifier.height(4.dp))
                HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.3f))
                Spacer(Modifier.height(4.dp))
                LeaderboardCompactRow(rank = myEntry.rank, entry = myEntry, isMe = true)
            }
        }
    }
}

@Composable
private fun LeaderboardCompactRow(rank: Int, entry: LeaderboardEntry, isMe: Boolean) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "#$rank",
            style = MaterialTheme.typography.titleSmall,
            color = colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(36.dp)
        )
        if (entry.photoUrl.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = (entry.displayName?.firstOrNull() ?: 'U').uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onPrimaryContainer
                )
            }
        } else {
            AsyncImage(
                model = entry.photoUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(colorScheme.surface)
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = (entry.displayName ?: "User") + if (isMe) " (you)" else "",
            style = MaterialTheme.typography.bodyMedium,
            color = colorScheme.onSurface,
            fontWeight = if (isMe) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "${entry.co2Conserved.format(2)} kg CO₂",
            style = MaterialTheme.typography.bodySmall,
            color = colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RecentSessionRow(
    session: SessionStats,
    unitSystem: UnitSystem,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val (icon, tint) = activityIconAndTint(session, colorScheme)
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = tint)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.date.ifBlank { "Session" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colorScheme.onSurface
                )
                Text(
                    text = formatDistance(session.totalDistance, unitSystem) +
                        " • " + formatDuration(session.totalDuration),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "${session.co2Conserved.format(2)} kg",
                style = MaterialTheme.typography.labelLarge,
                color = colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

private fun activityIconAndTint(
    s: SessionStats,
    colorScheme: androidx.compose.material3.ColorScheme
): Pair<ImageVector, androidx.compose.ui.graphics.Color> {
    val dominant = s.breakdown.maxByOrNull { it.value.distance }?.key ?: ActivityType.WALKING
    val icon = when (dominant) {
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

private fun formatDistance(meters: Double, unitSystem: UnitSystem): String {
    return if (unitSystem.usesMetricDistance()) {
        "${(meters / 1000.0).format(2)} km"
    } else {
        "${(meters / 1609.344).format(2)} mi"
    }
}

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

/** Step count formatted with a thousands separator, or "1.2k" / "12.3k" for big numbers. */
private fun formatStepCount(steps: Long): String {
    return when {
        steps < 10_000 -> String.format(Locale.getDefault(), "%,d", steps)
        steps < 1_000_000 -> "${(steps / 1000.0).format(1)}k"
        else -> "${(steps / 1_000_000.0).format(1)}M"
    }
}

// ── Weekly-report computation helpers ────────────────────────────────────────

/**
 * Highlights surfaced in the expandable section of the hero card. Any field
 * may be null when there isn't enough data; the UI hides those rows.
 */
private data class WeekHighlights(
    val bestDayLabel: String?,
    val longestTripLabel: String?,
    val topActivityLabel: String?,
    val topTripSavingsLabel: String?
) {
    val hasAny: Boolean
        get() = bestDayLabel != null || longestTripLabel != null ||
                topActivityLabel != null || topTripSavingsLabel != null

    companion object {
        val Empty = WeekHighlights(null, null, null, null)
    }
}

/**
 * Bins this-week sessions into 7 daily CO₂-saved totals.
 * Index 0 = 6 days ago, index 6 = today (matches [weekdayLabels]).
 * Sessions with no `sessionEndTimeMs` are skipped so we never assign them to "today" by accident.
 */
private fun computeDailyCo2Saved(sessions: List<SessionStats>, now: Long): List<Double> {
    val buckets = DoubleArray(7)
    val dayMs = TimeUnit.DAYS.toMillis(1)
    sessions.forEach { s ->
        val ts = s.sessionEndTimeMs.takeIf { it > 0 } ?: return@forEach
        val daysAgo = ((now - ts) / dayMs).toInt()
        if (daysAgo in 0..6) {
            buckets[6 - daysAgo] += s.co2Conserved
        }
    }
    return buckets.toList()
}

private fun computeWeekHighlights(
    sessions: List<SessionStats>,
    dailyCo2Saved: List<Double>,
    now: Long
): WeekHighlights {
    if (sessions.isEmpty()) return WeekHighlights.Empty

    // Best day = highest CO2-saved day in the 7-day window, only reported if > 0.
    val bestDayIdx = dailyCo2Saved.indices.maxByOrNull { dailyCo2Saved[it] } ?: -1
    val bestDayLabel = if (bestDayIdx >= 0 && dailyCo2Saved[bestDayIdx] > 0.0) {
        val labels = weekdayLabels(now)
        "${labels[bestDayIdx]} — ${dailyCo2Saved[bestDayIdx].format(2)} kg"
    } else null

    // Longest trip by total distance. Show as "8.5 km · cycling" when we know the dominant activity.
    val longest = sessions.maxByOrNull { it.totalDistance }
    val longestTripLabel = longest
        ?.takeIf { it.totalDistance > 0.0 }
        ?.let { s ->
            val km = (s.totalDistance / 1000.0).format(1)
            val activity = dominantActivityLabel(s)
            if (activity != null) "$km km · $activity" else "$km km"
        }

    // Top activity across the whole week — sum distance per ActivityType across
    // every session's breakdown map, then pick the leader (excluding IDLE).
    val activityTotals = mutableMapOf<ActivityType, Double>()
    sessions.forEach { s ->
        s.breakdown.forEach { (activity, b) ->
            if (activity != ActivityType.IDLE && b.distance > 0.0) {
                activityTotals[activity] = (activityTotals[activity] ?: 0.0) + b.distance
            }
        }
    }
    val topActivityLabel = activityTotals
        .maxByOrNull { it.value }
        ?.takeIf { it.value > 0.0 }
        ?.let { (activity, distance) ->
            "${activityDisplayName(activity)} (${(distance / 1000.0).format(1)} km)"
        }

    // Best single trip by CO2 conserved.
    val topTrip = sessions.maxByOrNull { it.co2Conserved }
    val topTripSavingsLabel = topTrip
        ?.takeIf { it.co2Conserved > 0.0 }
        ?.let { "${it.co2Conserved.format(2)} kg saved" }

    return WeekHighlights(
        bestDayLabel = bestDayLabel,
        longestTripLabel = longestTripLabel,
        topActivityLabel = topActivityLabel,
        topTripSavingsLabel = topTripSavingsLabel
    )
}

/** Dominant activity across a single session's breakdown (by distance). */
private fun dominantActivityLabel(s: SessionStats): String? {
    val dominant = s.breakdown
        .filterKeys { it != ActivityType.IDLE }
        .maxByOrNull { it.value.distance }
        ?.takeIf { it.value.distance > 0.0 }
        ?.key
    return dominant?.let { activityDisplayName(it) }
}

private fun activityDisplayName(a: ActivityType): String = when (a) {
    ActivityType.WALKING -> "Walking"
    ActivityType.RUNNING -> "Running"
    ActivityType.CYCLING -> "Cycling"
    ActivityType.MOTORCYCLE -> "Motorcycle"
    ActivityType.DRIVING -> "Driving"
    ActivityType.ELECTRIC_VEHICLE -> "EV"
    ActivityType.TRAIN -> "Train"
    ActivityType.FLYING -> "Flying"
    ActivityType.IDLE -> "Idle"
}

/**
 * Returns a list of 7 short weekday labels (e.g. "Mon", "Tue") with index 0 =
 * 6 days ago and index 6 = today, in the device locale.
 */
private fun weekdayLabels(now: Long): List<String> {
    val fmt = SimpleDateFormat("EEE", Locale.getDefault())
    val cal = Calendar.getInstance()
    return (6 downTo 0).map { daysAgo ->
        cal.timeInMillis = now
        cal.add(Calendar.DAY_OF_YEAR, -daysAgo)
        fmt.format(cal.time)
    }
}

/**
 * Percentage delta of [current] vs [previous]. Returns null when [previous] is
 * 0 (delta is undefined) — the UI surfaces that as a "NEW THIS WEEK" badge.
 */
private fun percentDelta(current: Double, previous: Double): Double? {
    if (previous <= 0.0001) return null
    return ((current - previous) / previous) * 100.0
}

/**
 * Builds a warm, motivational subtitle for the dashboard hero. Tone bands are
 * intentionally generous so the user feels seen even on a quiet week — the
 * goal is positive reinforcement, never pressure.
 */

/**
 * Draws a single teardrop leaf (pointed tip, rounded base) centred at
 * [anchorX]/[anchorY], rotated [angleDeg] degrees around that anchor.
 * Must live outside [SproutingPlantScene]'s Canvas lambda so the
 * [DrawScope] receiver is preserved through the nested [rotate] block.
 */
private fun DrawScope.drawTreeLeaf(
    anchorX: Float,
    anchorY: Float,
    length: Float,
    width: Float,
    angleDeg: Float,
) {
    val leafFill = Color(0xFF388E3C)   // deep green
    val leafVein = Color(0xFF81C784)   // lighter midrib

    rotate(degrees = angleDeg, pivot = Offset(anchorX, anchorY)) {
        val path = Path().apply {
            moveTo(anchorX, anchorY - length * 0.50f)          // tip
            cubicTo(
                anchorX + width * 0.52f, anchorY - length * 0.22f,
                anchorX + width * 0.52f, anchorY + length * 0.22f,
                anchorX, anchorY + length * 0.50f              // base
            )
            cubicTo(
                anchorX - width * 0.52f, anchorY + length * 0.22f,
                anchorX - width * 0.52f, anchorY - length * 0.22f,
                anchorX, anchorY - length * 0.50f              // back to tip
            )
        }
        drawPath(path, leafFill)
        // Central vein
        drawLine(
            color = leafVein.copy(alpha = 0.58f),
            start = Offset(anchorX, anchorY - length * 0.44f),
            end   = Offset(anchorX, anchorY + length * 0.44f),
            strokeWidth = width * 0.09f,
            cap = StrokeCap.Round
        )
    }
}

/**
 * Realistic growing-tree scene drawn entirely with [Canvas].
 *
 * A tapered brown trunk grows upward from a soil oval. Teardrop leaves
 * sprout on branch stubs, a soft canopy glow fills in as the tree matures,
 * root hints emerge late-stage, and a five-petal pink blossom crowns the
 * tree when the weekly CO₂ goal is reached.
 *
 *   progress 0.00 – 0.15 → seedling  (stem + 2 tiny leaves)
 *   progress 0.15 – 0.45 → sapling   (trunk visible, 2 – 4 leaves)
 *   progress 0.45 – 0.75 → young tree (4 leaves + canopy glow)
 *   progress 0.75 – 1.00 → full tree  (6 leaves + roots)
 *   progress ≥ 1.00       → blossom crowns the tree
 */
@Composable
private fun SproutingPlantScene(
    co2SavedKg: Double,
    goalKg: Double,
    @Suppress("UNUSED_PARAMETER") leafColor: Color,
    @Suppress("UNUSED_PARAMETER") stemColor: Color,
    modifier: Modifier = Modifier
) {
    val safeGoal = goalKg.coerceAtLeast(0.1)
    val rawProgress = (co2SavedKg / safeGoal).toFloat().coerceIn(0f, 1.4f)

    val animatedProgress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = rawProgress,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = 900,
            easing = androidx.compose.animation.core.FastOutSlowInEasing
        ),
        label = "plant_growth"
    )

    // Realistic palette — independent of app theme so the tree always reads
    // as a tree regardless of dark / light mode.
    val trunkColor    = Color(0xFF5D4037)   // dark bark
    val barkHighlight = Color(0xFF8D6E63)   // lighter bark stripe
    val soilColor     = Color(0xFF6D4C41)   // soil
    val canopyGreen   = Color(0xFF388E3C)   // leaf / canopy
    val petalColor    = Color(0xFFEC407A)   // blossom petals
    val petalCenter   = Color(0xFFFDD835)   // blossom centre

    androidx.compose.foundation.Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val p  = animatedProgress.coerceAtMost(1f)

        val baseY     = h * 0.91f
        val trunkH    = h * (0.13f + 0.56f * p)
        val stemTopY  = baseY - trunkH

        // Trunk widens with progress; top is ~42 % of base width.
        val trunkBaseW = w * (0.055f + 0.10f * p)
        val trunkTopW  = trunkBaseW * 0.42f

        // ── Soil oval ──────────────────────────────────────────────────────
        drawOval(
            color = soilColor.copy(alpha = 0.55f),
            topLeft = Offset(cx - w * 0.30f, baseY - h * 0.022f),
            size = Size(w * 0.60f, h * 0.046f)
        )

        // ── Root hints (appear when tree is well-established) ──────────────
        val rootAlpha = ((p - 0.58f) / 0.22f).coerceIn(0f, 1f)
        if (rootAlpha > 0f) {
            for (side in floatArrayOf(-1f, 1f)) {
                val rootPath = Path().apply {
                    moveTo(cx + side * trunkBaseW * 0.48f, baseY - h * 0.016f)
                    quadraticBezierTo(
                        cx + side * w * 0.18f, baseY + h * 0.006f,
                        cx + side * w * 0.27f, baseY + h * 0.002f
                    )
                }
                drawPath(
                    rootPath,
                    color = trunkColor.copy(alpha = 0.50f * rootAlpha),
                    style = Stroke(width = trunkBaseW * 0.28f, cap = StrokeCap.Round)
                )
            }
        }

        // ── Tapered trunk (organic bezier sides) ───────────────────────────
        val trunkPath = Path().apply {
            moveTo(cx - trunkBaseW * 0.50f, baseY)
            cubicTo(
                cx - trunkBaseW * 0.44f, baseY  - trunkH * 0.30f,
                cx - trunkTopW  * 0.80f, stemTopY + trunkH * 0.12f,
                cx - trunkTopW  * 0.50f, stemTopY
            )
            lineTo(cx + trunkTopW * 0.50f, stemTopY)
            cubicTo(
                cx + trunkTopW  * 0.80f, stemTopY + trunkH * 0.12f,
                cx + trunkBaseW * 0.44f, baseY  - trunkH * 0.30f,
                cx + trunkBaseW * 0.50f, baseY
            )
            close()
        }
        drawPath(trunkPath, trunkColor)

        // Bark highlight stripe on the right face
        if (p > 0.16f) {
            val bx = cx + trunkBaseW * 0.12f
            val barkPath = Path().apply {
                moveTo(bx, baseY - trunkH * 0.06f)
                cubicTo(
                    bx + trunkBaseW * 0.07f, baseY  - trunkH * 0.36f,
                    bx,                       stemTopY + trunkH * 0.22f,
                    bx - trunkBaseW * 0.05f,  stemTopY + trunkH * 0.04f
                )
            }
            drawPath(
                barkPath,
                color = barkHighlight.copy(alpha = 0.36f),
                style = Stroke(width = trunkBaseW * 0.26f, cap = StrokeCap.Round)
            )
        }

        // ── Leaves on branch stubs ─────────────────────────────────────────
        // Each entry: (side, yFrac from stem top, leaf angle, minProgress)
        data class LeafSlot(val side: Float, val yFrac: Float, val angle: Float, val minP: Float)
        val slots = listOf(
            LeafSlot(-1f, 0.04f, -38f, 0.03f),  // top-left   (seedling)
            LeafSlot(+1f, 0.04f, +38f, 0.03f),  // top-right  (seedling)
            LeafSlot(+1f, 0.28f, +44f, 0.18f),  // mid-right
            LeafSlot(-1f, 0.28f, -45f, 0.25f),  // mid-left
            LeafSlot(-1f, 0.54f, -40f, 0.46f),  // lower-left
            LeafSlot(+1f, 0.54f, +42f, 0.54f),  // lower-right
        )

        slots.forEach { s ->
            val scale = ((p - s.minP) / 0.17f).coerceIn(0f, 1f)
            if (scale < 0.02f) return@forEach

            val leafLen  = w * (0.20f + 0.09f * p) * scale
            val leafW    = leafLen * 0.58f
            val anchorY  = stemTopY + (baseY - stemTopY) * s.yFrac
            val anchorX  = cx + s.side * (trunkBaseW * 0.52f + w * 0.13f * scale)

            // Branch stub from trunk edge to leaf anchor
            drawLine(
                color       = trunkColor,
                start       = Offset(cx + s.side * trunkTopW * 0.38f, anchorY),
                end         = Offset(anchorX, anchorY - leafLen * 0.08f),
                strokeWidth = trunkBaseW * 0.30f * scale,
                cap         = StrokeCap.Round
            )

            drawTreeLeaf(
                anchorX  = anchorX,
                anchorY  = anchorY,
                length   = leafLen,
                width    = leafW,
                angleDeg = s.angle
            )
        }

        // ── Soft canopy glow behind the leaves ────────────────────────────
        val canopyA = ((p - 0.42f) / 0.30f).coerceIn(0f, 1f)
        if (canopyA > 0f) {
            drawCircle(
                color  = canopyGreen.copy(alpha = 0.13f * canopyA),
                radius = w * (0.30f + 0.12f * p),
                center = Offset(cx, stemTopY + w * 0.06f)
            )
        }

        // ── Goal-met blossom ───────────────────────────────────────────────
        if (animatedProgress >= 1.0f) {
            val bloom = ((animatedProgress - 1.0f) / 0.2f).coerceIn(0f, 1f)
                .let { 0.4f + 0.6f * it }
            val pr = w * 0.095f * bloom
            val blossomPos = Offset(cx, stemTopY - pr * 0.5f)
            repeat(5) { i ->
                val a = Math.toRadians(i * 72.0 - 90.0)
                drawCircle(
                    color  = petalColor.copy(alpha = 0.90f),
                    radius = pr,
                    center = Offset(
                        cx + (pr * 1.15f * kotlin.math.cos(a)).toFloat(),
                        blossomPos.y + (pr * 1.15f * kotlin.math.sin(a)).toFloat()
                    )
                )
            }
            drawCircle(color = petalCenter, radius = pr * 0.58f, center = blossomPos)
        }
    }
}

private fun motivationalHeroSubtitle(weekCo2SavedKg: Double): String = when {
    weekCo2SavedKg >= 10.0 -> "Crushing it this week 🌟"
    weekCo2SavedKg >= 5.0  -> "Great week — keep it going!"
    weekCo2SavedKg >= 1.0  -> "Nice work this week 🌱"
    weekCo2SavedKg > 0.0   -> "Every step counts!"
    else                   -> "Ready when you are — let's start small."
}

/**
 * Time-of-day greeting buckets in 24h local time. We pick four bands instead
 * of two so the dashboard feels alive across the whole day. Dawn/late night
 * both read as "Good night" — keeps the copy tight without saying "Hello,
 * 4 a.m. user".
 *  05–11 → "Good morning"
 *  12–16 → "Good afternoon"
 *  17–20 → "Good evening"
 *  21–04 → "Good night"
 */
private fun greetingForHour(hour: Int): String = when (hour) {
    in 5..11  -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..20 -> "Good evening"
    else      -> "Good night"
}

/**
 * Pulls the first whitespace-delimited token from a display name so the
 * greeting feels personal ("Good morning, Brian") even when the user
 * registered with their full legal name. Falls back to the original string
 * if there's no space (e.g. single-word handle).
 */
private fun String.firstName(): String =
    trim().substringBefore(' ').ifBlank { this }

/**
 * Returns a celebratory chip label only at meaningful milestones. We
 * deliberately skip 1- and 2-day counters because everyday streak counters
 * tend to create pressure when broken; milestones (3 / 7 / 30) feel like
 * little rewards instead.
 */
private fun streakMilestoneLabel(streakDays: Int): String? = when {
    streakDays >= 30 -> "🌳 ${streakDays}-day green streak!"
    streakDays >= 7  -> "🌿 ${streakDays}-day streak"
    streakDays >= 3  -> "✨ ${streakDays}-day streak"
    else             -> null
}

/**
 * Counts the trailing run of "green" days ending today. A day is "green" if
 * the user's CO₂ savings on that day are at least equal to their emissions —
 * which crucially includes days with no sessions at all (saved = emitted = 0).
 * That means rest days never break a streak and the chip never punishes the
 * user for not opening the app.
 *
 * We cap the search at the most recent 60 days for cheapness; users with
 * longer streaks will still see "60-day green streak" which is plenty as a
 * celebration ceiling.
 */
private fun computeGreenStreak(sessions: List<Kinetic_Eco.Tracker.data.SessionStats>, now: Long): Int {
    val dayMs = TimeUnit.DAYS.toMillis(1)
    val maxLookback = 60
    val saved = DoubleArray(maxLookback)
    val emitted = DoubleArray(maxLookback)
    sessions.forEach { s ->
        val ts = s.sessionEndTimeMs.takeIf { it > 0 } ?: return@forEach
        val daysAgo = ((now - ts) / dayMs).toInt()
        if (daysAgo in 0 until maxLookback) {
            saved[daysAgo] += s.co2Conserved
            emitted[daysAgo] += s.co2Emissions
        }
    }
    var streak = 0
    for (i in 0 until maxLookback) {
        if (saved[i] + 0.0001 >= emitted[i]) streak++ else break
    }
    return streak
}
