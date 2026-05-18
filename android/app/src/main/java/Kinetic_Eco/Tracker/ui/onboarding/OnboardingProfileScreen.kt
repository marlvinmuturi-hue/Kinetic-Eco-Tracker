package Kinetic_Eco.Tracker.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import java.io.File
import Kinetic_Eco.Tracker.data.*
import Kinetic_Eco.Tracker.services.Gender
import Kinetic_Eco.Tracker.services.UserPhysicalProfile
import Kinetic_Eco.Tracker.ui.components.BirthDatePicker

/**
 * Step 3 of 3 (post-login) — Profile setup.
 *
 * Three sections, top to bottom:
 *  - **Profile photo** (optional): tap the avatar to take a new photo or
 *    pick one from the gallery. The captured/picked URI is forwarded to
 *    [onPhotoSelected]; the caller is responsible for uploading it (we
 *    delegate to `ProfileViewModel.uploadPhoto` in the nav graph so this
 *    screen stays unaware of Firebase Storage).
 *  - **Physical details** (collapsible): weight, height, birth date, gender —
 *    used for calorie calculations. Age is derived from birth date so the
 *    user never has to revisit it.
 *  - **Vehicle profile** (collapsible): fuel type, engine size, EV type,
 *    train, aircraft — used for CO₂ baseline estimates.
 *
 * All sections are optional; "Skip for now" is always available so users
 * can land on the dashboard immediately and edit later in Profile / Settings.
 */
