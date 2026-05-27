package Kinetic_Eco.Tracker.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.outlined.TwoWheeler
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import Kinetic_Eco.Tracker.R
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import Kinetic_Eco.Tracker.data.*
import Kinetic_Eco.Tracker.ui.components.InterstitialAdManager
import Kinetic_Eco.Tracker.ui.components.SingleLineMetricValueText
import Kinetic_Eco.Tracker.ui.components.SingleLineValueText
import Kinetic_Eco.Tracker.ui.components.DynamicTrackerBackground
import Kinetic_Eco.Tracker.ui.theme.*
import Kinetic_Eco.Tracker.ui.utils.EnergyUnit
import Kinetic_Eco.Tracker.ui.utils.format
import Kinetic_Eco.Tracker.ui.utils.formatEnergy
import Kinetic_Eco.Tracker.ui.utils.formatSpeedMax
import Kinetic_Eco.Tracker.ui.utils.formatTime
import Kinetic_Eco.Tracker.ui.utils.usesMetricDistance
import Kinetic_Eco.Tracker.viewmodel.TrackerViewModel

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
fun TrackerScreen(
    viewModel: TrackerViewModel,
    unitSystem: UnitSystem = UnitSystem.METRIC,
    energyUnit: EnergyUnit = EnergyUnit.KCAL,
    onActivitySelectorClick: () -> Unit,
    userId: String = "",
    autoStartOnWalkEnabled: Boolean = false,
    onSessionSaved: () -> Unit = {}
) {
    // Log userId for debugging
    LaunchedEffect(userId) {
        android.util.Log.d("TrackerScreen", "🔑 TrackerScreen initialized with userId: '$userId' (isEmpty: ${userId.isEmpty()})")
    }
    
    // GPS warm-up: start when Tracker screen is shown so GPS is ready when user hits Start
    DisposableEffect(Unit) {
        viewModel.startGpsWarmUp()
        onDispose { viewModel.stopGpsWarmUp() }
    }
    
    val isTracking by viewModel.isTracking.collectAsStateWithLifecycle()

    // Reset the interstitial per-session gate whenever the user starts a new tracking session.
    LaunchedEffect(isTracking) {
        if (isTracking) InterstitialAdManager.resetSession()
    }

    val currentSpeed by viewModel.currentSpeed.collectAsStateWithLifecycle()
    
    // Animate speed for near-instant response (user prefers responsiveness over smoothness)
    val animatedSpeed by animateFloatAsState(
        targetValue = currentSpeed,
        animationSpec = tween(durationMillis = 100, easing = FastOutSlowInEasing),
        label = "speed_animation"
    )
    
    val currentActivity by viewModel.currentActivity.collectAsStateWithLifecycle()
    val manualActivityMode by viewModel.manualActivityMode.collectAsStateWithLifecycle()
    val leanActivityHint by viewModel.leanActivityHint.collectAsStateWithLifecycle()
    val showEvConfirmPrompt by viewModel.evConfirmPrompt.collectAsStateWithLifecycle()
    val sessionDuration by viewModel.sessionDuration.collectAsStateWithLifecycle()
    val sessionDistance by viewModel.sessionDistance.collectAsStateWithLifecycle()
    val sessionSteps by viewModel.sessionSteps.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val currentPosition by viewModel.currentPosition.collectAsStateWithLifecycle()
    val warmUpPosition by viewModel.warmUpPosition.collectAsStateWithLifecycle()
    var showStopDialog by remember { mutableStateOf(false) }
    var showSessionSummary by remember { mutableStateOf(false) }
    var savedSessionStats by remember { mutableStateOf<SessionStats?>(null) }
    var isSavingSession by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val speedDisplay = if (unitSystem.usesMetricDistance()) {
        "${(animatedSpeed * 3.6f).format(1)} km/h"
    } else {
        "${(animatedSpeed * 2.23694f).format(1)} mph"
    }
    
    val distanceDisplay = if (unitSystem.usesMetricDistance()) {
        "${(sessionDistance / 1000.0).format(2)} km"
    } else {
        "${(sessionDistance / 1609.34).format(2)} mi"
    }
    
    // Altitude display - from warm-up (before Start) or tracking (after Start)
    val altitudeValue = currentPosition?.altitude ?: warmUpPosition?.altitude
    val altitudeDisplay: String = if (altitudeValue != null) {
        if (unitSystem.usesMetricDistance()) {
            "${altitudeValue.toInt()} m"
        } else {
            "${(altitudeValue * 3.28084).toInt()} ft"
        }
    } else {
        if (unitSystem.usesMetricDistance()) "-- m" else "-- ft"
    }
    
    // Show steps for Walking and Running activities (show even if 0 to indicate tracking)
    val isWalkingOrRunning = currentActivity == ActivityType.WALKING || currentActivity == ActivityType.RUNNING
    val colorScheme = MaterialTheme.colorScheme
    
    Box(modifier = Modifier.fillMaxSize()) {
        // Animated, code-driven background: time-of-day base with a tint
        // that follows the current activity. Replaces the old looped MP4.
        DynamicTrackerBackground(
            currentActivity = currentActivity,
            modifier = Modifier.matchParentSize()
        )
        // Soft darken at the bottom only — keeps the dial/stats legible
        // without flattening the gradient like the old full-screen scrim did.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0.6f to Color.Transparent,
                        1.0f to Color.Black.copy(alpha = 0.35f)
                    )
                )
        )
        Column(modifier = Modifier.fillMaxSize()) {
            // Main content — vertically centred in the space above the activity panel
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
        // Error message
        errorMessage?.let { error ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = colorScheme.surfaceVariant.copy(alpha = 0.72f)
                )
            ) {
                Text(
                    text = error,
                    color = colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        
        // Speedometer Circle
        SpeedometerCircle(
            speed = animatedSpeed,
            speedDisplay = speedDisplay,
            activity = currentActivity,
            isTracking = isTracking,
            manualMode = manualActivityMode != null
        )
        
        Spacer(modifier = Modifier.height(24.dp))

        // Stats Row 1: Duration / Distance / Steps (walking/running only)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatCard(stringResource(R.string.duration), formatTime(sessionDuration), modifier = Modifier.weight(1f))
            StatCard(stringResource(R.string.distance), distanceDisplay, modifier = Modifier.weight(1f))
            if (isWalkingOrRunning) {
                StatCard(
                    label = stringResource(R.string.steps),
                    value = sessionSteps.toString(),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Manual mode indicator
        manualActivityMode?.let { mode ->
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = colorScheme.surfaceVariant.copy(alpha = 0.55f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.manual_mode, stringResource(mode.toActivityStringResId())),
                    color = colorScheme.onSurface,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        
        // Lean hint (manual mode only — never overrides pinned activity)
        val hint = leanActivityHint
        if (isTracking && hint != null && manualActivityMode != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = colorScheme.secondaryContainer.copy(alpha = 0.92f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = stringResource(R.string.lean_hint_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            R.string.lean_hint_message,
                            stringResource(manualActivityMode!!.toActivityStringResId()),
                            stringResource(hint.toActivityStringResId())
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { viewModel.dismissLeanActivityHint() }) {
                            Text(stringResource(R.string.lean_hint_dismiss))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            onClick = {
                                viewModel.setManualActivityMode(hint)
                            }
                        ) {
                            Text(stringResource(R.string.lean_hint_switch))
                        }
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(28.dp))

        // Play/Stop Button with Pulsating Animation
        PulsatingButton(
            isTracking = isTracking,
            onClick = {
                if (isTracking) {
                    showStopDialog = true
                } else {
                    viewModel.startTracking()
                }
            }
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = if (isTracking) stringResource(R.string.tracking_active) else stringResource(R.string.tap_to_start),
            color = colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "${stringResource(R.string.altitude)}: $altitudeDisplay",
            color = colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            style = MaterialTheme.typography.labelSmall
        )

        Spacer(modifier = Modifier.height(12.dp))
        } // end inner Column
        } // end Box(weight = 1f)

        // Activity strip — always visible at the bottom, scroll horizontally for all modes
        TrackerActivityStrip(
            manualActivityMode = manualActivityMode,
            onActivitySelected = { viewModel.setManualActivityMode(it) },
            onAutoSelected = { viewModel.setManualActivityMode(null) }
        )
        } // end outer Column
    }

    // Stop Dialog
    if (showStopDialog) {
        StopTrackingDialog(
            onDismiss = { if (!isSavingSession) showStopDialog = false },
            onPause = {
                if (isSavingSession) return@StopTrackingDialog
                viewModel.pauseTracking()
                showStopDialog = false
            },
            onStopAndSave = {
                if (isSavingSession) return@StopTrackingDialog
                android.util.Log.d("TrackerScreen", "🛑 ===== STOP AND SAVE CLICKED =====")
                android.util.Log.d("TrackerScreen", "🛑 User ID: '$userId' (isEmpty: ${userId.isEmpty()})")
                saveError = null

                if (userId.isEmpty()) {
                    android.util.Log.e("TrackerScreen", "❌ ERROR: User ID is empty! User might not be logged in.")
                    saveError = context.getString(R.string.please_login_save)
                    savedSessionStats = viewModel.getSessionStats()
                    viewModel.stopTracking()
                    viewModel.resetSession()
                    showStopDialog = false
                    showSessionSummary = true
                    return@StopTrackingDialog
                }

                // Get current stats before stopping
                val currentStats = viewModel.getSessionStats()
                savedSessionStats = currentStats
                isSavingSession = true

                viewModel.stopAndSaveSessionAsync(userId) { result ->
                    isSavingSession = false
                    result.fold(
                        onSuccess = {
                            android.util.Log.d("TrackerScreen", "✅ stopAndSaveSession completed successfully")
                            onSessionSaved()
                            // Pre-load the next interstitial while the user reads their summary.
                            InterstitialAdManager.preload(context)
                            showStopDialog = false
                            showSessionSummary = true
                        },
                        onFailure = { e ->
                            android.util.Log.e("TrackerScreen", "❌ ERROR: Failed to save session", e)
                            saveError = e.message ?: context.getString(R.string.failed_save_session)
                            viewModel.stopTracking()
                            viewModel.resetSession()
                            showStopDialog = false
                            showSessionSummary = true
                        }
                    )
                }
            },
            onDiscard = {
                if (isSavingSession) return@StopTrackingDialog
                viewModel.stopTracking()
                viewModel.resetSession()
                showStopDialog = false
            },
            isSaving = isSavingSession
        )
    }
    
    // Session Summary Dialog
    if (showSessionSummary && savedSessionStats != null) {
        SessionSummaryDialog(
            stats = savedSessionStats!!,
            unitSystem = unitSystem,
            energyUnit = energyUnit,
            saveError = saveError,
            onClose = {
                showSessionSummary = false
                savedSessionStats = null
                saveError = null
                // Show the pre-loaded interstitial (guards: 1 per session, 3-min cooldown).
                (context as? android.app.Activity)?.let { InterstitialAdManager.showIfReady(it) }
            }
        )
    }

    if (showEvConfirmPrompt && !isSavingSession) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissEvConfirmPrompt() },
            title = { Text("Electric vehicle?") },
            text = { Text("We detected driving. Are you in an EV? Switching to EV mode gives you more accurate CO₂ tracking.") },
            confirmButton = {
                Button(onClick = {
                    viewModel.dismissEvConfirmPrompt()
                    viewModel.setManualActivityMode(ActivityType.ELECTRIC_VEHICLE)
                }) { Text("Switch to EV") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissEvConfirmPrompt() }) { Text("Keep Driving") }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
}

