package Kinetic_Eco.Tracker.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * Step 2 of 3 (post-login) — Permissions.
 *
 * The screen previews **every** runtime permission the app may ever ask for
 * (Location, Background location, Activity recognition, Notifications, Camera,
 * Photos & media), so the user is never surprised by a system prompt later.
 * Each permission is tagged Required / Recommended / Optional so the user can
 * tell at a glance which ones genuinely matter.
 *
 * "Continue" is always enabled — we never hard-block here. Anything skipped
 * can be granted later from system settings or via the just-in-time prompts
 * the relevant feature triggers (e.g. tapping the profile-photo avatar).
 *
 * Background location nuance: Android 11+ requires the foreground location
 * grant *first*; until then the Background-location card's "Allow" button is
 * disabled with a hint. On API 30+, tapping Allow takes the user straight to
 * the system "Allow all the time" settings page (the runtime contract handles
 * that redirection for us).
 */
@Composable
fun OnboardingPermissionsScreen(onContinue: () -> Unit) {
    val context = LocalContext.current

    fun isGranted(perm: String) =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    // Photos uses split permissions: READ_MEDIA_IMAGES on API 33+,
    // READ_EXTERNAL_STORAGE on older builds. Resolved once so launcher and
    // status check stay in sync.
    val photosPermission = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_IMAGES
        else
            Manifest.permission.READ_EXTERNAL_STORAGE
    }

    var locationGranted by remember {
        mutableStateOf(
            isGranted(Manifest.permission.ACCESS_FINE_LOCATION) ||
                isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }
    var bgLocationGranted by remember {
        mutableStateOf(
            // Pre-Q, foreground location implicitly covers background, so we
            // treat it as granted up-front and skip showing the card.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            else true
        )
    }
    var activityGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                isGranted(Manifest.permission.ACTIVITY_RECOGNITION)
            else true
        )
    }
    var notifGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                isGranted(Manifest.permission.POST_NOTIFICATIONS)
            else true
        )
    }
    var cameraGranted by remember { mutableStateOf(isGranted(Manifest.permission.CAMERA)) }
    var photosGranted by remember { mutableStateOf(isGranted(photosPermission)) }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        locationGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }
    val bgLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { bgLocationGranted = it }
    val activityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { activityGranted = it }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { notifGranted = it }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { cameraGranted = it }
    val photosLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { photosGranted = it }

    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(36.dp))

        Text(
            text = "A few permissions needed",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Here's everything Kinetic Eco Tracker may ask for. " +
                "Required ones power the core tracker — recommended and " +
                "optional ones unlock extras and can be skipped.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))

        // ── Required ───────────────────────────────────────────────────────
        PermissionCard(
            tier = PermissionTier.Required,
            icon = Icons.Default.LocationOn,
            title = "Location",
            description = "Records your GPS route and calculates distance and speed.",
            granted = locationGranted,
            onGrant = {
                locationLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Spacer(Modifier.height(10.dp))
            PermissionCard(
                tier = PermissionTier.Required,
                icon = Icons.Default.DirectionsRun,
                title = "Activity recognition",
                description = "Detects whether you're walking, cycling, or driving so the tracker labels each session correctly.",
                granted = activityGranted,
                onGrant = { activityLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION) }
            )
        }

        // ── Recommended ────────────────────────────────────────────────────
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Spacer(Modifier.height(10.dp))
            // Background-location is gated on foreground location: the OS
            // refuses to grant background unless foreground was granted first
            // (and on Android 11+ silently denies a combined request).
            val canRequestBg = locationGranted
            PermissionCard(
                tier = PermissionTier.Recommended,
                icon = Icons.Default.MyLocation,
                title = "Background location",
                description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    "Lets tracking continue when the screen is off or you switch apps. Tap Allow, then choose \"Allow all the time\" in settings. Needed for auto-start."
                } else {
                    "Lets tracking continue when the screen is off or you switch apps. Needed for auto-start."
                },
                granted = bgLocationGranted,
                onGrant = {
                    bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                },
                enabled = canRequestBg,
                disabledHint = "Allow Location first"
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Spacer(Modifier.height(10.dp))
            PermissionCard(
                tier = PermissionTier.Recommended,
                icon = Icons.Default.Notifications,
                title = "Notifications",
                description = "Milestone alerts, session summaries, and your weekly digest.",
                granted = notifGranted,
                onGrant = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            )
        }

        // ── Optional ───────────────────────────────────────────────────────
        Spacer(Modifier.height(10.dp))
        PermissionCard(
            tier = PermissionTier.Optional,
            icon = Icons.Default.CameraAlt,
            title = "Camera",
            description = "Used only when you take a profile photo from inside the app.",
            granted = cameraGranted,
            onGrant = { cameraLauncher.launch(Manifest.permission.CAMERA) }
        )

        Spacer(Modifier.height(10.dp))
        PermissionCard(
            tier = PermissionTier.Optional,
            icon = Icons.Default.PhotoLibrary,
            title = "Photos & media",
            description = "Used only when you pick a profile photo from your gallery.",
            granted = photosGranted,
            onGrant = { photosLauncher.launch(photosPermission) }
        )

        Spacer(Modifier.height(28.dp))

        OnboardingStepIndicator(currentStep = 1, totalSteps = 3) // step 2 of 3

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Text("Continue", style = MaterialTheme.typography.labelLarge)
        }

        Spacer(Modifier.height(32.dp))
    }
}

/**
 * Visual tier for a permission row. The label is rendered as a small chip
 * next to the title so users can scan the list and tell at a glance which
 * permissions actually matter.
 */
private enum class PermissionTier(val label: String) {
    Required("Required"),
    Recommended("Recommended"),
    Optional("Optional")
}

@Composable
private fun PermissionCard(
    tier: PermissionTier,
    icon: ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    onGrant: () -> Unit,
    enabled: Boolean = true,
    disabledHint: String? = null
) {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.large
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    TierChip(tier = tier)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant
                )
                if (!enabled && disabledHint != null && !granted) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = disabledHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }
            if (granted) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Granted",
                    tint = colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
            } else {
                OutlinedButton(
                    onClick = onGrant,
                    enabled = enabled
                ) {
                    Text("Allow", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun TierChip(tier: PermissionTier) {
    val colorScheme = MaterialTheme.colorScheme
    val (bg, fg) = when (tier) {
        PermissionTier.Required -> colorScheme.primary.copy(alpha = 0.18f) to colorScheme.primary
        PermissionTier.Recommended -> colorScheme.tertiary.copy(alpha = 0.18f) to colorScheme.tertiary
        PermissionTier.Optional -> colorScheme.onSurfaceVariant.copy(alpha = 0.16f) to colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Text(
            text = tier.label,
            style = MaterialTheme.typography.labelSmall,
            color = fg
        )
    }
}
