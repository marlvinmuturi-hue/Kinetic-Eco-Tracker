package Kinetic_Eco.Tracker.ui.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Step 1 of 3 (post-login) — "Here's what you can do".
 *
 * Brief description of the app's capabilities, shown once the user has
 * created an account / signed in. The pre-login [OnboardingWelcomeScreen] is
 * intentionally minimal so users can sign up quickly; this is where the
 * marketing-style feature tour lives.
 */
@Composable
fun OnboardingDescriptionScreen(
    onContinue: () -> Unit
) {
    val scroll = rememberScrollState()
    val colorScheme = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(56.dp))

        Text(
            text = "Here's what you can do",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = colorScheme.onBackground
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "A quick tour of what Kinetic Eco Tracker does in the background as you go about your day.",
            style = MaterialTheme.typography.bodyMedium,
            color = colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))

        // Same six-feature highlight list previously embedded in the welcome
        // screen. Order is unchanged so existing screenshots / docs still match.
        val features = listOf(
            Icons.Default.LocationOn         to "GPS tracking with Kalman-filtered routes",
            Icons.Default.DirectionsBike     to "Auto-detects 7 activity types",
            Icons.Default.LocalFireDepartment to "Accurate calorie & CO₂ calculations",
            Icons.Default.BarChart           to "Rich analytics and session history",
            Icons.Default.EmojiEvents        to "Leaderboard to compare with friends",
            Icons.Default.NotificationsActive to "Weekly summary sent to your phone"
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                features.forEach { (icon, label) ->
                    FeatureRow(icon = icon, label = label)
                }
            }
        }

        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(28.dp))

        OnboardingStepIndicator(currentStep = 0, totalSteps = 3)

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

@Composable
private fun FeatureRow(icon: ImageVector, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
