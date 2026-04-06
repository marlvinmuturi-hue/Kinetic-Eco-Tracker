package Kinetic_Eco.Tracker.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import Kinetic_Eco.Tracker.R
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import Kinetic_Eco.Tracker.data.*
import Kinetic_Eco.Tracker.ui.components.SingleLineMetricValueText
import Kinetic_Eco.Tracker.ui.components.SingleLineValueText
import Kinetic_Eco.Tracker.ui.components.VideoBackground
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
    val currentSpeed by viewModel.currentSpeed.collectAsStateWithLifecycle()
    
    // Animate speed for near-instant response (user prefers responsiveness over smoothness)
    val animatedSpeed by animateFloatAsState(
        targetValue = currentSpeed,
        animationSpec = tween(durationMillis = 100, easing = FastOutSlowInEasing),
        label = "speed_animation"
    )
    
    val currentActivity by viewModel.currentActivity.collectAsStateWithLifecycle()
    val manualActivityMode by viewModel.manualActivityMode.collectAsStateWithLifecycle()
    val sessionDuration by viewModel.sessionDuration.collectAsStateWithLifecycle()
    val sessionDistance by viewModel.sessionDistance.collectAsStateWithLifecycle()
    val sessionSteps by viewModel.sessionSteps.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val currentPosition by viewModel.currentPosition.collectAsStateWithLifecycle()
    val warmUpPosition by viewModel.warmUpPosition.collectAsStateWithLifecycle()
    val sessionStats by viewModel.sessionStats.collectAsStateWithLifecycle()
    
    var showStopDialog by remember { mutableStateOf(false) }
    var showSessionSummary by remember { mutableStateOf(false) }
    var savedSessionStats by remember { mutableStateOf<SessionStats?>(null) }
    var isSavingSession by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val activityColor = ActivityColors.getColor(currentActivity)
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
    
    val maxSpeedDisplay = if (unitSystem.usesMetricDistance()) {
        "${(sessionStats.topSpeedMps * 3.6).format(1)} km/h"
    } else {
        "${(sessionStats.topSpeedMps * 2.23694).format(1)} mph"
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
    
    // Show altitude in stats for Flying mode (manual or auto-detected)
    val isFlying = currentActivity == ActivityType.FLYING
    val showAltitudeInStats = isFlying
    
    // Show steps for Walking and Running activities (show even if 0 to indicate tracking)
    val isWalkingOrRunning = currentActivity == ActivityType.WALKING || currentActivity == ActivityType.RUNNING
    val showStepsInStats = isWalkingOrRunning  // Show step counter when walking or running, even if 0
    val colorScheme = MaterialTheme.colorScheme
    
    Box(modifier = Modifier.fillMaxSize()) {
        VideoBackground(modifier = Modifier.matchParentSize())
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(Color.Black.copy(alpha = 0.5f))
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
        // Error message
        errorMessage?.let { error ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Red500.copy(alpha = 0.2f)
                )
            ) {
                Text(
                    text = error,
                    color = Red500,
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
            activityColor = activityColor,
            isTracking = isTracking,
            manualMode = manualActivityMode != null
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Stats Row - Dynamically adjust layout based on activity
        when {
            showAltitudeInStats -> {
                // Three cards layout for Flying mode
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatCard(stringResource(R.string.duration), formatTime(sessionDuration), modifier = Modifier.weight(1f))
                    StatCard(stringResource(R.string.distance), distanceDisplay, modifier = Modifier.weight(1f))
                    StatCard(
                        label = stringResource(R.string.altitude),
                        value = altitudeDisplay,
                        modifier = Modifier.weight(1f),
                        highlightColor = colorScheme.tertiary
                    )
                }
            }
            showStepsInStats -> {
                // Three cards layout for Walking/Running mode with steps
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatCard(stringResource(R.string.duration), formatTime(sessionDuration), modifier = Modifier.weight(1f))
                    StatCard(stringResource(R.string.distance), distanceDisplay, modifier = Modifier.weight(1f))
                    StatCard(
                        label = stringResource(R.string.steps),
                        value = sessionSteps.toString(),
                        modifier = Modifier.weight(1f),
                        highlightColor = colorScheme.secondary
                    )
                }
            }
            else -> {
                // Two cards layout for other modes
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatCard(stringResource(R.string.duration), formatTime(sessionDuration), modifier = Modifier.weight(1f))
                    StatCard(stringResource(R.string.distance), distanceDisplay, modifier = Modifier.weight(1f))
                }
            }
        }
        
        // Manual mode indicator
        manualActivityMode?.let { mode ->
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = colorScheme.primary.copy(alpha = 0.2f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.manual_mode, stringResource(mode.toActivityStringResId())),
                    color = colorScheme.primary,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        
        Spacer(modifier = Modifier.height(48.dp))
        
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
        
        // Row: Top speed (left) | Altitude (centre) | Activity selector (right)
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Top speed - circular badge (left)
            // Size and font scale with accessibility: larger circle + capped font when user uses large text
            val config = LocalConfiguration.current
            val fontScale = config.fontScale
            val badgeSize = (64 * kotlin.math.min(1f + (fontScale - 1f) * 0.5f, 1.25f)).dp
            val valueFontSize = (11f / kotlin.math.max(1f, fontScale)).sp
            val labelFontSize = (9f / kotlin.math.max(1f, fontScale)).sp
            Surface(
                modifier = Modifier.size(badgeSize),
                shape = CircleShape,
                color = colorScheme.primary,
                shadowElevation = 6.dp,
                tonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = maxSpeedDisplay,
                        fontSize = valueFontSize,
                        color = colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.max_speed_label),
                        fontSize = labelFontSize,
                        color = colorScheme.onPrimary.copy(alpha = 0.9f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            
            // Altitude - card (centre)
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = colorScheme.surface.copy(alpha = 0.6f)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Terrain,
                        contentDescription = stringResource(R.string.altitude),
                        tint = colorScheme.tertiary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    SingleLineMetricValueText(
                        text = stringResource(R.string.altitude_prefix) + altitudeDisplay,
                        style = MaterialTheme.typography.labelMedium,
                        color = colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        baseFontSize = 14.sp,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.weight(1f)
                    )
                }
                }
            }
            
            // Activity selector - circular FAB (right)
            FloatingActionButton(
                onClick = onActivitySelectorClick,
                modifier = Modifier.size(64.dp),
                containerColor = colorScheme.primary,
                shape = CircleShape,
                elevation = FloatingActionButtonDefaults.elevation(
                    defaultElevation = 6.dp,
                    pressedElevation = 12.dp
                )
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.DirectionsRun,
                    contentDescription = stringResource(R.string.select_activity),
                    tint = colorScheme.onPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        
        // Show steps for Walking/Running activities (show even if 0)
        if (isWalkingOrRunning) {
            Spacer(modifier = Modifier.height(8.dp))
            
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = colorScheme.secondary.copy(alpha = 0.15f)
                ),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, colorScheme.secondary.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.DirectionsWalk,
                        contentDescription = stringResource(R.string.steps),
                        tint = colorScheme.secondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.steps_prefix),
                        color = colorScheme.secondary,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 12.sp
                    )
                    Text(
                        text = sessionSteps.toString(),
                        color = colorScheme.onSurface,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }
        }
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
            }
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

