package Kinetic_Eco.Tracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import Kinetic_Eco.Tracker.R
import Kinetic_Eco.Tracker.data.AircraftCategory
import Kinetic_Eco.Tracker.data.DrivingEngineCcBand
import Kinetic_Eco.Tracker.data.ElectricMotorPowerBand
import Kinetic_Eco.Tracker.data.ElectricVehicleClass
import Kinetic_Eco.Tracker.data.IceFuel
import Kinetic_Eco.Tracker.data.PrimaryFuelType
import Kinetic_Eco.Tracker.data.TrainPropulsion
import Kinetic_Eco.Tracker.data.UnitSystem
import Kinetic_Eco.Tracker.data.VehicleBodyType
import Kinetic_Eco.Tracker.data.VehicleProfile
import Kinetic_Eco.Tracker.ui.components.BirthDatePicker
import Kinetic_Eco.Tracker.ui.theme.Red500
import Kinetic_Eco.Tracker.services.UserPhysicalProfile
import Kinetic_Eco.Tracker.services.UserPreferencesManager
import Kinetic_Eco.Tracker.services.Gender

private val LOCALE_OPTIONS = listOf("auto", "en", "fr", "de", "es", "zh")

@Composable
fun SettingsScreen(
    unitSystem: UnitSystem,
    onUnitSystemChange: (UnitSystem) -> Unit,
    currentLocale: String = "auto",
    onLocaleChange: (String) -> Unit = {},
    currentThemeMode: String = "dark",
    onThemeChange: (String) -> Unit = {},
    notificationSoundsEnabled: Boolean = true,
    onNotificationSoundsChange: (Boolean) -> Unit = {},
    autoStartOnWalkEnabled: Boolean = false,
    onAutoStartOnWalkChange: (Boolean) -> Unit = {},
    idleStopMinutes: Int = 10,
    onIdleStopMinutesChange: (Int) -> Unit = {},
    onLogout: () -> Unit,
    appVersion: String = "V1.0.0",
    leaderboardOptIn: Boolean = false,
    onLeaderboardOptInChange: (Boolean) -> Unit = {},
    leaderboardLoading: Boolean = false,
    leaderboardError: String? = null,
    onLeaderboardErrorDismiss: () -> Unit = {},
    onGoPremium: () -> Unit = {},
    weeklyDigestEnabled: Boolean = true,
    onWeeklyDigestChange: (Boolean) -> Unit = {},
    onProfileClick: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    /** Vehicle profile primary fuel is electric — surface quick link to tracker activity picker. */
    electricRoadUser: Boolean = false,
    onOpenActivitySelector: () -> Unit = {},
) {
    val colorScheme = MaterialTheme.colorScheme

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(colorScheme.background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = colorScheme.onBackground
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.settings),
                    style = MaterialTheme.typography.headlineLarge,
                    color = colorScheme.onBackground,
                    modifier = Modifier.padding(start = if (onBack != null) 0.dp else 0.dp)
                )
            }
        }

        // ── Go Premium ────────────────────────────────────────────────────────
        // Pinned to the very top (just below the screen header) so the upgrade
        // CTA is the first thing the user sees when they open Settings.
        item {
            GoPremiumCard(onClick = onGoPremium)
        }

        if (onProfileClick != null) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onProfileClick),
                    colors = CardDefaults.cardColors(containerColor = colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = colorScheme.onSurface,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Profile",
                                style = MaterialTheme.typography.titleMedium,
                                color = colorScheme.onSurface
                            )
                            Text(
                                text = "Physical & vehicle details, account",
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // ── Appearance (theme + language + units) ─────────────────────────────
        // Grouped per user request — all "how the app looks / what units it
        // speaks" lives behind one expandable card.
        item {
            AppearanceSection(
                currentThemeMode = currentThemeMode,
                onThemeChange = onThemeChange,
                currentLocale = currentLocale,
                onLocaleChange = onLocaleChange,
                unitSystem = unitSystem,
                onUnitSystemChange = onUnitSystemChange
            )
        }

        // ── Notifications (sounds + weekly digest) ──────────
        item {
            NotificationsSection(
                notificationSoundsEnabled = notificationSoundsEnabled,
                onNotificationSoundsChange = onNotificationSoundsChange,
                weeklyDigestEnabled = weeklyDigestEnabled,
                onWeeklyDigestChange = onWeeklyDigestChange
            )
        }

        // ── Leaderboard opt-in ────────────────────────────────────────────────
        // Moved here (was previously after Cloud Sync) per user request: the
        // social/community choice should sit immediately above the auto-start
        // toggle, since both relate to how the app behaves while you're moving.
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
                            enabled = !leaderboardLoading,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = colorScheme.surface,
                                checkedTrackColor = colorScheme.onSurface,
                                uncheckedThumbColor = colorScheme.outline,
                                uncheckedTrackColor = colorScheme.surfaceVariant,
                                disabledCheckedTrackColor = colorScheme.onSurface.copy(alpha = 0.35f),
                                disabledUncheckedTrackColor = colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            )
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

        if (electricRoadUser) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenActivitySelector),
                    colors = CardDefaults.cardColors(containerColor = colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ElectricCar,
                            contentDescription = null,
                            tint = colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.settings_ev_activity_selector_prompt),
                            style = MaterialTheme.typography.titleMedium,
                            color = colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }

        // ── Auto-start on movement ────────────────────────────────────────────
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
                            tint = colorScheme.onSurfaceVariant,
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
                            checkedTrackColor = colorScheme.onSurface,
                            uncheckedThumbColor = colorScheme.outline,
                            uncheckedTrackColor = colorScheme.surfaceVariant
                        )
                    )
                }
            }
        }

        // ── Auto-stop on idle ─────────────────────────────────────────────────
        item {
            IdleStopDropdownCard(
                idleStopMinutes = idleStopMinutes,
                onIdleStopMinutesChange = onIdleStopMinutesChange
            )
        }

        // (Cloud Sync section removed per user request — sessions are still
        // synced automatically by the ViewModel after each save; the manual
        // "Sync now" button was redundant.)

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

}

