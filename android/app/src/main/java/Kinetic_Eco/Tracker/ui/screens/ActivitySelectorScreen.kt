package Kinetic_Eco.Tracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.TwoWheeler
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import Kinetic_Eco.Tracker.R
import Kinetic_Eco.Tracker.data.ActivityType
import Kinetic_Eco.Tracker.ui.theme.*

data class ActivityOption(
    val type: ActivityType?,
    val label: String,
    val icon: ImageVector,
    val colorStart: androidx.compose.ui.graphics.Color,
    val colorEnd: androidx.compose.ui.graphics.Color,
    val description: String
)

/**
 * One-time setup dialog shown the first time the user selects Auto Detect.
 * Enables auto-start and lets the user choose their preferred idle-stop duration.
 */
@Composable
fun AutoDetectSetupDialog(
    currentIdleMinutes: Int,
    onConfirm: (idleMinutes: Int) -> Unit,
    onDismiss: () -> Unit
) {
    val minuteOptions = listOf(3, 5, 10, 15)
    var selectedMinutes by remember { mutableIntStateOf(currentIdleMinutes.coerceIn(minuteOptions.first(), minuteOptions.last())) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = Blue500
            )
        },
        title = { Text("Auto Detect Enabled") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "Tracking will start automatically when you begin walking and stop after you've been still for your chosen time.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Auto-stop after idle for:",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    minuteOptions.forEach { minutes ->
                        FilterChip(
                            selected = minutes == selectedMinutes,
                            onClick = { selectedMinutes = minutes },
                            label = { Text("${minutes}m") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selectedMinutes) }) {
                Text("Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Skip")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivitySelectorScreen(
    selectedMode: ActivityType?,
    onActivitySelected: (ActivityType?) -> Unit,
    onDismiss: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val activities = listOf(
        ActivityOption(
            type = null,
            label = "Auto Detect",
            icon = Icons.Default.AutoAwesome,
            colorStart = Blue500,
            colorEnd = Cyan500,
            description = "Automatically detect activity based on speed"
        ),
        ActivityOption(
            type = ActivityType.WALKING,
            label = "Walking",
            icon = Icons.AutoMirrored.Filled.DirectionsWalk,
            colorStart = Green500,
            colorEnd = Emerald500,
            description = "Track walking activity (saves CO2)"
        ),
        ActivityOption(
            type = ActivityType.RUNNING,
            label = "Running",
            icon = Icons.AutoMirrored.Filled.DirectionsRun,
            colorStart = Emerald500,
            colorEnd = Green500,
            description = "Track running activity (saves CO2)"
        ),
        ActivityOption(
            type = ActivityType.CYCLING,
            label = "Cycling",
            icon = Icons.Default.PedalBike,
            colorStart = Cyan500,
            colorEnd = Blue500,
            description = stringResource(R.string.cycling_desc)
        ),
        ActivityOption(
            type = ActivityType.MOTORCYCLE,
            label = stringResource(R.string.motorcycle),
            icon = Icons.Outlined.TwoWheeler,
            colorStart = Orange500,
            colorEnd = Amber500,
            description = stringResource(R.string.motorcycle_desc)
        ),
        ActivityOption(
            type = ActivityType.TRAIN,
            label = "Train",
            icon = Icons.Default.DirectionsRailway,
            colorStart = Teal500,
            colorEnd = Cyan500,
            description = "Track rail travel (low CO2 vs driving)"
        ),
        ActivityOption(
            type = ActivityType.DRIVING,
            label = "Driving",
            icon = Icons.Default.DirectionsCar,
            colorStart = Amber500,
            colorEnd = Red500,
            description = "Track gas/diesel vehicle (emits CO2)"
        ),
        ActivityOption(
            type = ActivityType.ELECTRIC_VEHICLE,
            label = "Electric Vehicle",
            icon = Icons.Default.ElectricCar,
            colorStart = Violet500,
            colorEnd = Indigo500,
            description = "Track EV (70% less CO2 than gas)"
        ),
        ActivityOption(
            type = ActivityType.FLYING,
            label = "Flying",
            icon = Icons.Default.Flight,
            colorStart = Blue500,
            colorEnd = Indigo500,
            description = "Track air travel (high CO2)"
        )
    )
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colorScheme.surface,
        dragHandle = {
            Spacer(modifier = Modifier.height(8.dp))
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            Text(
                text = "Select Activity Mode",
                style = MaterialTheme.typography.headlineMedium,
                color = colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 24.dp)
            )
            
            activities.forEach { activity ->
                ActivityCard(
                    activity = activity,
                    isSelected = activity.type == selectedMode,
                    colorScheme = colorScheme,
                    onClick = {
                        onActivitySelected(activity.type)
                        onDismiss()
                    },
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun ActivityCard(
    activity: ActivityOption,
    isSelected: Boolean,
    colorScheme: androidx.compose.material3.ColorScheme = MaterialTheme.colorScheme,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) colorScheme.surfaceVariant else colorScheme.surface
        ),
        border = if (isSelected) {
            androidx.compose.foundation.BorderStroke(2.dp, colorScheme.primary)
        } else {
            null
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon with gradient background
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(activity.colorStart, activity.colorEnd)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = activity.icon,
                    contentDescription = activity.label,
                    tint = colorScheme.onSurface,
                    modifier = Modifier.size(28.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = activity.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = colorScheme.onSurface
                    )
                    if (isSelected) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = colorScheme.primary.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "ACTIVE",
                                fontSize = 10.sp,
                                color = colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Text(
                    text = activity.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