@Composable
fun SpeedometerCircle(
    @Suppress("UNUSED_PARAMETER") speed: Float,
    speedDisplay: String,
    activity: ActivityType,
    activityColor: androidx.compose.ui.graphics.Color,
    isTracking: Boolean,
    manualMode: Boolean
) {
    val colorScheme = MaterialTheme.colorScheme
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
                        color = activityColor.copy(alpha = 0.3f),
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
                    color = (if (isTracking) activityColor else colorScheme.surfaceVariant).copy(alpha = 0.85f),
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
                                color = colorScheme.primary.copy(alpha = 0.3f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.manual),
                                    fontSize = 10.sp,
                                    color = colorScheme.primary,
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
        modifier = modifier.padding(horizontal = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = colorScheme.surface.copy(alpha = 0.75f)
        ),
        border = if (highlightColor != null) {
            androidx.compose.foundation.BorderStroke(1.dp, highlightColor.copy(alpha = 0.5f))
        } else null
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
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
        containerColor = (if (isTracking) Red500 else colorScheme.primary).copy(alpha = 0.85f),
        shape = CircleShape
    ) {
        if (isTracking) {
            Icon(
                imageVector = Icons.Default.Stop,
                contentDescription = stringResource(R.string.stop),
                modifier = Modifier.size(32.dp),
                tint = colorScheme.onPrimary
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_play),
                contentDescription = stringResource(R.string.start),
                modifier = Modifier.size(32.dp),
                tint = colorScheme.onPrimary
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
                        containerColor = colorScheme.secondary,
                        disabledContainerColor = colorScheme.secondary.copy(alpha = 0.6f)
                    )
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = colorScheme.onPrimary,
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
                        contentColor = Red500
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Red500.copy(alpha = 0.5f))
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
                        containerColor = colorScheme.primary
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
                // Success icon
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(colorScheme.secondary.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_session_summary),
                        contentDescription = stringResource(R.string.session_summary),
                        tint = colorScheme.secondary,
                        modifier = Modifier.size(32.dp)
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
                        color = Red500,
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
                            color = colorScheme.primary,
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
                            color = colorScheme.tertiary,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    
                    // Row 2: Top Speed (standalone)
                    SummaryStatCard(
                        label = stringResource(R.string.top_speed),
                        value = formatSpeedMax(stats.topSpeedMps, unitSystem),
                        icon = Icons.Default.ShowChart,
                        color = colorScheme.tertiary,
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    // Row 3: CO2 Saved (prominent, full width - like steps)
                    SummaryStatCard(
                        label = stringResource(R.string.co2_saved),
                        value = "${stats.co2Conserved.format(2)} kg",
                        iconPainter = painterResource(R.drawable.ic_co2_carbon_neutral),
                        color = colorScheme.secondary,
                        modifier = Modifier.fillMaxWidth(),
                        emphasizeValue = true
                    )
                    
                    // Row 4: Calories
                    SummaryStatCard(
                        label = if (energyUnit == EnergyUnit.KCAL) stringResource(R.string.calories) else stringResource(R.string.energy),
                        value = formatEnergy(stats.caloriesBurned, energyUnit),
                        icon = Icons.Default.FitnessCenter,
                        color = Amber500,
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
                                    value = String.format("%,d", stats.totalSteps),
                                    icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                                    color = colorScheme.secondary,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (stats.co2Emissions > 0) {
                                SummaryStatCardCompact(
                                    label = stringResource(R.string.co2_emitted),
                                    value = "${stats.co2Emissions.format(2)} kg",
                                    icon = Icons.Default.LocalFireDepartment,
                                    color = Red500,
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
                                    color = colorScheme.secondary,
                                    modifier = Modifier.weight(1f)
                                )
                                SummaryStatCard(
                                    label = stringResource(R.string.descent),
                                    value = formatElevation(stats.elevationLoss, unitSystem),
                                    icon = Icons.AutoMirrored.Filled.TrendingDown,
                                    color = colorScheme.tertiary,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                    
                    // Distance breakdown (time per km or mile)
                    if (stats.kmMilestones.isNotEmpty()) {
                        val distUnit = if (unitSystem.usesMetricDistance()) "km" else "mi"
                        Text(
                            text = if (unitSystem.usesMetricDistance()) stringResource(R.string.km_updates) else stringResource(R.string.mile_updates),
                            style = MaterialTheme.typography.labelMedium,
                            color = colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            stats.kmMilestones.forEach { milestone ->
                                val timeStr = formatTime(milestone.secondsForKm)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    SingleLineValueText(
                                        text = "${milestone.km} $distUnit",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                                        textAlign = TextAlign.Start
                                    )
                                    SingleLineValueText(
                                        text = timeStr,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colorScheme.onSurface,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f),
                                        textAlign = TextAlign.End
                                    )
                                }
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
                        containerColor = colorScheme.primary
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