// LanguageDropdownCard was the standalone Card-wrapped language picker.
// Inlined into AppearanceSection (lower in this file) along with theme and
// units, so this top-level composable is no longer needed.

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
                    tint = colorScheme.onSurfaceVariant,
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
                        focusedBorderColor = colorScheme.onSurface,
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
private fun settingsMonoFilterChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.onSurface,
    selectedLabelColor = MaterialTheme.colorScheme.surface,
    selectedLeadingIconColor = MaterialTheme.colorScheme.surface,
)

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
        colors = settingsMonoFilterChipColors()
    )
}

@Composable
fun GoPremiumCard(onClick: () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant),
        border = androidx.compose.foundation.BorderStroke(1.dp, colorScheme.outline.copy(alpha = 0.38f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.WorkspacePremium,
                contentDescription = null,
                tint = colorScheme.onSurface,
                modifier = Modifier.size(36.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.go_premium),
                    style = MaterialTheme.typography.titleMedium,
                    color = colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.go_premium_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colorScheme.onSurface,
                    contentColor = colorScheme.surface
                )
            ) {
                Text(stringResource(R.string.go_premium_cta))
            }
        }
    }
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
    onSave: (UserPhysicalProfile) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    // Always read from SharedPreferences when this composable is shown — parent state can be stale after process restart.
    val prefsManager = remember(context.applicationContext) {
        UserPreferencesManager(context.applicationContext)
    }
    val diskProfile = remember(prefsManager) {
        prefsManager.loadPhysicalProfile()
    }
    var weight by remember(diskProfile) { mutableStateOf("%.1f".format(diskProfile.weight)) }
    var height by remember(diskProfile) { mutableStateOf("%.1f".format(diskProfile.height)) }
    var birthDateMs by remember(diskProfile) { mutableStateOf(diskProfile.birthDateMs) }
    var gender by remember(diskProfile) { mutableStateOf(diskProfile.gender) }
    var expanded by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val savedSuccessMsg = stringResource(R.string.saved_success)
    
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
                        focusedBorderColor = colorScheme.onSurface,
                        focusedLabelColor = colorScheme.onSurface,
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
                        focusedBorderColor = colorScheme.onSurface,
                        focusedLabelColor = colorScheme.onSurface,
                        unfocusedBorderColor = colorScheme.outline,
                        unfocusedLabelColor = colorScheme.onSurfaceVariant
                    )
                )
                
                Spacer(modifier = Modifier.height(12.dp))

                BirthDatePicker(
                    birthDateMs = birthDateMs,
                    onBirthDateChange = { birthDateMs = it }
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
                        colors = settingsMonoFilterChipColors()
                    )
                    FilterChip(
                        selected = gender == Gender.FEMALE,
                        onClick = { gender = Gender.FEMALE },
                        label = { Text(stringResource(R.string.female)) },
                        modifier = Modifier.weight(1f),
                        colors = settingsMonoFilterChipColors()
                    )
                    FilterChip(
                        selected = gender == Gender.OTHER,
                        onClick = { gender = Gender.OTHER },
                        label = { Text(stringResource(R.string.other)) },
                        modifier = Modifier.weight(1f),
                        colors = settingsMonoFilterChipColors()
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Save button
                Button(
                    onClick = {
                        val weightValue = (weight.toDoubleOrNull() ?: 70.0).let { kotlin.math.round(it * 10) / 10.0 }
                        val heightValue = (height.toDoubleOrNull() ?: 170.0).let { kotlin.math.round(it * 10) / 10.0 }

                        val profile = UserPhysicalProfile(
                            weight = weightValue,
                            height = heightValue,
                            birthDateMs = birthDateMs,
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
                        containerColor = colorScheme.onSurface,
                        contentColor = colorScheme.surface
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
                        color = colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                
                // Info
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant),
                    border = androidx.compose.foundation.BorderStroke(1.dp, colorScheme.outline.copy(alpha = 0.28f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(R.string.why_physical_profile),
                            style = MaterialTheme.typography.labelLarge,
                            color = colorScheme.onSurface
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
private fun DrivingEngineCcBand.labelString(): String = when (this) {
    DrivingEngineCcBand.UP_TO_1000 -> stringResource(R.string.driving_cc_up_to_1000)
    DrivingEngineCcBand.CC_1001_1400 -> stringResource(R.string.driving_cc_1001_1400)
    DrivingEngineCcBand.CC_1401_1800 -> stringResource(R.string.driving_cc_1401_1800)
    DrivingEngineCcBand.CC_1801_2500 -> stringResource(R.string.driving_cc_1801_2500)
    DrivingEngineCcBand.OVER_2500 -> stringResource(R.string.driving_cc_over_2500)
}

@Composable
private fun TrainPropulsion.labelString(): String = when (this) {
    TrainPropulsion.ELECTRIC -> stringResource(R.string.train_propulsion_electric)
    TrainPropulsion.DIESEL_ELECTRIC -> stringResource(R.string.train_propulsion_diesel_electric)
}

@Composable
private fun AircraftCategory.labelString(): String = when (this) {
    AircraftCategory.REGIONAL_TURBOPROP -> stringResource(R.string.aircraft_regional_turboprop)
    AircraftCategory.NARROW_BODY_JET -> stringResource(R.string.aircraft_narrow_body_jet)
    AircraftCategory.WIDE_BODY_LONG_HAUL -> stringResource(R.string.aircraft_wide_body_long_haul)
}

@Composable
private fun IceFuel.labelString(): String = when (this) {
    IceFuel.PETROL -> stringResource(R.string.ice_fuel_petrol)
    IceFuel.DIESEL -> stringResource(R.string.ice_fuel_diesel)
}

@Composable
private fun PrimaryFuelType.labelString(): String = when (this) {
    PrimaryFuelType.PETROL -> stringResource(R.string.primary_fuel_petrol)
    PrimaryFuelType.DIESEL -> stringResource(R.string.primary_fuel_diesel)
    PrimaryFuelType.ELECTRIC -> stringResource(R.string.primary_fuel_electric)
}

@Composable
private fun VehicleBodyType.labelString(): String = when (this) {
    VehicleBodyType.HATCHBACK -> stringResource(R.string.body_type_hatchback)
    VehicleBodyType.SEDAN -> stringResource(R.string.body_type_sedan)
    VehicleBodyType.SUV_CROSSOVER -> stringResource(R.string.body_type_suv)
    VehicleBodyType.PICKUP -> stringResource(R.string.body_type_pickup)
    VehicleBodyType.MINIVAN_MPV -> stringResource(R.string.body_type_minivan)
}

@Composable
private fun ElectricVehicleClass.labelString(): String = when (this) {
    ElectricVehicleClass.TWO_WHEELER -> stringResource(R.string.ev_class_two_wheeler)
    ElectricVehicleClass.THREE_WHEELER -> stringResource(R.string.ev_class_three_wheeler)
    ElectricVehicleClass.CAR -> stringResource(R.string.ev_class_car)
}

@Composable
private fun ElectricMotorPowerBand.labelString(): String = when (this) {
    ElectricMotorPowerBand.UP_TO_3_KW -> stringResource(R.string.ev_motor_up_to_3_kw)
    ElectricMotorPowerBand.KW_3_TO_10 -> stringResource(R.string.ev_motor_3_to_10_kw)
    ElectricMotorPowerBand.KW_10_TO_60 -> stringResource(R.string.ev_motor_10_to_60_kw)
    ElectricMotorPowerBand.KW_60_TO_150 -> stringResource(R.string.ev_motor_60_to_150_kw)
    ElectricMotorPowerBand.OVER_150_KW -> stringResource(R.string.ev_motor_over_150_kw)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehicleProfileSection(
    onSave: (VehicleProfile) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val prefsManager = remember(context.applicationContext) {
        UserPreferencesManager(context.applicationContext)
    }
    val diskProfile = remember(prefsManager) {
        prefsManager.loadVehicleProfile()
    }
    // Top-level fuel type drives which sub-fields are interactive. Combustion
    // (Petrol/Diesel) and Electric inputs each have their own state vars so
    // flipping the radio doesn't lose the user's prior choices in either branch.
    var primaryFuel by remember(diskProfile) { mutableStateOf(diskProfile.primaryFuelType) }
    var drivingCc by remember(diskProfile) { mutableStateOf(diskProfile.drivingCcBand) }
    var bodyType by remember(diskProfile) { mutableStateOf(diskProfile.bodyType) }
    var evClass by remember(diskProfile) { mutableStateOf(diskProfile.electricVehicleClass) }
    var evMotor by remember(diskProfile) { mutableStateOf(diskProfile.electricMotorPower) }
    var train by remember(diskProfile) { mutableStateOf(diskProfile.trainPropulsion) }
    var aircraft by remember(diskProfile) { mutableStateOf(diskProfile.aircraftCategory) }
    var expanded by remember { mutableStateOf(false) }
    var ccMenuExpanded by remember { mutableStateOf(false) }
    var bodyMenuExpanded by remember { mutableStateOf(false) }
    var motorMenuExpanded by remember { mutableStateOf(false) }
    var trainMenuExpanded by remember { mutableStateOf(false) }
    var aircraftMenuExpanded by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val savedSuccessMsg = stringResource(R.string.saved_success)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
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
                        imageVector = Icons.Default.DirectionsCar,
                        contentDescription = stringResource(R.string.vehicle_profile),
                        tint = colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text(
                            text = stringResource(R.string.vehicle_profile),
                            style = MaterialTheme.typography.titleMedium,
                            color = colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.vehicle_profile_desc),
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

            if (expanded) {
                Spacer(modifier = Modifier.height(16.dp))

                // ── Step 1: top-level fuel type ────────────────────────────
                // FilterChip row (Petrol / Diesel / Electric) gates the
                // combustion-only and electric-only sub-fields below.
                Text(
                    text = stringResource(R.string.vehicle_profile_primary_fuel),
                    style = MaterialTheme.typography.titleSmall,
                    color = colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = stringResource(R.string.vehicle_profile_primary_fuel_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PrimaryFuelType.entries.forEach { option ->
                        FilterChip(
                            selected = primaryFuel == option,
                            onClick = { primaryFuel = option },
                            label = { Text(option.labelString()) },
                            modifier = Modifier.weight(1f),
                            colors = settingsMonoFilterChipColors()
                        )
                    }
                }

                // ── Step 2A: Petrol/Diesel sub-fields ──────────────────────
                if (primaryFuel == PrimaryFuelType.PETROL || primaryFuel == PrimaryFuelType.DIESEL) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.vehicle_profile_driving_cc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    ExposedDropdownMenuBox(
                        expanded = ccMenuExpanded,
                        onExpandedChange = { ccMenuExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = drivingCc.labelString(),
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = ccMenuExpanded)
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = colorScheme.onSurface,
                                unfocusedBorderColor = colorScheme.outline
                            )
                        )
                        ExposedDropdownMenu(
                            expanded = ccMenuExpanded,
                            onDismissRequest = { ccMenuExpanded = false }
                        ) {
                            DrivingEngineCcBand.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.labelString(), color = colorScheme.onSurface) },
                                    onClick = {
                                        drivingCc = option
                                        ccMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.vehicle_profile_body_type),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    ExposedDropdownMenuBox(
                        expanded = bodyMenuExpanded,
                        onExpandedChange = { bodyMenuExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = bodyType.labelString(),
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = bodyMenuExpanded)
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = colorScheme.onSurface,
                                unfocusedBorderColor = colorScheme.outline
                            )
                        )
                        ExposedDropdownMenu(
                            expanded = bodyMenuExpanded,
                            onDismissRequest = { bodyMenuExpanded = false }
                        ) {
                            VehicleBodyType.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.labelString(), color = colorScheme.onSurface) },
                                    onClick = {
                                        bodyType = option
                                        bodyMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                // ── Step 2B: Electric sub-fields ──────────────────────────
                if (primaryFuel == PrimaryFuelType.ELECTRIC) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.vehicle_profile_ev_class),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ElectricVehicleClass.entries.forEach { option ->
                            FilterChip(
                                selected = evClass == option,
                                onClick = { evClass = option },
                                label = { Text(option.labelString()) },
                                modifier = Modifier.weight(1f),
                                colors = settingsMonoFilterChipColors()
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.vehicle_profile_ev_motor_power),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    ExposedDropdownMenuBox(
                        expanded = motorMenuExpanded,
                        onExpandedChange = { motorMenuExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = evMotor.labelString(),
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = motorMenuExpanded)
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = colorScheme.onSurface,
                                unfocusedBorderColor = colorScheme.outline
                            )
                        )
                        ExposedDropdownMenu(
                            expanded = motorMenuExpanded,
                            onDismissRequest = { motorMenuExpanded = false }
                        ) {
                            ElectricMotorPowerBand.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.labelString(), color = colorScheme.onSurface) },
                                    onClick = {
                                        evMotor = option
                                        motorMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.vehicle_profile_train),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                ExposedDropdownMenuBox(
                    expanded = trainMenuExpanded,
                    onExpandedChange = { trainMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value = train.labelString(),
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = trainMenuExpanded)
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colorScheme.onSurface,
                            unfocusedBorderColor = colorScheme.outline
                        )
                    )
                    ExposedDropdownMenu(
                        expanded = trainMenuExpanded,
                        onDismissRequest = { trainMenuExpanded = false }
                    ) {
                        TrainPropulsion.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.labelString(), color = colorScheme.onSurface) },
                                onClick = {
                                    train = option
                                    trainMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.vehicle_profile_aircraft),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                ExposedDropdownMenuBox(
                    expanded = aircraftMenuExpanded,
                    onExpandedChange = { aircraftMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value = aircraft.labelString(),
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = aircraftMenuExpanded)
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colorScheme.onSurface,
                            unfocusedBorderColor = colorScheme.outline
                        )
                    )
                    ExposedDropdownMenu(
                        expanded = aircraftMenuExpanded,
                        onDismissRequest = { aircraftMenuExpanded = false }
                    ) {
                        AircraftCategory.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.labelString(), color = colorScheme.onSurface) },
                                onClick = {
                                    aircraft = option
                                    aircraftMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // ── Weekly CO₂-saved goal slider ─────────────────────────────
                // Lives in the vehicle profile section so the user sets it
                // alongside the inputs that shape their carbon math. Persists
                // immediately on slide-release; not gated by the Save button.
                var weeklyGoalKg by remember(prefsManager) {
                    mutableFloatStateOf(prefsManager.getWeeklyCo2GoalKg())
                }
                Text(
                    text = "Weekly CO₂ goal",
                    style = MaterialTheme.typography.titleSmall,
                    color = colorScheme.onSurface
                )
                Text(
                    text = "How much CO₂ would you like to save each week?",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "%.1f kg / week".format(weeklyGoalKg),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colorScheme.onSurface
                )
                Slider(
                    value = weeklyGoalKg,
                    onValueChange = { raw ->
                        // Snap to 0.5 kg increments to match onboarding.
                        weeklyGoalKg = ((raw * 2f).roundToInt() / 2f)
                            .coerceIn(0.5f, 50f)
                    },
                    onValueChangeFinished = {
                        prefsManager.setWeeklyCo2GoalKg(weeklyGoalKg)
                    },
                    valueRange = 0.5f..50f,
                    steps = ((50f - 0.5f) / 0.5f).toInt() - 1,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        // Project the top-level fuel type back into the legacy
                        // [IceFuel] field so combustion CO₂ math still has a
                        // sensible value when the user has chosen Electric
                        // (we keep both halves of the profile populated).
                        val derivedIceFuel = when (primaryFuel) {
                            PrimaryFuelType.PETROL -> IceFuel.PETROL
                            PrimaryFuelType.DIESEL -> IceFuel.DIESEL
                            PrimaryFuelType.ELECTRIC -> diskProfile.iceFuel
                        }
                        val profile = VehicleProfile(
                            primaryFuelType = primaryFuel,
                            iceFuel = derivedIceFuel,
                            drivingCcBand = drivingCc,
                            bodyType = bodyType,
                            electricVehicleClass = evClass,
                            electricMotorPower = evMotor,
                            trainPropulsion = train,
                            aircraftCategory = aircraft
                        )
                        onSave(profile)
                        saveMessage = savedSuccessMsg
                        coroutineScope.launch {
                            delay(3000)
                            saveMessage = null
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colorScheme.onSurface,
                        contentColor = colorScheme.surface
                    )
                ) {
                    Text(stringResource(R.string.save_vehicle_profile))
                }

                saveMessage?.let { msg ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant),
                    border = androidx.compose.foundation.BorderStroke(1.dp, colorScheme.outline.copy(alpha = 0.28f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(R.string.vehicle_profile_why),
                            style = MaterialTheme.typography.labelLarge,
                            color = colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.vehicle_profile_info),
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ── Grouped expandable sections ──────────────────────────────────────────────
//
// Both sections follow the same visual contract as PhysicalProfileSection /
// VehicleProfileSection lower in this file: a single Card with a tap-to-expand
// header (icon + title + subtitle + chevron) and a Column of content that
// reveals on expand. Items inside the expanded region are inlined directly
// (no nested Cards) to avoid the visual noise of card-in-card layout.

/**
 * "Appearance" section — theme, language, and unit preference together.
 * Replaces the three previously-separate ThemeSelectorCard / LanguageDropdownCard
 * / unit-preference Card so the user sees a single tidy entry point for all
 * "how the app looks / what units it speaks" choices.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceSection(
    currentThemeMode: String,
    onThemeChange: (String) -> Unit,
    currentLocale: String,
    onLocaleChange: (String) -> Unit,
    unitSystem: UnitSystem,
    onUnitSystemChange: (UnitSystem) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    var languageMenuExpanded by remember { mutableStateOf(false) }
    val languageDisplayName = when (currentLocale) {
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
        Column(modifier = Modifier.padding(16.dp)) {
            ExpandableSectionHeader(
                icon = Icons.Default.Palette,
                title = "Appearance",
                subtitle = "Theme, language, and units",
                expanded = expanded,
                onToggle = { expanded = !expanded }
            )

            if (expanded) {
                Spacer(modifier = Modifier.height(20.dp))

                // ── Theme ────────────────────────────────────────────────────
                SectionLabel(stringResource(R.string.theme))
                Text(
                    text = stringResource(R.string.theme_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FilterChip(
                        selected = currentThemeMode == "light",
                        onClick = { onThemeChange("light") },
                        leadingIcon = { Icon(Icons.Default.LightMode, contentDescription = null) },
                        label = { Text(stringResource(R.string.theme_light)) },
                        modifier = Modifier.weight(1f),
                        colors = settingsMonoFilterChipColors()
                    )
                    FilterChip(
                        selected = currentThemeMode != "light",
                        onClick = { onThemeChange("dark") },
                        leadingIcon = { Icon(Icons.Default.DarkMode, contentDescription = null) },
                        label = { Text(stringResource(R.string.theme_dark)) },
                        modifier = Modifier.weight(1f),
                        colors = settingsMonoFilterChipColors()
                    )
                }

                SectionDivider()

                // ── Language ────────────────────────────────────────────────
                SectionLabel(stringResource(R.string.language))
                Text(
                    text = stringResource(R.string.language_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
                )
                ExposedDropdownMenuBox(
                    expanded = languageMenuExpanded,
                    onExpandedChange = { languageMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value = languageDisplayName,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageMenuExpanded)
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = colorScheme.onSurface,
                            unfocusedTextColor = colorScheme.onSurface,
                            focusedBorderColor = colorScheme.onSurface,
                            unfocusedBorderColor = colorScheme.outline
                        )
                    )
                    ExposedDropdownMenu(
                        expanded = languageMenuExpanded,
                        onDismissRequest = { languageMenuExpanded = false }
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
                                    languageMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                SectionDivider()

                // ── Unit preference ─────────────────────────────────────────
                SectionLabel(stringResource(R.string.unit_preference))
                Text(
                    text = stringResource(R.string.unit_preference_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
                )
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
}

/**
 * "Notifications" section — notification sounds and weekly digest grouped together.
 */
@Composable
private fun NotificationsSection(
    notificationSoundsEnabled: Boolean,
    onNotificationSoundsChange: (Boolean) -> Unit,
    weeklyDigestEnabled: Boolean,
    onWeeklyDigestChange: (Boolean) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            ExpandableSectionHeader(
                icon = Icons.Outlined.Notifications,
                title = stringResource(R.string.notifications_section_title),
                subtitle = stringResource(R.string.notifications_section_subtitle),
                expanded = expanded,
                onToggle = { expanded = !expanded }
            )

            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                NotificationToggleRow(
                    title = stringResource(R.string.notification_sounds),
                    description = stringResource(R.string.notification_sounds_desc),
                    checked = notificationSoundsEnabled,
                    onCheckedChange = onNotificationSoundsChange
                )
                SectionDivider()
                NotificationToggleRow(
                    title = stringResource(R.string.notifications_weekly_digest_title),
                    description = stringResource(R.string.notifications_weekly_digest_desc),
                    checked = weeklyDigestEnabled,
                    onCheckedChange = onWeeklyDigestChange
                )
            }
        }
    }
}

/**
 * Shared header used by [AppearanceSection] and [NotificationsSection].
 * Pulled out so both sections render identically — same icon size, same
 * chevron behaviour, same hit-target.
 */
@Composable
private fun ExpandableSectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colorScheme.onSurface
                )
                Text(
                    text = subtitle,
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
}

/**
 * One row inside [NotificationsSection] — bell-style icon, title + description,
 * and a Switch on the right. Padding-free at top/bottom so dividers between
 * adjacent rows look consistent.
 */
@Composable
private fun NotificationToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
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
                tint = colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colorScheme.surface,
                checkedTrackColor = colorScheme.onSurface,
                uncheckedThumbColor = colorScheme.outline,
                uncheckedTrackColor = colorScheme.surfaceVariant
            )
        )
    }
}

/** Small caption used as a sub-section label inside [AppearanceSection]. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface
    )
}

/**
 * Thin horizontal rule between sub-sections inside an expandable card.
 * Faded outline keeps it subtle so it groups items without competing with
 * the surrounding content.
 */
@Composable
private fun SectionDivider() {
    Spacer(modifier = Modifier.height(16.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    Spacer(modifier = Modifier.height(16.dp))
}

