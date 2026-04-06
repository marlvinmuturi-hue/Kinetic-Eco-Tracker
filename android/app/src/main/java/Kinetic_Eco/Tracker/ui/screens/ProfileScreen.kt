package Kinetic_Eco.Tracker.ui.screens

import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.google.firebase.auth.FirebaseUser
import Kinetic_Eco.Tracker.R
import Kinetic_Eco.Tracker.data.UnitSystem
import Kinetic_Eco.Tracker.ui.utils.usesMetricDistance
import Kinetic_Eco.Tracker.ui.components.AnalysisPeriodSelector
import Kinetic_Eco.Tracker.ui.components.AnalysisPeriodSelectorMode
import Kinetic_Eco.Tracker.ui.components.StatCard
import Kinetic_Eco.Tracker.ui.components.StatCardCompact
import Kinetic_Eco.Tracker.ui.theme.Amber500
import Kinetic_Eco.Tracker.ui.theme.Red500
import Kinetic_Eco.Tracker.data.LeaderboardCategory
import Kinetic_Eco.Tracker.data.LeaderboardEntry
import Kinetic_Eco.Tracker.services.LeaderboardPeriod
import Kinetic_Eco.Tracker.ui.utils.format
import Kinetic_Eco.Tracker.ui.utils.formatSpeedMax
import Kinetic_Eco.Tracker.viewmodel.AnalyticsViewModel
import Kinetic_Eco.Tracker.viewmodel.ProfileViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    user: FirebaseUser?,
    viewModel: AnalyticsViewModel,
    profileViewModel: ProfileViewModel,
    unitSystem: UnitSystem = UnitSystem.METRIC,
    onLogout: () -> Unit,
    onSessionsClick: () -> Unit = {}
) {
    val colorScheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val userId = user?.uid ?: ""

    val allSessions by viewModel.getAllSessions(userId).collectAsStateWithLifecycle(initialValue = emptyList())
    val aggregatedStats = viewModel.getAggregatedStats(allSessions)
    val profile by profileViewModel.profile.collectAsStateWithLifecycle()
    val isLoading by profileViewModel.isLoading.collectAsStateWithLifecycle()
    val errorMessage by profileViewModel.errorMessage.collectAsStateWithLifecycle()
    val leaderboardEntries by profileViewModel.leaderboardEntries.collectAsStateWithLifecycle()
    val leaderboardPeriod by profileViewModel.leaderboardPeriod.collectAsStateWithLifecycle()
    val leaderboardCategory by profileViewModel.leaderboardCategory.collectAsStateWithLifecycle()
    val leaderboardSessionDayKey by profileViewModel.leaderboardSessionDayKey.collectAsStateWithLifecycle()
    val leaderboardLoading by profileViewModel.leaderboardLoading.collectAsStateWithLifecycle()

    var showEditDialog by remember { mutableStateOf(false) }
    var showPhotoSourceDialog by remember { mutableStateOf(false) }
    var reactionTargetEntry by remember { mutableStateOf<LeaderboardEntry?>(null) }
    var tempCameraUri by remember { mutableStateOf<Uri?>(null) }
    var pendingUpload by remember { mutableStateOf(false) }

    LaunchedEffect(userId) {
        if (userId.isNotEmpty()) {
            profileViewModel.loadProfile(userId)
            profileViewModel.loadLeaderboard(userId)
            profileViewModel.refreshLeaderboardIfOptedIn(userId)
        }
    }
    LaunchedEffect(leaderboardPeriod, leaderboardCategory, leaderboardSessionDayKey) {
        if (userId.isNotEmpty()) profileViewModel.loadLeaderboard(userId)
    }

    val mediaPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            android.Manifest.permission.READ_MEDIA_IMAGES,
            android.Manifest.permission.CAMERA
        )
    } else {
        arrayOf(
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.CAMERA
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) showPhotoSourceDialog = true
    }

    val galleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        showPhotoSourceDialog = false
        if (uri != null) {
            pendingUpload = true
            profileViewModel.uploadPhoto(userId, uri)
        } else {
            profileViewModel.clearError()
        }
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        showPhotoSourceDialog = false
        tempCameraUri?.let { uri ->
            tempCameraUri = null
            if (success) {
                pendingUpload = true
                profileViewModel.uploadPhoto(userId, uri)
            }
        }
    }

    // Close edit dialog when upload completes (success or failure)
    LaunchedEffect(isLoading, errorMessage, pendingUpload) {
        if (pendingUpload && !isLoading) {
            pendingUpload = false
            if (errorMessage == null) {
                showEditDialog = false
            }
        }
    }

    val displayName = profile?.displayName?.takeIf { it.isNotBlank() }
    val fallbackName = user?.email ?: stringResource(R.string.not_signed_in)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.profile),
                style = MaterialTheme.typography.headlineLarge,
                color = colorScheme.onBackground,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .clip(CircleShape)
                            .background(colorScheme.surfaceVariant)
                            .clickable { showEditDialog = true },
                        contentAlignment = Alignment.Center
                    ) {
                        val photoUrl = profile?.photoUrl
                        if (photoUrl != null) {
                            AsyncImage(
                                model = photoUrl,
                                contentDescription = stringResource(R.string.user),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = stringResource(R.string.user),
                                modifier = Modifier.size(48.dp),
                                tint = colorScheme.primary
                            )
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(4.dp)
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.CameraAlt,
                                contentDescription = stringResource(R.string.add_photo),
                                modifier = Modifier.size(16.dp),
                                tint = colorScheme.onPrimary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showEditDialog = true },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = displayName ?: fallbackName,
                            style = MaterialTheme.typography.titleLarge,
                            color = colorScheme.onSurface,
                            fontWeight = if (displayName != null) FontWeight.SemiBold else FontWeight.Normal
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = stringResource(R.string.edit_profile),
                            modifier = Modifier.size(20.dp),
                            tint = colorScheme.primary
                        )
                    }
                    if (displayName != null && user?.email != null) {
                        Text(
                            text = user.email!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }

        if (errorMessage != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = errorMessage!!,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        IconButton(onClick = { profileViewModel.clearError() }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = stringResource(R.string.lifetime_stats),
                style = MaterialTheme.typography.titleMedium,
                color = colorScheme.onBackground,
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(
                    title = stringResource(R.string.total_sessions),
                    value = "${allSessions.size}",
                    icon = Icons.AutoMirrored.Filled.List,
                    color = colorScheme.tertiary,
                    modifier = Modifier.weight(1f),
                    onClick = onSessionsClick
                )
                StatCard(
                    title = stringResource(R.string.total_distance),
                    value = if (unitSystem.usesMetricDistance()) {
                        "${(aggregatedStats.totalDistance / 1000.0).format(2)} km"
                    } else {
                        "${(aggregatedStats.totalDistance / 1609.344).format(2)} mi"
                    },
                    icon = Icons.Default.Straighten,
                    color = colorScheme.secondary,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            StatCard(
                title = stringResource(R.string.co2_saved),
                value = "${aggregatedStats.co2Conserved.format(3)} kg",
                iconPainter = painterResource(R.drawable.ic_co2_carbon_neutral),
                color = colorScheme.secondary,
                modifier = Modifier.fillMaxWidth(),
                emphasize = true
            )
        }

        if (aggregatedStats.totalSteps > 0 || aggregatedStats.co2Emissions > 0) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (aggregatedStats.totalSteps > 0) {
                        StatCardCompact(
                            title = stringResource(R.string.total_steps),
                            value = String.format("%,d", aggregatedStats.totalSteps),
                            icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                            color = Amber500,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (aggregatedStats.co2Emissions > 0) {
                        StatCardCompact(
                            title = stringResource(R.string.co2_emitted),
                            value = "${aggregatedStats.co2Emissions.format(3)} kg",
                            icon = Icons.Default.LocalFireDepartment,
                            color = Red500,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // Leaderboard
        item {
            Text(
                text = stringResource(R.string.leaderboard),
                style = MaterialTheme.typography.titleMedium,
                color = colorScheme.onBackground,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
            )
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.category),
                        style = MaterialTheme.typography.labelMedium,
                        color = colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        items(LeaderboardCategory.entries) { category ->
                            val labelRes = when (category) {
                                LeaderboardCategory.COMBINED -> R.string.leaderboard_category_combined
                                LeaderboardCategory.DISTANCE -> R.string.leaderboard_category_distance
                                LeaderboardCategory.TOP_SPEED -> R.string.leaderboard_category_top_speed
                                LeaderboardCategory.WALKING -> R.string.leaderboard_category_walking
                                LeaderboardCategory.RUNNING -> R.string.leaderboard_category_running
                                LeaderboardCategory.CYCLING -> R.string.leaderboard_category_cycling
                            }
                            FilterChip(
                                selected = leaderboardCategory == category,
                                onClick = { profileViewModel.setLeaderboardCategory(category) },
                                label = { Text(stringResource(labelRes), style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.leaderboard_period_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    AnalysisPeriodSelector(
                        mode = AnalysisPeriodSelectorMode.AiAnalysis,
                        rollingDays = when (val p = leaderboardPeriod) {
                            is LeaderboardPeriod.Rolling -> p.days
                            LeaderboardPeriod.AllTime -> 7
                        },
                        allTimeSelected = leaderboardPeriod is LeaderboardPeriod.AllTime,
                        selectedSessionDateKey = leaderboardSessionDayKey,
                        includeAllTimeOption = true,
                        onAiRollingDaysChanged = { profileViewModel.setLeaderboardRollingDays(it) },
                        onAllTimeSelected = { profileViewModel.setLeaderboardPeriod(LeaderboardPeriod.AllTime) },
                        onSessionDaySelected = { profileViewModel.setLeaderboardSingleDay(it) }
                    )
                    leaderboardSessionDayKey?.let { dayKey ->
                        Text(
                            text = stringResource(R.string.leaderboard_daily_subtitle, dayKey),
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    if (leaderboardLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        }
                    } else if (leaderboardEntries.isEmpty()) {
                        Text(
                            text = stringResource(
                                if (leaderboardSessionDayKey != null) R.string.leaderboard_daily_empty
                                else R.string.leaderboard_empty
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(leaderboardEntries) { entry ->
                                LeaderboardRow(
                                    entry = entry,
                                    category = leaderboardCategory,
                                    isCurrentUser = entry.userId == userId,
                                    unitSystem = unitSystem,
                                    currentUserId = userId,
                                    onLongPress = { if (entry.userId != userId) reactionTargetEntry = entry }
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Button(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Red500)
            ) {
                Text(stringResource(R.string.logout), style = MaterialTheme.typography.labelLarge)
            }
        }
    }

    if (showEditDialog) {
        var nameField by remember(showEditDialog) { mutableStateOf(displayName ?: "") }
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text(stringResource(R.string.edit_profile)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    errorMessage?.let { msg ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(msg, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                                Spacer(modifier = Modifier.weight(1f))
                                IconButton(onClick = { profileViewModel.clearError() }) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel), modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = nameField,
                        onValueChange = { nameField = it },
                        label = { Text(stringResource(R.string.preferred_name)) },
                        placeholder = { Text(stringResource(R.string.preferred_name_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedButton(
                        onClick = {
                            val hasPermission = mediaPermissions.all { perm ->
                                androidx.core.content.ContextCompat.checkSelfPermission(context, perm) ==
                                    android.content.pm.PackageManager.PERMISSION_GRANTED
                            }
                            if (hasPermission) {
                                showPhotoSourceDialog = true
                            } else {
                                activity?.let { permissionLauncher.launch(mediaPermissions) }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (profile?.photoUrl != null) stringResource(R.string.change_photo) else stringResource(R.string.add_photo))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        profileViewModel.saveDisplayName(userId, nameField.takeIf { it.isNotBlank() })
                        showEditDialog = false
                    }
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        Text(stringResource(R.string.save))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showPhotoSourceDialog) {
        AlertDialog(
            onDismissRequest = { showPhotoSourceDialog = false },
            title = { Text(stringResource(R.string.add_photo)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            showPhotoSourceDialog = false
                            galleryPicker.launch("image/*")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.choose_from_gallery))
                    }
                    TextButton(
                        onClick = {
                            val file = File(context.cacheDir, "profile_photo_${System.currentTimeMillis()}.jpg")
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                            tempCameraUri = uri
                            showPhotoSourceDialog = false
                            takePictureLauncher.launch(uri)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.take_photo))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showPhotoSourceDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    val targetEntry = reactionTargetEntry
    if (targetEntry != null && targetEntry.userId != userId) {
        ModalBottomSheet(onDismissRequest = { reactionTargetEntry = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.react_to_entry, targetEntry.displayName ?: "User"),
                    style = MaterialTheme.typography.titleMedium
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    val emojis = listOf(
                        "fire" to "🔥",
                        "sweat" to "😅",
                        "clap" to "👏",
                        "joy" to "😂",
                        "thumbs" to "👍",
                        "cool" to "😎"
                    )
                    items(emojis) { (code, emoji) ->
                        val isCurrentReaction = targetEntry.reactions[userId] == code
                        Surface(
                            onClick = {
                                profileViewModel.reactToEntry(userId, targetEntry.userId, code)
                                reactionTargetEntry = null
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.size(48.dp),
                            color = if (isCurrentReaction) colorScheme.primaryContainer else colorScheme.surface
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Text(text = emoji, style = MaterialTheme.typography.headlineMedium)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun LeaderboardRow(
    entry: LeaderboardEntry,
    category: LeaderboardCategory,
    isCurrentUser: Boolean,
    unitSystem: UnitSystem,
    currentUserId: String,
    onLongPress: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val crownRes = when (entry.rank) {
        1 -> R.drawable.ic_crown_gold
        2 -> R.drawable.ic_crown_silver
        3 -> R.drawable.ic_crown_bronze
        else -> null
    }
    val subtitle = when (category) {
        LeaderboardCategory.COMBINED -> {
            if (unitSystem.usesMetricDistance()) {
                "${(entry.totalDistance / 1000.0).format(2)} km • ${entry.totalSessions} sessions • ${entry.co2Conserved.format(2)} kg CO₂ saved"
            } else {
                "${(entry.totalDistance / 1609.344).format(2)} mi • ${entry.totalSessions} sessions • ${entry.co2Conserved.format(2)} kg CO₂ saved"
            }
        }
        LeaderboardCategory.DISTANCE -> {
            if (unitSystem.usesMetricDistance()) {
                "${(entry.totalDistance / 1000.0).format(2)} km"
            } else {
                "${(entry.totalDistance / 1609.344).format(2)} mi"
            }
        }
        LeaderboardCategory.TOP_SPEED -> formatSpeedMax(entry.topSpeedMps, unitSystem)
        LeaderboardCategory.WALKING -> {
            if (unitSystem.usesMetricDistance()) {
                "${(entry.distanceWalking / 1000.0).format(2)} km walking"
            } else {
                "${(entry.distanceWalking / 1609.344).format(2)} mi walking"
            }
        }
        LeaderboardCategory.RUNNING -> {
            if (unitSystem.usesMetricDistance()) {
                "${(entry.distanceRunning / 1000.0).format(2)} km running"
            } else {
                "${(entry.distanceRunning / 1609.344).format(2)} mi running"
            }
        }
        LeaderboardCategory.CYCLING -> {
            if (unitSystem.usesMetricDistance()) {
                "${(entry.distanceCycling / 1000.0).format(2)} km cycling"
            } else {
                "${(entry.distanceCycling / 1609.344).format(2)} mi cycling"
            }
        }
    }
    val modifier = if (isCurrentUser) Modifier.fillMaxWidth() else Modifier
        .fillMaxWidth()
        .pointerInput(entry.userId) {
            detectTapGestures(onLongPress = { onLongPress() })
        }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = if (isCurrentUser) colorScheme.primaryContainer else colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.widthIn(min = 36.dp)
            ) {
                Text(
                    text = "#${entry.rank}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isCurrentUser) colorScheme.onPrimaryContainer else colorScheme.onSurfaceVariant
                )
                if (crownRes != null) {
                    Image(
                        painter = painterResource(crownRes),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                if (entry.photoUrl != null && entry.photoUrl.isNotEmpty()) {
                    AsyncImage(
                        model = entry.photoUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = colorScheme.primary
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.displayName ?: "User",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (isCurrentUser) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isCurrentUser) colorScheme.onPrimaryContainer else colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isCurrentUser) colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else colorScheme.onSurfaceVariant
                )
            }
            }
            if (entry.reactions.isNotEmpty()) {
                val emojiDisplay = listOf(
                    "fire" to "🔥", "sweat" to "😅", "clap" to "👏",
                    "joy" to "😂", "thumbs" to "👍", "cool" to "😎"
                )
                val myReaction = entry.reactions[currentUserId]
                val counts = emojiDisplay.map { (code, emoji) ->
                    val count = entry.reactions.values.count { it == code }
                    if (count > 0) Triple(emoji, count, code == myReaction) else null
                }.filterNotNull()
                if (counts.isNotEmpty()) {
                    Row(
                        modifier = Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        counts.forEach { (emoji, count, isMine) ->
                            Text(
                                text = "$emoji $count",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isMine) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isCurrentUser) colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
