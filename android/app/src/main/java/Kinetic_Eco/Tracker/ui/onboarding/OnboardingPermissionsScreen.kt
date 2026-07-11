package Kinetic_Eco.Tracker.ui.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import Kinetic_Eco.Tracker.util.BatteryOptimizationHelper

/** App accent green — mirrors KineticPrimary / Emerald500 from the theme. */
private val EcoGreen = Color(0xFF10B981)

private data class BenefitCard(
    val icon: ImageVector,
    val headline: String,
    val supporting: String
)

private val benefitCards = listOf(
    BenefitCard(
        icon = Icons.Default.LocationOn,
        headline = "Every route recorded.",
        supporting = "Distance, speed, altitude, and path — your full journey, not just a number."
    ),
    BenefitCard(
        icon = Icons.AutoMirrored.Filled.DirectionsBike,
        headline = "It knows the difference.",
        supporting = "Walk, cycle, drive, fly — 7 activity types detected automatically. No labels needed."
    ),
    BenefitCard(
        icon = Icons.Default.Eco,
        headline = "Your carbon footprint, honestly.",
        supporting = "Every km driven, every kg saved — tracked against real global CO₂ averages."
    ),
    BenefitCard(
        icon = Icons.Default.BarChart,
        headline = "Stay motivated.",
        supporting = "Weekly summaries, a personal leaderboard, and milestones that celebrate real progress."
    )
)

/**
 * Step 1 of 2 (post-login) — a merged intro-tour + Permissions screen.
 *
 * The top of the screen is a swipeable benefit tour (what the app does); below it the screen previews
 * **every** runtime permission the app may ask for (Location, Background location, Activity recognition,
 * Notifications) plus the battery-optimization exemption, so the user is never surprised by a system
 * prompt later. Each permission is tagged Required / Recommended so users can tell what matters.
 *
 * "Continue" is always enabled — we never hard-block here. Anything skipped can be granted later from
 * system settings or via the just-in-time prompts the relevant feature triggers.
 *
 * Background location nuance: Android 11+ requires the foreground location grant *first*; until then the
 * Background-location card's "Allow" button is disabled with a hint. On API 30+, tapping Allow takes the
 * user straight to the system "Allow all the time" settings page.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingPermissionsScreen(onContinue: () -> Unit) {
    val context = LocalContext.current

    fun isGranted(perm: String) =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

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

    // Battery-optimization exemption isn't a runtime permission — it's a settings toggle, so we launch
    // the settings screen and re-check the state when the user returns (StartActivityForResult callback).
    var batteryExempt by remember {
        mutableStateOf(BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context))
    }
    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { batteryExempt = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context) }

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
            text = "Welcome to Kinetic",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(Modifier.height(6.dp))

        Text(
            text = "Swipe to see what it does — then grant a few permissions to get going.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(20.dp))

        BenefitTour()

        Spacer(Modifier.height(28.dp))

        Text(
            text = "Permissions",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Start)
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = "Tap each to grant — you can always change them later in settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))

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

        // Battery-optimization exemption — the make-or-break setting for auto-starting trips when the
        // app is closed. Deep-links to the battery settings list (or the app-info page as a fallback).
        Spacer(Modifier.height(10.dp))
        PermissionCard(
            tier = PermissionTier.Recommended,
            icon = Icons.Default.BatteryChargingFull,
            title = "Keep tracking alive",
            description = "Lets Kinetic auto-start your walks and drives when it's closed. Tap Allow, then set battery to \"Unrestricted\". Barely affects battery — it only wakes on movement.",
            granted = batteryExempt,
            onGrant = {
                val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", context.packageName, null))
                try {
                    batteryLauncher.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (e: Exception) {
                    batteryLauncher.launch(appDetails)
                }
            }
        )

        Spacer(Modifier.height(28.dp))

        OnboardingStepIndicator(currentStep = 0, totalSteps = 2) // step 1 of 2

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
 * Compact, swipeable benefit tour shown at the top of the merged first onboarding step. A fixed-height
 * pager (so it sits happily inside the screen's vertical scroll) with the four outcome cards + dots.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BenefitTour() {
    val colorScheme = MaterialTheme.colorScheme
    val pagerState = rememberPagerState(pageCount = { benefitCards.size })
    val cardContainerColor = EcoGreen.copy(alpha = 0.12f).compositeOver(colorScheme.surface)
    val iconCircleColor = EcoGreen.copy(alpha = 0.22f).compositeOver(colorScheme.surface)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp)
        ) { page ->
            val card = benefitCards[page]
            Card(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 2.dp),
                colors = CardDefaults.cardColors(containerColor = cardContainerColor),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .background(iconCircleColor, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = card.icon,
                            contentDescription = null,
                            tint = EcoGreen,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = card.headline,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color = colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = card.supporting,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(benefitCards.size) { index ->
                val selected = pagerState.currentPage == index
                Box(
                    modifier = Modifier
                        .size(if (selected) 9.dp else 6.dp)
                        .background(
                            color = if (selected) colorScheme.primary
                            else colorScheme.onSurface.copy(alpha = 0.25f),
                            shape = CircleShape
                        )
                )
            }
        }
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
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // weight(1f) so a long title yields space to the tier chip instead of pushing it
                    // off-screen — the chip keeps its full intrinsic width and stays readable in full,
                    // even at large system font sizes (the title wraps to a second line if needed).
                    Text(
                        text = title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    TierChip(tier = tier)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
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
            AnimatedVisibility(
                visible      = granted,
                enter        = scaleIn(tween(300)) + fadeIn(tween(300))
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector        = Icons.Default.CheckCircle,
                        contentDescription = "Granted",
                        tint               = colorScheme.primary,
                        modifier           = Modifier.size(22.dp)
                    )
                }
            }
            if (!granted) {
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
            color = fg,
            maxLines = 1,
            softWrap = false
        )
    }
}