@Composable
fun OnboardingProfileScreen(
    initialPhysical: UserPhysicalProfile = UserPhysicalProfile(),
    initialVehicle: VehicleProfile = VehicleProfile.DEFAULT,
    initialWeeklyCo2GoalKg: Float = 5.0f,
    currentPhotoUrl: String? = null,
    onPhotoSelected: (Uri) -> Unit = {},
    onSave: (UserPhysicalProfile, VehicleProfile, Float) -> Unit,
    onSkip: () -> Unit
) {
    var physicalExpanded by remember { mutableStateOf(true) }
    var vehicleExpanded by remember { mutableStateOf(false) }

    // Personal weekly CO₂-saved goal. Kept in kg, half-kg granularity. The
    // default (5 kg) is also what's used as the visual reference for the
    // sprouting plant on the dashboard, so a user who skips this slider
    // will see exactly the same animation as one who explicitly accepts it.
    var weeklyGoalKg by remember { mutableFloatStateOf(initialWeeklyCo2GoalKg) }

    // Physical fields
    var weight by remember { mutableStateOf(if (initialPhysical.weight == 70.0) "" else initialPhysical.weight.toString()) }
    var height by remember { mutableStateOf(if (initialPhysical.height == 170.0) "" else initialPhysical.height.toString()) }
    var birthDateMs by remember { mutableStateOf(initialPhysical.birthDateMs) }
    var gender by remember { mutableStateOf(initialPhysical.gender) }

    // Vehicle fields. The top-level `primaryFuel` chip drives which sub-fields
    // are interactive — combustion shows CC band + body type, electric shows
    // EV class + motor band. Train/aircraft remain orthogonal.
    var primaryFuel  by remember { mutableStateOf(initialVehicle.primaryFuelType) }
    var ccBand       by remember { mutableStateOf(initialVehicle.drivingCcBand) }
    var bodyType     by remember { mutableStateOf(initialVehicle.bodyType) }
    var evClass      by remember { mutableStateOf(initialVehicle.electricVehicleClass) }
    var evMotor      by remember { mutableStateOf(initialVehicle.electricMotorPower) }
    var trainType    by remember { mutableStateOf(initialVehicle.trainPropulsion) }
    var aircraftType by remember { mutableStateOf(initialVehicle.aircraftCategory) }

    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(36.dp))

        Text(
            text = "Set up your profile",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "Add a photo and help us tailor calorie and CO₂ estimates to you. Anything you skip can be set later in Profile.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // ── Profile photo ─────────────────────────────────────────────────────
        ProfilePhotoPicker(
            photoUrl = currentPhotoUrl,
            onPhotoSelected = onPhotoSelected
        )

        // ── Physical details ─────────────────────────────────────────────────
        ProfileSection(
            title = "Physical details",
            expanded = physicalExpanded,
            onToggle = { physicalExpanded = !physicalExpanded }
        ) {
            OutlinedTextField(
                value = weight,
                onValueChange = { weight = it },
                label = { Text("Weight (kg)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = height,
                onValueChange = { height = it },
                label = { Text("Height (cm)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            BirthDatePicker(
                birthDateMs = birthDateMs,
                onBirthDateChange = { birthDateMs = it }
            )
            Text(
                text = "Gender",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Gender.entries.forEach { g ->
                    FilterChip(
                        selected = gender == g,
                        onClick = { gender = g },
                        label = {
                            Text(
                                text = when (g) {
                                    Gender.MALE   -> "Male"
                                    Gender.FEMALE -> "Female"
                                    Gender.OTHER  -> "Other"
                                }
                            )
                        }
                    )
                }
            }
        }

        // ── Vehicle profile ──────────────────────────────────────────────────
        ProfileSection(
            title = "Vehicle profile",
            expanded = vehicleExpanded,
            onToggle = { vehicleExpanded = !vehicleExpanded }
        ) {
            // ── Primary fuel type ────────────────────────────────────────
            // Top-level pick that gates the combustion-only and electric-only
            // sections below; matches the new flow in Settings → Vehicle.
            Text("Fuel type", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryFuelType.entries.forEach { f ->
                    FilterChip(
                        selected = primaryFuel == f,
                        onClick = { primaryFuel = f },
                        label = {
                            Text(
                                when (f) {
                                    PrimaryFuelType.PETROL -> "Petrol"
                                    PrimaryFuelType.DIESEL -> "Diesel"
                                    PrimaryFuelType.ELECTRIC -> "Electric"
                                }
                            )
                        }
                    )
                }
            }

            // ── Combustion (Petrol/Diesel) sub-fields ────────────────────
            if (primaryFuel == PrimaryFuelType.PETROL || primaryFuel == PrimaryFuelType.DIESEL) {
                Text("Engine size", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    DrivingEngineCcBand.entries.forEach { band ->
                        val label = when (band) {
                            DrivingEngineCcBand.UP_TO_1000   -> "Up to 1 000 cc"
                            DrivingEngineCcBand.CC_1001_1400 -> "1 001 – 1 400 cc"
                            DrivingEngineCcBand.CC_1401_1800 -> "1 401 – 1 800 cc"
                            DrivingEngineCcBand.CC_1801_2500 -> "1 801 – 2 500 cc"
                            DrivingEngineCcBand.OVER_2500    -> "Over 2 500 cc"
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = ccBand == band, onClick = { ccBand = band })
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                Text("Body type", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    VehicleBodyType.entries.forEach { bt ->
                        val label = when (bt) {
                            VehicleBodyType.HATCHBACK     -> "Hatchback"
                            VehicleBodyType.SEDAN         -> "Sedan"
                            VehicleBodyType.SUV_CROSSOVER -> "SUV / Crossover"
                            VehicleBodyType.PICKUP        -> "Pickup truck"
                            VehicleBodyType.MINIVAN_MPV   -> "Minivan / MPV"
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = bodyType == bt, onClick = { bodyType = bt })
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            // ── Electric sub-fields ──────────────────────────────────────
            if (primaryFuel == PrimaryFuelType.ELECTRIC) {
                Text("Vehicle type", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ElectricVehicleClass.entries.forEach { ev ->
                        FilterChip(
                            selected = evClass == ev,
                            onClick = { evClass = ev },
                            label = {
                                Text(
                                    when (ev) {
                                        ElectricVehicleClass.TWO_WHEELER   -> "2-wheeler"
                                        ElectricVehicleClass.THREE_WHEELER -> "3-wheeler"
                                        ElectricVehicleClass.CAR           -> "Car"
                                    }
                                )
                            }
                        )
                    }
                }

                Text("Motor rating", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ElectricMotorPowerBand.entries.forEach { band ->
                        val label = when (band) {
                            ElectricMotorPowerBand.UP_TO_3_KW   -> "≤ 3 kW (e-bike, micro scooter)"
                            ElectricMotorPowerBand.KW_3_TO_10   -> "3 – 10 kW (scooter, light 3-wheeler)"
                            ElectricMotorPowerBand.KW_10_TO_60  -> "10 – 60 kW (city EV, larger 3-wheeler)"
                            ElectricMotorPowerBand.KW_60_TO_150 -> "60 – 150 kW (mid-range EV)"
                            ElectricMotorPowerBand.OVER_150_KW  -> "> 150 kW (performance / luxury EV)"
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = evMotor == band, onClick = { evMotor = band })
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            // Train type
            Text("Train type", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TrainPropulsion.entries.forEach { t ->
                    FilterChip(
                        selected = trainType == t,
                        onClick = { trainType = t },
                        label = {
                            Text(
                                when (t) {
                                    TrainPropulsion.ELECTRIC        -> "Electric"
                                    TrainPropulsion.DIESEL_ELECTRIC -> "Diesel-electric"
                                }
                            )
                        }
                    )
                }
            }

            // Aircraft
            Text("Aircraft type", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                AircraftCategory.entries.forEach { ac ->
                    val label = when (ac) {
                        AircraftCategory.REGIONAL_TURBOPROP  -> "Regional turboprop"
                        AircraftCategory.NARROW_BODY_JET     -> "Narrow-body jet"
                        AircraftCategory.WIDE_BODY_LONG_HAUL -> "Wide-body / long-haul"
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = aircraftType == ac, onClick = { aircraftType = ac })
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Weekly CO₂-saved goal ─────────────────────────────────────────
        // A simple slider — half-kg increments, capped at 20 kg/week, which
        // covers anyone short of a heroic bike-everything-everywhere user.
        // Skippable: leaving the default 5 kg in place is fine.
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "Weekly CO₂ goal",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "How much CO₂ would you like to save each week? Your dashboard plant grows toward this.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "${"%.1f".format(weeklyGoalKg)} kg / week",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Slider(
                    value = weeklyGoalKg,
                    onValueChange = { weeklyGoalKg = it },
                    valueRange = 0.5f..20f,
                    // 39 steps gives 0.5 kg increments across the [0.5, 20] range.
                    steps = 39,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Light starter (0.5–2 kg)  ·  Solid (3–7 kg)  ·  Ambitious (8+ kg)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        OnboardingStepIndicator(currentStep = 2, totalSteps = 3) // step 3 of 3

        Spacer(Modifier.height(4.dp))

        Button(
            onClick = {
                val physical = UserPhysicalProfile(
                    weight = weight.toDoubleOrNull() ?: 70.0,
                    height = height.toDoubleOrNull() ?: 170.0,
                    birthDateMs = birthDateMs,
                    gender = gender
                )
                // Mirror the top-level fuel pick into the legacy iceFuel slot
                // so combustion math has a sensible value even when the user
                // chose Electric. The unselected branch keeps its initial
                // value from `initialVehicle` for round-tripping.
                val derivedIceFuel = when (primaryFuel) {
                    PrimaryFuelType.PETROL   -> IceFuel.PETROL
                    PrimaryFuelType.DIESEL   -> IceFuel.DIESEL
                    PrimaryFuelType.ELECTRIC -> initialVehicle.iceFuel
                }
                val vehicle = VehicleProfile(
                    primaryFuelType      = primaryFuel,
                    iceFuel              = derivedIceFuel,
                    drivingCcBand        = ccBand,
                    bodyType             = bodyType,
                    electricVehicleClass = evClass,
                    electricMotorPower   = evMotor,
                    trainPropulsion      = trainType,
                    aircraftCategory     = aircraftType
                )
                onSave(physical, vehicle, weeklyGoalKg)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Text("Save & get started", style = MaterialTheme.typography.labelLarge)
        }

        OutlinedButton(
            onClick = onSkip,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large
        ) {
            Text("Skip for now")
        }

        Spacer(Modifier.height(32.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Profile photo picker
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Circular avatar with a camera-icon overlay. Tapping opens a dialog with
 * "Take photo" / "Choose from gallery" options. Camera permission is
 * requested only when the user taps "Take photo" — gallery picking uses the
 * scoped storage picker which doesn't need a runtime permission.
 *
 * The local URI returned by either flow is forwarded via [onPhotoSelected];
 * actual upload is the caller's responsibility.
 */
@Composable
private fun ProfilePhotoPicker(
    photoUrl: String?,
    onPhotoSelected: (Uri) -> Unit
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme

    var showSourceDialog by remember { mutableStateOf(false) }
    // Holds the FileProvider URI created right before launching the camera.
    // We need to remember it so the result callback knows where the camera
    // wrote the image (TakePicture only returns a boolean success flag).
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    // Locally-selected URI, shown immediately as preview while upload runs.
    var localPreviewUri by remember { mutableStateOf<Uri?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        showSourceDialog = false
        if (uri != null) {
            localPreviewUri = uri
            onPhotoSelected(uri)
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        showSourceDialog = false
        pendingCameraUri?.let { uri ->
            pendingCameraUri = null
            if (success) {
                localPreviewUri = uri
                onPhotoSelected(uri)
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchCamera(context) { uri ->
                pendingCameraUri = uri
                cameraLauncher.launch(uri)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(112.dp)
                .clip(CircleShape)
                .background(colorScheme.surfaceVariant)
                .clickable { showSourceDialog = true },
            contentAlignment = Alignment.Center
        ) {
            // Prefer the just-picked local preview, then any remote URL, then
            // fall back to the placeholder person icon.
            val displayModel: Any? = localPreviewUri ?: photoUrl
            if (displayModel != null) {
                AsyncImage(
                    model = displayModel,
                    contentDescription = "Profile photo",
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = colorScheme.primary
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.CameraAlt,
                    contentDescription = "Add a profile photo",
                    modifier = Modifier.size(18.dp),
                    tint = colorScheme.onPrimary
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Tap to add a profile photo (optional)",
            style = MaterialTheme.typography.bodySmall,
            color = colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }

    if (showSourceDialog) {
        AlertDialog(
            onDismissRequest = { showSourceDialog = false },
            title = { Text("Choose a photo") },
            text = { Text("Take a new photo or pick one from your gallery.") },
            confirmButton = {
                TextButton(onClick = {
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        showSourceDialog = false
                        launchCamera(context) { uri ->
                            pendingCameraUri = uri
                            cameraLauncher.launch(uri)
                        }
                    } else {
                        showSourceDialog = false
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.CameraAlt, null, modifier = Modifier.size(18.dp))
                        Text("Take photo")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSourceDialog = false
                    galleryLauncher.launch("image/*")
                }) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.PhotoLibrary, null, modifier = Modifier.size(18.dp))
                        Text("Gallery")
                    }
                }
            }
        )
    }
}

/**
 * Creates a fresh FileProvider URI in the app's cache dir for the camera to
 * write into, then passes it back via [onUriReady]. Mirrors the helper used
 * by `ProfileScreen` so both flows produce identical FileProvider authorities.
 */
private fun launchCamera(
    context: android.content.Context,
    onUriReady: (Uri) -> Unit
) {
    val file = File(context.cacheDir, "onboarding_profile_photo_${System.currentTimeMillis()}.jpg")
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    onUriReady(uri)
}

@Composable
private fun ProfileSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.large
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                IconButton(onClick = onToggle) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand"
                    )
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    content()
                }
            }
        }
    }
}