@Composable
fun TrackerScreenWithFAB(
    viewModel: TrackerViewModel,
    unitSystem: UnitSystem = UnitSystem.METRIC,
    energyUnit: EnergyUnit = EnergyUnit.KCAL,
    onActivitySelectorClick: () -> Unit,
    userId: String = "",
    autoStartOnWalkEnabled: Boolean = false,
    onSessionSaved: () -> Unit = {}
) {
    TrackerScreen(
        viewModel = viewModel,
        unitSystem = unitSystem,
        energyUnit = energyUnit,
        onActivitySelectorClick = onActivitySelectorClick,
        userId = userId,
        autoStartOnWalkEnabled = autoStartOnWalkEnabled,
        onSessionSaved = onSessionSaved
    )
}

// ── Activity strip (always visible) ──────────────────────────────────────────

private data class TrackerActivityItem(
    val type: ActivityType?,
    val label: String,
    val icon: ImageVector,
    val color: Color
)

private val trackerActivityOptions = listOf(
    TrackerActivityItem(null,                          "Auto",  Icons.Default.AutoAwesome,                  Color(0xFF2196F3)),
    TrackerActivityItem(ActivityType.WALKING,          "Walk",  Icons.AutoMirrored.Filled.DirectionsWalk,   Color(0xFF4CAF50)),
    TrackerActivityItem(ActivityType.RUNNING,          "Run",   Icons.AutoMirrored.Filled.DirectionsRun,    Color(0xFF10B981)),
    TrackerActivityItem(ActivityType.CYCLING,          "Cycle", Icons.AutoMirrored.Filled.DirectionsBike,   Color(0xFF00BCD4)),
    TrackerActivityItem(ActivityType.MOTORCYCLE,       "Moto",  Icons.Outlined.TwoWheeler,                  Color(0xFFFF9800)),
    TrackerActivityItem(ActivityType.TRAIN,            "Train", Icons.Default.Train,                        Color(0xFF009688)),
    TrackerActivityItem(ActivityType.DRIVING,          "Drive", Icons.Default.DirectionsCar,                Color(0xFFFFC107)),
    TrackerActivityItem(ActivityType.ELECTRIC_VEHICLE, "EV",   Icons.Default.ElectricCar,                  Color(0xFF673AB7)),
    TrackerActivityItem(ActivityType.FLYING,           "Fly",  Icons.Default.Flight,                        Color(0xFF1565C0)),
)

