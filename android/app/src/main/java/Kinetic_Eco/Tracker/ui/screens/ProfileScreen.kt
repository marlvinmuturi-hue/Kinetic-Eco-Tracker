package Kinetic_Eco.Tracker.ui.screens

import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.google.firebase.auth.FirebaseUser
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import Kinetic_Eco.Tracker.R
import Kinetic_Eco.Tracker.data.UnitSystem
import Kinetic_Eco.Tracker.ui.utils.usesMetricDistance
import Kinetic_Eco.Tracker.ui.components.AppOpenAdManager
import Kinetic_Eco.Tracker.ui.components.StatCard
import Kinetic_Eco.Tracker.ui.components.StatCardCompact
import Kinetic_Eco.Tracker.data.TravelRecap
import Kinetic_Eco.Tracker.data.TripRecord
import Kinetic_Eco.Tracker.ui.theme.Amber500
import Kinetic_Eco.Tracker.ui.theme.Green500
import Kinetic_Eco.Tracker.ui.theme.Red500
import Kinetic_Eco.Tracker.ui.theme.glassTile
import Kinetic_Eco.Tracker.ui.theme.kineticGradientBackground
import Kinetic_Eco.Tracker.ui.utils.format
import Kinetic_Eco.Tracker.viewmodel.AnalyticsViewModel
import Kinetic_Eco.Tracker.viewmodel.ProfileViewModel
import Kinetic_Eco.Tracker.data.VehicleProfile
import Kinetic_Eco.Tracker.services.UserPhysicalProfile
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    user: FirebaseUser?,
    viewModel: AnalyticsViewModel,
    profileViewModel: ProfileViewModel,
    unitSystem: UnitSystem = UnitSystem.METRIC,
    onSessionsClick: () -> Unit = {},
    onPhysicalProfileSave: (UserPhysicalProfile) -> Unit = {},
    onVehicleProfileSave: (VehicleProfile) -> Unit = {}
) {
    val colorScheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val userId = user?.uid ?: ""

    // Frosted-glass backdrop (dark theme only — see Glass.kt/Theme.kt): a gradient
    // layer marked as the haze source, with the scrollable content's glassTile
    // cards drawn on top of it. In light theme, hazeState stays null and every
    // card below falls back to its original flat MaterialTheme surface color.
    val hazeState = remember { HazeState() }

    val allSessions by viewModel.getAllSessions(userId).collectAsStateWithLifecycle(initialValue = emptyList())
    val aggregatedStats = viewModel.getAggregatedStats(allSessions)
    val travelRecap by viewModel.travelRecap.collectAsStateWithLifecycle()
    val travelRecapLoading by viewModel.travelRecapLoading.collectAsStateWithLifecycle()
    // Annual carbon footprint = last 365 days of co2Conserved (in kg) summed from
    // the user's sessions. Computed here so the existing CO2-saved card can be
    // repurposed without changing the StatCard API.
    val annualCo2SavedKg = remember(allSessions) {
        val cutoff = System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1000
        allSessions.filter { it.sessionEndTimeMs >= cutoff }.sumOf { it.co2Conserved }
    }
    val profile by profileViewModel.profile.collectAsStateWithLifecycle()
    val isLoading by profileViewModel.isLoading.collectAsStateWithLifecycle()
    val errorMessage by profileViewModel.errorMessage.collectAsStateWithLifecycle()

    var showEditDialog by remember { mutableStateOf(false) }
    var showPhotoSourceDialog by remember { mutableStateOf(false) }
    var tempCameraUri by remember { mutableStateOf<Uri?>(null) }
    var pendingUpload by remember { mutableStateOf(false) }

    // Note: the leaderboard used to live on this screen with its own period
    // selector + reaction sheet. It was lifted out per user request — the
    // compact leaderboard row on the Dashboard remains the canonical surface,
    // and DashboardScreen drives `loadLeaderboard` itself, so we don't need
    // to fetch it here.
    LaunchedEffect(userId) {
        if (userId.isNotEmpty()) {
            profileViewModel.loadProfile(userId)
            viewModel.loadTravelRecap(userId)
        }
    }

    val mediaPermissions = arrayOf(android.Manifest.permission.CAMERA)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) showPhotoSourceDialog = true
    }

    val galleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
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

    Box(modifier = Modifier.fillMaxSize()) {
    if (hazeState != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .kineticGradientBackground()
                .haze(state = hazeState)
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .then(if (hazeState == null) Modifier.background(colorScheme.background) else Modifier)
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
            val profileCardShape = RoundedCornerShape(16.dp)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (hazeState != null) Modifier.glassTile(hazeState, profileCardShape) else Modifier),
                colors = CardDefaults.cardColors(
                    containerColor = if (hazeState != null) Color.Transparent else colorScheme.surface
                ),
                shape = profileCardShape
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
                    onClick = onSessionsClick,
                    hazeState = hazeState
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
                    modifier = Modifier.weight(1f),
                    hazeState = hazeState
                )
            }
        }

        item {
            StatCard(
                title = stringResource(R.string.annual_carbon_footprint),
                value = "${annualCo2SavedKg.format(3)} kg",
                iconPainter = painterResource(R.drawable.ic_co2_carbon_neutral),
                color = colorScheme.secondary,
                modifier = Modifier.fillMaxWidth(),
                emphasize = true,
                hazeState = hazeState
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
                            modifier = Modifier.weight(1f),
                            hazeState = hazeState
                        )
                    }
                    if (aggregatedStats.co2Emissions > 0) {
                        StatCardCompact(
                            title = stringResource(R.string.co2_emitted),
                            value = "${aggregatedStats.co2Emissions.format(3)} kg",
                            icon = Icons.Default.LocalFireDepartment,
                            color = Red500,
                            modifier = Modifier.weight(1f),
                            hazeState = hazeState
                        )
                    }
                }
            }
        }

        item {
            TravelRecapCard(recap = travelRecap, isLoading = travelRecapLoading, hazeState = hazeState)
        }

        item {
            PhysicalProfileSection(onSave = onPhysicalProfileSave)
        }
        item {
            VehicleProfileSection(onSave = onVehicleProfileSave)
        }
    }
    } // end backdrop Box

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
                            // App-initiated external picker — don't let an App Open ad cover the return.
                            AppOpenAdManager.suppressNextForegroundAd()
                            galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
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
                            // App-initiated external camera — don't let an App Open ad cover the return.
                            AppOpenAdManager.suppressNextForegroundAd()
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

}

