package Kinetic_Eco.Tracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.DirectionsWalk
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import Kinetic_Eco.Tracker.R
import Kinetic_Eco.Tracker.data.UnitSystem
import Kinetic_Eco.Tracker.ui.theme.*
import Kinetic_Eco.Tracker.services.UserPhysicalProfile
import Kinetic_Eco.Tracker.services.Gender

private val LOCALE_OPTIONS = listOf("auto", "en", "fr", "de", "es", "zh")
private val THEME_OPTIONS = listOf("system", "light", "dark")

@Composable
fun SettingsScreen(
    unitSystem: UnitSystem,
    onUnitSystemChange: (UnitSystem) -> Unit,
    currentLocale: String = "auto",
    onLocaleChange: (String) -> Unit = {},
    currentThemeMode: String = "system",
    onThemeChange: (String) -> Unit = {},
    distanceAlertsEnabled: Boolean = false,
    onDistanceAlertsChange: (Boolean) -> Unit = {},
    notificationSoundsEnabled: Boolean = true,
    onNotificationSoundsChange: (Boolean) -> Unit = {},
    autoStartOnWalkEnabled: Boolean = false,
    onAutoStartOnWalkChange: (Boolean) -> Unit = {},
    idleStopMinutes: Int = 10,
    onIdleStopMinutesChange: (Int) -> Unit = {},
    onLogout: () -> Unit,
    appVersion: String = "V1.0.0",
    physicalProfile: UserPhysicalProfile? = null,
    onPhysicalProfileSave: (UserPhysicalProfile) -> Unit = {},
    userId: String = "",
    onSyncSessions: suspend () -> Result<Int> = { Result.success(0) },
    leaderboardOptIn: Boolean = false,
    onLeaderboardOptInChange: (Boolean) -> Unit = {},
    leaderboardLoading: Boolean = false,
    leaderboardError: String? = null,
    onLeaderboardErrorDismiss: () -> Unit = {}
) {
    val colorScheme = MaterialTheme.colorScheme
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.settings),
                style = MaterialTheme.typography.headlineLarge,
                color = colorScheme.onBackground,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        // Theme
        item {
            ThemeDropdownCard(
                currentThemeMode = currentThemeMode,
                onThemeChange = onThemeChange
            )
        }

        // Language
        item {
            LanguageDropdownCard(
                currentLocale = currentLocale,
                onLocaleChange = onLocaleChange
            )
        }
        
        // Unit Preference (km,kcal | Miles,Wh | Miles,kcal)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.unit_preference),
                        style = MaterialTheme.typography.titleMedium,
                        color = colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Text(
                        text = stringResource(R.string.unit_preference_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    
                    // Row 1: km,kcal | km,Wh
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        UnitSystemOption(
                            label = stringResource(R.string.unit_km_kcal),
                            selected = unitSystem == UnitSystem.METRIC,
                            onClick = { onUnitSystemChange(UnitSystem.METRIC) },
                            modifier = Modifier.weight(1f)
                        )
                        UnitSystemOption(
                            label = stringResource(R.string.unit_km_wh),
                            selected = unitSystem == UnitSystem.METRIC_WH,
                            onClick = { onUnitSystemChange(UnitSystem.METRIC_WH) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    // Row 2: Miles,Wh | Miles,kcal
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        UnitSystemOption(
                            label = stringResource(R.string.unit_miles_wh),
                            selected = unitSystem == UnitSystem.IMPERIAL,
                            onClick = { onUnitSystemChange(UnitSystem.IMPERIAL) },
                            modifier = Modifier.weight(1f)
                        )
                        UnitSystemOption(
                            label = stringResource(R.string.unit_miles_kcal),
                            selected = unitSystem == UnitSystem.IMPERIAL_KCAL,
                            onClick = { onUnitSystemChange(UnitSystem.IMPERIAL_KCAL) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
        
        // Distance Milestone Notifications (km or miles based on unit)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Notifications,
                            contentDescription = null,
                            tint = Green400,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.distance_alerts),
                                style = MaterialTheme.typography.titleMedium,
                                color = colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.distance_alerts_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = distanceAlertsEnabled,
                        onCheckedChange = onDistanceAlertsChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colorScheme.surface,
                            checkedTrackColor = Green500,
                            uncheckedThumbColor = colorScheme.outline,
                            uncheckedTrackColor = colorScheme.surfaceVariant
                        )
                    )
                }
            }
        }

        // Notification sounds
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Notifications,
                            contentDescription = null,
                            tint = Amber500,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.notification_sounds),
                                style = MaterialTheme.typography.titleMedium,
                                color = colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.notification_sounds_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = notificationSoundsEnabled,
                        onCheckedChange = onNotificationSoundsChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colorScheme.surface,
                            checkedTrackColor = Amber500,
                            uncheckedThumbColor = colorScheme.outline,
                            uncheckedTrackColor = colorScheme.surfaceVariant
                        )
                    )
                }
            }
        }
        
        // Auto-start on walk
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.DirectionsWalk,
                            contentDescription = null,
                            tint = Indigo500,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.auto_start_walk),
                                style = MaterialTheme.typography.titleMedium,
                                color = colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.auto_start_walk_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = autoStartOnWalkEnabled,
                        onCheckedChange = onAutoStartOnWalkChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colorScheme.surface,
                            checkedTrackColor = Indigo500,
                            uncheckedThumbColor = colorScheme.outline,
                            uncheckedTrackColor = colorScheme.surfaceVariant
                        )
                    )
                }
            }
        }

        // Auto-stop on idle
        item {
            IdleStopDropdownCard(
                idleStopMinutes = idleStopMinutes,
                onIdleStopMinutesChange = onIdleStopMinutesChange
            )
        }
        
        // Physical Profile
        item {
            PhysicalProfileSection(
                initialProfile = physicalProfile,
                onSave = onPhysicalProfileSave
            )
        }
        
        // Cloud Sync
        item {
            CloudSyncSection(
                userId = userId,
                onSyncSessions = onSyncSessions
            )
        }

        // Leaderboard opt-in
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = colorScheme.surface)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.leaderboard),
                                style = MaterialTheme.typography.titleMedium,
                                color = colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.leaderboard_opt_in_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        Switch(
                            checked = leaderboardOptIn,
                            onCheckedChange = onLeaderboardOptInChange,
                            enabled = !leaderboardLoading
                        )
                    }
                    if (leaderboardError != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = leaderboardError!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = onLeaderboardErrorDismiss) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.cancel),
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
        
        // App Version
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.app_version),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colorScheme.onSurface
                    )
                    Text(
                        text = appVersion,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        
        // Logout
        item {
            SettingsItem(
                title = stringResource(R.string.logout),
                icon = Icons.AutoMirrored.Filled.ExitToApp,
                onClick = onLogout,
                isDestructive = true
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeDropdownCard(
    currentThemeMode: String,
    onThemeChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val displayName = when (currentThemeMode) {
        "system" -> stringResource(R.string.theme_system)
        "light" -> stringResource(R.string.theme_light)
        "dark" -> stringResource(R.string.theme_dark)
        else -> stringResource(R.string.theme_system)
    }
    val colorScheme = MaterialTheme.colorScheme

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.theme),
                style = MaterialTheme.typography.titleMedium,
                color = colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = stringResource(R.string.theme_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colorScheme.onSurface,
                        unfocusedTextColor = colorScheme.onSurface,
                        focusedBorderColor = Green500,
                        unfocusedBorderColor = colorScheme.outline
                    )
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    THEME_OPTIONS.forEach { option ->
                        val optDisplayName = when (option) {
                            "system" -> stringResource(R.string.theme_system)
                            "light" -> stringResource(R.string.theme_light)
                            "dark" -> stringResource(R.string.theme_dark)
                            else -> option
                        }
                        DropdownMenuItem(
                            text = { Text(optDisplayName, color = colorScheme.onSurface) },
                            onClick = {
                                onThemeChange(option)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageDropdownCard(
    currentLocale: String,
    onLocaleChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val colorScheme = MaterialTheme.colorScheme
    val displayName = when (currentLocale) {
        "auto" -> stringResource(R.string.language_auto)
        "en" -> stringResource(R.string.language_en)
        "fr" -> stringResource(R.string.language_fr)
        "de" -> stringResource(R.string.language_de)
        "es" -> stringResource(R.string.language_es)
        "zh" -> stringResource(R.string.language_zh)
        else -> stringResource(R.string.language_auto)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.language),
                style = MaterialTheme.typography.titleMedium,
                color = colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = stringResource(R.string.language_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colorScheme.onSurface,
                        unfocusedTextColor = colorScheme.onSurface,
                        focusedBorderColor = Green500,
                        unfocusedBorderColor = colorScheme.outline
                    )
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    LOCALE_OPTIONS.forEach { option ->
                        val optDisplayName = when (option) {
                            "auto" -> stringResource(R.string.language_auto)
                            "en" -> stringResource(R.string.language_en)
                            "fr" -> stringResource(R.string.language_fr)
                            "de" -> stringResource(R.string.language_de)
                            "es" -> stringResource(R.string.language_es)
                            "zh" -> stringResource(R.string.language_zh)
                            else -> option
                        }
                        DropdownMenuItem(
                            text = { Text(optDisplayName, color = colorScheme.onSurface) },
                            onClick = {
                                onLocaleChange(option)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

private val IDLE_STOP_OPTIONS = listOf(3, 5, 10)

@Composable
fun IdleStopDropdownCard(
    idleStopMinutes: Int,
    onIdleStopMinutesChange: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val colorScheme = MaterialTheme.colorScheme
    val displayName = stringResource(R.string.idle_stop_minutes, idleStopMinutes)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Timer,
                    contentDescription = null,
                    tint = Amber500,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.idle_stop),
                    style = MaterialTheme.typography.titleMedium,
                    color = colorScheme.onSurface
                )
            }
            Text(
                text = stringResource(R.string.idle_stop_desc),
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = true },
                    trailingIcon = {
                        IconButton(onClick = { expanded = !expanded }) {
                            Icon(
                                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colorScheme.onSurface,
                        unfocusedTextColor = colorScheme.onSurface,
                        focusedBorderColor = Amber500,
                        unfocusedBorderColor = colorScheme.outline
                    )
                )
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    IDLE_STOP_OPTIONS.forEach { minutes ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.idle_stop_minutes, minutes),
                                    color = colorScheme.onSurface
                                )
                            },
                            onClick = {
                                onIdleStopMinutesChange(minutes)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun UnitSystemOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = modifier,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Indigo500,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
        )
    )
}

@Composable
fun SettingsItem(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    isDestructive: Boolean = false
) {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (isDestructive) Red500 else colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isDestructive) Red500 else colorScheme.onSurface
            )
        }
    }
}

@Composable
fun PhysicalProfileSection(
    initialProfile: UserPhysicalProfile?,
    onSave: (UserPhysicalProfile) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    var weight by remember { mutableStateOf("%.1f".format(initialProfile?.weight ?: 70.0)) }
    var height by remember { mutableStateOf("%.1f".format(initialProfile?.height ?: 170.0)) }
    var age by remember { mutableStateOf(initialProfile?.age?.toString() ?: "30") }
    var gender by remember { mutableStateOf(initialProfile?.gender ?: Gender.MALE) }
    var expanded by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val savedSuccessMsg = stringResource(R.string.saved_success)
    
    // Update state when initialProfile changes (format to 1 decimal)
    LaunchedEffect(initialProfile) {
        initialProfile?.let { profile ->
            weight = "%.1f".format(profile.weight)
            height = "%.1f".format(profile.height)
            age = profile.age.toString()
            gender = profile.gender
        }
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header with expand/collapse - entire row clickable
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FitnessCenter,
                        contentDescription = "Physical Profile",
                        tint = colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text(
                            text = stringResource(R.string.physical_profile),
                            style = MaterialTheme.typography.titleMedium,
                            color = colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.physical_profile_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) stringResource(R.string.collapse) else stringResource(R.string.expand),
                    tint = colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
            
            // Expandable content
            if (expanded) {
                Spacer(modifier = Modifier.height(16.dp))
                
                // Weight (max 1 decimal place)
                OutlinedTextField(
                    value = weight,
                    onValueChange = { newVal ->
                        val filtered = newVal.filter { it.isDigit() || it == '.' }
                        val parts = filtered.split('.')
                        weight = when {
                            parts.size == 1 -> parts[0].take(5)
                            parts.size == 2 -> parts[0].take(5) + "." + parts[1].take(1)
                            else -> weight
                        }
                    },
                    label = { Text(stringResource(R.string.weight_kg)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Green500,
                        focusedLabelColor = Green500,
                        unfocusedBorderColor = colorScheme.outline,
                        unfocusedLabelColor = colorScheme.onSurfaceVariant
                    )
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Height (max 1 decimal place)
                OutlinedTextField(
                    value = height,
                    onValueChange = { newVal ->
                        val filtered = newVal.filter { it.isDigit() || it == '.' }
                        val parts = filtered.split('.')
                        height = when {
                            parts.size == 1 -> parts[0].take(5)
                            parts.size == 2 -> parts[0].take(5) + "." + parts[1].take(1)
                            else -> height
                        }
                    },
                    label = { Text(stringResource(R.string.height_cm)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Green500,
                        focusedLabelColor = Green500,
                        unfocusedBorderColor = colorScheme.outline,
                        unfocusedLabelColor = colorScheme.onSurfaceVariant
                    )
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Age
                OutlinedTextField(
                    value = age,
                    onValueChange = { age = it },
                    label = { Text(stringResource(R.string.age_years)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Green500,
                        focusedLabelColor = Green500,
                        unfocusedBorderColor = colorScheme.outline,
                        unfocusedLabelColor = colorScheme.onSurfaceVariant
                    )
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Gender
                Text(
                    text = stringResource(R.string.gender),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = gender == Gender.MALE,
                        onClick = { gender = Gender.MALE },
                        label = { Text(stringResource(R.string.male)) },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Green500,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                    FilterChip(
                        selected = gender == Gender.FEMALE,
                        onClick = { gender = Gender.FEMALE },
                        label = { Text(stringResource(R.string.female)) },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Green500,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                    FilterChip(
                        selected = gender == Gender.OTHER,
                        onClick = { gender = Gender.OTHER },
                        label = { Text(stringResource(R.string.other)) },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Green500,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Save button
                Button(
                    onClick = {
                        val weightValue = (weight.toDoubleOrNull() ?: 70.0).let { kotlin.math.round(it * 10) / 10.0 }
                        val heightValue = (height.toDoubleOrNull() ?: 170.0).let { kotlin.math.round(it * 10) / 10.0 }
                        val ageValue = age.toIntOrNull() ?: 30
                        
                        val profile = UserPhysicalProfile(
                            weight = weightValue,
                            height = heightValue,
                            age = ageValue,
                            gender = gender
                        )
                        
                        onSave(profile)
                        weight = "%.1f".format(weightValue)
                        height = "%.1f".format(heightValue)
                        saveMessage = savedSuccessMsg
                        
                        // Clear message after 3 seconds
                        coroutineScope.launch {
                            delay(3000)
                            saveMessage = null
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Green600
                    )
                ) {
                    Text(stringResource(R.string.save_physical_profile))
                }
                
                // Save message
                saveMessage?.let { msg ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = Green400,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                
                // Info
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Blue500.copy(alpha = 0.1f)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Blue500.copy(alpha = 0.3f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(R.string.why_physical_profile),
                            style = MaterialTheme.typography.labelLarge,
                            color = Blue400
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.physical_profile_info),
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CloudSyncSection(
    userId: String,
    onSyncSessions: suspend () -> Result<Int>
) {
    val colorScheme = MaterialTheme.colorScheme
    var syncing by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Cloud,
                    contentDescription = "Cloud Sync",
                    tint = Blue400,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.cloud_sync),
                    style = MaterialTheme.typography.titleMedium,
                    color = colorScheme.onSurface
                )
            }
            
            Text(
                text = stringResource(R.string.cloud_sync_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            Button(
                onClick = {
                    coroutineScope.launch {
                        syncing = true
                        syncMessage = null
                        
                        val result = onSyncSessions()
                        result.onSuccess { count ->
                            syncMessage = context.getString(R.string.sync_success, count)
                        }.onFailure { error ->
                            syncMessage = context.getString(R.string.sync_failed, error.message ?: "")
                        }
                        
                        syncing = false
                        
                        // Clear message after 5 seconds
                        delay(5000)
                        syncMessage = null
                    }
                },
                enabled = !syncing && userId.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Blue600,
                    disabledContainerColor = colorScheme.surfaceVariant
                )
            ) {
                if (syncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = colorScheme.onSurface,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.syncing))
                } else {
                    Icon(
                        imageVector = Icons.Default.CloudUpload,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.sync_sessions))
                }
            }
            
            // Sync message
            syncMessage?.let { msg ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (msg.startsWith("✓")) Green400 else Red400,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            
            // Info
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Amber400.copy(alpha = 0.1f)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Amber400.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = stringResource(R.string.note),
                        style = MaterialTheme.typography.labelLarge,
                        color = Amber400
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.sync_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