@Composable
private fun TrackerActivityStrip(
    manualActivityMode: ActivityType?,
    onActivitySelected: (ActivityType) -> Unit,
    onAutoSelected: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    var expanded by rememberSaveable { mutableStateOf(false) }

    val draggableState = rememberDraggableState { delta ->
        if (delta < 0f && !expanded) expanded = true
        else if (delta > 0f && expanded) expanded = false
    }

    val firstRow = trackerActivityOptions.take(3)
    val restRows  = trackerActivityOptions.drop(3).chunked(3)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colorScheme.surface.copy(alpha = 0.88f))
    ) {
        // Drag handle — tap or swipe to expand / collapse
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .draggable(orientation = Orientation.Vertical, state = draggableState)
                .clickable { expanded = !expanded },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(width = 32.dp, height = 4.dp)
                    .background(
                        colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                        RoundedCornerShape(2.dp)
                    )
            )
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowDown
                              else Icons.Default.KeyboardArrowUp,
                contentDescription = if (expanded) "Collapse" else "Expand activities",
                tint = colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp)
                    .size(18.dp)
            )
        }

        // Always-visible first row
        Column(modifier = Modifier.padding(horizontal = 10.dp)) {
            ActivityTileRow(
                rowItems = firstRow,
                manualActivityMode = manualActivityMode,
                onActivitySelected = onActivitySelected,
                onAutoSelected = onAutoSelected
            )

            // Remaining rows slide in on expand
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(expandFrom = Alignment.Top),
                exit  = shrinkVertically(shrinkTowards = Alignment.Top)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.height(8.dp))
                    restRows.forEach { rowItems ->
                        ActivityTileRow(
                            rowItems = rowItems,
                            manualActivityMode = manualActivityMode,
                            onActivitySelected = onActivitySelected,
                            onAutoSelected = onAutoSelected
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun ActivityTileRow(
    rowItems: List<TrackerActivityItem>,
    manualActivityMode: ActivityType?,
    onActivitySelected: (ActivityType) -> Unit,
    onAutoSelected: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        rowItems.forEach { item ->
            val isSelected = item.type == manualActivityMode
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isSelected) item.color.copy(alpha = 0.28f)
                        else colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    )
                    .clickable {
                        if (item.type == null) onAutoSelected()
                        else onActivitySelected(item.type)
                    }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(item.color.copy(alpha = if (isSelected) 0.38f else 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        tint = if (isSelected) Color.White else item.color,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontSize = 11.sp,
                    color = if (isSelected) Color.White else colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
        }
    }
}

// ── Activity grid panel ───────────────────────────────────────────────────────

@Composable
private fun ActivityGridPanel(
    manualActivityMode: ActivityType?,
    onActivitySelected: (ActivityType) -> Unit,
    onAutoSelected: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    var expanded by rememberSaveable { mutableStateOf(false) }

    val draggableState = rememberDraggableState { delta ->
        if (delta > 0f && expanded) expanded = false
        else if (delta < 0f && !expanded) expanded = true
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colorScheme.surface.copy(alpha = 0.88f))
    ) {
        // Drag handle row — tap or drag to expand/collapse
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .draggable(orientation = Orientation.Vertical, state = draggableState)
                .clickable { expanded = !expanded },
            contentAlignment = Alignment.Center
        ) {
            // Pill handle
            Box(
                modifier = Modifier
                    .size(width = 36.dp, height = 4.dp)
                    .background(
                        colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                        RoundedCornerShape(2.dp)
                    )
            )
            // Current selection label (shown when collapsed)
            if (!expanded) {
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = manualActivityMode?.let { activityTileIcon(it) }
                            ?: Icons.Default.Autorenew,
                        contentDescription = null,
                        tint = colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = manualActivityMode?.let { activityTileShortName(it) } ?: "Auto",
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }
            // Chevron indicator
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowDown
                              else Icons.Default.KeyboardArrowUp,
                contentDescription = if (expanded) "Collapse" else "Expand activities",
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp)
                    .size(20.dp),
                tint = colorScheme.onSurfaceVariant
            )
        }

        // Grid — slides in/out
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(expandFrom = Alignment.Top),
            exit = shrinkVertically(shrinkTowards = Alignment.Top)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Auto-detect (full-width row above the grid)
                ActivityAutoTile(
                    isSelected = manualActivityMode == null,
                    onClick = onAutoSelected,
                    modifier = Modifier.fillMaxWidth()
                )

                // 3 × 3 activity grid
                listOf(
                    listOf(ActivityType.IDLE,    ActivityType.WALKING, ActivityType.RUNNING),
                    listOf(ActivityType.CYCLING, ActivityType.MOTORCYCLE, ActivityType.TRAIN),
                    listOf(ActivityType.DRIVING, ActivityType.ELECTRIC_VEHICLE, ActivityType.FLYING)
                ).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEach { activity ->
                            ActivityTile(
                                activity = activity,
                                isSelected = manualActivityMode == activity,
                                onClick = { onActivitySelected(activity) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityTile(
    activity: ActivityType,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val accent = activityTileAccentColor(activity)
    val containerColor = if (isSelected) accent.copy(alpha = 0.82f)
                         else colorScheme.surfaceVariant.copy(alpha = 0.55f)
    val contentColor = if (isSelected) Color.White else colorScheme.onSurface
    val border = if (isSelected)
        androidx.compose.foundation.BorderStroke(1.5.dp, accent) else null

    Surface(
        onClick = onClick,
        modifier = modifier.aspectRatio(1f),
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        border = border
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = activityTileIcon(activity),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = activityTileShortName(activity),
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                maxLines = 1,
                textAlign = TextAlign.Center,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun ActivityAutoTile(
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val containerColor = if (isSelected) colorScheme.primary.copy(alpha = 0.82f)
                         else colorScheme.surfaceVariant.copy(alpha = 0.55f)
    val contentColor = if (isSelected) colorScheme.onPrimary else colorScheme.onSurface
    val border = if (isSelected)
        androidx.compose.foundation.BorderStroke(1.5.dp, colorScheme.primary) else null

    Surface(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        border = border
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Autorenew,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Auto-detect",
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

private fun activityTileIcon(activity: ActivityType): ImageVector = when (activity) {
    ActivityType.IDLE             -> Icons.Default.Accessibility
    ActivityType.WALKING          -> Icons.AutoMirrored.Filled.DirectionsWalk
    ActivityType.RUNNING          -> Icons.AutoMirrored.Filled.DirectionsRun
    ActivityType.CYCLING          -> Icons.Default.DirectionsBike
    ActivityType.MOTORCYCLE       -> Icons.Default.Motorcycle
    ActivityType.TRAIN            -> Icons.Default.Train
    ActivityType.DRIVING          -> Icons.Default.DirectionsCar
    ActivityType.ELECTRIC_VEHICLE -> Icons.Default.ElectricCar
    ActivityType.FLYING           -> Icons.Default.Flight
}

private fun activityTileShortName(activity: ActivityType): String = when (activity) {
    ActivityType.IDLE             -> "Idle"
    ActivityType.WALKING          -> "Walk"
    ActivityType.RUNNING          -> "Run"
    ActivityType.CYCLING          -> "Cycle"
    ActivityType.MOTORCYCLE       -> "Moto"
    ActivityType.TRAIN            -> "Train"
    ActivityType.DRIVING          -> "Drive"
    ActivityType.ELECTRIC_VEHICLE -> "EV"
    ActivityType.FLYING           -> "Fly"
}

private fun activityTileAccentColor(activity: ActivityType): Color = when (activity) {
    ActivityType.IDLE             -> Color(0xFF708090)
    ActivityType.WALKING          -> Color(0xFF4CAF80)
    ActivityType.RUNNING          -> Color(0xFFFF7043)
    ActivityType.CYCLING          -> Color(0xFF29B6F6)
    ActivityType.MOTORCYCLE       -> Color(0xFFAB47BC)
    ActivityType.TRAIN            -> Color(0xFF5C8EE8)
    ActivityType.DRIVING          -> Color(0xFFEF5350)
    ActivityType.ELECTRIC_VEHICLE -> Color(0xFF26C6DA)
    ActivityType.FLYING           -> Color(0xFF90CAF9)
}

@Composable
fun SpeedometerCircle(
    @Suppress("UNUSED_PARAMETER") speed: Float,
    speedDisplay: String,
    activity: ActivityType,
    isTracking: Boolean,
    manualMode: Boolean
) {
    val colorScheme = MaterialTheme.colorScheme
    val ringColor = colorScheme.outline.copy(alpha = if (isTracking) 0.9f else 0.52f)
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isTracking) 1.05f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    
    Box(
        modifier = Modifier.size(256.dp),
        contentAlignment = Alignment.Center
    ) {
        // Pulsating ring (when tracking)
        if (isTracking) {
            Box(
                modifier = Modifier
                    .size(256.dp)
                    .scale(scale)
                    .border(
                        width = 8.dp,
                        color = ringColor.copy(alpha = 0.28f),
                        shape = CircleShape
                    )
            )
        }
        
        // Main circle (semi-transparent to show video background)
        Box(
            modifier = Modifier
                .size(256.dp)
                .border(
                    width = 8.dp,
                    color = ringColor.copy(alpha = 0.92f),
                    shape = CircleShape
                )
                .background(
                    color = colorScheme.surface.copy(alpha = 0.75f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SingleLineValueText(
                    text = speedDisplay.split(" ").getOrElse(0) { "" },
                    style = MaterialTheme.typography.headlineLarge,
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                SingleLineValueText(
                    text = speedDisplay.split(" ").getOrElse(1) { "" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 14.sp,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Activity badge (semi-transparent)
                Surface(
                    color = colorScheme.surfaceVariant.copy(alpha = 0.75f),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(activity.toActivityStringResId()),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.onSurface
                        )
                        if (manualMode) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                color = colorScheme.surfaceVariant.copy(alpha = 0.9f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.manual),
                                    fontSize = 10.sp,
                                    color = colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    highlightColor: androidx.compose.ui.graphics.Color? = null
) {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier.padding(horizontal = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = colorScheme.surface.copy(alpha = 0.75f)
        ),
        border = if (highlightColor != null) {
            androidx.compose.foundation.BorderStroke(1.dp, highlightColor.copy(alpha = 0.5f))
        } else null
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label.uppercase(),
                fontSize = 10.sp,
                color = highlightColor ?: colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(modifier = Modifier.height(4.dp))
            SingleLineMetricValueText(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                color = colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun PulsatingButton(
    isTracking: Boolean,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val infiniteTransition = rememberInfiniteTransition(label = "button_pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (!isTracking) 1.05f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "button_scale"
    )
    
    FloatingActionButton(
        onClick = onClick,
        modifier = Modifier
            .size(80.dp)
            .scale(scale),
        containerColor = if (isTracking) {
            Red500.copy(alpha = 0.88f)
        } else {
            colorScheme.surfaceVariant.copy(alpha = 0.92f)
        },
        shape = CircleShape
    ) {
        if (isTracking) {
            Icon(
                imageVector = Icons.Default.Stop,
                contentDescription = stringResource(R.string.stop),
                modifier = Modifier.size(32.dp),
                tint = colorScheme.surface
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_play),
                contentDescription = stringResource(R.string.start),
                modifier = Modifier.size(32.dp),
                tint = colorScheme.onSurface
            )
        }
    }
}

@Composable
fun StopTrackingDialog(
    onDismiss: () -> Unit,
    onPause: () -> Unit,
    onStopAndSave: () -> Unit,
    onDiscard: () -> Unit,
    isSaving: Boolean = false
) {
    val colorScheme = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_app_logo),
                    contentDescription = null,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(MaterialTheme.shapes.large)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.stop_tracking_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                Text(
                    text = stringResource(R.string.stop_tracking_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 24.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                
                // Stop & Save Button - disabled during save to prevent double-tap
                Button(
                    onClick = onStopAndSave,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSaving,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colorScheme.surfaceVariant.copy(alpha = 0.95f),
                        contentColor = colorScheme.onSurface,
                        disabledContainerColor = colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        disabledContentColor = colorScheme.onSurfaceVariant
                    )
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = colorScheme.onSurface,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = if (isSaving) stringResource(R.string.saving) else stringResource(R.string.stop_save_session),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Discard Button - disabled during save
                OutlinedButton(
                    onClick = onDiscard,
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = colorScheme.onSurfaceVariant
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, colorScheme.outline.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = stringResource(R.string.discard_data),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Pause Button - disabled during save
                Button(
                    onClick = onPause,
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colorScheme.surfaceVariant.copy(alpha = 0.88f),
                        contentColor = colorScheme.onSurface
                    )
                ) {
                    Text(
                        text = stringResource(R.string.pause_resume),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Cancel Button
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.cancel),
                        color = colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

private fun formatElevation(value: Double, unitSystem: UnitSystem): String {
    return if (unitSystem.usesMetricDistance()) {
        "${value.toInt()} m"
    } else {
        "${(value * 3.28084).toInt()} ft"
    }
}

@Composable
fun SessionSummaryDialog(
    stats: SessionStats,
    unitSystem: UnitSystem = UnitSystem.METRIC,
    energyUnit: EnergyUnit = EnergyUnit.KCAL,
    saveError: String? = null,
    onClose: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val scrollState = rememberScrollState()
    Dialog(onDismissRequest = onClose) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // App logo
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(colorScheme.surfaceVariant.copy(alpha = 0.55f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_app_logo),
                        contentDescription = stringResource(R.string.session_summary),
                        modifier = Modifier.size(48.dp),
                        contentScale = ContentScale.Fit
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = if (saveError != null) stringResource(R.string.session_summary) else stringResource(R.string.session_saved),
                    style = MaterialTheme.typography.headlineMedium,
                    color = colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
                
                saveError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.error,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                
                Text(
                    text = stringResource(R.string.session_summary_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
                )
                
                // Stats Grid
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Row 1: Duration and Distance
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SummaryStatCard(
                            label = stringResource(R.string.duration),
                            value = formatTime(stats.totalDuration),
                            icon = Icons.Default.Timer,
                            color = colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        SummaryStatCard(
                            label = stringResource(R.string.distance),
                            value = if (unitSystem.usesMetricDistance()) {
                                "${(stats.totalDistance / 1000.0).format(2)} km"
                            } else {
                                "${(stats.totalDistance / 1609.344).format(2)} mi"
                            },
                            icon = Icons.Default.Straighten,
                            color = colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    
                    // Row 2: Top Speed (standalone)
                    SummaryStatCard(
                        label = stringResource(R.string.top_speed),
                        value = formatSpeedMax(stats.topSpeedMps, unitSystem),
                        icon = Icons.Default.ShowChart,
                        color = colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    // Row 3: CO2 Saved (prominent, full width - like steps)
                    SummaryStatCard(
                        label = stringResource(R.string.co2_saved),
                        value = "${stats.co2Conserved.format(2)} kg",
                        iconPainter = painterResource(R.drawable.ic_co2_carbon_neutral),
                        color = colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        emphasizeValue = true
                    )
                    
                    // Row 4: Calories
                    SummaryStatCard(
                        label = if (energyUnit == EnergyUnit.KCAL) stringResource(R.string.calories) else stringResource(R.string.energy),
                        value = formatEnergy(stats.caloriesBurned, energyUnit),
                        icon = Icons.Default.FitnessCenter,
                        color = colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    // Row 5: Steps and CO2 Emitted (minimized, side by side)
                    if (stats.totalSteps > 0 || stats.co2Emissions > 0) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (stats.totalSteps > 0) {
                                SummaryStatCardCompact(
                                    label = stringResource(R.string.total_steps),
                                    value = String.format(java.util.Locale.getDefault(), "%,d", stats.totalSteps),
                                    icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                                    color = colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (stats.co2Emissions > 0) {
                                SummaryStatCardCompact(
                                    label = stringResource(R.string.co2_emitted),
                                    value = "${stats.co2Emissions.format(2)} kg",
                                    icon = Icons.Default.LocalFireDepartment,
                                    color = colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                    
                    // Elevation section (if we have altitude data)
                    val hasElevation = stats.startingAltitude != null || stats.stoppingAltitude != null ||
                        stats.elevationGain > 0 || stats.elevationLoss > 0
                    if (hasElevation) {
                        if (stats.startingAltitude != null || stats.stoppingAltitude != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                stats.startingAltitude?.let { alt ->
                                    SummaryStatCard(
                                        label = stringResource(R.string.start_alt),
                                        value = formatElevation(alt, unitSystem),
                                        icon = Icons.Default.Place,
                                        color = colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                stats.stoppingAltitude?.let { alt ->
                                    SummaryStatCard(
                                        label = stringResource(R.string.stop_alt),
                                        value = formatElevation(alt, unitSystem),
                                        icon = Icons.Default.Place,
                                        color = colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                        if (stats.elevationGain > 0 || stats.elevationLoss > 0) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                SummaryStatCard(
                                    label = stringResource(R.string.ascent),
                                    value = formatElevation(stats.elevationGain, unitSystem),
                                    icon = Icons.AutoMirrored.Filled.TrendingUp,
                                    color = colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                SummaryStatCard(
                                    label = stringResource(R.string.descent),
                                    value = formatElevation(stats.elevationLoss, unitSystem),
                                    icon = Icons.AutoMirrored.Filled.TrendingDown,
                                    color = colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                    
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Close Button
                Button(
                    onClick = onClose,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colorScheme.surfaceVariant.copy(alpha = 0.95f),
                        contentColor = colorScheme.onSurface
                    )
                ) {
                    Text(
                        text = stringResource(R.string.continue_tracking),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun SummaryStatCard(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    emphasizeValue: Boolean = false
) {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (emphasizeValue) 16.dp else 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(if (emphasizeValue) 44.dp else 36.dp)
                    .background(color.copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = color,
                    modifier = Modifier.size(if (emphasizeValue) 22.dp else 18.dp)
                )
            }
            Spacer(modifier = Modifier.height(if (emphasizeValue) 12.dp else 8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant,
                fontSize = if (emphasizeValue) 12.sp else 11.sp,
                maxLines = 2,
                softWrap = true,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            SingleLineValueText(
                text = value,
                style = if (emphasizeValue) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                color = colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun SummaryStatCard(
    label: String,
    value: String,
    iconPainter: Painter,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    emphasizeValue: Boolean = false
) {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (emphasizeValue) 16.dp else 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(if (emphasizeValue) 44.dp else 36.dp)
                    .background(color.copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = iconPainter,
                    contentDescription = label,
                    tint = color,
                    modifier = Modifier.size(if (emphasizeValue) 22.dp else 18.dp)
                )
            }
            Spacer(modifier = Modifier.height(if (emphasizeValue) 12.dp else 8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant,
                fontSize = if (emphasizeValue) 12.sp else 11.sp,
                maxLines = 2,
                softWrap = true,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            SingleLineValueText(
                text = value,
                style = if (emphasizeValue) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                color = colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun SummaryStatCardCompact(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = colorScheme.surfaceVariant.copy(alpha = 0.7f)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = color,
                modifier = Modifier.size(16.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                    fontSize = 10.sp
                )
                SingleLineValueText(
                    text = value,
                    style = MaterialTheme.typography.labelMedium,
                    color = colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start
                )
            }
        }
    }
}