@Composable
private fun TravelRecapCard(recap: TravelRecap?, isLoading: Boolean, hazeState: HazeState?) {
    val colorScheme = MaterialTheme.colorScheme
    val recapShape = RoundedCornerShape(16.dp)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (hazeState != null) Modifier.glassTile(hazeState, recapShape) else Modifier),
        shape = recapShape,
        colors = CardDefaults.cardColors(
            containerColor = if (hazeState != null) Color.Transparent else colorScheme.surfaceVariant
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(Green500.copy(alpha = 0.18f), Color(0xFF06B6D4).copy(alpha = 0.12f))
                    )
                )
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🌍", fontSize = 22.sp)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        "Travel Recap",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.onSurface
                    )
                    if (recap?.firstTripDate != null) {
                        Text(
                            "First trip: ${fmtRecapDate(recap.firstTripDate)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        when {
            isLoading && recap == null -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Loading your travel history…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }

            recap == null || recap.totalSessions == 0 -> {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🌱", fontSize = 40.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "No trips yet",
                            style = MaterialTheme.typography.titleSmall,
                            color = colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Start your first eco-friendly adventure!",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            else -> {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Countries + cities — only shown when geocoding resolved at least one location
                    if (recap.countriesVisited.isNotEmpty()) {
                        if (recap.countryCodes.isNotEmpty()) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                items(recap.countryCodes.take(8)) { code ->
                                    Text(countryCodeToFlag(code), fontSize = 26.sp)
                                }
                                if (recap.countryCodes.size > 8) {
                                    item {
                                        Text(
                                            "+${recap.countryCodes.size - 8}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(start = 4.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Text(
                            buildString {
                                append("${recap.countriesVisited.size} ")
                                append(if (recap.countriesVisited.size == 1) "country" else "countries")
                                if (recap.citiesVisited.isNotEmpty()) {
                                    append("  ·  ${recap.citiesVisited.size} ")
                                    append(if (recap.citiesVisited.size == 1) "city" else "cities")
                                }
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = colorScheme.onSurface
                        )

                        if (recap.citiesVisited.isNotEmpty()) {
                            val displayed = recap.citiesVisited.take(5).joinToString(" · ")
                            val suffix = if (recap.citiesVisited.size > 5) " …" else ""
                            Text(
                                displayed + suffix,
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant
                            )
                        }

                        HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.25f))
                    }

                    // Personal bests header
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.EmojiEvents,
                            contentDescription = null,
                            tint = Color(0xFFFFD700),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Personal Bests",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = colorScheme.onSurface
                        )
                    }

                    // 2-column grid of personal record tiles
                    val tiles = listOfNotNull(
                        recap.longestTrip?.let         { Triple("📏", "Longest Trip",     it) },
                        recap.longestSession?.let      { Triple("⏱️", "Longest Session",  it) },
                        recap.topSpeedRecord?.let      { Triple("⚡", "Top Speed",        it) },
                        recap.mostElevationRecord?.let { Triple("🏔️", "Highest Climb",   it) },
                        recap.bestCo2Record?.let       { Triple("🌿", "Best CO₂ Save",   it) },
                        recap.mostCaloriesRecord?.let  { Triple("🔥", "Most Calories",   it) },
                    )

                    tiles.chunked(2).forEach { pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            pair.forEach { (emoji, label, record) ->
                                TripBestTile(
                                    emoji = emoji,
                                    label = label,
                                    record = record,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (pair.size < 2) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TripBestTile(
    emoji: String,
    label: String,
    record: TripRecord,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(emoji, fontSize = 16.sp)
                Text(
                    record.displayValue,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.primary
                )
            }
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant
            )
            if (record.city != null) {
                Text(
                    record.city,
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )
            }
            Text(
                fmtRecapDate(record.sessionDate),
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun countryCodeToFlag(code: String): String {
    if (code.length != 2) return ""
    return code.uppercase().map { char ->
        String(Character.toChars(char.code - 'A'.code + 0x1F1E6))
    }.joinToString("")
}

private fun fmtRecapDate(date: String): String {
    return try {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        val out = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
        sdf.parse(date)?.let { out.format(it) } ?: date
    } catch (e: Exception) { date }
}
